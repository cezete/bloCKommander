package me.buciqua.blockommander.runtime.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class BlockEditorSessionTest {
    private val f = BlockEditorFixtures
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class Rig {
        val queue = Queue()
        var live = true
        var wrongBasis = false
        var context = f.context()
        var lifecycle = EditorLifecycle(1, 2, 3, 4, f.epoch)
        val gets = mutableListOf<Pair<BlockEditorRequestContext, CompletableFuture<BlockEditorTransportOutcome<EditorGetResult>>>>()
        val applies = mutableListOf<Triple<BlockEditorRequestContext, EditorChanges, CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>>>>()
        val frames = mutableListOf<CompletableFuture<String>>()
        val events = mutableListOf<EditorSessionSnapshot>()
        var worldWrites = 0
        var observer: (EditorSessionSnapshot) -> Unit = {}
        val sessions = BlockEditorSessionCoordinator(
            recapture = { _, opened, request -> if (!live) null else EditorCapture(context.copy(requestId = request,
                applyBasis = opened?.let { EditorApplyBasis(it.schemaRef, it.versionContext.stageWorkspaceEpoch,
                    it.versionContext.workingRevision + if (wrongBasis) 1 else 0) }), lifecycle) },
            get = { c -> CompletableFuture<BlockEditorTransportOutcome<EditorGetResult>>().also { gets.add(c to it) } },
            apply = { c, changes -> CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>>().also { applies.add(Triple(c, changes, it)) } },
            reconcile = { _, _ -> CompletableFuture<String>().also { frames.add(it) } },
            mainThread = queue,
            changed = { events.add(it); observer(it) },
        )
        fun open() = sessions.open(EditorCapture(context, lifecycle))!!
        fun view() = BlockEditorResponseParser.get(f.fixture("start-get"))
        fun getDone(index: Int = gets.lastIndex, result: BlockEditorResult<EditorGetResult> = BlockEditorResult.Success(view())) {
            val (c, future) = gets[index]; future.complete(BlockEditorTransportOutcome(c, result)); queue.drain()
        }
        fun editing(): EditorSessionId = open().also { getDone() }
        fun state(id: EditorSessionId) = sessions.snapshot(id)!!.state
        fun apply(id: EditorSessionId) = sessions.apply(id, EditorChanges(label = EditorStringChange.Value("input")))
        fun applyDone(result: BlockEditorResult<EditorApplyResult>? = null, modified: Boolean = true) {
            val (c, _, future) = applies.last()
            val parsed = BlockEditorResponseParser.apply(f.fixture("start-apply"))
            val value = if (modified) parsed else parsed.copy(modified = false,
                editor = parsed.editor.copy(versionContext = parsed.editor.versionContext.copy(workingRevision = 12)))
            future.complete(BlockEditorTransportOutcome(c, result ?: BlockEditorResult.Success(value.copy(requestId = c.requestId))))
            queue.drain()
        }
        fun error(status: Int, code: String = "TEST_ERROR") = BlockEditorResult.ApiError(
            EditorApiError(status, code, "Rejected", null, listOf(EditorFieldDiagnostic("label", "INVALID", "Invalid", emptyMap()))))
        fun unknown(result: BlockEditorResult<EditorApplyResult>): EditorSessionId = editing().also { apply(it); applyDone(result) }
    }

    @Test fun `opening GET completion is queued on main executor`() {
        val r = Rig(); val id = r.open()
        assertEquals(EditorSessionState.OPENING, r.state(id)); assertFalse(r.apply(id))
        val (c, future) = r.gets.single(); future.complete(BlockEditorTransportOutcome(c, BlockEditorResult.Success(r.view())))
        assertEquals(EditorSessionState.OPENING, r.state(id)); r.queue.drain()
        assertEquals(EditorSessionState.EDITING, r.state(id)); assertEquals(r.view(), r.sessions.snapshot(id)!!.opened)
    }
    @Test fun `GET failure is closed with structured failure`() {
        val r = Rig(); val id = r.open(); r.getDone(result = r.error(403))
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertNotNull(r.sessions.snapshot(id)!!.failure)
    }
    @Test fun `exceptional GET future is closed`() {
        val r = Rig(); val id = r.open(); r.gets.single().second.completeExceptionally(IllegalStateException()); r.queue.drain()
        assertEquals(EditorSessionState.CLOSED, r.state(id))
    }
    @Test fun `double OPEN discards late GET`() {
        val r = Rig(); val a = r.open(); val b = r.open(); r.getDone(1); r.getDone(0)
        assertTrue(b.generation > a.generation); assertNull(r.sessions.snapshot(a)); assertEquals(EditorSessionState.EDITING, r.state(b))
        assertTrue(r.events.any { it.id == a && it.closedReason == "NEW_OPEN" })
    }
    @Test fun `A B A never revives old session`() {
        val r = Rig(); val a = r.open(); val b = r.open(); val again = r.open()
        r.getDone(0); r.getDone(1); assertEquals(EditorSessionState.OPENING, r.state(again)); r.getDone(2)
        assertEquals(3, again.generation); assertFalse(r.apply(a)); assertFalse(r.apply(b))
    }
    @Test fun `disconnect while GET pending drops callback`() {
        val r = Rig(); val id = r.open(); r.live = false; r.sessions.poll(); r.getDone()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertNull(r.sessions.snapshot(id)!!.opened)
    }
    @Test fun `same UUID connection replacement closes session`() {
        val r = Rig(); val id = r.editing(); r.lifecycle = r.lifecycle.copy(connection = 2); r.sessions.poll()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertFalse(r.apply(id))
    }
    private fun identityChange(change: (BlockEditorRequestContext) -> BlockEditorRequestContext) {
        val r = Rig(); val id = r.open(); r.context = change(r.context); r.getDone()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertFalse(r.apply(id))
    }
    @Test fun `Hall change rejects callback`() = identityChange { it.copy(transport = it.transport.copy(hallId = UUID.randomUUID())) }
    @Test fun `Stage change rejects callback`() = identityChange { it.copy(stageId = UUID.randomUUID()) }
    @Test fun `world dimension change rejects callback`() = identityChange { it.copy(worldUuid = UUID.randomUUID()) }
    @Test fun `Program change rejects callback`() = identityChange { it.copy(transport = it.transport.copy(programId = UUID.randomUUID())) }
    @Test fun `WorkingCopy change rejects callback`() = identityChange { it.copy(transport = it.transport.copy(workingCopyId = UUID.randomUUID())) }
    @Test fun `instance replacement rejects callback`() = identityChange { it.copy(instanceId = UUID.randomUUID()) }
    private fun generationChange(change: (EditorLifecycle) -> EditorLifecycle) {
        val r = Rig(); val id = r.editing(); r.lifecycle = change(r.lifecycle); assertFalse(r.apply(id))
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertTrue(r.applies.isEmpty())
    }
    @Test fun `navigation ABA is rejected`() = generationChange { it.copy(navigation = it.navigation!! + 2) }
    @Test fun `Hall selection generation is rejected`() = generationChange { it.copy(hall = it.hall + 1) }
    @Test fun `Graph projection reload generation is rejected`() = generationChange { it.copy(projection = it.projection + 1) }
    @Test fun `workspace reload epoch is rejected`() = generationChange { it.copy(epoch = UUID.randomUUID()) }
    @Test fun `shutdown rejects pending callbacks and new opens`() {
        val r = Rig(); val id = r.open(); r.sessions.shutdown(); r.getDone()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertNull(r.sessions.open(EditorCapture(r.context, r.lifecycle)))
    }
    @Test fun `GET with new epoch requires explicit reload`() {
        val r = Rig(); val id = r.open(); r.getDone(result = BlockEditorResult.Success(r.view().let {
            it.copy(versionContext = it.versionContext.copy(stageWorkspaceEpoch = UUID.randomUUID())) }))
        assertEquals(EditorSessionState.CONFLICT, r.state(id)); assertFalse(r.apply(id))
    }
    @Test fun `APPLY authoritative values accepted and second APPLY blocked`() {
        val r = Rig(); val id = r.editing(); assertTrue(r.apply(id)); r.applyDone()
        assertEquals(EditorSessionState.ACCEPTED, r.state(id)); assertEquals("HTTP edited", r.sessions.snapshot(id)!!.accepted!!.editor.values.label)
        assertFalse(r.apply(id)); val next = r.editing(); assertTrue(r.apply(next))
    }
    @Test fun `no-op keeps revision and still requires GET`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(modified = false)
        assertEquals(12L, r.sessions.snapshot(id)!!.accepted!!.editor.versionContext.workingRevision); assertFalse(r.apply(id))
    }
    @Test fun `two fields are a single request and PENDING blocks duplicates`() {
        val r = Rig(); val id = r.editing(); val changes = EditorChanges(EditorStringChange.Value("label"), EditorStringChange.Clear)
        assertTrue(r.sessions.apply(id, changes)); assertFalse(r.apply(id)); assertEquals(1, r.applies.size)
        assertEquals(changes, r.applies.single().second)
    }
    @Test fun `original GET basis is retained on validation retry`() {
        val r = Rig(); val id = r.editing(); r.apply(id)
        val basis = r.applies.single().first.applyBasis; r.applyDone(r.error(422, "VALIDATION_FAILED"))
        assertEquals(EditorSessionState.VALIDATION_ERROR, r.state(id)); assertNotNull(r.sessions.snapshot(id)!!.failure)
        assertTrue(r.apply(id)); assertEquals(basis, r.applies.last().first.applyBasis)
        assertEquals(12L, basis!!.workingRevision)
    }
    private fun conflict(code: String) {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(r.error(409, code))
        assertEquals(EditorSessionState.CONFLICT, r.state(id)); assertFalse(r.apply(id)); assertEquals(1, r.gets.size)
    }
    @Test fun `stale epoch conflict`() = conflict("STALE_WORKSPACE_EPOCH")
    @Test fun `stale revision conflict`() = conflict("STALE_WORKING_REVISION")
    @Test fun `schema mismatch conflict`() = conflict("SCHEMA_MISMATCH")
    @Test fun `unrecognized 422 does not allow retry`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(r.error(422)); assertFalse(r.apply(id))
        assertEquals(EditorSessionState.CLOSED, r.state(id))
    }
    @Test fun `read only permission blocks APPLY`() {
        val r = Rig(); val id = r.open(); r.getDone(result = BlockEditorResult.Success(r.view().let { it.copy(permissions = it.permissions.copy(apply = false)) }))
        assertFalse(r.apply(id)); assertTrue(r.applies.isEmpty())
    }
    @Test fun `noneditable schema field blocks APPLY`() {
        val r = Rig(); val id = r.open(); val v = r.view(); r.getDone(result = BlockEditorResult.Success(v.copy(
            schema = v.schema.copy(fields = v.schema.fields.map { it.copy(editable = false) }))))
        assertFalse(r.apply(id)); assertTrue(r.applies.isEmpty())
    }
    @Test fun `late APPLY cannot mutate new session or rollback domain`() {
        val r = Rig(); val old = r.editing(); r.apply(old); val next = r.open(); r.applyDone()
        assertEquals(EditorSessionState.OPENING, r.state(next)); assertTrue(r.frames.isEmpty()); assertEquals(1, r.applies.size)
    }
    @Test fun `disconnect during APPLY drops result`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.sessions.invalidate(f.player, "DISCONNECT"); r.applyDone()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertNull(r.sessions.snapshot(id)!!.accepted); assertTrue(r.frames.isEmpty())
    }
    private fun unknown(result: BlockEditorResult<EditorApplyResult>) {
        val r = Rig(); val id = r.unknown(result)
        assertEquals(EditorSessionState.UNKNOWN, r.state(id)); assertEquals(2, r.gets.size); assertEquals(1, r.applies.size)
        assertTrue(r.sessions.snapshot(id)!!.recoveryPending); assertFalse(r.apply(id))
    }
    @Test fun `timeout starts GET only recovery`() = unknown(BlockEditorResult.TransportFailure(EditorTransportFailureKind.TIMEOUT, true))
    @Test fun `connection reset starts GET only recovery`() = unknown(BlockEditorResult.TransportFailure(EditorTransportFailureKind.UNAVAILABLE, true))
    @Test fun `malformed success starts GET only recovery`() = unknown(BlockEditorResult.ProtocolFailure("INVALID_EDITOR_RESPONSE", true))
    @Test fun `HTTP 500 starts GET only recovery`() { val r = Rig(); unknown(r.error(500)) }
    @Test fun `HTTP 503 starts GET only recovery`() { val r = Rig(); unknown(r.error(503)) }
    @Test fun `exceptional APPLY future is unknown`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applies.single().third.completeExceptionally(IllegalStateException()); r.queue.drain()
        assertEquals(EditorSessionState.UNKNOWN, r.state(id)); assertEquals(2, r.gets.size)
    }
    @Test fun `proven not sent failure does not recover or retry`() {
        val r = Rig(); val id = r.editing(); r.apply(id)
        r.applyDone(BlockEditorResult.TransportFailure(EditorTransportFailureKind.NOT_CONFIGURED, false))
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertEquals(1, r.gets.size); assertEquals(1, r.applies.size)
    }
    @Test fun `successful recovery matching desired values never proves APPLY`() {
        val r = Rig(); val id = r.unknown(BlockEditorResult.ProtocolFailure("MALFORMED", true)); val v = r.view().copy(values = EditorValues("input", null))
        r.getDone(result = BlockEditorResult.Success(v)); val snapshot = r.sessions.snapshot(id)!!
        assertEquals(EditorSessionState.UNKNOWN, snapshot.state); assertEquals(v, snapshot.recovery); assertNull(snapshot.accepted)
        assertEquals(12L, snapshot.opened!!.versionContext.workingRevision); assertFalse(r.apply(id)); assertEquals(1, r.applies.size)
    }
    @Test fun `failed recovery is noneditable and separate failure`() {
        val r = Rig(); val id = r.unknown(BlockEditorResult.ProtocolFailure("MALFORMED", true)); r.getDone(result = r.error(503))
        val s = r.sessions.snapshot(id)!!; assertEquals(EditorSessionState.UNKNOWN, s.state); assertNotNull(s.recoveryFailure)
        assertFalse(s.recoveryPending); assertFalse(r.apply(id)); assertTrue(r.frames.isEmpty())
    }
    @Test fun `late recovery GET is discarded`() {
        val r = Rig(); val id = r.unknown(BlockEditorResult.ProtocolFailure("MALFORMED", true)); r.sessions.close(id, "CLOSE"); r.getDone()
        assertNull(r.sessions.snapshot(id)!!.recovery); assertTrue(r.frames.isEmpty())
    }
    @Test fun `accepted APPLY does not project or synchronize Graph`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone()
        assertEquals(EditorReconciliationState.PENDING, r.sessions.snapshot(id)!!.reconciliation); assertEquals(0, r.worldWrites)
    }
    @Test fun `reconciliation handoff succeeds only once and makes no synchronization claim`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(); r.frames.single().complete("prepared"); r.queue.drain()
        assertEquals(EditorReconciliationState.AVAILABLE_NOT_PROJECTED, r.sessions.snapshot(id)!!.reconciliation)
        assertTrue(r.sessions.withReconciliation(id) { assertEquals("prepared", it); r.worldWrites++ })
        assertFalse(r.sessions.withReconciliation(id) { r.worldWrites++ }); assertEquals(1, r.worldWrites)
    }
    @Test fun `reconciliation failure preserves accepted domain result`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(); r.frames.single().completeExceptionally(IllegalStateException()); r.queue.drain()
        assertEquals(EditorSessionState.ACCEPTED, r.state(id)); assertNotNull(r.sessions.snapshot(id)!!.accepted)
        assertEquals(EditorReconciliationState.FAILED, r.sessions.snapshot(id)!!.reconciliation)
    }
    @Test fun `late reconciliation never reaches world consumer`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(); r.open(); r.frames.single().complete("old"); r.queue.drain()
        assertFalse(r.sessions.withReconciliation(id) { r.worldWrites++ }); assertEquals(0, r.worldWrites)
    }
    @Test fun `Stage switch during reconciliation drops frame`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(); r.lifecycle = r.lifecycle.copy(navigation = 8)
        r.frames.single().complete("old"); r.queue.drain(); assertEquals(EditorSessionState.CLOSED, r.state(id))
        assertFalse(r.sessions.withReconciliation(id) { r.worldWrites++ }); assertEquals(0, r.worldWrites)
    }
    @Test fun `lifecycle rechecked immediately before prepared handoff`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.applyDone(); r.frames.single().complete("old"); r.queue.drain()
        r.live = false; assertFalse(r.sessions.withReconciliation(id) { r.worldWrites++ }); assertEquals(0, r.worldWrites)
    }
    @Test fun `stale session snapshot closes rather than delivering editable data`() {
        val r = Rig(); val id = r.editing(); r.live = false; assertFalse(r.sessions.snapshot(id)!!.canApply)
    }
    @Test fun `reentrant close during pending notification prevents HTTP start`() {
        val r = Rig(); val id = r.editing(); r.observer = { if (it.state == EditorSessionState.PENDING) r.sessions.close(it.id, "CLOSE") }
        assertFalse(r.apply(id)); assertTrue(r.applies.isEmpty())
    }
    @Test fun `old disconnect does not invalidate replacement connection`() {
        val invalidations = mutableListOf<UUID>(); val tracker = EditorConnections { invalidations.add(it) }
        val old = Any(); val next = Any(); tracker.join(f.player, old); val generation = tracker.generation(f.player, old)!!
        tracker.join(f.player, old); assertEquals(1, invalidations.size)
        tracker.join(f.player, next); assertTrue(tracker.generation(f.player, next)!! > generation)
        tracker.disconnect(f.player, old); assertNotNull(tracker.generation(f.player, next)); assertEquals(2, invalidations.size)
        tracker.disconnect(f.player, next); assertNull(tracker.generation(f.player, next)); assertEquals(3, invalidations.size)
        tracker.join(f.player, old); assertTrue(tracker.generation(f.player, old)!! > generation + 1)
    }
    @Test fun `invalid lifecycle at GET start sends no request`() {
        val r = Rig(); r.live = false; val id = r.open()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertTrue(r.gets.isEmpty())
    }
    @Test fun `recapture cannot refresh original CAS from a newer registry revision`() {
        val r = Rig(); val id = r.editing(); r.wrongBasis = true
        assertFalse(r.apply(id)); assertTrue(r.applies.isEmpty()); assertEquals(EditorSessionState.CLOSED, r.state(id))
    }
    @Test fun `callback context mismatch is unknown rather than accepted`() {
        val r = Rig(); val id = r.editing(); r.apply(id)
        val (c, _, future) = r.applies.single()
        future.complete(BlockEditorTransportOutcome(c.copy(requestId = UUID.randomUUID()),
            BlockEditorResult.Success(BlockEditorResponseParser.apply(f.fixture("start-apply")))))
        r.queue.drain(); assertEquals(EditorSessionState.UNKNOWN, r.state(id)); assertNull(r.sessions.snapshot(id)!!.accepted)
    }
    @Test fun `shutdown during APPLY cannot undo domain or accept late result`() {
        val r = Rig(); val id = r.editing(); r.apply(id); r.sessions.shutdown(); r.applyDone()
        assertEquals(EditorSessionState.CLOSED, r.state(id)); assertEquals(1, r.applies.size); assertTrue(r.frames.isEmpty())
    }
    @Test fun `reentrant OPEN from notification cannot orphan another active session`() {
        val r = Rig(); val first = r.editing(); var attempted = false
        var reentrant: EditorSessionId? = null
        r.observer = { if (it.state == EditorSessionState.CLOSED) {
            attempted = true; reentrant = r.sessions.open(EditorCapture(r.context, r.lifecycle))
        } }
        val next = r.open(); assertTrue(attempted); assertNull(reentrant); assertNull(r.sessions.snapshot(first))
        assertEquals(EditorSessionState.OPENING, r.state(next))
    }
}
