package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.network.EditorSessionKey
import me.buciqua.blockommander.runtime.StageUpdateFence
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

enum class DeclarationState { OPENING, READY, PENDING, APPLIED_TO_WORKSPACE, REJECTED, UNKNOWN, STALE, RELOAD_REQUIRED, CLOSED }
internal data class DeclarationCapture(val context: DeclarationContext, val connection: Long, val fence: StageUpdateFence, val dimension: String)
internal data class DeclarationProjection(val committed: Boolean, val stamp: Any?)
internal data class DeclarationSessionSnapshot(val key: EditorSessionKey, val capture: DeclarationCapture,
    val state: DeclarationState, val page: DeclarationPage?, val busy: Boolean, val error: String?,
    val projection: String, val receipt: DeclarationReceipt?, val lastOperation: Long) {
    val editable get() = !busy && projection == "CURRENT" && state in setOf(DeclarationState.READY, DeclarationState.REJECTED, DeclarationState.APPLIED_TO_WORKSPACE)
}

/** Server-thread confined, independently testable; no domain rules or retry loop. */
internal class DeclarationSessionCoordinator(
    private val api: DeclarationGateway,
    private val current: (DeclarationCapture) -> Boolean,
    private val stamp: (DeclarationCapture) -> Any,
    private val reconcile: (DeclarationCapture, DeclarationPage, () -> Boolean) -> CompletableFuture<DeclarationProjection>,
    private val main: Executor,
    private val changed: (DeclarationSessionSnapshot) -> Unit = {},
    private val mutated: (DeclarationContext, EditorSessionKey) -> Unit = { _, _ -> },
) {
    private class Session(val key: EditorSessionKey, val capture: DeclarationCapture) {
        var state = DeclarationState.OPENING
        var page: DeclarationPage? = null
        var busy = false
        var error: String? = null
        var projection = "NOT_CHECKED"
        var receipt: DeclarationReceipt? = null
        var operation = 0L
        var readGeneration = 0L
        var stamp: Any? = null
        fun snapshot() = DeclarationSessionSnapshot(key, capture, state, page, busy, error, projection, receipt, operation)
    }
    private val sessions = mutableMapOf<UUID, Session>()
    private val generations = mutableMapOf<UUID, Long>()
    private val programChanges = mutableMapOf<UUID, Long>()
    private var stopped = false
    fun open(capture: DeclarationCapture): EditorSessionKey? {
        if (stopped || !current(capture)) return null
        invalidate(capture.context.player, "NEW_OPEN")
        val generation = Math.addExact(generations[capture.context.player] ?: 0, 1)
        generations[capture.context.player] = generation
        val key = EditorSessionKey(capture.context.player, capture.connection, generation, UUID.randomUUID())
        val session = Session(key, capture); sessions[key.player] = session
        emit(session); read(session, 0, null)
        return key
    }
    fun snapshot(key: EditorSessionKey) = sessions[key.player]?.takeIf { it.key == key }?.snapshot()
    private fun live(s: Session): Boolean {
        if (stopped || sessions[s.key.player] !== s || s.state == DeclarationState.CLOSED) return false
        if (!current(s.capture)) { close(s, "LIFECYCLE_CHANGED"); return false }
        return true
    }
    fun invalidate(player: UUID, reason: String) { sessions[player]?.let { close(it, reason) } }
    private fun close(s: Session, reason: String) {
        if (s.state == DeclarationState.CLOSED) return
        s.state = DeclarationState.CLOSED; s.error = reason; s.busy = false; s.readGeneration++; emit(s)
    }
    fun shutdown() { sessions.values.toList().forEach { close(it, "SERVER_SHUTDOWN") }; stopped = true }
    fun poll() {
        sessions.values.toList().forEach { s ->
            if (live(s) && !s.busy && s.page != null && s.stamp != stamp(s.capture) && s.state != DeclarationState.STALE) {
                s.state = DeclarationState.STALE; s.error = "LOCAL_PROGRAM_CHANGED"; emit(s)
            }
        }
    }
    fun programChanged(context: DeclarationContext, except: EditorSessionKey) {
        programChanges.merge(context.transport.programId, 1L, Long::plus)
        sessions.values.toList().filter { it.key != except && it.capture.context.transport.programId == context.transport.programId }.forEach {
            if (live(it) && !it.busy) { it.state = DeclarationState.STALE; it.error = "LOCAL_PROGRAM_CHANGED"; emit(it) }
        }
    }
    fun intent(player: UUID, key: EditorSessionKey, sequence: Long, action: String, target: UUID?, fields: DeclarationFields?): Boolean {
        val s = sessions[player]?.takeIf { it.key == key && key.player == player } ?: return false
        if (!live(s) || sequence <= s.operation) return false
        if (action == "CLOSE") { s.operation = sequence; close(s, "EXPLICIT_CLOSE"); return true }
        if (s.busy) return false
        s.operation = sequence
        if (action == "REFRESH") { read(s, 0, null); return true }
        if (action in setOf("NEXT", "PREV")) {
            val p = s.page ?: return false
            if (s.state in setOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED)) return false
            if (action == "NEXT" && !p.hasNext || action == "PREV" && p.index == 0) return false
            read(s, p.index + if (action == "NEXT") 1 else -1, p.token); return true
        }
        if (action !in setOf("CREATE", "UPDATE", "DELETE") || !s.snapshot().editable) return false
        val page = s.page ?: return false
        if (s.stamp != stamp(s.capture)) { s.state = DeclarationState.STALE; s.error = "LOCAL_PROGRAM_CHANGED"; emit(s); return false }
        if (action == "CREATE") {
            if (!page.canCreate || target != null || fields == null) return false
        } else if (!page.canEdit || page.rows.none { it.id == target && it.editable } || (fields == null) != (action == "DELETE")) return false
        s.state = DeclarationState.PENDING; s.busy = true; s.error = null; s.receipt = null; emit(s)
        val request = UUID.randomUUID()
        val future = try { api.mutate(s.capture.context, page, action, target, fields, request) }
            catch (_: Exception) { CompletableFuture.completedFuture(DeclarationResult.Failure("DISPATCH_FAILED", true)) }
        future.whenCompleteAsync({ value, error ->
            if (live(s)) {
                s.busy = false
                when (val result = if (error != null || value == null) DeclarationResult.Failure("COMPLETION_FAILED", true) else value) {
                    is DeclarationResult.Success -> {
                        s.receipt = result.value; s.page = page.copy(version = result.value.version)
                        s.state = DeclarationState.APPLIED_TO_WORKSPACE; s.projection = "PENDING"; emit(s)
                        mutated(s.capture.context, s.key)
                        read(s, 0, null)
                    }
                    is DeclarationResult.Rejected -> {
                        s.error = result.code
                        s.state = when {
                            result.status >= 500 -> DeclarationState.UNKNOWN
                            result.code == "RELOAD_REQUIRED" -> DeclarationState.RELOAD_REQUIRED
                            result.status == 409 -> DeclarationState.STALE
                            result.status == 422 -> DeclarationState.REJECTED
                            else -> DeclarationState.RELOAD_REQUIRED
                        }
                        emit(s)
                    }
                    is DeclarationResult.Failure -> {
                        s.error = result.code; s.state = if (result.unknown) DeclarationState.UNKNOWN else DeclarationState.REJECTED; emit(s)
                    }
                }
            }
        }, main)
        return true
    }
    private fun read(s: Session, page: Int, token: String?) {
        if (!live(s)) return
        val generation = ++s.readGeneration
        val programChange = programChanges[s.capture.context.transport.programId]
        val previousState = s.state
        s.busy = true; emit(s)
        val future = try { api.read(s.capture.context, "declaration-editor", page, token) }
            catch (_: Exception) { CompletableFuture.completedFuture(DeclarationResult.Failure("READ_DISPATCH_FAILED")) }
        future.whenCompleteAsync({ value, error ->
            if (live(s) && s.readGeneration == generation) {
                s.busy = false
                when (val result = if (error != null || value == null) DeclarationResult.Failure("READ_COMPLETION_FAILED") else value) {
                    is DeclarationResult.Success -> {
                        val view = result.value
                        if (programChange != programChanges[s.capture.context.transport.programId]) {
                            s.state = if (previousState in setOf(DeclarationState.UNKNOWN, DeclarationState.APPLIED_TO_WORKSPACE)) previousState else DeclarationState.STALE
                            s.error = "LOCAL_PROGRAM_CHANGED"; s.projection = "STALE"; emit(s)
                            return@whenCompleteAsync
                        }
                        if (view.version.epoch != s.capture.fence.workspaceEpoch || view.version.revision < s.capture.fence.workingRevision ||
                            (s.receipt != null && view.version.revision < s.receipt!!.version.revision)) {
                            s.state = DeclarationState.RELOAD_REQUIRED; s.error = "WORKSPACE_VERSION_CHANGED"; emit(s)
                        } else {
                            s.page = view; s.busy = true; s.projection = "PENDING"
                            s.state = if (previousState == DeclarationState.UNKNOWN) DeclarationState.UNKNOWN
                                else if (s.receipt != null) DeclarationState.APPLIED_TO_WORKSPACE else DeclarationState.READY
                            if (previousState != DeclarationState.UNKNOWN) s.error = if (view.scopeAvailable) null else "SCOPE_UNAVAILABLE"
                            emit(s)
                            val frame = try { reconcile(s.capture, view) { live(s) && s.readGeneration == generation } }
                                catch (_: Exception) { CompletableFuture.completedFuture(DeclarationProjection(false, null)) }
                            frame.whenCompleteAsync({ committed, failure ->
                                if (live(s) && s.readGeneration == generation) {
                                    s.busy = false; s.projection = if (failure == null && committed?.committed == true && committed.stamp == stamp(s.capture) &&
                                        programChange == programChanges[s.capture.context.transport.programId]) "CURRENT" else "FAILED"
                                    s.stamp = committed?.stamp
                                    if (s.projection == "FAILED") s.error = "PROJECTION_RECONCILIATION_REQUIRED"
                                    emit(s)
                                }
                            }, main)
                        }
                    }
                    is DeclarationResult.Rejected -> {
                        s.error = result.code
                        s.state = if (previousState == DeclarationState.UNKNOWN) DeclarationState.UNKNOWN
                            else if (previousState == DeclarationState.APPLIED_TO_WORKSPACE) previousState
                            else if (result.code == "RELOAD_REQUIRED") DeclarationState.RELOAD_REQUIRED else DeclarationState.STALE
                        s.projection = "STALE"; emit(s)
                    }
                    is DeclarationResult.Failure -> {
                        s.error = result.code; s.state = if (previousState in setOf(DeclarationState.UNKNOWN, DeclarationState.APPLIED_TO_WORKSPACE)) previousState else DeclarationState.STALE
                        s.projection = "STALE"; emit(s)
                    }
                }
            }
        }, main)
    }
    private fun emit(s: Session) { changed(s.snapshot()) }
}
