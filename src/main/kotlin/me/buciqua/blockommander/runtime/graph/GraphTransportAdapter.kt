package me.buciqua.blockommander.runtime.graph

import net.minecraft.server.level.ServerPlayer
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
    fun reconcile(stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<ReconciliationOutcome>
    fun send(command: GraphCommand): CompletableFuture<CommandTransportOutcome>
}

class GraphTransportAdapter(
    private val client: GraphApiClient,
    private val projections: GraphProjectionStore,
    private val mainThread: Executor,
) : GraphGateway {
    fun reconcile(stageId: UUID, player: ServerPlayer): CompletableFuture<ReconciliationOutcome> =
        reconcile(stageId, ServerPlayerIdentity.from(player))

    override fun reconcile(stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<ReconciliationOutcome> {
        if (!client.isConfigured) return CompletableFuture.completedFuture(
            ReconciliationOutcome(GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."), null),
        )
        val token = try {
            projections.begin(stageId, GraphRequestKind.RECONCILIATION)
        } catch (_: IllegalStateException) {
            return CompletableFuture.completedFuture(
                ReconciliationOutcome(GraphHttpResult.ProtocolFailure("Ismeretlen Stage."), null),
            )
        }
        return client.getState(stageId, player).thenCompose { transport -> onMain {
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
        } }
    }

    override fun send(command: GraphCommand): CompletableFuture<CommandTransportOutcome> {
        if (!client.isConfigured) return CompletableFuture.completedFuture(
            CommandTransportOutcome(GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."), null, null),
        )
        val token = try {
            projections.begin(command.stageId, GraphRequestKind.COMMAND)
        } catch (_: IllegalStateException) {
            return CompletableFuture.completedFuture(
                CommandTransportOutcome(GraphHttpResult.ProtocolFailure("Ismeretlen Stage."), null, null),
            )
        }
        return client.send(command).thenCompose { transport ->
            when (transport) {
                is GraphHttpResult.Success -> if (transport.response.requestId != command.requestId) {
                    onMain {
                        projections.fail(token, "REQUEST_ID_MISMATCH", true)
                    }.thenCompose {
                        val mismatch = GraphHttpResult.ProtocolFailure("A graph command válasz request_id mezője eltér.")
                        reconcile(command.stageId, command.player).thenApply { recovered ->
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
                        if (needsReconciliation) reconcile(command.stageId, command.player).thenApply {
                            CommandTransportOutcome(transport, null, it)
                        } else CompletableFuture.completedFuture(CommandTransportOutcome(transport, null, null))
                    }
                }
                is GraphHttpResult.Unavailable, is GraphHttpResult.ProtocolFailure ->
                    onMain { projections.fail(token, "UNCERTAIN_COMMAND_RESULT", true) }.thenCompose {
                        reconcile(command.stageId, command.player).thenApply { recovered ->
                            CommandTransportOutcome(transport, null, recovered)
                        }
                    }
            }
        }
    }

    private fun <T> onMain(action: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(action, mainThread)
}
