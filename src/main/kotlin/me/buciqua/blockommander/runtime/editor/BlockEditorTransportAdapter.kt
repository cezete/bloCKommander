package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.Hall
import me.buciqua.blockommander.runtime.WorldIdentity
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal sealed interface BlockEditorResult<out T> {
    data class Success<T>(val value: T) : BlockEditorResult<T>
    data class ApiError(val error: EditorApiError) : BlockEditorResult<Nothing>
    data class TransportFailure(val kind: EditorTransportFailureKind, val applyOutcomeUnknown: Boolean) : BlockEditorResult<Nothing>
    data class ProtocolFailure(val reason: String, val applyOutcomeUnknown: Boolean) : BlockEditorResult<Nothing>
}
internal data class BlockEditorTransportOutcome<T>(val context: BlockEditorRequestContext, val result: BlockEditorResult<T>)

/** Capture on the server thread from ServerPlayer and a registry-resolved Hall.
 * B3/B must additionally check session/connection/navigation generations before use.
 */
internal fun captureBlockEditorContext(
    player: ServerPlayer, hall: Hall, instanceId: UUID,
    requestId: UUID = UUID.randomUUID(), opened: EditorGetResult? = null,
): BlockEditorRequestContext {
    val playerId = player.uuid
    val worldId = WorldIdentity.uuid(player.level())
    require(hall.ownerUuid == playerId.toString() && hall.worldUuid == worldId.toString()) { "EDITOR_PLAYER_CONTEXT_MISMATCH" }
    require(hall.transportContext.hallId.toString() == hall.id) { "EDITOR_HALL_ID_MISMATCH" }
    val stage = requireNotNull(hall.stage)
    val block = stage.blocks.single { it.instanceId == instanceId.toString() }
    val blockType = EditorBlockType.valueOf(block.type)
    val stageId = UUID.fromString(stage.id)
    val basis = opened?.let {
        require(it.target == EditorTarget(hall.transportContext.programId, stageId, instanceId, blockType)
            && it.transportContext.context == hall.transportContext && it.transportContext.stageId == stageId) { "EDITOR_OPENED_CONTEXT_MISMATCH" }
        // Keep the GET's versions; never replace stale CAS with the registry's current version.
        EditorApplyBasis(it.schemaRef, it.versionContext.stageWorkspaceEpoch, it.versionContext.workingRevision)
    }
    return BlockEditorRequestContext(playerId, worldId, hall.transportContext, stageId, instanceId, blockType, requestId, basis)
}

/** No registry, projection store, world writer, session state or interaction hooks. */
internal class BlockEditorTransportAdapter(private val client: BlockEditorApiClient, private val mainThread: Executor) {
    internal fun get(context: BlockEditorRequestContext): CompletableFuture<BlockEditorTransportOutcome<EditorGetResult>> =
        deliver(context, client.get(context)) { response ->
            val view = BlockEditorResponseParser.get(response.body)
            validate(context, view)
            view
        }

    internal fun apply(context: BlockEditorRequestContext, changes: EditorChanges): CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>> =
        deliver(context, client.apply(EditorApplyRequest(context, changes))) { response ->
            val result = BlockEditorResponseParser.apply(response.body)
            validate(context, result.editor)
            require(result.requestId == context.requestId) { "REQUEST_ID_MISMATCH" }
            val basis = requireNotNull(context.applyBasis)
            require(result.editor.schemaRef == basis.schema && result.editor.versionContext.stageWorkspaceEpoch == basis.workspaceEpoch) { "APPLY_VERSION_CONTEXT_MISMATCH" }
            val expectedRevision = if (result.modified) Math.addExact(basis.workingRevision, 1) else basis.workingRevision
            require(result.editor.versionContext.workingRevision == expectedRevision) { "APPLY_REVISION_MISMATCH" }
            result
        }

    private fun validate(context: BlockEditorRequestContext, view: EditorGetResult) {
        require(view.transportContext.context == context.transport && view.transportContext.stageId == context.stageId) { "TRANSPORT_CONTEXT_MISMATCH" }
        require(view.target.programId == context.transport.programId && view.target.stageId == context.stageId &&
            view.target.instanceId == context.instanceId && (context.blockType == null || view.target.blockType == context.blockType)) { "TARGET_IDENTITY_MISMATCH" }
    }

    private fun <T> deliver(
        context: BlockEditorRequestContext, future: CompletableFuture<EditorWireResult>,
        parse: (EditorHttpResponse) -> T,
    ): CompletableFuture<BlockEditorTransportOutcome<T>> = future.thenApply { wire ->
        val apply = context.applyBasis != null
        when (wire) {
            is EditorWireResult.Failure -> BlockEditorResult.TransportFailure(wire.kind,
                apply && wire.kind in setOf(EditorTransportFailureKind.TIMEOUT, EditorTransportFailureKind.UNAVAILABLE))
            is EditorWireResult.Response -> try {
                val response = wire.response
                if (response.status == 200) BlockEditorResult.Success(parse(response))
                else {
                    val error = BlockEditorResponseParser.error(response.status, response.body)
                    require(error.requestId == null || error.requestId == context.requestId) { "REQUEST_ID_MISMATCH" }
                    BlockEditorResult.ApiError(error)
                }
            } catch (_: Exception) {
                // Do not leak arbitrary backend body/value/secret text into failure messages.
                BlockEditorResult.ProtocolFailure("INVALID_EDITOR_RESPONSE", apply)
            }
        }
    }.thenApplyAsync({ BlockEditorTransportOutcome(context, it) }, mainThread)
}
