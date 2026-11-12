package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.ProjectionKey
import me.buciqua.blockommander.runtime.Diagnostics
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

enum class WireAction { PLACE, REMOVE, BIND }
enum class WireActionStatus { SUCCESS, RECOVERED, REJECTED, FORBIDDEN, NOT_SYNCED, STALE, NEEDS_REPAIR }
enum class WireWorldStatus { SYNCED, NEEDS_REPAIR }

data class WireActionResult(
    val action: WireAction,
    val status: WireActionStatus,
    val requestId: UUID,
    val projection: WireProjectionResult? = null,
    val detail: String? = null,
    val discoveryDiagnostics: List<GraphDiscoveryDiagnostic> = emptyList(),
)

class WireInteractionCoordinator(
    private val projections: GraphProjectionStore,
    private val gateway: GraphGateway,
    private val projector: WireWorldProjector,
    private val mainThread: Executor,
    private val projectionFence: (WireStageContext) -> Boolean = { true },
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    companion object {
        const val AUTOMATIC_NEIGHBOR_PLACEMENT_STATUS = "SUPPORTED_WIRE_PLACE_INTENT"
    }

    private val worldStates = ConcurrentHashMap<ProjectionKey, Pair<Long, WireWorldStatus>>()

    fun worldStatus(key: ProjectionKey): WireWorldStatus = worldStates[key]
        ?.takeIf { projections.isLifecycleCurrent(key,it.first) }?.second ?: WireWorldStatus.NEEDS_REPAIR

    /** Metadata reconciliation may certify an unchanged Wire world, never infer it from HTTP success. */
    internal fun acceptVerifiedProjection(stage: WireStageContext, snapshot: GraphStateSnapshot, world: WireWorldAccess): Boolean {
        if (!validLifecycle(stage) || current(stage)?.snapshot != snapshot) return false
        if (!projector.isCurrent(projector.prepare(stage, snapshot.workingRevision, snapshot, world), stage, world)) return false
        worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.SYNCED
        return true
    }

    internal fun markProjectionNeedsRepair(stage: WireStageContext) {
        if (projections.isLifecycleCurrent(stage.key, stage.lifecycleGeneration))
            worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.NEEDS_REPAIR
    }

    fun place(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
        intent: GraphPlacementIntent = GraphPlacementIntent.AUTO,
    ): CompletableFuture<WireActionResult> = operate(
        stage, player, absolutePosition, world, WireAction.PLACE, intent,
    )

    fun remove(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
    ): CompletableFuture<WireActionResult> = operate(
        stage, player, absolutePosition, world, WireAction.REMOVE, null,
    )

    fun reconcileAndRender(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        world: WireWorldAccess,
        projectionAllowed: () -> Boolean = { true },
    ): CompletableFuture<WireActionResult> {
        val requestId = requestIdFactory()
        if (!validLifecycle(stage)) return CompletableFuture.completedFuture(
            WireActionResult(WireAction.PLACE,WireActionStatus.STALE,requestId,detail="STAGE_LIFECYCLE_CHANGED"),
        )
        return gateway.reconcile(stage.transportContext, stage.stageId, player).thenCompose {
            renderCurrent(stage, world, WireAction.PLACE, requestId, null, recovered = true,
                projectionAllowed = projectionAllowed)
        }
    }

    fun bind(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        world: WireWorldAccess,
    ): CompletableFuture<WireActionResult> {
        val requestId = requestIdFactory()
        return ensureSynced(stage, player).thenCompose { sync ->
            val view=sync.view
            if (view == null) return@thenCompose CompletableFuture.completedFuture(
                WireActionResult(
                    WireAction.BIND,
                    if (sync.forbidden) WireActionStatus.FORBIDDEN else WireActionStatus.NOT_SYNCED,
                    requestId,
                    detail = if (sync.forbidden) "FORBIDDEN" else "GRAPH_NOT_SYNCED",
                ),
            )
            val command = BindEdgesCommand(
                requestId, stage.stageId, player, view.stageEpoch, view.stageRevision,
            )
            gateway.send(stage.transportContext, command).thenCompose { outcome ->
                val discoveryDiagnostics = outcomeDiscoveryDiagnostics(outcome)
                val authoritative = outcome.application is GraphApplyResult.Applied ||
                    outcome.reconciliation?.application is GraphApplyResult.Applied
                if (!authoritative) {
                    CompletableFuture.completedFuture(WireActionResult(
                        WireAction.BIND, if (outcome.transport.isForbidden()) WireActionStatus.FORBIDDEN else WireActionStatus.REJECTED, requestId,
                        detail = transportDetail(outcome.transport), discoveryDiagnostics = discoveryDiagnostics,
                    ))
                } else {
                    val forceRejected = outcome.transport is GraphHttpResult.ApiError
                    renderCurrent(
                        stage, world, WireAction.BIND, requestId, null,
                        recovered = outcome.application == null, forceRejected = forceRejected,
                        discoveryDiagnostics = discoveryDiagnostics,
                    ).thenCompose { rendered ->
                        if (rendered.detail != "WORLD_RENDER_FAILED") CompletableFuture.completedFuture(rendered)
                        else gateway.reconcile(stage.transportContext, stage.stageId, player).thenCompose {
                            renderCurrent(
                                stage, world, WireAction.BIND, requestId, null,
                                recovered = true, forceRejected = forceRejected,
                                discoveryDiagnostics = discoveryDiagnostics,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun operate(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
        action: WireAction,
        placementIntent: GraphPlacementIntent?,
    ): CompletableFuture<WireActionResult> {
        val requestId = requestIdFactory()
        val relative = absolutePosition - stage.origin
        Diagnostics.event("WIRE_OPERATION_BEGIN",stage.key,"request_id" to requestId,"action" to action,
            "epoch" to stage.workspaceEpoch,"lifecycle" to stage.lifecycleGeneration)
        if (relative.x !in 0 until stage.size.x || relative.y !in 0 until stage.size.y ||
            relative.z !in 0 until stage.size.z) {
            return CompletableFuture.completedFuture(
                WireActionResult(action, WireActionStatus.REJECTED, requestId, detail = "WIRE_OUTSIDE_STAGE"),
            ).also { Diagnostics.event("WIRE_LOCAL_REJECT",stage.key,"request_id" to requestId,"reason" to "WIRE_OUTSIDE_STAGE") }
        }
        return ensureSynced(stage, player).thenCompose { sync ->
            val view=sync.view
            if (view == null) {
                Diagnostics.event("WIRE_LOCAL_REJECT",stage.key,"request_id" to requestId,
                    "reason" to if(sync.forbidden) "FORBIDDEN" else "GRAPH_NOT_SYNCED","http_sent" to false)
                return@thenCompose CompletableFuture.supplyAsync({
                    world.correct(absolutePosition)
                    WireActionResult(
                        action,
                        if (sync.forbidden) WireActionStatus.FORBIDDEN else WireActionStatus.NOT_SYNCED,
                        requestId,
                        detail = if (sync.forbidden) "FORBIDDEN" else "GRAPH_NOT_SYNCED",
                    )
                }, mainThread)
            }
            Diagnostics.event("WIRE_COMMAND_SEND",stage.key,"request_id" to requestId,"action" to action,
                "epoch" to view.stageEpoch,"revision" to view.stageRevision,"lifecycle" to view.lifecycleGeneration)
            val command = when (action) {
                WireAction.PLACE -> WirePlaceIntentCommand(
                    requestId, stage.stageId, player, view.stageEpoch, view.stageRevision,
                    relative, requireNotNull(placementIntent),
                )
                WireAction.REMOVE -> WireLayoutCommand(
                    requestId, stage.stageId, player, view.stageEpoch, view.stageRevision,
                    listOf(GraphWireChangeInput(relative, null)),
                )
                WireAction.BIND -> error("A bind nem placement/remove művelet.")
            }
            gateway.send(stage.transportContext, command).thenCompose { outcome ->
                val discoveryDiagnostics = outcomeDiscoveryDiagnostics(outcome)
                val authoritative = outcome.application is GraphApplyResult.Applied ||
                    outcome.reconciliation?.application is GraphApplyResult.Applied
                if (!authoritative) {
                    CompletableFuture.supplyAsync({
                        world.correct(absolutePosition)
                        WireActionResult(
                            action, if (outcome.transport.isForbidden()) WireActionStatus.FORBIDDEN else WireActionStatus.REJECTED, requestId,
                            detail = transportDetail(outcome.transport), discoveryDiagnostics = discoveryDiagnostics,
                        )
                    }, mainThread)
                } else {
                    val forceRejected = outcome.transport is GraphHttpResult.ApiError
                    renderCurrent(
                        stage, world, action, requestId, absolutePosition,
                        recovered = outcome.application == null, forceRejected = forceRejected,
                        discoveryDiagnostics = discoveryDiagnostics,
                    )
                        .thenCompose { rendered ->
                            if (rendered.detail != "WORLD_RENDER_FAILED") {
                                CompletableFuture.completedFuture(rendered)
                            } else {
                                gateway.reconcile(stage.transportContext, stage.stageId, player).thenCompose {
                                    renderCurrent(
                                        stage, world, action, requestId, absolutePosition,
                                        recovered = true, forceRejected = forceRejected,
                                        discoveryDiagnostics = discoveryDiagnostics,
                                    )
                                }
                            }
                        }
                }
            }
        }.thenApply { result ->
            Diagnostics.event("WIRE_OPERATION_RESULT",stage.key,"request_id" to requestId,
                "status" to result.status,"reason" to result.detail)
            result
        }
    }

    private data class SyncCheck(val view:StageGraphView?,val forbidden:Boolean=false)

    private fun ensureSynced(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
    ): CompletableFuture<SyncCheck> {
        val valid=validLifecycle(stage)
        val prior=projections.view(stage.key)
        Diagnostics.event("WIRE_SYNC_CHECK",stage.key,"valid_lifecycle" to valid,
            "context_epoch" to stage.workspaceEpoch,"slot_epoch" to prior?.stageEpoch,"slot_revision" to prior?.stageRevision,
            "slot_status" to prior?.status,"slot_problem" to prior?.problem,
            "requested_lifecycle" to stage.lifecycleGeneration,"slot_lifecycle" to prior?.lifecycleGeneration)
        if (!valid) return CompletableFuture.completedFuture(SyncCheck(null))
        current(stage)?.let { return CompletableFuture.completedFuture(SyncCheck(it)) }
        return gateway.reconcile(stage.transportContext, stage.stageId, player).thenApply { outcome ->
            val forbidden=outcome.transport is GraphHttpResult.ApiError &&
                outcome.transport.response.httpStatus in setOf(401,403)
            SyncCheck(current(stage),forbidden)
        }
    }

    private fun validLifecycle(stage: WireStageContext) =
        projections.isLifecycleCurrent(stage.key,stage.lifecycleGeneration) && projectionFence(stage)

    private fun current(stage: WireStageContext): StageGraphView? = projections.view(stage.key)?.takeIf {
        validLifecycle(stage) && it.lifecycleGeneration==stage.lifecycleGeneration &&
            it.status == GraphSyncStatus.SYNCED && it.stageEpoch == stage.workspaceEpoch && it.snapshot != null
    }

    private fun renderCurrent(
        stage: WireStageContext,
        world: WireWorldAccess,
        action: WireAction,
        requestId: UUID,
        target: GraphPosition?,
        recovered: Boolean,
        forceRejected: Boolean = false,
        discoveryDiagnostics: List<GraphDiscoveryDiagnostic> = emptyList(),
        projectionAllowed: () -> Boolean = { true },
    ): CompletableFuture<WireActionResult> = CompletableFuture.supplyAsync({
        if (!validLifecycle(stage) || !projectionAllowed()) return@supplyAsync WireActionResult(
            action, WireActionStatus.STALE, requestId, detail = "STAGE_NOT_ACTIVE",
        ).also { Diagnostics.event("WIRE_RENDER_REJECT",stage.key,"request_id" to requestId,"reason" to "STAGE_NOT_ACTIVE") }
        val view = current(stage) ?: return@supplyAsync WireActionResult(
            action, WireActionStatus.STALE, requestId, detail = "STAGE_LIFECYCLE_CHANGED",
        ).also { Diagnostics.event("WIRE_RENDER_REJECT",stage.key,"request_id" to requestId,"reason" to "STAGE_LIFECYCLE_CHANGED") }
        try {
            val result = projector.apply(stage, view.stageRevision, view.snapshot!!, world)
            val targetMatches = target == null || when (action) {
                WireAction.PLACE -> target in result.rendered
                WireAction.REMOVE -> !world.cell(target).isCkWire
                WireAction.BIND -> true
            }
            if (result.complete && targetMatches && !forceRejected) {
                worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.SYNCED
                WireActionResult(
                    action, if (recovered) WireActionStatus.RECOVERED else WireActionStatus.SUCCESS,
                    requestId, result, discoveryDiagnostics = discoveryDiagnostics,
                )
            } else if (result.complete) {
                worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.SYNCED
                target?.let(world::correct)
                WireActionResult(
                    action, WireActionStatus.REJECTED, requestId, result,
                    "COMMAND_NOT_PRESENT_AFTER_RECONCILIATION", discoveryDiagnostics,
                )
            } else {
                worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.NEEDS_REPAIR
                WireActionResult(
                    action, WireActionStatus.NEEDS_REPAIR, requestId, result,
                    "WORLD_PROJECTION_INCOMPLETE", discoveryDiagnostics,
                )
            }
        } catch (_: Exception) {
            worldStates[stage.key] = stage.lifecycleGeneration to WireWorldStatus.NEEDS_REPAIR
            WireActionResult(action, WireActionStatus.NEEDS_REPAIR, requestId, detail = "WORLD_RENDER_FAILED")
        }
    }, mainThread)

    private fun transportDetail(result: GraphHttpResult): String = when (result) {
        is GraphHttpResult.ApiError -> buildString {
            append(result.response.errorCode)
            result.response.diagnostics.firstNotNullOfOrNull(GraphDiagnostic::fieldName)?.let {
                append(": ")
                append(it)
            }
        }
        is GraphHttpResult.ProtocolFailure -> "PROTOCOL_FAILURE"
        is GraphHttpResult.Unavailable -> "TRANSPORT_UNAVAILABLE"
        is GraphHttpResult.Success -> "PROJECTION_REJECTED"
    }

    private fun GraphHttpResult.isForbidden()=this is GraphHttpResult.ApiError &&
        response.httpStatus in setOf(401,403)

    private fun discoveryDiagnostics(result: GraphHttpResult): List<GraphDiscoveryDiagnostic> = when (result) {
        is GraphHttpResult.Success -> result.response.discovery?.diagnostics.orEmpty()
        is GraphHttpResult.ApiError -> result.response.discovery?.diagnostics.orEmpty()
        is GraphHttpResult.ProtocolFailure, is GraphHttpResult.Unavailable -> emptyList()
    }

    private fun outcomeDiscoveryDiagnostics(outcome: CommandTransportOutcome): List<GraphDiscoveryDiagnostic> =
        discoveryDiagnostics(outcome.transport).ifEmpty {
            outcome.reconciliation?.let { discoveryDiagnostics(it.transport) }.orEmpty()
        }
}
