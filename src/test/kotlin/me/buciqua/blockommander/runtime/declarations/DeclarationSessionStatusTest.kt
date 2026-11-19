package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.GraphRequestKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Real registry/status fences, production stamp proof, coordinator, reconciliation
 * and GUI model. Only HTTP and physical block/sign storage are isolated fakes. */
class DeclarationSessionStatusTest {
    private val f = DeclarationFixtures
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks += command }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private data class Frame(val view: DeclarationPage, val current: () -> Boolean, val result: CompletableFuture<DeclarationProjection>)
    private inner class Rig {
        val base = f.page("editor-empty").copy(version = DeclarationVersion(f.f.epoch, 16, 3, 3, false, false),
            total = 32, hasNext = true, rows = List(16) { i -> DeclarationRow(UUID(0, 1000L + i), f.context.stage,
                DeclarationFields("agent_%02d".format(i + 1), DeclarationType.INTEGER, DeclarationMutability.MUTABLE), DeclarationGroup.OWN, true) })
        val physical = DeclarationReconciliationTest().Rig(base)
        val queue = Queue()
        val model = DeclarationUiModel(f.context.player)
        val gets = mutableListOf<CompletableFuture<DeclarationResult<DeclarationPage>>>()
        val queries = mutableListOf<Pair<Int, String?>>()
        val writes = mutableListOf<CompletableFuture<DeclarationResult<DeclarationReceipt>>>()
        val requests = mutableListOf<Triple<String, DeclarationFields?, UUID>>()
        val frames = mutableListOf<Frame>()
        val events = mutableListOf<DeclarationSessionSnapshot>()
        var sequence = 0L
        var duringOptimism: () -> Unit = {}
        val api = object : DeclarationGateway {
            override fun read(c: DeclarationContext, endpoint: String, page: Int, token: String?): CompletableFuture<DeclarationResult<DeclarationPage>> {
                queries += page to token
                return CompletableFuture<DeclarationResult<DeclarationPage>>().also { gets += it }
            }
            override fun mutate(c: DeclarationContext, opened: DeclarationPage, operation: String, target: UUID?, fields: DeclarationFields?, request: UUID): CompletableFuture<DeclarationResult<DeclarationReceipt>> {
                assertEquals(base, opened)
                requests += Triple(operation, fields, request)
                return CompletableFuture<DeclarationResult<DeclarationReceipt>>().also { writes += it }
            }
        }
        fun stamp() = declarationProgramStamp(physical.registry, physical.registry.inWorld(f.context.world.toString()))
        val sessions = DeclarationSessionCoordinator(api,
            { physical.current && physical.registry.isStageFenceCurrent(it.fence) && physical.hall().stage!!.workspaceEpoch == it.fence.workspaceEpoch },
            { stamp() }, { _, view, current -> CompletableFuture<DeclarationProjection>().also { frames += Frame(view, current, it) } },
            queue, { events += it; model.receive(it.ui(++sequence)) }, optimisticStatus = {
                val before = stamp()
                assertNotNull(physical.registry.beginProgramStatus(physical.hall().id))
                physical.world.renderSaveState(physical.hall())
                duringOptimism()
                DeclarationOwnStatusChange.verified(before, stamp(), f.context.transport.hallId)
            })
        val key: EditorSessionKey
        init {
            key = sessions.open(physical.capture)!!
            readDone(); projectDone()
            assertEquals(DeclarationState.READY, snapshot().state)
            assertFalse(physical.hall().programDirty)
        }
        fun snapshot() = sessions.snapshot(key)!!
        fun readDone(page: DeclarationPage = base) { gets.last().complete(DeclarationResult.Success(page)); queue.drain() }
        fun projectDone() {
            val frame = frames.last()
            val h = physical.hall()
            val ok = DeclarationReconciliation(physical.registry, physical.wires).commit(physical.capture, frame.view, h,
                physical.registry.graphProjections.observe(physical.key)!!, physical.frame(frame.view), physical.world, frame.current)
            frame.result.complete(DeclarationProjection(ok, stamp())); queue.drain()
        }
        fun send(action: DeclarationAction = DeclarationAction.CREATE, name: String = "1counter"): Boolean {
            if (action in setOf(DeclarationAction.CREATE, DeclarationAction.UPDATE)) model.edit(DeclarationFields(name, DeclarationType.INTEGER, DeclarationMutability.MUTABLE))
            val intent = model.intent(action) ?: return false
            val decoded = DeclarationPacketWire.intent(DeclarationPacketWire.encode(intent))
            return sessions.intent(f.context.player, decoded.session, decoded.operation, decoded.action.name, decoded.target, decoded.fields)
        }
        fun reject(code: String = "INVALID_DECLARATION_NAME", status: Int = 422) {
            val request = requests.last().third
            val body = """{"api_version":"ck-declarations-v1","status":"error","error_code":"$code","message":"Declaration request rejected.","request_id":"$request","retry_automatically":false}"""
            writes.last().complete(DeclarationJson.error(body, status, request)); queue.drain()
        }
    }

    @ParameterizedTest
    @CsvSource("1counter,INVALID_DECLARATION_NAME", "my-var,INVALID_DECLARATION_NAME", "my var,INVALID_DECLARATION_NAME",
        "árvíztűrő,INVALID_DECLARATION_NAME", "LONG,INVALID_DECLARATION_NAME", "agent_01,DUPLICATE_DECLARATION_NAME")
    fun `six invalid creates restore saved state and retain diagnostic draft revision and basis through polls`(raw: String, code: String) {
        val name = if (raw == "LONG") "a".repeat(65) else raw
        val r = Rig(); val initial = r.snapshot().page; val blocks = r.physical.hall().stage!!.blocks
        assertTrue(r.send(name = name)); assertTrue(r.physical.hall().programDirty); assertTrue(r.physical.boardDirty!!)
        r.reject(code)
        assertEquals(DeclarationState.REJECTED, r.snapshot().state); assertEquals(code, r.snapshot().error)
        assertEquals(0 to r.base.token, r.queries.last())
        repeat(5) { r.sessions.poll() }
        assertEquals(code, r.snapshot().error)
        r.readDone(); r.projectDone()
        repeat(5) { r.sessions.poll() }
        assertEquals(DeclarationState.REJECTED, r.snapshot().state); assertEquals(code, r.snapshot().error)
        assertEquals(initial, r.snapshot().page); assertEquals(16L, r.physical.hall().stage!!.workingRevision)
        assertEquals(3L, r.snapshot().page!!.version.programSaved); assertFalse(r.snapshot().page!!.version.programDirty)
        assertFalse(r.physical.hall().programDirty); assertFalse(r.physical.boardDirty!!)
        assertEquals(name, r.model.fields.name); assertTrue(r.model.draftDirty); assertTrue(r.model.editable)
        assertEquals(blocks, r.physical.hall().stage!!.blocks); assertEquals(0, r.physical.wireWrites)
        assertEquals(1, r.writes.size); assertEquals(16, r.snapshot().page!!.rows.size)
        assertFalse(declarationError(code).contains("REFRESH"))
    }
    @Test fun `successful CREATE advances once and follows authoritative GET without SAVE`() {
        val r = Rig(); assertTrue(r.send(name = "extra"))
        val next = r.base.copy(version = r.base.version.copy(revision = 17, stageDirty = true, programDirty = true), total = 34,
            basisDigest = "$DECLARATION_BASIS:" + "a".repeat(64), token = "$DECLARATION_BASIS:" + "b".repeat(64))
        val row = r.base.rows[0].copy(id = UUID(0, 9000), fields = DeclarationFields("extra", DeclarationType.INTEGER, DeclarationMutability.MUTABLE))
        r.writes.last().complete(DeclarationResult.Success(DeclarationReceipt(r.requests.last().third, "CREATE", row, true, r.base.version, next.version)))
        r.queue.drain(); assertEquals(0 to null, r.queries.last())
        r.readDone(next); r.projectDone(); repeat(3) { r.sessions.poll() }
        assertEquals(DeclarationState.APPLIED_TO_WORKSPACE, r.snapshot().state); assertTrue(r.snapshot().editable)
        assertEquals(17L, r.physical.hall().stage!!.workingRevision); assertTrue(r.physical.boardDirty!!)
        assertEquals(3L, r.snapshot().page!!.version.programSaved); assertEquals(1, r.writes.size)
    }
    @Test fun `real external change after validation makes view stale without erasing validation diagnostic`() {
        val r = Rig(); r.send(); r.reject(); r.readDone(); r.projectDone()
        val store = r.physical.registry.graphProjections
        store.apply(store.begin(r.physical.key, GraphRequestKind.COMMAND), r.physical.before.copy(workingRevision = 17, dirty = true))
        r.sessions.poll()
        assertEquals(DeclarationState.STALE, r.snapshot().state); assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error)
        assertEquals("STALE", r.snapshot().projection); assertFalse(r.model.editable)
        assertNull(r.model.snapshot!!.programDirty)
    }
    @Test fun `concurrent real change inside optimism is never acknowledged as own status or dispatched`() {
        val r = Rig()
        r.duringOptimism = {
            val store = r.physical.registry.graphProjections
            store.apply(store.begin(r.physical.key, GraphRequestKind.COMMAND), r.physical.before.copy(workingRevision = 17, dirty = true))
        }
        assertFalse(r.send()); assertEquals(0, r.writes.size)
        assertEquals(DeclarationState.STALE, r.snapshot().state)
    }
    @Test fun `pinned recovery never silently refreshes CAS or parent basis`() {
        val r = Rig(); r.send(); r.reject()
        val changed = r.base.copy(basisDigest = "$DECLARATION_BASIS:" + "c".repeat(64))
        r.readDone(changed)
        assertEquals(r.base, r.snapshot().page); assertEquals(DeclarationState.STALE, r.snapshot().state)
        assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error); assertFalse(r.model.editable)
        assertEquals(1, r.frames.size) // initial open only
    }
    @Test fun `real HTTP epoch revision and parent basis conflicts retain their own code and require refresh`() {
        for (code in listOf("STALE_WORKSPACE_EPOCH", "STALE_WORKING_REVISION", "STALE_RESOLUTION_BASIS")) {
            val r = Rig(); r.send(); r.reject(code, 409); repeat(3) { r.sessions.poll() }
            assertEquals(DeclarationState.STALE, r.snapshot().state); assertEquals(code, r.snapshot().error)
            assertEquals(r.base, r.snapshot().page); assertEquals(1, r.gets.size); assertFalse(r.model.editable)
        }
    }
    @Test fun `UNKNOWN survives own and external status changes polls and explicit REFRESH without mutation retry`() {
        val r = Rig(); r.send()
        r.writes.last().complete(DeclarationResult.Failure("TIMEOUT", true)); r.queue.drain()
        repeat(4) { r.sessions.poll() }
        assertEquals(DeclarationState.UNKNOWN, r.snapshot().state); assertEquals("TIMEOUT", r.snapshot().error)
        assertTrue(r.send(DeclarationAction.REFRESH)); r.readDone(); r.projectDone()
        repeat(4) { r.sessions.poll() }
        assertEquals(DeclarationState.UNKNOWN, r.snapshot().state); assertEquals("TIMEOUT", r.snapshot().error)
        assertFalse(r.model.editable); assertNull(r.model.intent(DeclarationAction.CREATE))
        assertEquals(1, r.writes.size); assertNull(r.snapshot().receipt)
        r.sessions.programChanged(f.context, r.key.copy(nonce = UUID.randomUUID()))
        assertEquals(DeclarationState.UNKNOWN, r.snapshot().state); assertEquals("TIMEOUT", r.snapshot().error)
    }
    @Test fun `late mutation after CLOSE or navigation cannot restore status or notify a replacement session`() {
        for (navigate in listOf(false, true)) {
            val r = Rig(); r.send()
            if (navigate) { r.physical.registry.beginStageSwitch(r.physical.hall().id, f.context.player); r.sessions.poll() }
            else r.send(DeclarationAction.CLOSE)
            val events = r.events.size; val gets = r.gets.size
            r.reject()
            assertEquals(DeclarationState.CLOSED, r.snapshot().state); assertEquals(events, r.events.size); assertEquals(gets, r.gets.size)
        }
    }
    @Test fun `explicit refresh clears old diagnostic only after completion and another invalid CREATE stays rejected`() {
        val r = Rig(); r.send(); r.reject(); r.readDone(); r.projectDone()
        assertTrue(r.send(DeclarationAction.REFRESH)); assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error)
        r.readDone(); r.projectDone(); assertNull(r.snapshot().error); assertEquals("1counter", r.model.fields.name)
        assertTrue(r.send()); r.reject(); r.readDone(); r.projectDone(); repeat(3) { r.sessions.poll() }
        assertEquals(DeclarationState.REJECTED, r.snapshot().state); assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error)
        assertFalse(r.physical.boardDirty!!); assertEquals(2, r.writes.size)
    }
    @Test fun `failed recovery stays read-only with concrete diagnostic and no blind clean rollback`() {
        val r = Rig(); r.send(); r.reject()
        r.gets.last().complete(DeclarationResult.Failure("TIMEOUT")); r.queue.drain(); r.sessions.poll()
        assertEquals(DeclarationState.REJECTED, r.snapshot().state); assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error)
        assertFalse(r.model.editable); assertTrue(r.physical.hall().programDirty); assertTrue(r.physical.boardDirty!!)
        assertNull(r.model.snapshot!!.programDirty)
    }
    @Test fun `validation recovery never advertises an editable gap before its GET is pending`() {
        val r = Rig(); r.send(); r.reject()
        val rejected = r.events.filter { it.state == DeclarationState.REJECTED }
        assertTrue(rejected.isNotEmpty()); assertTrue(rejected.all { it.busy && !it.editable })
        assertNull(r.model.intent(DeclarationAction.CREATE)); assertNull(r.model.intent(DeclarationAction.REFRESH))
        assertEquals("INVALID_DECLARATION_NAME", r.snapshot().error)
        r.readDone(); r.projectDone(); assertTrue(r.model.editable)
    }
    @Test fun `late status repair after Stage change cannot write the board`() {
        val r = Rig(); r.send(); r.reject(); r.readDone()
        r.physical.registry.beginStageSwitch(r.physical.hall().id, f.context.player); r.sessions.poll()
        val calls = r.physical.boardCalls
        r.projectDone()
        assertEquals(calls, r.physical.boardCalls); assertEquals(DeclarationState.CLOSED, r.snapshot().state)
    }
    @Test fun `own proof rejects identity version hierarchy lifecycle and unrelated Hall changes`() {
        val r = Rig(); val before = r.stamp(); val own = before.halls.single()
        val cleanProof = before.copy(halls = listOf(own.copy(programDirty = true)))
        assertNotNull(DeclarationOwnStatusChange.verified(before, cleanProof, own.hall))
        val bad = listOf(own.copy(epoch = UUID.randomUUID()), own.copy(revision = 17), own.copy(hierarchy = 8),
            own.copy(projection = 99), own.copy(navigation = 99), own.copy(hallGeneration = 99),
            own.copy(transport = own.transport.copy(workingCopyId = UUID.randomUUID())), own.copy(programEpoch = UUID.randomUUID()))
        for (entry in bad) assertNull(DeclarationOwnStatusChange.verified(before, before.copy(halls = listOf(entry.copy(programDirty = true))), own.hall))
        val other = own.copy(hall = UUID(0, 500), programDirty = false)
        val multi = before.copy(halls = listOf(own, other))
        assertNull(DeclarationOwnStatusChange.verified(multi, multi.copy(halls = listOf(own.copy(programDirty = true), other.copy(programDirty = true))), own.hall))
    }
}
