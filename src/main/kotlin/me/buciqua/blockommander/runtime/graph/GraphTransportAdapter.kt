package me.buciqua.blockommander.runtime.graph

import net.minecraft.server.level.ServerPlayer
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import me.buciqua.blockommander.runtime.projectionKey
import me.buciqua.blockommander.runtime.Diagnostics
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

data class ReconciliationOutcome(
    val transport: GraphHttpResult,
    val application: GraphApplyResult?,
)

data class CommandTransportOutcome(
    val transport: GraphHttpResult,
    val application: GraphApplyResult?,
    val reconciliation: ReconciliationOutcome?,
)

interface GraphGateway {
    fun reconcile(
        context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayerIdentity,
    ): CompletableFuture<ReconciliationOutcome>
    fun send(context: WorkingCopyTransportContext, command: GraphCommand): CompletableFuture<CommandTransportOutcome>
}

class GraphTransportAdapter(
    private val client: GraphApiClient,
    private val projections: GraphProjectionStore,
    private val mainThread: Executor,
) : GraphGateway {
    fun reconcile(
        context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayer,
    ): CompletableFuture<ReconciliationOutcome> = reconcile(context, stageId, ServerPlayerIdentity.from(player))

    override fun reconcile(
        context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayerIdentity,
    ): CompletableFuture<ReconciliationOutcome> {
        if (!client.isConfigured) return CompletableFuture.completedFuture(
            ReconciliationOutcome(GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."), null),
        )
        val token = try {
            projections.begin(context.projectionKey(stageId), GraphRequestKind.RECONCILIATION)
        } catch (_: IllegalStateException) {
            return CompletableFuture.completedFuture(
                ReconciliationOutcome(GraphHttpResult.ProtocolFailure("Ismeretlen Stage."), null),
            )
        }
        return reconcileWithToken(token,context,stageId,player)
    }

    private fun reconcileWithToken(
        token:GraphRequestToken, context:WorkingCopyTransportContext, stageId:UUID, player:ServerPlayerIdentity,
    ):CompletableFuture<ReconciliationOutcome> {
        val before=projections.view(token.key)
        val trace=UUID.randomUUID()
        Diagnostics.event("GRAPH_GET_BEGIN",token.key,"epoch" to before?.stageEpoch,"revision" to before?.stageRevision,
            "diagnostic_id" to trace,"lifecycle" to token.lifecycleGeneration,"request_generation" to token.requestGeneration)
        return client.getState(context, stageId, player,trace).thenCompose { transport -> onMain {
            when (transport) {
                is GraphHttpResult.Success -> ReconciliationOutcome(transport, projections.apply(token, transport.response.state))
                is GraphHttpResult.ApiError -> {
                    projections.fail(token, transport.response.errorCode, false)
                    ReconciliationOutcome(transport, null)
                }
                is GraphHttpResult.Unavailable -> {
                    projections.fail(token, "TRANSPORT_UNAVAILABLE", true)
                    ReconciliationOutcome(transport, null)
                }
                is GraphHttpResult.ProtocolFailure -> {
                    projections.fail(token, "PROTOCOL_FAILURE", true)
                    ReconciliationOutcome(transport, null)
                }
            }
        } }.thenApply { result ->
            val after=projections.view(token.key)
            Diagnostics.event("GRAPH_GET_COMPLETE",token.key,"transport" to result.transport.javaClass.simpleName,
                "diagnostic_id" to trace,"lifecycle" to token.lifecycleGeneration,"request_generation" to token.requestGeneration,
                "application" to result.application?.javaClass?.simpleName,"problem" to after?.problem,
                "status" to after?.status)
            result
        }
    }

    override fun send(
        context: WorkingCopyTransportContext, command: GraphCommand,
    ): CompletableFuture<CommandTransportOutcome> {
        if (!client.isConfigured) return CompletableFuture.completedFuture(
            CommandTransportOutcome(GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."), null, null),
        )
        val token = try {
            projections.begin(context.projectionKey(command.stageId), GraphRequestKind.COMMAND)
        } catch (_: IllegalStateException) {
            return CompletableFuture.completedFuture(
                CommandTransportOutcome(GraphHttpResult.ProtocolFailure("Ismeretlen Stage."), null, null),
            )
        }
        return client.send(context, command).thenCompose { transport ->
            when (transport) {
                is GraphHttpResult.Success -> if (transport.response.requestId != command.requestId) {
                    onMain {
                        projections.fail(token, "REQUEST_ID_MISMATCH", true)
                    }.thenCompose {
                        val mismatch = GraphHttpResult.ProtocolFailure("A graph command válasz request_id mezője eltér.")
                        recover(token, context, command).thenApply { recovered ->
                            CommandTransportOutcome(mismatch, null, recovered)
                        }
                    }
                } else {
                    onMain {
                        val applied = projections.apply(token, transport.response.state)
                        CommandTransportOutcome(transport, applied, null)
                    }
                }
                is GraphHttpResult.ApiError -> {
                    val needsReconciliation = transport.response.httpStatus == 409 || transport.response.httpStatus >= 500
                    onMain { projections.fail(token, transport.response.errorCode, needsReconciliation) }.thenCompose {
                        if (needsReconciliation) recover(token, context, command).thenApply {
                            CommandTransportOutcome(transport, null, it)
                        } else CompletableFuture.completedFuture(CommandTransportOutcome(transport, null, null))
                    }
                }
                is GraphHttpResult.Unavailable, is GraphHttpResult.ProtocolFailure ->
                    onMain { projections.fail(token, "UNCERTAIN_COMMAND_RESULT", true) }.thenCompose {
                        recover(token, context, command).thenApply { recovered ->
                            CommandTransportOutcome(transport, null, recovered)
                        }
                    }
            }
        }
    }

    private fun recover(
        token: GraphRequestToken, context: WorkingCopyTransportContext, command: GraphCommand,
    ): CompletableFuture<ReconciliationOutcome> = onMain {
        projections.beginIfLifecycleCurrent(token.key, token.lifecycleGeneration,GraphRequestKind.RECONCILIATION)
    }.thenCompose { recoveryToken ->
        if (recoveryToken!=null) reconcileWithToken(recoveryToken,context,command.stageId,command.player)
        else CompletableFuture.completedFuture(ReconciliationOutcome(
            GraphHttpResult.ProtocolFailure("STALE_PROJECTION_LIFECYCLE"), null,
        ))
    }

    private fun <T> onMain(action: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(action, mainThread)
}
