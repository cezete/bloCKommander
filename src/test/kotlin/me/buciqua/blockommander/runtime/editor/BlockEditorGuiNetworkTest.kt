package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.*
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import io.netty.buffer.Unpooled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

/** Pure UI/draft and real packet codecs/router; no fake test is labelled live Minecraft acceptance. */
class BlockEditorGuiNetworkTest {
    private val f = BlockEditorFixtures
    private val id = EditorSessionKey(f.player, 1, 1, UUID(0, 991))
    private val fields = listOf(EditorUiField("label", "Felirat", 1, 32, true, false, true, true, 10, false),
        EditorUiField("description", "Leírás", 0, 256, false, true, true, true, 20, true))
    private fun state(phase: EditorUiState = EditorUiState.EDITING, sequence: Long = 1, session: EditorSessionKey = id) = EditorUiSnapshot(
        session, sequence, phase, "START", if (phase == EditorUiState.OPENING) null else EditorUiSchema("ck.block.START.metadata", 1, fields),
        if (phase == EditorUiState.OPENING) null else EditorUiValues("Initial", null),
        phase in setOf(EditorUiState.EDITING, EditorUiState.VALIDATION_ERROR), emptyList(), "Domain", "NONE", "", false, null, null, false)
    private fun model() = EditorUiModel(f.player).also { assertTrue(it.receive(state())) }
    private fun apply() = EditorIntent(id, EditorIntentAction.APPLY, EditorPatch(EditorPatchKind.VALUE, "Edited"))
    private fun rejected(bytes: ByteArray) { assertThrows(Exception::class.java) { EditorPacketWire.intent(bytes) } }

