package me.buciqua.blockommander.runtime.declarations

import io.netty.buffer.Unpooled
import me.buciqua.blockommander.network.*
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DeclarationGuiTest {
    private val f = DeclarationFixtures
    private val key = EditorSessionKey(f.context.player, 1, 1, UUID(0, 55))
    private fun snapshot(page: DeclarationPage = f.page(), seq: Long = 1) = DeclarationUiSnapshot(key, seq, "minecraft:overworld",
        f.context.transport.programId, f.context.stage, DeclarationState.READY, false, page.rows, page.index, page.total, page.hasNext,
        page.canCreate, page.canEdit, page.version.programDirty, page.version.stageDirty, page.version.stageSaved, page.version.programSaved,
        page.version.revision, null, "CURRENT", null, 0)
    private fun model(page: DeclarationPage = f.page()) = DeclarationUiModel(key.player).also { assertTrue(it.receive(snapshot(page))) }
    private fun intent(action: DeclarationAction = DeclarationAction.CREATE) = DeclarationIntent(key, 1, action,
        if (action in setOf(DeclarationAction.UPDATE, DeclarationAction.DELETE)) UUID(0, 9000) else null,
        if (action in setOf(DeclarationAction.CREATE, DeclarationAction.UPDATE)) DeclarationFields("counter", DeclarationType.INTEGER, DeclarationMutability.MUTABLE) else null)

    @Test fun `real Fabric codecs roundtrip both bounded packets without context or CAS in C2S`() {
        for (action in DeclarationAction.entries) {
            val value = intent(action)
            val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
            try {
                DeclarationIntentPayload.CODEC.encode(buf, DeclarationIntentPayload(value))
                assertEquals(value, DeclarationIntentPayload.CODEC.decode(buf).intent)
            } finally { buf.release() }
        }
        val view = snapshot()
        val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
        try {
            DeclarationStatePayload.CODEC.encode(buf, DeclarationStatePayload(view))
            assertEquals(view, DeclarationStatePayload.CODEC.decode(buf).snapshot)
        } finally { buf.release() }
    }
    @Test fun `malformed oversized trailing truncated packets and illegal field shapes rejected`() {
        val bytes = DeclarationPacketWire.encode(intent())
        for (size in bytes.indices) assertThrows(Exception::class.java) { DeclarationPacketWire.intent(bytes.copyOf(size)) }
        val state = DeclarationPacketWire.encode(snapshot())
        for (size in state.indices) assertThrows(Exception::class.java) { DeclarationPacketWire.snapshot(state.copyOf(size)) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.intent(ByteArray(DECLARATION_PACKET_LIMIT + 1)) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.intent(bytes + byteArrayOf(1)) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.intent(bytes.copyOf().also { it[3] = 99 }) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.encode(intent().copy(target = UUID.randomUUID())) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.encode(intent().copy(operation = 0)) }
        assertThrows(Exception::class.java) { DeclarationPacketWire.encode(intent().copy(fields = intent().fields!!.copy(name = "a".repeat(129)))) }
    }
    @Test fun `sixteen maximum rows fit packet budget with no silent truncation`() {
        val rows = List(16) { i -> f.page().rows[0].copy(id = UUID(0, i.toLong()), fields = f.page().rows[0].fields.copy(name = "a".repeat(64))) }
        val s = snapshot().copy(rows = rows, total = 16)
        val bytes = DeclarationPacketWire.encode(s)
        assertTrue(bytes.size + 3 < 16384); assertEquals(rows, DeclarationPacketWire.snapshot(bytes).rows)
        assertThrows(Exception::class.java) { DeclarationPacketWire.encode(s.copy(rows = rows + rows[0])) }
    }
    @Test fun `local editable inherited and shadowed rows read only`() {
        val child = f.page("editor-child"); val m = model(child)
        for (row in child.rows) {
            assertTrue(m.select(row))
            assertEquals(row.group in setOf(DeclarationGroup.OWN, DeclarationGroup.LOCAL), m.editable)
            if (!row.editable) { val before = m.fields; m.edit(before.copy(name = "forged")); assertEquals(before, m.fields); assertNull(m.intent(DeclarationAction.UPDATE)) }
        }
    }
    @Test fun `CREATE UPDATE DELETE confirmation and pending double dispatch protection`() {
        val m = model(); assertTrue(m.select(m.snapshot!!.rows[0]))
        assertNull(m.intent(DeclarationAction.DELETE)); assertTrue(m.confirmDelete)
        val deletion = m.intent(DeclarationAction.DELETE, true)!!; assertEquals(m.selectedRow!!.id, deletion.target)
        assertNull(m.intent(DeclarationAction.DELETE, true))
        val edit = model(); edit.select(edit.snapshot!!.rows[0]); edit.edit(edit.fields.copy(name = "edited"))
        val update = edit.intent(DeclarationAction.UPDATE)!!; assertEquals("edited", update.fields!!.name)
        val create = model(); create.edit(create.fields.copy(name = "new")); assertNull(create.intent(DeclarationAction.CREATE)!!.target)
    }
    @Test fun `REFRESH preserves unsent local draft and explicit selection confirms discard`() {
        val m = model(); m.select(m.snapshot!!.rows[0]); m.edit(m.fields.copy(name = "unsent"))
        val request = m.intent(DeclarationAction.REFRESH)!!
        assertTrue(m.receive(snapshot(seq = 2).copy(acknowledgedOperation = request.operation)))
        assertEquals("unsent", m.fields.name); assertTrue(m.draftDirty)
        assertFalse(m.select(null)); assertTrue(m.select(null, true)); assertEquals("", m.fields.name)
    }
    @Test fun `server errors retain data and UNKNOWN stays read-only after recovery`() {
        val m = model(); val before = m.snapshot!!.rows
        m.receive(snapshot(seq = 2).copy(state = DeclarationState.STALE, canCreate = false, canEdit = false, error = "STALE_PAGE"))
        assertEquals(before, m.snapshot!!.rows); assertFalse(m.editable)
        assertTrue(declarationError(m.snapshot!!.error).contains("REFRESH"))
        m.receive(snapshot(seq = 3).copy(state = DeclarationState.UNKNOWN, canCreate = false, canEdit = false))
        assertFalse(m.editable); assertNull(m.intent(DeclarationAction.CREATE)); assertNotNull(m.intent(DeclarationAction.REFRESH))
    }
    @Test fun `dismissed and foreign session late state cannot reopen client model`() {
        val m = model(); assertNotNull(m.intent(DeclarationAction.CLOSE))
        assertFalse(m.receive(snapshot(seq = 2)))
        assertFalse(m.receive(snapshot(seq = 3).copy(session = key.copy(player = UUID.randomUUID()))))
    }
    @Test fun `NEXT PREV use explicit sequenced intents and local draft survives pages`() {
        val m = model(f.page().copy(total = 18, hasNext = true)); m.edit(m.fields.copy(name = "draft"))
        val next = m.intent(DeclarationAction.NEXT)!!
        m.receive(snapshot(seq = 2).copy(page = 1, total = 18, hasNext = false, acknowledgedOperation = next.operation))
        assertEquals("draft", m.fields.name); assertNotNull(m.intent(DeclarationAction.PREV))
    }
    @Test fun `small default and wide GUI layouts keep all controls on screen`() {
        for ((width, height) in listOf(320 to 240, 426 to 240, 640 to 360, 1280 to 720)) {
            val l = DeclarationLayout(width, height)
            assertTrue(l.left >= 0 && l.left + l.contentWidth <= width)
            assertTrue(l.listY + l.rowCount * 18 <= l.formY - 22)
            assertTrue(l.formY >= 0 && l.formY + 92 <= height - 36)
            assertTrue(l.rowCount in 1..16)
        }
    }
    @Test fun `two editor surfaces reject reversed S2C order in both directions`() {
        for ((first, second) in listOf("BLOCK" to "DECLARATION", "DECLARATION" to "BLOCK")) {
            val gate = EditorSurfaceFence(key.player); val newer = key.copy(nonce = UUID.randomUUID())
            assertTrue(gate.receive(second, newer, 3, false))
            assertFalse(gate.receive(first, key, 1, false)); assertFalse(gate.receive(first, key, 2, true))
            assertTrue(gate.receive(second, newer, 4, false))
        }
    }
    @Test fun `two editor switch tombstones previous key even if a later callback arrives`() {
        val gate = EditorSurfaceFence(key.player); val newer = key.copy(nonce = UUID.randomUUID())
        assertTrue(gate.receive("BLOCK", key, 1, false)); assertTrue(gate.receive("DECLARATION", newer, 2, false))
        assertFalse(gate.receive("BLOCK", key, 3, false))
        gate.invalidate(); assertFalse(gate.receive("DECLARATION", newer, 4, false))
        assertTrue(gate.receive("BLOCK", key.copy(generation = 2), 5, false))
    }
    @Test fun `surface gate owner and reconnect connection object fences are independent`() {
        val gate = EditorSurfaceFence(key.player)
        assertFalse(gate.receive("DECLARATION", key.copy(player = UUID.randomUUID()), 1, false))
        val connection = EditorClientConnectionFence(); val old = Any(); connection.join(old)
        val replacement = Any(); connection.join(replacement)
        assertFalse(connection.current(old)); assertFalse(connection.disconnect(old)); assertTrue(connection.current(replacement))
    }
}
