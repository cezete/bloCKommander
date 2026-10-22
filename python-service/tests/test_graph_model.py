import unittest
from dataclasses import FrozenInstanceError
from uuid import UUID

from hangar.graph.model import (
    CardinalDirection, EdgeKind, EdgeRoute, GraphEdge, GraphNode, GridPos,
    LocalSide, PortDefinition, PortDirection, PortKind, PortRef, RouteStatus,
    WireCell, WireChannel, WireChannelState, WireStep,
)


STAGE_ID = UUID("00000000-0000-0000-0000-000000000001")
SOURCE_ID = UUID("00000000-0000-0000-0000-000000000002")
TARGET_ID = UUID("00000000-0000-0000-0000-000000000003")
EDGE_ID = UUID("00000000-0000-0000-0000-000000000004")
ROUTE_ID = UUID("00000000-0000-0000-0000-000000000005")


def make_port(**overrides):
    values = {
        "port_key": "true_branch",
        "port_kind": PortKind.CONTROL,
        "direction": PortDirection.OUT,
        "local_side": LocalSide.RIGHT,
        "ordinal": 0,
        "min_edges": 0,
        "max_edges": 1,
        "branch_label": "true",
    }
    values.update(overrides)
    return PortDefinition(**values)


def make_edge(edge_id=EDGE_ID):
    return GraphEdge(
        edge_id=edge_id,
        stage_id=STAGE_ID,
        source_instance_id=SOURCE_ID,
        source_port_key="true_branch",
        target_instance_id=TARGET_ID,
        target_port_key="control_in",
        edge_kind=EdgeKind.CONTROL,
    )


