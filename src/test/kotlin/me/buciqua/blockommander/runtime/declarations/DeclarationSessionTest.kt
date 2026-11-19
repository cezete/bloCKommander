package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.network.EditorSessionKey
import me.buciqua.blockommander.network.ui
import me.buciqua.blockommander.runtime.StageUpdateFence
import me.buciqua.blockommander.runtime.projectionKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class DeclarationSessionTest {
    private val f = DeclarationFixtures
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class Rig {
        val queue = Queue()
        val capture = DeclarationCapture(f.context, 1, StageUpdateFence(f.context.transport.hallId.toString(), f.context.stage.toString(),
            f.f.epoch, 0, f.context.transport.projectionKey(f.context.stage), 2, 3, 4), "minecraft:overworld")
        var actual = capture
        var stamp = 0
        val gets = mutableListOf<CompletableFuture<DeclarationResult<DeclarationPage>>>()
        val readArgs = mutableListOf<Pair<Int, String?>>()
        val writes = mutableListOf<CompletableFuture<DeclarationResult<DeclarationReceipt>>>()
        val commands = mutableListOf<Triple<DeclarationPage, String, UUID?>>()
        val reconciliations = mutableListOf<CompletableFuture<DeclarationProjection>>()
        val events = mutableListOf<DeclarationSessionSnapshot>()
        val gateway = object : DeclarationGateway {
            override fun read(c: DeclarationContext, endpoint: String, page: Int, token: String?): CompletableFuture<DeclarationResult<DeclarationPage>> {
                assertEquals(f.context, c); assertEquals("declaration-editor", endpoint)
                readArgs += page to token
                return CompletableFuture<DeclarationResult<DeclarationPage>>().also { gets += it }
            }
            override fun mutate(c: DeclarationContext, opened: DeclarationPage, operation: String, target: UUID?, fields: DeclarationFields?, request: UUID): CompletableFuture<DeclarationResult<DeclarationReceipt>> {
                assertEquals(f.context, c); commands += Triple(opened, operation, target)
                return CompletableFuture<DeclarationResult<DeclarationReceipt>>().also { writes += it }
            }
        }
        val sessions = DeclarationSessionCoordinator(gateway, { actual == it }, { stamp }, { _, _, _ ->
            CompletableFuture<DeclarationProjection>().also { reconciliations += it }
        }, queue, { events += it })
        fun open() = sessions.open(capture)!!
        fun ready(view: DeclarationPage = f.page("editor-empty")) = open().also { readDone(view); reconciliationDone() }
        fun readDone(view: DeclarationPage = f.page("editor-empty"), index: Int = gets.lastIndex) { gets[index].complete(DeclarationResult.Success(view)); queue.drain() }
        fun reconciliationDone(ok: Boolean = true) { reconciliations.last().complete(DeclarationProjection(ok, stamp)); queue.drain() }
        fun snapshot(key: EditorSessionKey) = sessions.snapshot(key)!!
        fun intent(key: EditorSessionKey, seq: Long = 1, action: String = "CREATE", target: UUID? = null) = sessions.intent(key.player, key, seq, action, target,
            if (action in setOf("CREATE", "UPDATE")) DeclarationFields("counter", DeclarationType.INTEGER, DeclarationMutability.MUTABLE) else null)
        fun accepted(modified: Boolean = true) {
            val previous = commands.last().first.version
            val row = f.page().rows.first()
            writes.last().complete(DeclarationResult.Success(DeclarationReceipt(UUID.randomUUID(), commands.last().second, row, modified, previous,
                if (modified) previous.copy(revision = previous.revision + 1, stageDirty = true, programDirty = true) else previous)))
            queue.drain()
        }
    }
    @Test fun `OPENING single read completes only on server executor`() {
        val r = Rig(); val key = r.open()
        assertEquals(DeclarationState.OPENING, r.snapshot(key).state)
        r.gets.last().complete(DeclarationResult.Success(f.page("editor-empty")))
        assertEquals(DeclarationState.OPENING, r.snapshot(key).state)
        r.queue.drain(); assertTrue(r.snapshot(key).busy)
        r.reconciliationDone(); assertEquals(DeclarationState.READY, r.snapshot(key).state)
        assertEquals(1, r.gets.size)
    }
    @Test fun `mutation pending blocks duplicate dispatch preserves original CAS and reads after receipt`() {
        val r = Rig(); val key = r.ready()
        assertTrue(r.intent(key)); assertFalse(r.intent(key, 2))
        assertEquals(DeclarationState.PENDING, r.snapshot(key).state); assertEquals(1, r.writes.size)
        assertEquals(f.page("editor-empty").basisDigest, r.commands.single().first.basisDigest)
        r.accepted()
        assertEquals(DeclarationState.APPLIED_TO_WORKSPACE, r.snapshot(key).state)
        assertEquals(2, r.gets.size); assertFalse(r.intent(key, 3))
        r.readDone(f.page()); r.reconciliationDone()
        assertTrue(r.snapshot(key).editable); assertTrue(r.snapshot(key).receipt!!.modified)
    }
    @Test fun `mutation success and reconciliation failure remains applied with GET data`() {
        val r = Rig(); val key = r.ready(); r.intent(key); r.accepted(); r.readDone(f.page()); r.reconciliationDone(false)
        assertEquals(DeclarationState.APPLIED_TO_WORKSPACE, r.snapshot(key).state)
        assertEquals("FAILED", r.snapshot(key).projection); assertNotNull(r.snapshot(key).receipt)
        assertFalse(r.snapshot(key).editable); assertEquals(1, r.writes.size)
    }
    @Test fun `UNKNOWN has no automatic retry or GET and explicit read cannot prove lost request success`() {
        val r = Rig(); val key = r.ready(); r.intent(key)
        r.writes.last().complete(DeclarationResult.Failure("TIMEOUT", true)); r.queue.drain()
        assertEquals(DeclarationState.UNKNOWN, r.snapshot(key).state); assertEquals(1, r.gets.size)
        assertFalse(r.intent(key, 2)); assertTrue(r.intent(key, 3, "REFRESH"))
        r.readDone(f.page()); r.reconciliationDone()
        assertEquals(DeclarationState.UNKNOWN, r.snapshot(key).state); assertNull(r.snapshot(key).receipt)
        assertNull(r.snapshot(key).ui(1).programDirty)
        assertFalse(r.snapshot(key).editable); assertEquals(1, r.writes.size)
    }
    @Test fun `CAS and parent basis conflict are stale without replacing GET basis`() {
        for (code in listOf("STALE_WORKING_REVISION", "STALE_WORKSPACE_EPOCH", "STALE_RESOLUTION_BASIS", "SCOPE_UNAVAILABLE")) {
            val r = Rig(); val key = r.ready(); val original = r.snapshot(key).page
            r.intent(key); r.writes.last().complete(DeclarationResult.Rejected(409, code)); r.queue.drain()
            assertEquals(DeclarationState.STALE, r.snapshot(key).state); assertEquals(original, r.snapshot(key).page)
            assertFalse(r.intent(key, 2)); assertEquals(1, r.writes.size)
        }
    }
    @Test fun `validation error preserves editable authoritative view and draft can be corrected`() {
        val r = Rig(); val key = r.ready(); r.intent(key)
        r.writes.last().complete(DeclarationResult.Rejected(422, "INVALID_DECLARATION_NAME")); r.queue.drain()
        r.readDone(); r.reconciliationDone()
        assertEquals(DeclarationState.REJECTED, r.snapshot(key).state); assertTrue(r.snapshot(key).editable)
        assertTrue(r.intent(key, 2)); assertEquals(2, r.writes.size)
    }
    @Test fun `server failure after dispatch is UNKNOWN`() {
        val r = Rig(); val key = r.ready(); r.intent(key)
        r.writes.last().complete(DeclarationResult.Rejected(503, "REPOSITORY_UNAVAILABLE")); r.queue.drain()
        assertEquals(DeclarationState.UNKNOWN, r.snapshot(key).state)
    }
    @Test fun `close fences pending completion but does not claim backend cancellation`() {
        val r = Rig(); val key = r.ready(); r.intent(key); assertTrue(r.intent(key, 2, "CLOSE"))
        val before = r.events.size; r.accepted()
        assertEquals(DeclarationState.CLOSED, r.snapshot(key).state); assertEquals(before, r.events.size)
        assertEquals(1, r.writes.size); assertFalse(r.writes.single().isCancelled)
    }
    @Test fun `reopen drops out of order GET replies`() {
        val r = Rig(); val a = r.open(); val b = r.open()
        r.readDone(index = 1); r.reconciliationDone(); val before = r.events.size
        r.readDone(index = 0)
        assertEquals(before, r.events.size); assertEquals(DeclarationState.READY, r.snapshot(b).state)
        assertNull(r.sessions.snapshot(a)); assertNotEquals(a.nonce, b.nonce)
    }
    @Test fun `connection world dimension Hall Stage navigation projection and epoch changes close session`() {
        val changes = listOf<(DeclarationCapture) -> DeclarationCapture>(
            { it.copy(connection = 2) }, { it.copy(dimension = "minecraft:the_nether") },
            { it.copy(context = it.context.copy(world = UUID.randomUUID())) },
            { it.copy(context = it.context.copy(stage = UUID.randomUUID())) },
            { it.copy(context = it.context.copy(transport = it.context.transport.copy(hallId = UUID.randomUUID()))) },
            { it.copy(context = it.context.copy(transport = it.context.transport.copy(workingCopyId = UUID.randomUUID()))) },
            { it.copy(fence = it.fence.copy(navigationGeneration = 8)) }, { it.copy(fence = it.fence.copy(projectionGeneration = 8)) },
            { it.copy(fence = it.fence.copy(workspaceEpoch = UUID.randomUUID())) })
        for (change in changes) {
            val r = Rig(); val key = r.ready(); r.actual = change(r.capture); r.sessions.poll()
            assertEquals(DeclarationState.CLOSED, r.snapshot(key).state); assertFalse(r.intent(key))
        }
    }
    @Test fun `foreign player and forged nonce or connection cannot mutate`() {
        val r = Rig(); val key = r.ready()
        assertFalse(r.sessions.intent(UUID.randomUUID(), key, 1, "CREATE", null, null))
        assertFalse(r.intent(key.copy(nonce = UUID.randomUUID())))
        assertFalse(r.intent(key.copy(connection = 2))); assertEquals(0, r.writes.size)
    }
    @Test fun `UPDATE DELETE local UUID only and no-op preserves version`() {
        for (action in listOf("UPDATE", "DELETE")) {
            val r = Rig(); val key = r.ready(f.page()); val id = f.page().rows.first().id
            assertFalse(r.intent(key, action = action, target = UUID.randomUUID()))
            assertTrue(r.intent(key, 2, action, id)); r.accepted(action == "DELETE")
            assertEquals(action == "DELETE", r.snapshot(key).receipt!!.modified)
            assertEquals(f.page().version.revision + if (action == "DELETE") 1 else 0, r.snapshot(key).page!!.version.revision)
        }
    }
    @Test fun `known local changes stale the view before another dispatch`() {
        val r = Rig(); val key = r.ready(); r.stamp++; r.sessions.poll()
        assertEquals(DeclarationState.STALE, r.snapshot(key).state); assertFalse(r.intent(key))
        assertTrue(r.intent(key, 2, "REFRESH"))
    }
    @Test fun `parent notification marks other Program session stale`() {
        val r = Rig(); val key = r.ready()
        r.sessions.programChanged(f.context, key.copy(nonce = UUID.randomUUID()))
        assertEquals(DeclarationState.STALE, r.snapshot(key).state)
    }
    @Test fun `paging uses same basis token and stale read preserves previous page`() {
        val r = Rig(); val first = f.page().copy(hasNext = true, total = 18); val key = r.ready(first)
        assertTrue(r.intent(key, 1, "NEXT")); assertEquals(1 to first.token, r.readArgs.last())
        r.gets.last().complete(DeclarationResult.Rejected(409, "STALE_PAGE")); r.queue.drain()
        assertEquals(first, r.snapshot(key).page); assertEquals(DeclarationState.STALE, r.snapshot(key).state)
    }
    @Test fun `read failure after successful mutation retains applied receipt and prior view`() {
        val r = Rig(); val key = r.ready(); r.intent(key); r.accepted()
        r.gets.last().complete(DeclarationResult.Failure("READ_FAILED")); r.queue.drain()
        assertEquals(DeclarationState.APPLIED_TO_WORKSPACE, r.snapshot(key).state)
        assertNotNull(r.snapshot(key).receipt); assertEquals("STALE", r.snapshot(key).projection)
    }
    @Test fun `change between projection completion and session callback cannot publish CURRENT`() {
        val r = Rig(); val key = r.open(); r.readDone()
        r.reconciliations.last().complete(DeclarationProjection(true, r.stamp))
        r.stamp++; r.queue.drain()
        assertEquals("FAILED", r.snapshot(key).projection)
        assertFalse(r.snapshot(key).editable)
    }
    @Test fun `permission rejection cannot retain edit capability from previous GET`() {
        val r = Rig(); val key = r.ready(); r.intent(key)
        r.writes.last().complete(DeclarationResult.Rejected(403, "FORBIDDEN")); r.queue.drain()
        assertEquals(DeclarationState.RELOAD_REQUIRED, r.snapshot(key).state)
        assertFalse(r.snapshot(key).editable)
    }
    @Test fun `parent change while GET pending is not lost and requires explicit fresh read`() {
        val r = Rig(); val key = r.open()
        r.sessions.programChanged(f.context, key.copy(nonce = UUID.randomUUID()))
        r.readDone()
        assertEquals(DeclarationState.STALE, r.snapshot(key).state)
        assertEquals(0, r.reconciliations.size)
        assertTrue(r.intent(key, 1, "REFRESH")); r.readDone(); r.reconciliationDone()
        assertEquals(DeclarationState.READY, r.snapshot(key).state)
    }
}
