package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class WireVisualProjectionTest {
    private val position = GraphPosition(1, 0, 0)
    private val cell = GraphWireCell(position, listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST"))))
    private val edge = GraphEdge(EDGE_ID, NODE_A, "out", NODE_B, "in", "CONTROL")

    @Test fun `wire cell without edge is unbound`() = assertState(WireVisualState.UNBOUND)

    @Test fun `valid route associated with an edge is bound valid`() =
        assertState(WireVisualState.BOUND_VALID, edge, route("VALID"))

    @Test fun `known non-valid route associated with an edge needs repair`() =
        assertState(WireVisualState.BOUND_NEEDS_REPAIR, edge, route("BROKEN"))

    @Test fun `unknown route status is never green`() =
        assertState(WireVisualState.UNKNOWN, edge, route("FUTURE_STATUS"))

    @Test fun `route not containing the wire cell is never green`() = assertState(
        WireVisualState.UNBOUND, edge, route("VALID", GraphPosition(2, 0, 0)),
    )

    @Test fun `multiple route associations have deterministic fail-safe priority`() {
        val routes = listOf(
            route("VALID"),
            route("ILLEGAL", routeId = UUID.fromString("aaaaaaaa-2222-2222-2222-222222222222")),
        )
        assertState(WireVisualState.BOUND_NEEDS_REPAIR, listOf(edge), routes)
        assertState(WireVisualState.BOUND_NEEDS_REPAIR, listOf(edge), routes.reversed())
    }

    private fun route(
        status: String,
        step: GraphPosition = position,
        routeId: UUID = ROUTE_ID,
    ) = GraphRoute(
        routeId, EDGE_ID, GraphPortRef(NODE_A, "out"), GraphPortRef(NODE_B, "in"),
        listOf(GraphRouteStep(step, "SINGLE")), status,
    )

    private fun assertState(expected: WireVisualState, edge: GraphEdge? = null, route: GraphRoute? = null) =
        assertState(expected, listOfNotNull(edge), listOfNotNull(route))

    private fun assertState(expected: WireVisualState, edges: List<GraphEdge>, routes: List<GraphRoute>) {
        val actual = WireVisualProjection.states(snapshot().copy(
            wireCells = listOf(cell), edges = edges, routes = routes,
        ))[position]
        assertEquals(expected, actual)
    }
}