class GraphModelTest(unittest.TestCase):
    def test_grid_position_is_frozen(self):
        position = GridPos(1, 2, 3)
        with self.assertRaises(FrozenInstanceError):
            position.x = 9

    def test_grid_positions_are_sortable(self):
        self.assertEqual(
            sorted((GridPos(1, 0, 0), GridPos(0, 9, 9))),
            [GridPos(0, 9, 9), GridPos(1, 0, 0)],
        )

    def test_port_definition_is_frozen(self):
        definition = make_port()
        with self.assertRaises(FrozenInstanceError):
            definition.ordinal = 1

    def test_port_definition_rejects_negative_ordinal(self):
        with self.assertRaises(ValueError):
            make_port(ordinal=-1)

    def test_port_definition_rejects_negative_minimum(self):
        with self.assertRaises(ValueError):
            make_port(min_edges=-1)

    def test_port_definition_rejects_negative_maximum(self):
        with self.assertRaises(ValueError):
            make_port(max_edges=-1)

    def test_port_definition_rejects_inverted_cardinality(self):
        with self.assertRaises(ValueError):
            make_port(min_edges=2, max_edges=1)

    def test_port_definition_rejects_empty_key(self):
        with self.assertRaises(ValueError):
            make_port(port_key="")

    def test_port_definition_rejects_whitespace_key(self):
        with self.assertRaises(ValueError):
            make_port(port_key="   ")

    def test_port_definition_rejects_coordinate_encoded_key(self):
        with self.assertRaises(ValueError):
            make_port(port_key="12,64,-3")

    def test_port_references_compare_by_value(self):
        self.assertEqual(PortRef(SOURCE_ID, "true_branch"), PortRef(SOURCE_ID, "true_branch"))

    def test_graph_node_is_frozen(self):
        node = GraphNode(SOURCE_ID, STAGE_ID, "if_node", GridPos(4, 5, 6), CardinalDirection.NORTH)
        with self.assertRaises(FrozenInstanceError):
            node.orientation = CardinalDirection.SOUTH

    def test_graph_edge_is_frozen(self):
        edge = make_edge()
        with self.assertRaises(FrozenInstanceError):
            edge.edge_kind = EdgeKind.CONTROL

    def test_semantic_endpoint_key_excludes_edge_id(self):
        first = make_edge(UUID("00000000-0000-0000-0000-000000000010"))
        second = make_edge(UUID("00000000-0000-0000-0000-000000000011"))
        self.assertEqual(first.semantic_endpoint_key, second.semantic_endpoint_key)
        self.assertNotEqual(first.edge_id, second.edge_id)

    def test_semantic_endpoint_key_has_contract_order(self):
        self.assertEqual(
            make_edge().semantic_endpoint_key,
            (STAGE_ID, SOURCE_ID, "true_branch", TARGET_ID, "control_in", EdgeKind.CONTROL),
        )

    def test_unbound_incomplete_route_is_representable(self):
        route = EdgeRoute(ROUTE_ID, STAGE_ID, None, None, None, (), RouteStatus.INCOMPLETE)
        self.assertIsNone(route.edge_id)
        self.assertEqual(route.steps, ())

    def test_single_channel_allows_two_connections(self):
        state = WireChannelState(
            WireChannel.SINGLE,
            frozenset({CardinalDirection.NORTH, CardinalDirection.EAST}),
        )
        self.assertEqual(WireCell(GridPos(0, 0, 0), (state,)).channels, (state,))

    def test_single_channel_allows_draft_without_connections(self):
        state = WireChannelState(WireChannel.SINGLE, frozenset())
        self.assertEqual(WireCell(GridPos(0, 0, 0), (state,)).channels, (state,))

    def test_single_channel_allows_draft_with_one_connection(self):
        state = WireChannelState(WireChannel.SINGLE, frozenset({CardinalDirection.NORTH}))
        self.assertEqual(WireCell(GridPos(0, 0, 0), (state,)).channels, (state,))

    def test_cross_allows_independent_ns_and_ew_channels(self):
        ns, ew = self._cross_channels()
        cell = WireCell(GridPos(0, 0, 0), (ns, ew))
        self.assertEqual(set(cell.channels), {ns, ew})

    def test_cross_channels_do_not_share_connectivity(self):
        ns, ew = self._cross_channels()
        cell = WireCell(GridPos(0, 0, 0), (ns, ew))
        self.assertTrue(cell.channels[0].connections.isdisjoint(cell.channels[1].connections))

    def test_wire_cell_rejects_duplicate_channels(self):
        first = WireChannelState(WireChannel.SINGLE, frozenset())
        second = WireChannelState(WireChannel.SINGLE, frozenset({CardinalDirection.NORTH}))
        with self.assertRaises(ValueError):
            WireCell(GridPos(0, 0, 0), (first, second))

    def test_single_channel_rejects_three_way_junction(self):
        with self.assertRaises(ValueError):
            WireChannelState(
                WireChannel.SINGLE,
                frozenset({CardinalDirection.NORTH, CardinalDirection.EAST, CardinalDirection.SOUTH}),
            )

    def test_single_channel_rejects_four_way_junction(self):
        with self.assertRaises(ValueError):
            WireChannelState(WireChannel.SINGLE, frozenset(CardinalDirection))

    def test_wire_cell_rejects_empty_channel_set(self):
        with self.assertRaises(ValueError):
            WireCell(GridPos(0, 0, 0), ())

    def test_wire_cell_rejects_single_mixed_with_cross_channel(self):
        single = WireChannelState(WireChannel.SINGLE, frozenset())
        ns, _ = self._cross_channels()
        with self.assertRaises(ValueError):
            WireCell(GridPos(0, 0, 0), (single, ns))

    def test_wire_cell_rejects_lone_cross_channel(self):
        ns, _ = self._cross_channels()
        with self.assertRaises(ValueError):
            WireCell(GridPos(0, 0, 0), (ns,))

    def test_wire_cell_rejects_malformed_cross(self):
        with self.assertRaises(ValueError):
            WireChannelState(WireChannel.NS, frozenset({CardinalDirection.NORTH}))

    def test_route_status_does_not_change_graph_edge_identity(self):
        edge = make_edge()
        step = WireStep(GridPos(1, 0, 0), WireChannel.SINGLE)
        incomplete = EdgeRoute(
            ROUTE_ID, STAGE_ID, edge.edge_id,
            PortRef(SOURCE_ID, "true_branch"), PortRef(TARGET_ID, "control_in"),
            (step,), RouteStatus.INCOMPLETE,
        )
        broken = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000000006"), STAGE_ID, edge.edge_id,
            incomplete.source_port, incomplete.target_port, (step,), RouteStatus.BROKEN,
        )
        self.assertNotEqual(incomplete.status, broken.status)
        self.assertEqual(edge.semantic_endpoint_key, make_edge().semantic_endpoint_key)

    @staticmethod
    def _cross_channels():
        return (
            WireChannelState(
                WireChannel.NS,
                frozenset({CardinalDirection.NORTH, CardinalDirection.SOUTH}),
            ),
            WireChannelState(
                WireChannel.EW,
                frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
            ),
        )


if __name__ == "__main__":
    unittest.main()
