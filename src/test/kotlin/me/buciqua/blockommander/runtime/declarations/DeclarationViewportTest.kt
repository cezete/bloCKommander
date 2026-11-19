package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.network.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID

/** Exercises the model-owned viewport used by DeclarationScreen, through the real
 * S2C binary codec. OWN/LOCAL are supplied server presentation rows, not resolved here. */
class DeclarationViewportTest {
    private val f = DeclarationFixtures
    private val key = EditorSessionKey(f.context.player, 1, 1, UUID(0, 604))
    private fun identity(row: DeclarationRow) = row.group to row.id
    private inner class Rig(count: Int = 17, capacity: Int = 15) {
        val own = List(count) { i -> DeclarationRow(UUID(0, 1000L + i), f.context.stage,
            DeclarationFields("agent_%02d".format(i + 1), DeclarationType.INTEGER, DeclarationMutability.MUTABLE), DeclarationGroup.OWN, true) }
        val all = own + own.map { it.copy(group = DeclarationGroup.LOCAL) }
        val model = DeclarationUiModel(key.player)
        val view get() = model.viewport
        var sequence = 0L
        init { view.resize(capacity); deliver(0) }
        fun state(page: Int, ack: Long = 0) = DeclarationUiSnapshot(key, ++sequence, "minecraft:overworld", f.context.transport.programId,
            f.context.stage, DeclarationState.READY, false, all.drop(page * 16).take(16), page, all.size, (page + 1) * 16 < all.size,
            true, true, true, true, 0, 0, 17, null, "CURRENT", null, ack)
        fun receive(s: DeclarationUiSnapshot): Boolean = model.receive(DeclarationPacketWire.snapshot(DeclarationPacketWire.encode(s)))
        fun deliver(page: Int, ack: Long = 0) = receive(state(page, ack)).also { assertTrue(it) }
        fun turn(action: DeclarationAction) {
            val page = model.snapshot!!.page
            val op = model.intent(action)!!
            val offset = view.offset
            assertTrue(receive(state(page, op.operation).copy(busy = true, canCreate = false, canEdit = false)))
            assertEquals(offset, view.offset, "dispatch/progress must not reset the old window")
            deliver(page + if (action == DeclarationAction.NEXT) 1 else -1, op.operation)
            assertEquals(0, view.offset)
        }
        fun rowsInAllLocalWindows(): List<DeclarationRow> {
            val offsets = mutableListOf<Int>()
            val rows = mutableListOf<DeclarationRow>()
            do {
                offsets += view.offset; rows += view.visibleRows
                if (view.visibleRows.isNotEmpty()) {
                    assertEquals(view.visibleRows, all.subList((view.firstRow - 1).toInt(), view.lastRow.toInt()))
                    assertTrue(view.lastRow <= all.size)
                }
            } while (view.down())
            assertFalse(view.canDown); val end = view.offset; assertFalse(view.down()); assertEquals(end, view.offset)
            val reverse = mutableListOf(view.offset)
            while (view.up()) reverse += view.offset
            assertEquals(offsets.reversed(), reverse)
            assertFalse(view.canUp); assertFalse(view.up()); assertEquals(0, view.offset)
            return rows
        }
    }

    @ParameterizedTest(name = "{0} LOCAL declarations, {1} visible rows")
    @CsvSource("0,8", "0,15", "1,8", "1,15", "15,8", "15,15", "16,8", "16,15",
        "17,8", "17,15", "18,8", "18,15", "32,8", "32,15", "33,8", "33,15")
    fun `every OWN and LOCAL row is reachable once through pages and deterministic windows`(count: Int, capacity: Int) {
        val r = Rig(count, capacity)
        val seen = mutableListOf<DeclarationRow>()
        val pages = mutableListOf<List<DeclarationRow>>()
        do {
            val rows = r.rowsInAllLocalWindows(); seen += rows; pages += rows
            if (!r.model.snapshot!!.hasNext) break
            r.turn(DeclarationAction.NEXT)
        } while (true)
        assertEquals(r.all, seen)
        assertEquals(count * 2, seen.map(::identity).toSet().size)
        for (i in pages.lastIndex - 1 downTo 0) {
            r.turn(DeclarationAction.PREV)
            assertEquals(pages[i], r.rowsInAllLocalWindows())
        }
        assertEquals(0, r.view.pageIndex)
        if (count == 0) {
            assertEquals("oldal 1/1 | sor 0–0/0", r.view.indicator)
            assertTrue(r.view.visibleRows.isEmpty())
        }
    }

