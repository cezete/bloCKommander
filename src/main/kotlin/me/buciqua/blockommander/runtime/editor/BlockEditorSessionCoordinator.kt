package me.buciqua.blockommander.runtime.editor

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal enum class EditorSessionState { OPENING, EDITING, PENDING, ACCEPTED, CONFLICT, VALIDATION_ERROR, UNKNOWN, CLOSED }
internal data class EditorSessionId(val player: UUID, val connection: Long, val generation: Long, val nonce: UUID)
internal data class EditorLifecycle(
    val connection: Long, val hall: Long, val projection: Long, val navigation: Long?, val epoch: UUID,
)
internal data class EditorCapture(val context: BlockEditorRequestContext, val lifecycle: EditorLifecycle)
internal enum class EditorReconciliationState { NONE, PENDING, AVAILABLE_NOT_PROJECTED, COMMITTED, FAILED }
internal data class EditorSessionSnapshot(
    val id: EditorSessionId, val state: EditorSessionState, val target: BlockEditorRequestContext,
    val opened: EditorGetResult?, val accepted: EditorApplyResult?, val recovery: EditorGetResult?,
    val failure: BlockEditorResult<Nothing>?, val recoveryFailure: BlockEditorResult<Nothing>?,
    val recoveryPending: Boolean, val reconciliation: EditorReconciliationState, val closedReason: String?,
    val projectionResult: MetadataCommitResult? = null,
) {
    val canApply: Boolean get() = state in setOf(EditorSessionState.EDITING, EditorSessionState.VALIDATION_ERROR) &&
        opened?.permissions?.let { it.read && it.apply } == true
}

/** Server-thread confined. The production facade alone constructs trusted captures.
 * All completions, including exceptional futures, return through mainThread.
 * R is an authoritative prepared frame, never a claim that the world was projected.
 */
