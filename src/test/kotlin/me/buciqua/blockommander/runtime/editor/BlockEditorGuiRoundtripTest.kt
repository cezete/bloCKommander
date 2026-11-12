package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.network.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class BlockEditorGuiRoundtripTest {
    private val f = BlockEditorFixtures
    private inner class Rig {
        val ui = EditorUiModel(f.player)
        val opened = BlockEditorResponseParser.get(f.fixture("start-get"))
        val lifecycle = EditorLifecycle(1, 2, 3, 4, f.epoch)
        var sequence = 0L
        var applyCount = 0
        var getCount = 0
        var validation = false
        var unknown = false
        var projectionFails = false
        var live = true
        lateinit var request: BlockEditorRequestContext
        var pending = CompletableFuture<BlockEditorTransportOutcome<EditorApplyResult>>()
        val sessions = BlockEditorSessionCoordinator<String>(
            recapture = { _, basis, request -> if (!live) null else EditorCapture(f.context().copy(requestId = request,
                applyBasis = basis?.let { EditorApplyBasis(it.schemaRef, it.versionContext.stageWorkspaceEpoch, it.versionContext.workingRevision) }), lifecycle) },
            get = { c -> getCount++; CompletableFuture.completedFuture(BlockEditorTransportOutcome(c, BlockEditorResult.Success(opened))) },
            apply = { c, _ -> applyCount++; request = c; pending },
            reconcile = { _, _ -> CompletableFuture.completedFuture("prepared") },
            mainThread = Executor { it.run() }, changed = ::deliver,
        )
        private fun deliver(snapshot: EditorSessionSnapshot) {
            if (snapshot.reconciliation == EditorReconciliationState.AVAILABLE_NOT_PROJECTED && snapshot.state != EditorSessionState.CLOSED) {
                sessions.withReconciliation(snapshot.id) {
                    sessions.projectionCompleted(snapshot.id, MetadataCommitResult(
                        if (projectionFails) MetadataCommitStatus.PROJECTION_FAILED else MetadataCommitStatus.COMMITTED, "TEST_PROJECTION_ADAPTER"))
                }
            } else {
                ui.receive(EditorPacketWire.snapshot(EditorPacketWire.encode(EditorStateMapper.map(snapshot, ++sequence))))
            }
        }
        val port = object : EditorIntentPort {
            override fun current(key: EditorSessionKey) = sessions.snapshot(key.id())?.state?.let { it != EditorSessionState.CLOSED } == true
            override fun apply(intent: EditorIntent) = sessions.apply(intent.session.id(), EditorChanges(intent.label.domain(), intent.description.domain()))
            override fun close(key: EditorSessionKey) = sessions.close(key.id(), "CLIENT_CLOSE")
            override fun reload(key: EditorSessionKey): Boolean { sessions.open(EditorCapture(f.context(), lifecycle)); return true }
            override fun reconcile(key: EditorSessionKey) = sessions.reconcileAgain(key.id())
        }
        fun open() = sessions.open(EditorCapture(f.context(), lifecycle))!!
        fun send(intent: EditorIntent) = EditorIntentRouter.route(f.player, EditorPacketWire.intent(EditorPacketWire.encode(intent)), port)
        fun finish() {
            val result: BlockEditorResult<EditorApplyResult> = when {
                validation -> BlockEditorResult.ApiError(EditorApiError(422, "VALIDATION_FAILED", "Rejected", request.requestId,
                    listOf(EditorFieldDiagnostic("label", "STRING_TOO_LONG", "Too long", emptyMap()))))
                unknown -> BlockEditorResult.TransportFailure(EditorTransportFailureKind.TIMEOUT, true)
                else -> BlockEditorResult.Success(BlockEditorResponseParser.apply(f.fixture("start-apply")).copy(requestId = request.requestId))
            }
            pending.complete(BlockEditorTransportOutcome(request, result))
        }
    }
    @Test fun `GUI packet coordinator authoritative response and projection status roundtrip`() {
        val r = Rig(); r.open(); r.ui.editLabel("Draft"); val intent = r.ui.intent(EditorIntentAction.APPLY)!!
        assertTrue(r.send(intent)); assertFalse(r.send(intent)); assertEquals(EditorUiState.PENDING, r.ui.snapshot!!.state)
        assertEquals("Draft", r.ui.label); r.finish()
        assertEquals(EditorUiState.ACCEPTED, r.ui.snapshot!!.state); assertEquals("HTTP edited", r.ui.label)
        assertEquals("COMMITTED", r.ui.snapshot!!.projection); assertEquals(1, r.applyCount); assertEquals(1, r.getCount)
    }
    @Test fun `validation retry keeps draft and original server CAS across packet boundary`() {
        val r = Rig(); r.validation = true; r.open(); r.ui.editLabel("Draft"); r.send(r.ui.intent(EditorIntentAction.APPLY)!!)
        val basis = r.request.applyBasis; r.finish(); assertEquals("Draft", r.ui.label)
        assertEquals(EditorUiState.VALIDATION_ERROR, r.ui.snapshot!!.state)
        r.pending = CompletableFuture(); r.validation = false; r.ui.editLabel("Corrected"); r.send(r.ui.intent(EditorIntentAction.APPLY)!!)
        assertEquals(basis, r.request.applyBasis); r.finish(); assertEquals(2, r.applyCount)
    }
    @Test fun `UNKNOWN roundtrip remains uncertain after successful projection and no retry`() {
        val r = Rig(); r.unknown = true; r.open(); r.ui.editLabel("Draft"); r.send(r.ui.intent(EditorIntentAction.APPLY)!!); r.finish()
        assertEquals(EditorUiState.UNKNOWN, r.ui.snapshot!!.state); assertEquals("COMMITTED", r.ui.snapshot!!.projection)
        assertEquals("Draft", r.ui.label); assertFalse(r.ui.canApply); assertEquals(1, r.applyCount); assertEquals(2, r.getCount)
    }
    @Test fun `domain acceptance projection failure reload and late old CLOSE are distinct`() {
        val r = Rig(); val old = r.open(); r.projectionFails = true; r.ui.editLabel("Draft")
        r.send(r.ui.intent(EditorIntentAction.APPLY)!!); r.finish()
        assertEquals(EditorUiState.ACCEPTED, r.ui.snapshot!!.state); assertEquals("FAILED", r.ui.snapshot!!.projection)
        assertTrue(r.send(r.ui.intent(EditorIntentAction.RELOAD)!!)); val next = r.ui.snapshot!!.session
        assertTrue(next.generation > old.generation); assertFalse(r.send(EditorIntent(old.key(), EditorIntentAction.CLOSE)))
        assertEquals(EditorUiState.EDITING, r.ui.snapshot!!.state); assertEquals(next, r.ui.snapshot!!.session)
    }
    @Test fun `disconnect before APPLY response prevents client and session acceptance`() {
        val r = Rig(); val id = r.open(); r.ui.editLabel("Draft"); r.send(r.ui.intent(EditorIntentAction.APPLY)!!)
        r.live = false; r.sessions.invalidate(f.player, "DISCONNECT"); r.finish()
        assertEquals(EditorUiState.CLOSED, r.ui.snapshot!!.state); assertNull(r.sessions.snapshot(id)!!.accepted)
        assertFalse(r.ui.canApply)
    }
}
