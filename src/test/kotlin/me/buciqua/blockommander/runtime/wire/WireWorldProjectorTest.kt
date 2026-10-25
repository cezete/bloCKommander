package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WireWorldProjectorTest {
    private val stage = WireStageContext(STAGE_ID, GraphPosition(10, 20, 30), GraphPosition(7, 5, 7), EPOCH_A)
    private val target = GraphPosition(11, 20, 30)

    @Test fun `supported E-W cell renders and repeated reconciliation is idempotent`() {
        val world = FakeWireWorld()
        val projector = WireWorldProjector()
        val first = projector.apply(stage, 1, wireSnapshot(revision = 1), world)
        val second = projector.apply(stage, 1, wireSnapshot(revision = 1), world)
        assertTrue(first.complete)
        assertEquals(setOf(target), first.rendered)
        assertEquals(1, world.setCalls)
        assertTrue(second.complete)
        assertEquals(1, world.setCalls)
    }

    @Test fun `missing authoritative cell removes only CK managed wire`() {
        val world = FakeWireWorld(mutableMapOf(
            target to WireWorldCell.CK_WIRE,
            GraphPosition(12, 20, 30) to WireWorldCell.FOREIGN,
        ))
        val result = WireWorldProjector().apply(stage, 2, wireSnapshot(revision = 2, withWire = false), world)
        assertEquals(setOf(target), result.removed)
        assertEquals(WireWorldCell.FOREIGN, world.cell(GraphPosition(12, 20, 30)))
    }

    @Test fun `unsupported geometry is diagnosed and not rendered`() {
        val world = FakeWireWorld()
        val snapshot = wireSnapshot(revision = 1).copy(wireCells = listOf(
            GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("NORTH", "SOUTH")))),
        ))
        val result = WireWorldProjector().apply(stage, 1, snapshot, world)
        assertFalse(result.complete)
        assertEquals("UNSUPPORTED_WIRE_GEOMETRY", result.issues.single().code)
        assertEquals(0, world.setCalls)
    }

    @Test fun `foreign block is never overwritten`() {
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.FOREIGN))
        val result = WireWorldProjector().apply(stage, 1, wireSnapshot(revision = 1), world)
        assertEquals("FOREIGN_BLOCK_AT_WIRE_POSITION", result.issues.single().code)
        assertEquals(WireWorldCell.FOREIGN, world.cell(target))
    }
}

internal class FakeWireWorld(
    val cells: MutableMap<GraphPosition, WireWorldCell> = mutableMapOf(),
) : WireWorldAccess {
    var setCalls = 0
    var removeCalls = 0
    var correctCalls = 0
    var failWrites = false

    override fun ckWirePositions(stage: WireStageContext): Set<GraphPosition> =
        cells.filterValues { it == WireWorldCell.CK_WIRE }.keys

    override fun cell(position: GraphPosition): WireWorldCell = cells[position] ?: WireWorldCell.REPLACEABLE
    override fun setEastWestWire(position: GraphPosition) {
        if (failWrites) error("render failed")
        setCalls++
        cells[position] = WireWorldCell.CK_WIRE
    }
    override fun removeWire(position: GraphPosition) {
        if (failWrites) error("render failed")
        removeCalls++
        cells.remove(position)
    }
    override fun correct(position: GraphPosition) { correctCalls++ }
}

internal fun wireSnapshot(
    revision: Long,
    epoch: java.util.UUID = EPOCH_A,
    withWire: Boolean = true,
) = snapshot(epoch = epoch, revision = revision).copy(
    wireCells = if (withWire) listOf(
        GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST")))),
    ) else emptyList(),
)
