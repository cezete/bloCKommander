package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.CkWireGeometry
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

    @Test fun `N-S SINGLE renders the north-south blockstate`() {
        val world = FakeWireWorld()
        val snapshot = wireSnapshot(revision = 1).copy(wireCells = listOf(
            GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("NORTH", "SOUTH")))),
        ))
        val result = WireWorldProjector().apply(stage, 1, snapshot, world)
        assertTrue(result.complete)
        assertEquals(CkWireGeometry.NORTH_SOUTH, world.geometry(target))
    }

    @Test fun `all four corners map to deterministic geometry`() {
        val cases = listOf(
            listOf("NORTH", "EAST") to CkWireGeometry.NORTH_EAST,
            listOf("EAST", "SOUTH") to CkWireGeometry.EAST_SOUTH,
            listOf("SOUTH", "WEST") to CkWireGeometry.SOUTH_WEST,
            listOf("WEST", "NORTH") to CkWireGeometry.WEST_NORTH,
        )
        cases.forEach { (connections, expected) ->
            val world = FakeWireWorld()
            val state = wireSnapshot(1).copy(wireCells = listOf(
                GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", connections))),
            ))
            assertTrue(WireWorldProjector().apply(stage, 1, state, world).complete)
            assertEquals(expected, world.geometry(target))
        }
    }

    @Test fun `open and zero-ended draft cells are renderable without binding`() {
        listOf(emptyList<String>() to CkWireGeometry.NONE, listOf("EAST") to CkWireGeometry.EAST).forEach {
            (connections, expected) ->
            val world = FakeWireWorld()
            val state = wireSnapshot(1).copy(wireCells = listOf(
                GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", connections))),
            ))
            assertTrue(WireWorldProjector().apply(stage, 1, state, world).complete)
            assertEquals(expected, world.geometry(target))
            assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(target))
        }
    }

    @Test fun `CROSS keeps NS and EW channels logically separate`() {
        val world = FakeWireWorld()
        val state = wireSnapshot(1).copy(wireCells = listOf(GraphWireCell(
            GraphPosition(1, 0, 0), listOf(
                GraphWireChannel("NS", listOf("NORTH", "SOUTH")),
                GraphWireChannel("EW", listOf("EAST", "WEST")),
            ),
        )))
        assertTrue(WireWorldProjector().apply(stage, 1, state, world).complete)
        assertEquals(CkWireGeometry.CROSS, world.geometry(target))
    }

    @Test fun `implicit three-way SINGLE is rejected without partial world mutation`() {
        val stale = GraphPosition(13, 20, 30)
        val world = FakeWireWorld(mutableMapOf(stale to WireWorldCell.CK_WIRE_UNBOUND))
        val snapshot = wireSnapshot(revision = 1).copy(wireCells = listOf(
            GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("NORTH", "EAST", "SOUTH")))),
        ))
        val result = WireWorldProjector().apply(stage, 1, snapshot, world)
        assertFalse(result.complete)
        assertEquals("UNSUPPORTED_WIRE_GEOMETRY", result.issues.single().code)
        assertEquals(0, world.setCalls)
        assertEquals(0, world.removeCalls)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(stale))
    }

    @Test fun `foreign block is never overwritten`() {
        val stale = GraphPosition(13, 20, 30)
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.FOREIGN, stale to WireWorldCell.CK_WIRE_UNBOUND))
        val result = WireWorldProjector().apply(stage, 1, wireSnapshot(revision = 1), world)
        assertEquals("FOREIGN_BLOCK_AT_WIRE_POSITION", result.issues.single().code)
        assertEquals(WireWorldCell.FOREIGN, world.cell(target))
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(stale))
        assertEquals(0, world.removeCalls)
    }

    @Test fun `multi-cell straight and corner project from one authoritative snapshot`() {
        val world = FakeWireWorld()
        val cells = listOf(
            GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST")))),
            GraphWireCell(GraphPosition(2, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("WEST", "SOUTH")))),
            GraphWireCell(GraphPosition(2, 0, 1), listOf(GraphWireChannel("SINGLE", listOf("NORTH")))),
        )
        val result = WireWorldProjector().apply(stage, 4, wireSnapshot(4).copy(wireCells = cells), world)
        assertTrue(result.complete)
        assertEquals(3, result.rendered.size)
        assertEquals(CkWireGeometry.EAST_WEST, world.geometry(GraphPosition(11, 20, 30)))
        assertEquals(CkWireGeometry.SOUTH_WEST, world.geometry(GraphPosition(12, 20, 30)))
        assertEquals(CkWireGeometry.NORTH, world.geometry(GraphPosition(12, 20, 31)))
    }

    @Test fun `stale epoch or revision cannot mutate world`() {
        listOf(
            wireSnapshot(2),
            wireSnapshot(1, epoch = EPOCH_B),
        ).forEach { staleSnapshot ->
            val world = FakeWireWorld()
            val result = WireWorldProjector().apply(stage, 1, staleSnapshot, world)
            assertEquals("STALE_WORLD_PROJECTION", result.issues.single().code)
            assertEquals(0, world.setCalls)
            assertEquals(0, world.removeCalls)
        }
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
        assertEquals(CkWireGeometry.EAST_WEST, world.geometry(target))
        assertEquals(1, world.setCalls)
        assertEquals(0, world.removeCalls)
    }

    @Test fun `reconnect restores authoritative geometry without changing graph domain objects`() {
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val original = wireSnapshot(6, edgeStatus = "VALID").copy(wireCells = listOf(
            GraphWireCell(GraphPosition(1, 0, 0), listOf(GraphWireChannel("SINGLE", listOf("NORTH", "SOUTH")))),
        ))
        val edges = original.edges
        val routes = original.routes
        val projector = WireWorldProjector()
        assertTrue(projector.apply(stage, 6, original, world).complete)
        assertEquals(CkWireGeometry.NORTH_SOUTH, world.geometry(target))
        assertEquals(edges, original.edges)
        assertEquals(routes, original.routes)
        val calls = world.setCalls
        assertTrue(projector.apply(stage, 6, original, world).complete)
        assertEquals(calls, world.setCalls)
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
    private val geometries = cells.filterValues { it.isCkWire && it != WireWorldCell.CK_WIRE_GEOMETRY_MISMATCH }
        .keys.associateWith { CkWireGeometry.EAST_WEST }.toMutableMap()
    var setCalls = 0
    var removeCalls = 0
    var correctCalls = 0
    var failWrites = false

    override fun ckWirePositions(stage: WireStageContext): Set<GraphPosition> =
        cells.filterValues { it.isCkWire }.keys

    override fun cell(position: GraphPosition): WireWorldCell = cells[position] ?: WireWorldCell.REPLACEABLE
    override fun geometry(position: GraphPosition): CkWireGeometry? = geometries[position]
    override fun setWire(position: GraphPosition, geometry: CkWireGeometry, visualState: WireVisualState) {
        if (failWrites) error("render failed")
        setCalls++
        geometries[position] = geometry
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
        geometries.remove(position)
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
