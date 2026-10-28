import unittest
from dataclasses import FrozenInstanceError
from uuid import UUID

from hangar.graph.binding import RouteCandidate
from hangar.graph.discovery import (
    DiscoveryDiagnosticCode,
    PhysicalComponentStatus,
    discover_physical_topology,
)
from hangar.graph.model import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GraphNode,
    GridPos,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.port_catalog import CK_CONTROL_PORT_CATALOG
from hangar.graph.state import GraphWorkspaceState


STAGE_ID = UUID("30000000-0000-0000-0000-000000000001")
START_A = UUID("30000000-0000-0000-0000-000000000010")
STOP_A = UUID("30000000-0000-0000-0000-000000000011")
START_B = UUID("30000000-0000-0000-0000-000000000020")
STOP_B = UUID("30000000-0000-0000-0000-000000000021")
EDGE_ID = UUID("30000000-0000-0000-0000-000000000030")
ROUTE_ID = UUID("30000000-0000-0000-0000-000000000031")


def node(instance_id, type_key, position, facing):
    return GraphNode(instance_id, STAGE_ID, type_key, position, facing)


def single(position, *connections):
    return WireCell(
        position,
        (WireChannelState(WireChannel.SINGLE, frozenset(connections)),),
    )


def cross(position):
    return WireCell(
        position,
        (
            WireChannelState(
                WireChannel.NS,
                frozenset({CardinalDirection.NORTH, CardinalDirection.SOUTH}),
            ),
            WireChannelState(
                WireChannel.EW,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        ),
    )


def workspace(*, nodes=(), cells=(), edges=(), routes=()):
    return GraphWorkspaceState(
        stage_id=STAGE_ID,
        nodes=tuple(nodes),
        edges=tuple(edges),
        wire_cells=tuple(cells),
        routes=tuple(routes),
        working_revision=9,
        validated_revision=None,
        saved_version=4,
        dirty=True,
        last_validation=None,
    )


def horizontal_nodes(start_id=START_A, stop_id=STOP_A, z=0):
    return (
        node(start_id, "START", GridPos(0, 0, z), CardinalDirection.EAST),
        node(stop_id, "STOP", GridPos(4, 0, z), CardinalDirection.EAST),
    )


def horizontal_cells(z=0):
    return tuple(
        single(
            GridPos(x, 0, z),
            CardinalDirection.EAST,
            CardinalDirection.WEST,
        )
        for x in (1, 2, 3)
    )


class PhysicalTopologyDiscoveryTest(unittest.TestCase):
    def discover(self, state):
        return discover_physical_topology(state, CK_CONTROL_PORT_CATALOG)

    def test_multicell_straight_start_to_stop_is_one_complete_candidate(self):
        result = self.discover(workspace(
            nodes=horizontal_nodes(),
            cells=horizontal_cells(),
        ))

        self.assertEqual(len(result.components), 1)
        self.assertEqual(result.components[0].status, PhysicalComponentStatus.COMPLETE)
        self.assertEqual(len(result.candidates), 1)
        self.assertEqual(result.diagnostics, ())
        self.assertEqual(
            result.candidates[0].steps,
            tuple(WireStep(GridPos(x, 0, 0), WireChannel.SINGLE) for x in (1, 2, 3)),
        )
        self.assertIsInstance(result.candidates[0].as_route_candidate(), RouteCandidate)

    def test_corner_start_to_stop_has_deterministic_ordered_steps(self):
        nodes = (
            node(START_A, "START", GridPos(0, 0, 0), CardinalDirection.SOUTH),
            node(STOP_A, "STOP", GridPos(2, 0, 2), CardinalDirection.SOUTH),
        )
        cells = (
            single(GridPos(0, 0, 1), CardinalDirection.NORTH, CardinalDirection.EAST),
            single(GridPos(1, 0, 1), CardinalDirection.WEST, CardinalDirection.EAST),
            single(GridPos(2, 0, 1), CardinalDirection.WEST, CardinalDirection.SOUTH),
        )

        result = self.discover(workspace(nodes=nodes, cells=cells))

        self.assertEqual(result.components[0].status, PhysicalComponentStatus.COMPLETE)
        self.assertEqual(
            tuple(step.position for step in result.candidates[0].steps),
            (GridPos(0, 0, 1), GridPos(1, 0, 1), GridPos(2, 0, 1)),
        )

    def test_cross_keeps_two_routes_physically_independent(self):
        nodes = (
            node(START_A, "START", GridPos(-2, 0, 0), CardinalDirection.EAST),
            node(STOP_A, "STOP", GridPos(2, 0, 0), CardinalDirection.EAST),
            node(START_B, "START", GridPos(0, 0, -2), CardinalDirection.SOUTH),
            node(STOP_B, "STOP", GridPos(0, 0, 2), CardinalDirection.SOUTH),
        )
        cells = (
            single(GridPos(-1, 0, 0), CardinalDirection.WEST, CardinalDirection.EAST),
            cross(GridPos(0, 0, 0)),
            single(GridPos(1, 0, 0), CardinalDirection.WEST, CardinalDirection.EAST),
            single(GridPos(0, 0, -1), CardinalDirection.NORTH, CardinalDirection.SOUTH),
            single(GridPos(0, 0, 1), CardinalDirection.NORTH, CardinalDirection.SOUTH),
        )

        result = self.discover(workspace(nodes=nodes, cells=cells))

        self.assertEqual(len(result.components), 2)
        self.assertEqual(len(result.candidates), 2)
        self.assertEqual(
            {tuple(step.channel for step in item.steps) for item in result.candidates},
            {
                (WireChannel.SINGLE, WireChannel.EW, WireChannel.SINGLE),
                (WireChannel.SINGLE, WireChannel.NS, WireChannel.SINGLE),
            },
        )
        self.assertTrue(all(
            item.status is PhysicalComponentStatus.COMPLETE for item in result.components
        ))

    def test_half_built_wire_is_incomplete_with_open_diagnostic(self):
        state = workspace(
            nodes=(node(START_A, "START", GridPos(0, 0, 0), CardinalDirection.EAST),),
            cells=(
                single(GridPos(1, 0, 0), CardinalDirection.WEST, CardinalDirection.EAST),
                single(GridPos(2, 0, 0), CardinalDirection.WEST, CardinalDirection.EAST),
            ),
        )

        result = self.discover(state)

        self.assertEqual(result.components[0].status, PhysicalComponentStatus.INCOMPLETE)
        self.assertEqual(result.candidates, ())
        self.assertEqual(result.diagnostics[0].code, DiscoveryDiagnosticCode.OPEN_WIRE)
        self.assertEqual(len(result.components[0].open_ends), 1)

    def test_two_output_ports_are_incompatible_and_not_bindable(self):
        nodes = (
            node(START_A, "START", GridPos(0, 0, 0), CardinalDirection.EAST),
            node(START_B, "START", GridPos(4, 0, 0), CardinalDirection.WEST),
        )

        result = self.discover(workspace(nodes=nodes, cells=horizontal_cells()))

        self.assertEqual(result.components[0].status, PhysicalComponentStatus.INCOMPATIBLE)
        self.assertEqual(result.candidates, ())
        self.assertEqual(
            result.diagnostics[0].code,
            DiscoveryDiagnosticCode.INCOMPATIBLE_PORTS,
        )

    def test_three_way_single_is_rejected_by_existing_domain_invariant(self):
        with self.assertRaises(ValueError):
            WireChannelState(
                WireChannel.SINGLE,
                frozenset({
                    CardinalDirection.NORTH,
                    CardinalDirection.EAST,
                    CardinalDirection.SOUTH,
                }),
            )

    def test_three_port_component_is_ambiguous(self):
        nodes = (
            node(START_A, "START", GridPos(0, 0, 0), CardinalDirection.EAST),
            node(STOP_A, "STOP", GridPos(2, 0, 0), CardinalDirection.EAST),
            node(STOP_B, "STOP", GridPos(2, 0, 0), CardinalDirection.EAST),
        )
        cells = (
            single(GridPos(1, 0, 0), CardinalDirection.WEST, CardinalDirection.EAST),
        )

        result = self.discover(workspace(nodes=nodes, cells=cells))

        self.assertEqual(result.components[0].status, PhysicalComponentStatus.AMBIGUOUS)
        self.assertEqual(result.candidates, ())
        self.assertEqual(
            result.diagnostics[0].code,
            DiscoveryDiagnosticCode.AMBIGUOUS_TOPOLOGY,
        )

    def test_duplicate_wire_cell_position_is_illegal(self):
        duplicate = single(
            GridPos(1, 0, 0),
            CardinalDirection.WEST,
            CardinalDirection.EAST,
        )

        result = self.discover(workspace(cells=(duplicate, duplicate)))

        self.assertEqual(result.components[0].status, PhysicalComponentStatus.ILLEGAL)
        self.assertEqual(result.candidates, ())
        self.assertEqual(
            result.diagnostics[0].code,
            DiscoveryDiagnosticCode.ILLEGAL_TOPOLOGY,
        )

    def test_cycle_terminates_and_is_reported_deterministically(self):
        cells = (
            single(GridPos(0, 0, 0), CardinalDirection.EAST, CardinalDirection.SOUTH),
            single(GridPos(1, 0, 0), CardinalDirection.WEST, CardinalDirection.SOUTH),
            single(GridPos(1, 0, 1), CardinalDirection.WEST, CardinalDirection.NORTH),
            single(GridPos(0, 0, 1), CardinalDirection.EAST, CardinalDirection.NORTH),
        )
        state = workspace(cells=cells)

        first = self.discover(state)
        second = self.discover(state)

        self.assertEqual(first, second)
        self.assertEqual(first.components[0].status, PhysicalComponentStatus.CYCLIC)
        self.assertEqual(first.candidates, ())
        self.assertEqual(
            first.diagnostics[0].code,
            DiscoveryDiagnosticCode.CYCLIC_COMPONENT,
        )

    def test_repeated_discovery_has_identical_ids_and_order(self):
        state = workspace(nodes=horizontal_nodes(), cells=horizontal_cells())

        first = self.discover(state)
        second = self.discover(state)

        self.assertEqual(first, second)
        self.assertEqual(
            first.components[0].component_id,
            second.components[0].component_id,
        )
        self.assertEqual(
            first.candidates[0].candidate_id,
            second.candidates[0].candidate_id,
        )

    def test_discovery_does_not_modify_existing_edge_route_or_workspace_metadata(self):
        nodes = horizontal_nodes()
        steps = tuple(WireStep(GridPos(x, 0, 0), WireChannel.SINGLE) for x in (1, 2, 3))
        edge = GraphEdge(EDGE_ID, STAGE_ID, START_A, "out", STOP_A, "in", EdgeKind.CONTROL)
        route = EdgeRoute(
            ROUTE_ID,
            STAGE_ID,
            EDGE_ID,
            result_port(START_A, "out"),
            result_port(STOP_A, "in"),
            steps,
            RouteStatus.VALID,
        )
        state = workspace(
            nodes=nodes,
            cells=horizontal_cells(),
            edges=(edge,),
            routes=(route,),
        )
        before = state

        result = self.discover(state)

        self.assertEqual(state, before)
        self.assertIs(state.edges[0], edge)
        self.assertIs(state.routes[0], route)
        self.assertEqual(state.working_revision, 9)
        self.assertTrue(state.dirty)
        self.assertEqual(len(result.candidates), 1)

    def test_discovery_result_is_immutable(self):
        result = self.discover(workspace(nodes=horizontal_nodes(), cells=horizontal_cells()))
        with self.assertRaises(FrozenInstanceError):
            result.components = ()


def result_port(instance_id, port_key):
    from hangar.graph.model import PortRef
    return PortRef(instance_id, port_key)


if __name__ == "__main__":
    unittest.main()