    @Test fun `intent roundtrip has only session action and changes`() {
        val intent = apply().copy(description = EditorPatch(EditorPatchKind.CLEAR))
        assertEquals(intent, EditorPacketWire.intent(EditorPacketWire.encode(intent)))
    }
    @Test fun `all control actions roundtrip without fields`() {
        for (action in listOf(EditorIntentAction.CLOSE, EditorIntentAction.RELOAD, EditorIntentAction.RECONCILE)) {
            val intent = EditorIntent(id, action); assertEquals(intent, EditorPacketWire.intent(EditorPacketWire.encode(intent)))
        }
    }
    @Test fun `complete snapshot schema diagnostics and nullable values roundtrip`() {
        val snapshot = state().copy(diagnostics = listOf(EditorUiDiagnostic("label", "STRING_TOO_LONG", "Too long")),
            recovery = EditorUiValues("Recovered", ""), projection = "FAILED", projectionMessage = "SAVE_BOARD", programDirty = true)
        assertEquals(snapshot, EditorPacketWire.snapshot(EditorPacketWire.encode(snapshot)))
    }
    @Test fun `OPENING packet has no editable schema values or CAS`() {
        val snapshot = state(EditorUiState.OPENING); assertEquals(snapshot, EditorPacketWire.snapshot(EditorPacketWire.encode(snapshot)))
        assertNull(snapshot.schema); assertNull(snapshot.values); assertFalse(snapshot.canApply)
    }
    @Test fun `oversized payload rejected before parsing`() = rejected(ByteArray(EDITOR_PACKET_LIMIT + 1))
    @Test fun `oversized label rejected at encode`() {
        assertThrows(IllegalArgumentException::class.java) { EditorPacketWire.encode(apply().copy(label = EditorPatch(EditorPatchKind.VALUE, "a".repeat(129)))) }
    }
    @Test fun `oversized description rejected at encode`() {
        assertThrows(IllegalArgumentException::class.java) { EditorPacketWire.encode(apply().copy(description = EditorPatch(EditorPatchKind.VALUE, "a".repeat(1025)))) }
    }
    @Test fun `unsupported packet version rejected`() { val bytes = EditorPacketWire.encode(apply()); bytes[3] = 9; rejected(bytes) }
    @Test fun `unknown action tag rejected`() { val bytes = EditorPacketWire.encode(apply()); bytes[52] = 127; rejected(bytes) }
    @Test fun `unknown patch tag rejected`() { val bytes = EditorPacketWire.encode(apply()); bytes[53] = 127; rejected(bytes) }
    @Test fun `every truncated intent is rejected`() {
        val bytes = EditorPacketWire.encode(apply()); for (length in bytes.indices) rejected(bytes.copyOf(length))
    }
    @Test fun `every truncated snapshot is rejected`() {
        val bytes = EditorPacketWire.encode(state())
        for (length in bytes.indices) assertThrows(Exception::class.java) { EditorPacketWire.snapshot(bytes.copyOf(length)) }
    }
    @Test fun `forged target appended to intent rejected`() = rejected(EditorPacketWire.encode(apply()) + "hall_id=other;instance_id=other".toByteArray())
    @Test fun `forged CAS and schema appended to intent rejected`() = rejected(EditorPacketWire.encode(apply()) + "epoch=x;revision=999;schema_id=other".toByteArray())
    @Test fun `malformed UTF8 rejected`() {
        val bytes = EditorPacketWire.encode(apply().copy(label = EditorPatch(EditorPatchKind.VALUE, "x")))
        bytes[58] = 0x80.toByte(); rejected(bytes)
    }
    @Test fun `empty APPLY and nullable label rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EditorPacketWire.encode(EditorIntent(id, EditorIntentAction.APPLY)) }
        assertThrows(IllegalArgumentException::class.java) { EditorPacketWire.encode(apply().copy(label = EditorPatch(EditorPatchKind.CLEAR))) }
    }
    @Test fun `control action cannot carry changes`() {
        assertThrows(IllegalArgumentException::class.java) { EditorPacketWire.encode(apply().copy(action = EditorIntentAction.RELOAD)) }
    }
    @Test fun `real Fabric intent codec roundtrip and outer trailing data rejection`() {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        try {
            EditorIntentPayload.CODEC.encode(buffer, EditorIntentPayload(apply()))
            assertEquals(apply(), EditorIntentPayload.CODEC.decode(buffer).intent)
            buffer.clear(); EditorIntentPayload.CODEC.encode(buffer, EditorIntentPayload(apply())); buffer.writeByte(1)
            assertThrows(IllegalArgumentException::class.java) { EditorIntentPayload.CODEC.decode(buffer) }
        } finally { buffer.release() }
    }
    @Test fun `real Fabric snapshot codec roundtrip`() {
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        try { EditorStatePayload.CODEC.encode(buffer, EditorStatePayload(state())); assertEquals(state(), EditorStatePayload.CODEC.decode(buffer).snapshot) }
        finally { buffer.release() }
    }
    private inner class Port : EditorIntentPort {
        var active = id; var pending = false; var calls = 0; var closed = false
        override fun current(key: EditorSessionKey) = !closed && key == active
        override fun apply(intent: EditorIntent): Boolean { if (pending) return false; pending = true; calls++; return true }
        override fun close(key: EditorSessionKey) { closed = true }
        override fun reload(key: EditorSessionKey): Boolean { active = key.copy(generation = key.generation + 1, nonce = UUID.randomUUID()); calls++; return true }
        override fun reconcile(key: EditorSessionKey): Boolean { calls++; return true }
    }
    @Test fun `foreign player session rejected before port dispatch`() {
        val port = Port(); assertFalse(EditorIntentRouter.route(UUID.randomUUID(), apply(), port)); assertEquals(0, port.calls)
    }
    @Test fun `stale session nonce rejected`() {
        val port = Port(); assertFalse(EditorIntentRouter.route(f.player, apply().copy(session = id.copy(nonce = UUID.randomUUID())), port)); assertEquals(0, port.calls)
    }
    @Test fun `duplicate APPLY reaches port only once as an accepted operation`() {
        val port = Port(); assertTrue(EditorIntentRouter.route(f.player, apply(), port)); assertFalse(EditorIntentRouter.route(f.player, apply(), port)); assertEquals(1, port.calls)
    }
    @Test fun `RELOAD rotates identity and old commands rejected`() {
        val port = Port(); assertTrue(EditorIntentRouter.route(f.player, EditorIntent(id, EditorIntentAction.RELOAD), port))
        assertFalse(EditorIntentRouter.route(f.player, apply(), port)); assertEquals(1, port.calls)
    }
    @Test fun `disconnect closes port and late intent rejected`() {
        val port = Port(); port.closed = true; assertFalse(EditorIntentRouter.route(f.player, apply(), port)); assertEquals(0, port.calls)
    }
    @Test fun `connection generation mismatch rejected`() {
        val port = Port(); port.active = id.copy(connection = 2); assertFalse(EditorIntentRouter.route(f.player, apply(), port))
    }
    @Test fun `OPENING to EDITING installs authoritative values`() {
        val model = EditorUiModel(f.player); model.receive(state(EditorUiState.OPENING)); assertFalse(model.editable)
        model.receive(state(sequence = 2)); assertTrue(model.editable); assertEquals("Initial", model.label); assertTrue(model.descriptionNull)
    }
    @Test fun `draft stays separate and two fields produce a single intent`() {
        val model = model(); model.editLabel("Edited"); model.editDescription("Text")
        assertEquals("Initial", model.snapshot!!.values!!.label)
        val intent = model.intent(EditorIntentAction.APPLY)!!; assertEquals("Edited", intent.label.value); assertEquals("Text", intent.description.value)
        assertNull(model.intent(EditorIntentAction.APPLY)); assertEquals("Edited", model.label)
    }
    @Test fun `unchanged fields are MISSING and empty description is VALUE not null`() {
        val model = model(); model.editDescription(""); val intent = model.intent(EditorIntentAction.APPLY)!!
        assertEquals(EditorPatchKind.MISSING, intent.label.kind); assertEquals(EditorPatch(EditorPatchKind.VALUE, ""), intent.description)
    }
    @Test fun `explicit description null yields CLEAR`() {
        val model = EditorUiModel(f.player); model.receive(state().copy(values = EditorUiValues("Initial", "Text")))
        model.clearDescription(); assertEquals(EditorPatchKind.CLEAR, model.intent(EditorIntentAction.APPLY)!!.description.kind)
    }
    @Test fun `empty label blocked without normalization`() {
        val model = model(); model.editLabel(""); assertFalse(model.canApply); assertNotNull(model.fieldError("label"))
        model.editLabel(" x "); assertEquals(" x ", model.intent(EditorIntentAction.APPLY)!!.label.value)
    }
    @Test fun `Unicode codepoint maximum allows 32 emoji and rejects 33`() {
        val model = model(); model.editLabel("😀".repeat(32)); assertTrue(model.canApply)
        model.editLabel("😀".repeat(33)); assertFalse(model.canApply)
    }
    @Test fun `unpaired surrogate cannot be submitted`() {
        val model = model(); model.editLabel("\uD83D"); assertFalse(model.canApply); assertNotNull(model.fieldError("label"))
    }
    @Test fun `PENDING retains draft and forbids further editing`() {
        val model = model(); model.editLabel("Draft"); model.intent(EditorIntentAction.APPLY)
        model.receive(state(EditorUiState.PENDING, 2)); model.editLabel("Lost")
        assertEquals("Draft", model.label); assertFalse(model.canApply)
    }
    @Test fun `VALIDATION_ERROR retains draft and permits corrected intent`() {
        val model = model(); model.editLabel("Draft"); model.intent(EditorIntentAction.APPLY)
        model.receive(state(EditorUiState.VALIDATION_ERROR, 2).copy(diagnostics = listOf(EditorUiDiagnostic("label", "INVALID", "Invalid"))))
        assertEquals("Draft", model.label); assertTrue(model.editable); model.editLabel("Corrected")
        assertNotNull(model.intent(EditorIntentAction.APPLY))
    }
    @Test fun `CONFLICT forbids APPLY and requires confirmed reload for dirty draft`() {
        val model = model(); model.editLabel("Draft"); model.receive(state(EditorUiState.CONFLICT, 2))
        assertFalse(model.editable); assertNull(model.intent(EditorIntentAction.RELOAD))
        assertNotNull(model.intent(EditorIntentAction.RELOAD, true))
    }
    @Test fun `new authoritative session resets draft after explicit reload`() {
        val model = model(); model.editLabel("Draft"); assertNotNull(model.intent(EditorIntentAction.RELOAD, true))
        val next = id.copy(generation = 2, nonce = UUID.randomUUID()); model.receive(state(session = next, sequence = 2))
        assertEquals("Initial", model.label); assertFalse(model.dirty); assertTrue(model.editable)
    }
    @Test fun `ACCEPTED uses authoritative normalized values and preserves projection failure distinction`() {
        val model = model(); model.editLabel("  draft  "); model.intent(EditorIntentAction.APPLY)
        model.receive(state(EditorUiState.ACCEPTED, 2).copy(values = EditorUiValues("Authoritative", "Normalized"), projection = "FAILED", programDirty = true))
        assertEquals("Authoritative", model.label); assertEquals(EditorUiState.ACCEPTED, model.snapshot!!.state)
        assertEquals("FAILED", model.snapshot!!.projection); assertFalse(model.editable); assertTrue(model.canReconcile)
    }
    @Test fun `UNKNOWN with COMMITTED projection remains unknown and keeps local draft`() {
        val model = model(); model.editLabel("Draft"); model.intent(EditorIntentAction.APPLY)
        model.receive(state(EditorUiState.UNKNOWN, 2).copy(projection = "COMMITTED", recovery = EditorUiValues("Now", null)))
        assertEquals(EditorUiState.UNKNOWN, model.snapshot!!.state); assertEquals("Draft", model.label)
        assertFalse(model.canApply); assertTrue(model.canReconcile)
    }
    @Test fun `reconcile uses control intent and later allows explicit reload`() {
        val model = model(); model.receive(state(EditorUiState.ACCEPTED, 2))
        val intent = model.intent(EditorIntentAction.RECONCILE)!!; assertEquals(EditorPatchKind.MISSING, intent.label.kind)
        assertFalse(model.canReload); model.receive(state(EditorUiState.ACCEPTED, 3).copy(projection = "COMMITTED"))
        assertTrue(model.canReload)
    }
    @Test fun `late response sequence ignored`() {
        val model = model(); model.receive(state(EditorUiState.PENDING, 3))
        assertFalse(model.receive(state(sequence = 2))); assertEquals(EditorUiState.PENDING, model.snapshot!!.state)
    }
    @Test fun `old CLOSED never closes newer session window`() {
        val model = model(); val next = id.copy(generation = 2, nonce = UUID.randomUUID())
        model.receive(state(session = next, sequence = 2)); assertFalse(model.receive(state(EditorUiState.CLOSED, 3)))
        assertEquals(next, model.snapshot!!.session); assertTrue(model.editable)
    }
    @Test fun `CLOSE suppresses late nonterminal reopening`() {
        val model = model(); assertNotNull(model.intent(EditorIntentAction.CLOSE)); assertFalse(model.receive(state(sequence = 2)))
        assertTrue(model.receive(state(EditorUiState.CLOSED, 3))); assertFalse(model.editable)
    }
    @Test fun `world or disconnect closure invalidates draft and late response`() {
        val model = model(); model.transportClosed(); assertFalse(model.receive(state(sequence = 3))); assertFalse(model.editable)
    }
    @Test fun `queued response from old connection cannot cross reconnect`() {
        val fence = EditorClientConnectionFence(); val old = Any(); val next = Any()
        fence.join(old); val captured = fence.token; assertTrue(fence.current(captured)); fence.disconnect(old); fence.join(next)
        assertFalse(fence.current(captured)); assertFalse(fence.disconnect(old)); assertTrue(fence.current(next))
    }
    @Test fun `same generation with forged nonce does not replace UI`() {
        val model = model(); assertFalse(model.receive(state(session = id.copy(nonce = UUID.randomUUID()), sequence = 3)))
    }
    @Test fun `server state mapping separates UNKNOWN from projection success without CAS in view`() {
        val opened = BlockEditorResponseParser.get(f.fixture("start-get"))
        val server = EditorSessionSnapshot(id.id(), EditorSessionState.UNKNOWN, f.context(), opened, null, opened,
            BlockEditorResult.TransportFailure(EditorTransportFailureKind.TIMEOUT, true), null, false,
            EditorReconciliationState.COMMITTED, null, MetadataCommitResult(MetadataCommitStatus.COMMITTED, "CURRENT"))
        val view = EditorStateMapper.map(server, 1)
        assertEquals(EditorUiState.UNKNOWN, view.state); assertTrue(view.domainMessage.contains("bizonytalan"))
        assertEquals("COMMITTED", view.projection); assertFalse(view.canApply)
        assertEquals(view, EditorPacketWire.snapshot(EditorPacketWire.encode(view)))
    }
    private fun hall(): Hall {
        val c = f.context(); return Hall(f.hall.toString(), "Hall", f.player.toString(), "Owner", c.worldUuid.toString(), "STONE",
            BlockPos.ZERO, BlockPos(10, 10, 10), BlockPos.ZERO, "NORTH", emptyList(),
            Stage(f.stage.toString(), BlockPos(1, 1, 1), 7, 5, 7, false, f.epoch, 12,
                listOf(StageBlock(f.start.toString(), "START", BlockPos(2, 1, 2), "EAST", "Initial", ""))), c.transport)
    }
    @Test fun `clicked target resolves from server Hall and physical type`() {
        val hall = hall(); assertEquals(f.start.toString(), resolveBlockEditorTarget(hall, f.player, f.context().worldUuid, BlockPos(2, 1, 2), "START", false, true)!!.instanceId)
    }
    @Test fun `wrong owner world type or pending navigation cannot open editor`() {
        val hall = hall(); val pos = BlockPos(2, 1, 2); val world = f.context().worldUuid
        assertNull(resolveBlockEditorTarget(hall, UUID.randomUUID(), world, pos, "START", false, true))
        assertNull(resolveBlockEditorTarget(hall, f.player, UUID.randomUUID(), pos, "START", false, true))
        assertNull(resolveBlockEditorTarget(hall, f.player, world, pos, "STOP", false, true))
        assertNull(resolveBlockEditorTarget(hall, f.player, world, pos, "START", true, true))
        assertNull(resolveBlockEditorTarget(hall, f.player, world, pos, "START", false, false))
    }
    @Test fun `legacy form cannot write START STOP but other blocks remain supported`() {
        val block = hall().stage!!.blocks.single(); assertFalse(permitsLegacyBlockMetadata(block))
        assertFalse(permitsLegacyBlockMetadata(block.copy(type = "STOP"))); assertTrue(permitsLegacyBlockMetadata(block.copy(type = "IF")))
    }
    @Test fun `earlier server EDITING notification cannot unblock locally pending APPLY`() {
        val model = model(); model.editLabel("Draft"); assertNotNull(model.intent(EditorIntentAction.APPLY))
        model.receive(state(sequence = 2)); assertTrue(model.pending); assertNull(model.intent(EditorIntentAction.APPLY))
        assertEquals("Draft", model.label)
        model.receive(state(EditorUiState.VALIDATION_ERROR, 3)); assertFalse(model.pending); assertTrue(model.canApply)
    }
}
