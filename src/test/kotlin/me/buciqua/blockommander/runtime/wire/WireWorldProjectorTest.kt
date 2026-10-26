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
            target to WireWorldCell.CK_WIRE_UNBOUND,
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

    @Test fun `wire outside Stage is diagnosed without touching world`() {
        val world = FakeWireWorld()
        val snapshot = wireSnapshot(1).copy(wireCells = listOf(
            GraphWireCell(GraphPosition(7, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST")))),
        ))
        val result = WireWorldProjector().apply(stage, 1, snapshot, world)
        assertEquals("WIRE_OUTSIDE_STAGE", result.issues.single().code)
        assertEquals(0, world.setCalls)
        assertEquals(0, world.removeCalls)
    }

    @Test fun `physical geometry mismatch is repaired before a valid route is shown green`() {
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_GEOMETRY_MISMATCH))
        val result = WireWorldProjector().apply(stage, 1, wireSnapshot(1, edgeStatus = "VALID"), world)
        assertTrue(result.complete)
        assertEquals(WireWorldCell.CK_WIRE_BOUND_VALID, world.cell(target))
        assertEquals(1, world.setCalls)
        assertEquals(0, world.removeCalls)
    }

    @Test fun `visual state transitions update only the existing blockstate and remain idempotent`() {
        val world = FakeWireWorld()
        val projector = WireWorldProjector()
        projector.apply(stage, 1, wireSnapshot(1), world)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(target))
        projector.apply(stage, 2, wireSnapshot(2, edgeStatus = "VALID"), world)
        assertEquals(WireWorldCell.CK_WIRE_BOUND_VALID, world.cell(target))
        projector.apply(stage, 3, wireSnapshot(3, edgeStatus = "BROKEN"), world)
        assertEquals(WireWorldCell.CK_WIRE_BOUND_NEEDS_REPAIR, world.cell(target))
        projector.apply(stage, 4, wireSnapshot(4, edgeStatus = "VALID"), world)
        assertEquals(WireWorldCell.CK_WIRE_BOUND_VALID, world.cell(target))
        val calls = world.setCalls
        projector.apply(stage, 4, wireSnapshot(4, edgeStatus = "VALID"), world)
        assertEquals(calls, world.setCalls)
        assertEquals(0, world.removeCalls)
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
        cells.filterValues { it.isCkWire }.keys

    override fun cell(position: GraphPosition): WireWorldCell = cells[position] ?: WireWorldCell.REPLACEABLE
    override fun setEastWestWire(position: GraphPosition, visualState: WireVisualState) {
        if (failWrites) error("render failed")
        setCalls++
        cells[position] = when (visualState) {
            WireVisualState.UNBOUND -> WireWorldCell.CK_WIRE_UNBOUND
            WireVisualState.BOUND_VALID -> WireWorldCell.CK_WIRE_BOUND_VALID
            WireVisualState.BOUND_NEEDS_REPAIR, WireVisualState.UNKNOWN -> WireWorldCell.CK_WIRE_BOUND_NEEDS_REPAIR
        }
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
    edgeStatus: String? = null,
) = snapshot(epoch = epoch, revision = revision).copy(
    wireCells = if (withWire) listOf(
        GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST")))),
    ) else emptyList(),
    edges = if (edgeStatus == null) emptyList() else listOf(GraphEdge(EDGE_ID, NODE_A, "out", NODE_B, "in", "CONTROL")),
    routes = if (edgeStatus == null) emptyList() else listOf(GraphRoute(
        ROUTE_ID, EDGE_ID, GraphPortRef(NODE_A, "out"), GraphPortRef(NODE_B, "in"),
        listOf(GraphRouteStep(GraphPosition(1, 0, 0), "SINGLE")), edgeStatus,
    )),
)
