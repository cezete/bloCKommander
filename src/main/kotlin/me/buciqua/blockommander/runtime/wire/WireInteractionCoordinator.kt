package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

enum class WireAction { PLACE, REMOVE, BIND }
enum class WireActionStatus { SUCCESS, RECOVERED, REJECTED, NOT_SYNCED, STALE, NEEDS_REPAIR }
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
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    companion object {
        const val AUTOMATIC_NEIGHBOR_PLACEMENT_STATUS = "SUPPORTED_WIRE_PLACE_INTENT"
    }

    private val worldStates = ConcurrentHashMap<UUID, WireWorldStatus>()

    fun worldStatus(stageId: UUID): WireWorldStatus = worldStates[stageId] ?: WireWorldStatus.NEEDS_REPAIR

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
    ): CompletableFuture<WireActionResult> {
        val requestId = requestIdFactory()
        return gateway.reconcile(stage.stageId, player).thenCompose {
            renderCurrent(stage, world, WireAction.PLACE, requestId, null, recovered = true)
        }
    }

    fun bind(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        world: WireWorldAccess,
    ): CompletableFuture<WireActionResult> {
        val requestId = requestIdFactory()
        return ensureSynced(stage, player).thenCompose { view ->
            if (view == null) return@thenCompose CompletableFuture.completedFuture(
                WireActionResult(WireAction.BIND, WireActionStatus.NOT_SYNCED, requestId, detail = "GRAPH_NOT_SYNCED"),
            )
            val command = BindEdgesCommand(
                requestId, stage.stageId, player, view.stageEpoch, view.stageRevision,
            )
            gateway.send(command).thenCompose { outcome ->
                val discoveryDiagnostics = outcomeDiscoveryDiagnostics(outcome)
                val authoritative = outcome.application is GraphApplyResult.Applied ||
                    outcome.reconciliation?.application is GraphApplyResult.Applied
                if (!authoritative) {
                    CompletableFuture.completedFuture(WireActionResult(
                        WireAction.BIND, WireActionStatus.REJECTED, requestId,
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
                        else gateway.reconcile(stage.stageId, player).thenCompose {
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
        if (relative.x !in 0 until stage.size.x || relative.y !in 0 until stage.size.y ||
            relative.z !in 0 until stage.size.z) {
            return CompletableFuture.completedFuture(
                WireActionResult(action, WireActionStatus.REJECTED, requestId, detail = "WIRE_OUTSIDE_STAGE"),
            )
        }
        return ensureSynced(stage, player).thenCompose { view ->
            if (view == null) {
                return@thenCompose CompletableFuture.supplyAsync({
                    world.correct(absolutePosition)
                    WireActionResult(action, WireActionStatus.NOT_SYNCED, requestId, detail = "GRAPH_NOT_SYNCED")
                }, mainThread)
            }
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
            gateway.send(command).thenCompose { outcome ->
                val discoveryDiagnostics = outcomeDiscoveryDiagnostics(outcome)
                val authoritative = outcome.application is GraphApplyResult.Applied ||
                    outcome.reconciliation?.application is GraphApplyResult.Applied
                if (!authoritative) {
                    CompletableFuture.supplyAsync({
                        world.correct(absolutePosition)
                        WireActionResult(
                            action, WireActionStatus.REJECTED, requestId,
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
                                gateway.reconcile(stage.stageId, player).thenCompose {
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
        }
    }

    private fun ensureSynced(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
    ): CompletableFuture<StageGraphView?> {
        current(stage)?.let { return CompletableFuture.completedFuture(it) }
        return gateway.reconcile(stage.stageId, player).thenApply { current(stage) }
    }

    private fun current(stage: WireStageContext): StageGraphView? = projections.view(stage.stageId)?.takeIf {
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
    ): CompletableFuture<WireActionResult> = CompletableFuture.supplyAsync({
        val view = current(stage) ?: return@supplyAsync WireActionResult(
            action, WireActionStatus.STALE, requestId, detail = "STAGE_LIFECYCLE_CHANGED",
        )
        try {
            val result = projector.apply(stage, view.stageRevision, view.snapshot!!, world)
            val targetMatches = target == null || when (action) {
                WireAction.PLACE -> target in result.rendered
                WireAction.REMOVE -> !world.cell(target).isCkWire
                WireAction.BIND -> true
            }
            if (result.complete && targetMatches && !forceRejected) {
                worldStates[stage.stageId] = WireWorldStatus.SYNCED
                WireActionResult(
                    action, if (recovered) WireActionStatus.RECOVERED else WireActionStatus.SUCCESS,
                    requestId, result, discoveryDiagnostics = discoveryDiagnostics,
                )
            } else if (result.complete) {
                worldStates[stage.stageId] = WireWorldStatus.SYNCED
                target?.let(world::correct)
                WireActionResult(
                    action, WireActionStatus.REJECTED, requestId, result,
                    "COMMAND_NOT_PRESENT_AFTER_RECONCILIATION", discoveryDiagnostics,
                )
            } else {
                worldStates[stage.stageId] = WireWorldStatus.NEEDS_REPAIR
                WireActionResult(
                    action, WireActionStatus.NEEDS_REPAIR, requestId, result,
                    "WORLD_PROJECTION_INCOMPLETE", discoveryDiagnostics,
                )
            }
        } catch (_: Exception) {
            worldStates[stage.stageId] = WireWorldStatus.NEEDS_REPAIR
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