    @Test fun `34 rows show exact global ranges and page changes reset the old offset`() {
        val r = Rig()
        assertEquals("oldal 1/3 | sor 1–15/34", r.view.indicator)
        assertTrue(r.view.down()); assertEquals("oldal 1/3 | sor 16–16/34", r.view.indicator)
        assertFalse(r.view.down())
        r.turn(DeclarationAction.NEXT)
        assertEquals("oldal 2/3 | sor 17–31/34", r.view.indicator)
        assertEquals("agent_17", r.view.visibleRows.first().fields.name)
        assertEquals(DeclarationGroup.OWN, r.view.visibleRows.first().group)
        r.view.down(); assertEquals("oldal 2/3 | sor 32–32/34", r.view.indicator)
        r.turn(DeclarationAction.NEXT)
        assertEquals("oldal 3/3 | sor 33–34/34", r.view.indicator)
        assertFalse(r.view.canDown)
        r.turn(DeclarationAction.PREV)
        assertEquals("oldal 2/3 | sor 17–31/34", r.view.indicator)
    }
    @Test fun `REFRESH keeps offset while pending then resets identical accepted page without losing draft`() {
        val r = Rig(); val selected = r.own[0]
        assertTrue(r.model.select(selected)); r.model.edit(selected.fields.copy(name = "unsent draft"))
        r.view.down(); val op = r.model.intent(DeclarationAction.REFRESH)!!
        assertEquals(15, r.view.offset)
        r.receive(r.state(0, op.operation).copy(busy = true, canCreate = false, canEdit = false))
        assertEquals(15, r.view.offset)
        r.deliver(0, op.operation)
        assertEquals(0, r.view.offset); assertEquals("unsent draft", r.model.fields.name)
        assertTrue(r.model.draftDirty); assertEquals(identity(selected), r.model.selected)
        // A later status notification for the same accepted view must not jump the window.
        r.view.down(); r.deliver(0, op.operation); assertEquals(15, r.view.offset)
    }
    @Test fun `NEXT PREV preserves draft and selection uses group plus stable UUID instead of name`() {
        val r = Rig(); val selected = r.own.first()
        r.model.select(selected); r.model.edit(selected.fields.copy(name = "draft"))
        r.turn(DeclarationAction.NEXT)
        assertEquals(identity(selected), r.model.selected); assertNull(r.model.selectedRow)
        assertFalse(r.model.editable); assertEquals("draft", r.model.fields.name)
        r.turn(DeclarationAction.PREV)
        assertEquals(selected.id, r.model.selectedRow!!.id); assertEquals("draft", r.model.fields.name)
        r.model.select(null, true)
        r.turn(DeclarationAction.NEXT)
        val agent17 = r.view.visibleRows.first()
        assertTrue(r.model.select(agent17)); assertEquals(r.own.last().id, r.model.selectedRow!!.id)
    }
    @Test fun `stale failed and rejected completions cannot reset or replace the accepted presentation`() {
        for (state in listOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED, DeclarationState.REJECTED)) {
            val r = Rig(); r.view.down(); val before = r.view.visibleRows; val indicator = r.view.indicator
            val op = r.model.intent(DeclarationAction.NEXT)!!
            r.receive(r.state(1, op.operation).copy(state = state, projection = "STALE", error = "STALE_PAGE", canCreate = false, canEdit = false))
            assertEquals(15, r.view.offset); assertEquals(before, r.view.visibleRows); assertEquals(indicator, r.view.indicator)
        }
        val r = Rig(); r.view.down(); val op = r.model.intent(DeclarationAction.REFRESH)!!
        r.receive(r.state(0, op.operation).copy(projection = "FAILED", error = "PROJECTION_RECONCILIATION_REQUIRED", canCreate = false, canEdit = false))
        assertEquals(15, r.view.offset)
    }
    @Test fun `late foreign and dismissed replies leave the actual Screen viewport unchanged`() {
        val r = Rig(); val late = r.state(1); r.deliver(0); r.view.down()
        val before = r.view.indicator
        assertFalse(r.receive(late)); assertEquals(before, r.view.indicator)
        assertFalse(r.receive(r.state(1).copy(session = key.copy(player = UUID.randomUUID()))))
        assertEquals(before, r.view.indicator)
        r.model.intent(DeclarationAction.CLOSE)
        assertFalse(r.receive(r.state(1))); assertEquals(before, r.view.indicator)
    }
    @Test fun `malformed completed page cannot disturb the accepted window`() {
        val r = Rig(); r.view.down(); val before = r.view.visibleRows
        // Binary bounds permit two rows, but page 2/34 requires a complete 16-row page.
        r.receive(r.state(1).copy(rows = r.all.take(2)))
        assertEquals(15, r.view.offset); assertEquals(before, r.view.visibleRows)
    }
    @Test fun `eight row windows are nonoverlapping and both arrow directions are exact inverses`() {
        val r = Rig(capacity = 8)
        val first = r.view.visibleRows
        assertTrue(r.view.down()); val last = r.view.visibleRows; assertEquals(8, r.view.offset)
        assertTrue(first.map(::identity).toSet().intersect(last.map(::identity).toSet()).isEmpty())
        assertFalse(r.view.down()); assertTrue(r.view.up()); assertEquals(first, r.view.visibleRows)
        assertFalse(r.view.up()); assertTrue(r.view.down()); assertEquals(last, r.view.visibleRows)
    }
    @Test fun `short final page and whole page capacity cannot overscroll into a repeated last row`() {
        val r = Rig(capacity = 8); r.turn(DeclarationAction.NEXT); r.turn(DeclarationAction.NEXT)
        assertEquals(2, r.view.visibleRows.size); assertFalse(r.view.canDown); assertFalse(r.view.down())
        r.view.resize(16); r.turn(DeclarationAction.PREV)
        assertEquals(16, r.view.visibleRows.size); assertFalse(r.view.canDown); assertFalse(r.view.down())
    }
    @Test fun `resize and fresh session start from a valid first window without rebinding selection`() {
        val r = Rig(capacity = 8); r.model.select(r.own[0]); r.model.edit(r.own[0].fields.copy(name = "draft")); r.view.down()
        r.view.resize(15); assertEquals(0, r.view.offset); assertEquals("draft", r.model.fields.name)
        r.view.down(); val nextSession = key.copy(generation = 2, nonce = UUID.randomUUID())
        r.receive(r.state(0).copy(session = nextSession, state = DeclarationState.OPENING, busy = true, projection = "NOT_CHECKED", rows = emptyList(), canCreate = false, canEdit = false))
        assertEquals(0, r.view.offset); assertTrue(r.view.visibleRows.isEmpty())
        r.receive(r.state(0).copy(session = nextSession)); assertEquals(0, r.view.offset)
    }
    @Test fun `UNKNOWN read-only refresh can reset the view without claiming the mutation succeeded`() {
        val r = Rig(); r.view.down()
        r.receive(r.state(0).copy(state = DeclarationState.UNKNOWN, canCreate = false, canEdit = false, programDirty = null))
        val op = r.model.intent(DeclarationAction.REFRESH)!!
        r.receive(r.state(0, op.operation).copy(state = DeclarationState.UNKNOWN, canCreate = false, canEdit = false, programDirty = null))
        assertEquals(0, r.view.offset); assertEquals(DeclarationState.UNKNOWN, r.model.snapshot!!.state)
        assertNull(r.model.snapshot!!.modified); assertFalse(r.model.editable)
    }
}
