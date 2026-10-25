package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

enum class WireAction { PLACE, REMOVE }
enum class WireActionStatus { SUCCESS, RECOVERED, REJECTED, NOT_SYNCED, STALE, NEEDS_REPAIR }
enum class WireWorldStatus { SYNCED, NEEDS_REPAIR }

data class WireActionResult(
    val action: WireAction,
    val status: WireActionStatus,
    val requestId: UUID,
    val projection: WireProjectionResult? = null,
    val detail: String? = null,
)

class WireInteractionCoordinator(
    private val projections: GraphProjectionStore,
    private val gateway: GraphGateway,
    private val projector: WireWorldProjector,
    private val mainThread: Executor,
    private val requestIdFactory: () -> UUID = UUID::randomUUID,
) {
    private val worldStates = ConcurrentHashMap<UUID, WireWorldStatus>()

    fun worldStatus(stageId: UUID): WireWorldStatus = worldStates[stageId] ?: WireWorldStatus.NEEDS_REPAIR

    fun place(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
    ): CompletableFuture<WireActionResult> = operate(stage, player, absolutePosition, world, WireAction.PLACE)

    fun remove(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
    ): CompletableFuture<WireActionResult> = operate(stage, player, absolutePosition, world, WireAction.REMOVE)

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

    private fun operate(
        stage: WireStageContext,
        player: ServerPlayerIdentity,
        absolutePosition: GraphPosition,
        world: WireWorldAccess,
        action: WireAction,
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
            val cell = if (action == WireAction.PLACE) GraphWireCellInput(
                listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST"))),
            ) else null
            val command = WireLayoutCommand(
                requestId, stage.stageId, player, view.stageEpoch, view.stageRevision,
                listOf(GraphWireChangeInput(relative, cell)),
            )
            gateway.send(command).thenCompose { outcome ->
                val authoritative = outcome.application is GraphApplyResult.Applied ||
                    outcome.reconciliation?.application is GraphApplyResult.Applied
                if (!authoritative) {
                    CompletableFuture.supplyAsync({
                        world.correct(absolutePosition)
                        WireActionResult(action, WireActionStatus.REJECTED, requestId, detail = transportDetail(outcome.transport))
                    }, mainThread)
                } else {
                    val forceRejected = outcome.transport is GraphHttpResult.ApiError
                    renderCurrent(
                        stage, world, action, requestId, absolutePosition,
                        recovered = outcome.application == null, forceRejected = forceRejected,
                    )
                        .thenCompose { rendered ->
                            if (rendered.detail != "WORLD_RENDER_FAILED") {
                                CompletableFuture.completedFuture(rendered)
                            } else {
                                gateway.reconcile(stage.stageId, player).thenCompose {
                                    renderCurrent(
                                        stage, world, action, requestId, absolutePosition,
                                        recovered = true, forceRejected = forceRejected,
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
    ): CompletableFuture<WireActionResult> = CompletableFuture.supplyAsync({
        val view = current(stage) ?: return@supplyAsync WireActionResult(
            action, WireActionStatus.STALE, requestId, detail = "STAGE_LIFECYCLE_CHANGED",
        )
        try {
            val result = projector.apply(stage, view.stageRevision, view.snapshot!!, world)
            val targetMatches = target == null || when (action) {
                WireAction.PLACE -> target in result.rendered
                WireAction.REMOVE -> world.cell(target) != WireWorldCell.CK_WIRE
            }
            if (result.complete && targetMatches && !forceRejected) {
                worldStates[stage.stageId] = WireWorldStatus.SYNCED
                WireActionResult(
                    action, if (recovered) WireActionStatus.RECOVERED else WireActionStatus.SUCCESS,
                    requestId, result,
                )
            } else if (result.complete) {
                worldStates[stage.stageId] = WireWorldStatus.SYNCED
                target?.let(world::correct)
                WireActionResult(action, WireActionStatus.REJECTED, requestId, result, "COMMAND_NOT_PRESENT_AFTER_RECONCILIATION")
            } else {
                worldStates[stage.stageId] = WireWorldStatus.NEEDS_REPAIR
                WireActionResult(action, WireActionStatus.NEEDS_REPAIR, requestId, result, "WORLD_PROJECTION_INCOMPLETE")
            }
        } catch (_: Exception) {
            worldStates[stage.stageId] = WireWorldStatus.NEEDS_REPAIR
            WireActionResult(action, WireActionStatus.NEEDS_REPAIR, requestId, detail = "WORLD_RENDER_FAILED")
        }
    }, mainThread)

    private fun transportDetail(result: GraphHttpResult): String = when (result) {
        is GraphHttpResult.ApiError -> result.response.errorCode
        is GraphHttpResult.ProtocolFailure -> "PROTOCOL_FAILURE"
        is GraphHttpResult.Unavailable -> "TRANSPORT_UNAVAILABLE"
        is GraphHttpResult.Success -> "PROJECTION_REJECTED"
    }
}