internal class BlockEditorSessionCoordinator<R>(
    private val recapture: (EditorCapture, EditorGetResult?, UUID) -> EditorCapture?,
    private val get: (BlockEditorRequestContext) -> CompletableFuture<BlockEditorTransportOutcome<EditorGetResult>>,
    private val apply: (BlockEditorRequestContext, EditorChanges) -> CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>>,
    private val reconcile: (EditorCapture, EditorGetResult) -> CompletableFuture<R>,
    private val mainThread: Executor,
    private val changed: (EditorSessionSnapshot) -> Unit = {},
) {
    private inner class Session(val id: EditorSessionId, val capture: EditorCapture) {
        var state = EditorSessionState.OPENING
        var opened: EditorGetResult? = null
        var accepted: EditorApplyResult? = null
        var recovery: EditorGetResult? = null
        var failure: BlockEditorResult<Nothing>? = null
        var recoveryFailure: BlockEditorResult<Nothing>? = null
        var recoveryPending = false
        var reconciliation = EditorReconciliationState.NONE
        var frame: R? = null
        var closedReason: String? = null
        var projectionResult: MetadataCommitResult? = null
        fun snapshot() = EditorSessionSnapshot(id, state, capture.context, opened, accepted, recovery,
            failure, recoveryFailure, recoveryPending, reconciliation, closedReason, projectionResult)
    }
    private val sessions = mutableMapOf<UUID, Session>()
    private val generations = mutableMapOf<UUID, Long>()
    private var stopped = false
    private var notificationDepth = 0

    fun open(capture: EditorCapture): EditorSessionId? {
        if (stopped || notificationDepth != 0) return null
        val player = capture.context.playerUuid
        sessions[player]?.let { close(it.id, "NEW_OPEN") }
        val generation = Math.addExact(generations[player] ?: 0, 1)
        generations[player] = generation
        val id = EditorSessionId(player, capture.lifecycle.connection, generation, UUID.randomUUID())
        val session = Session(id, capture)
        sessions[player] = session
        if (!current(session)) return id
        notify(session)
        if (current(session)) read(session, recovery = false)
        return id
    }

    fun snapshot(id: EditorSessionId): EditorSessionSnapshot? {
        val session = sessions[id.player]?.takeIf { it.id == id } ?: return null
        if (session.state != EditorSessionState.CLOSED) current(session)
        return session.snapshot()
    }

    fun apply(id: EditorSessionId, changes: EditorChanges): Boolean {
        val session = sessions[id.player]?.takeIf { it.id == id } ?: return false
        if (!current(session) || !session.snapshot().canApply) return false
        val opened = session.opened ?: return false
        val context = capture(session, opened)?.context ?: return false
        // Only fields granted by this authoritative schema may leave the session.
        val keys = listOfNotNull("label".takeIf { changes.label != EditorStringChange.Missing },
            "description".takeIf { changes.description != EditorStringChange.Missing })
        if (keys.any { key -> opened.schema.fields.none { it.key == key && it.editable } }) return false
        session.state = EditorSessionState.PENDING
        session.failure = null
        notify(session)
        if (!current(session)) return false
        complete(session, context, { apply(context, changes) }) { result ->
            when (result) {
                is BlockEditorResult.Success -> {
                    session.accepted = result.value
                    session.state = EditorSessionState.ACCEPTED
                    notify(session)
                    prepare(session, result.value.editor)
                }
                else -> {
                    session.failure = failure(result)
                    session.state = when {
                        result is BlockEditorResult.ApiError && result.error.httpStatus == 422 &&
                            result.error.errorCode == "VALIDATION_FAILED" -> EditorSessionState.VALIDATION_ERROR
                        result is BlockEditorResult.ApiError && result.error.httpStatus == 409 -> EditorSessionState.CONFLICT
                        uncertain(result) -> EditorSessionState.UNKNOWN
                        else -> EditorSessionState.CLOSED
                    }
                    if (session.state == EditorSessionState.CLOSED) session.closedReason = "APPLY_REJECTED"
                    notify(session)
                    if (session.state == EditorSessionState.UNKNOWN && current(session)) read(session, recovery = true)
                }
            }
        }
        return true
    }

    fun close(id: EditorSessionId, reason: String) {
        val session = sessions[id.player]?.takeIf { it.id == id && it.state != EditorSessionState.CLOSED } ?: return
        session.state = EditorSessionState.CLOSED
        session.closedReason = reason
        session.frame = null
        session.recoveryPending = false
        notify(session)
    }
    fun invalidate(player: UUID, reason: String) { sessions[player]?.let { close(it.id, reason) } }
    fun poll() { sessions.values.toList().filter { it.state != EditorSessionState.CLOSED }.forEach { current(it) } }
    fun shutdown() {
        stopped = true
        sessions.values.toList().forEach { close(it.id, "SERVER_SHUTDOWN") }
    }

    /** Synchronous, server-thread handoff only. A consumer must not retain R for an unfenced later write. */
    fun withReconciliation(id: EditorSessionId, consumer: (R) -> Unit): Boolean {
        return withReconciliationContext(id) { _, _, frame -> consumer(frame) }
    }

    fun isCurrentSession(id: EditorSessionId): Boolean = sessions[id.player]?.takeIf { it.id == id }?.let { current(it) } == true

    fun withReconciliationContext(id: EditorSessionId, consumer: (EditorCapture, EditorGetResult, R) -> Unit): Boolean {
        val session = sessions[id.player]?.takeIf { it.id == id } ?: return false
        if (!current(session)) return false
        val frame = session.frame ?: return false
        val view = session.recovery ?: session.accepted?.editor ?: return false
        session.frame = null // one handoff per prepared result
        consumer(session.capture, view, frame)
        return true
    }

    fun projectionCompleted(id: EditorSessionId, result: MetadataCommitResult) {
        val session = sessions[id.player]?.takeIf { it.id == id } ?: return
        if (!current(session)) return
        session.projectionResult = result
        session.reconciliation = if (result.status == MetadataCommitStatus.COMMITTED)
            EditorReconciliationState.COMMITTED else EditorReconciliationState.FAILED
        notify(session)
    }

    /** Explicit read-only recovery. Never resends APPLY or changes its original GET/CAS basis. */
    fun reconcileAgain(id: EditorSessionId): Boolean {
        val session = sessions[id.player]?.takeIf { it.id == id } ?: return false
        if (!current(session) || session.state !in setOf(EditorSessionState.ACCEPTED, EditorSessionState.UNKNOWN) ||
            session.recoveryPending || session.reconciliation == EditorReconciliationState.PENDING) return false
        session.frame = null
        session.projectionResult = null
        session.reconciliation = EditorReconciliationState.NONE
        session.recovery = null
        session.recoveryFailure = null
        read(session, recovery = true)
        return true
    }

    private fun read(session: Session, recovery: Boolean) {
        val context = capture(session, null)?.context ?: return
        if (recovery) { session.recoveryPending = true; notify(session) }
        if (!current(session)) return
        complete(session, context, { get(context) }) { result ->
            if (recovery) session.recoveryPending = false
            when (result) {
                is BlockEditorResult.Success -> {
                    if (recovery) session.recovery = result.value
                    else {
                        session.opened = result.value
                        session.state = if (result.value.versionContext.stageWorkspaceEpoch == session.capture.lifecycle.epoch)
                            EditorSessionState.EDITING else EditorSessionState.CONFLICT
                    }
                    notify(session)
                    if (recovery) prepare(session, result.value)
                }
                else -> {
                    if (recovery) {
                        session.recoveryFailure = failure(result)
                        session.reconciliation = EditorReconciliationState.FAILED
                    }
                    else {
                        session.failure = failure(result)
                        session.state = EditorSessionState.CLOSED
                        session.closedReason = "GET_REJECTED"
                    }
                    notify(session)
                }
            }
        }
    }

    private fun prepare(session: Session, view: EditorGetResult) {
        if (!current(session)) return
        session.reconciliation = EditorReconciliationState.PENDING
        notify(session)
        if (!current(session)) return
        val future = try { reconcile(session.capture, view) } catch (_: Exception) { CompletableFuture.failedFuture<R>(IllegalStateException()) }
        future.whenCompleteAsync({ frame, error ->
            if (current(session)) {
                session.frame = if (error == null) frame else null
                session.reconciliation = if (error == null && frame != null)
                    EditorReconciliationState.AVAILABLE_NOT_PROJECTED else EditorReconciliationState.FAILED
                notify(session)
            }
        }, mainThread)
    }

    private fun capture(session: Session, opened: EditorGetResult?): EditorCapture? {
        val value = try { recapture(session.capture, opened, UUID.randomUUID()) } catch (_: Exception) { null }
        val original = session.capture.context
        if (value == null || value.lifecycle != session.capture.lifecycle ||
            value.context.copy(requestId = original.requestId, applyBasis = original.applyBasis) != original ||
            value.context.applyBasis != opened?.let { EditorApplyBasis(it.schemaRef, it.versionContext.stageWorkspaceEpoch, it.versionContext.workingRevision) }) {
            close(session.id, "LIFECYCLE_CHANGED")
            return null
        }
        return value
    }
    private fun current(session: Session): Boolean = !stopped && sessions[session.id.player] === session &&
        session.state != EditorSessionState.CLOSED && capture(session, null) != null

    private fun <T> complete(session: Session, context: BlockEditorRequestContext,
        start: () -> CompletableFuture<BlockEditorTransportOutcome<T>>, consume: (BlockEditorResult<T>) -> Unit,
    ) {
        val future = try { start() } catch (_: Exception) { CompletableFuture.failedFuture(IllegalStateException()) }
        future.whenCompleteAsync({ outcome, error ->
            if (current(session)) {
                val result = if (error != null || outcome == null || outcome.context != context)
                    BlockEditorResult.ProtocolFailure("EDITOR_COMPLETION_FAILED", context.applyBasis != null)
                else outcome.result
                consume(result)
            }
        }, mainThread)
    }
    private fun notify(session: Session) {
        notificationDepth++
        try { runCatching { changed(session.snapshot()) } } finally { notificationDepth-- }
    }
    private fun uncertain(result: BlockEditorResult<*>): Boolean = when (result) {
        is BlockEditorResult.TransportFailure -> result.applyOutcomeUnknown
        is BlockEditorResult.ProtocolFailure -> result.applyOutcomeUnknown
        is BlockEditorResult.ApiError -> result.error.httpStatus >= 500
        else -> false
    }
    private fun failure(result: BlockEditorResult<*>): BlockEditorResult<Nothing> = when (result) {
        is BlockEditorResult.ApiError -> result
        is BlockEditorResult.TransportFailure -> result
        is BlockEditorResult.ProtocolFailure -> result
        else -> error("Expected failure")
    }
}
