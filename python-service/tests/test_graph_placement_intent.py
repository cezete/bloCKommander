import unittest
import uuid

from hangar.graph import CardinalDirection, GridPos, WireChannel
from hangar.graph_application import parse_graph_command
from tests.test_graph_application_discovery import (
    DiscoveryApplicationHarness,
    horizontal_lane,
    single,
)


def placement_command(
    harness,
    position,
    *,
    intent="AUTO",
    revision=None,
    epoch=None,
    request_id=None,
):
    context = harness.context()
    raw = {
        "schema_version": 1,
        "request_id": str(request_id or uuid.uuid4()),
        "stage_id": harness.stage_id,
        "player_uuid": harness.owner,
        "workspace_epoch": epoch or str(context.workspace_epoch),
        "expected_working_revision": (
            context.state.working_revision if revision is None else revision
        ),
        "operation": "WIRE_PLACE_INTENT",
        "payload": {
            "position": {"x": position.x, "y": position.y, "z": position.z},
            "intent": intent,
        },
    }
    return parse_graph_command(
        raw,
        expected_stage_id=harness.stage_id,
        expected_operation="WIRE_PLACE_INTENT",
    )


def place(harness, position, **kwargs):
    return harness.app.place_wire(placement_command(harness, position, **kwargs))


def cell_at(state, position):
    return next(cell for cell in state.wire_cells if cell.position == position)


def connections(state, position, channel=WireChannel.SINGLE):
    cell = cell_at(state, position)
    return next(item.connections for item in cell.channels if item.channel is channel)


class GraphPlacementIntentTest(unittest.TestCase):
    target = GridPos(2, 0, 2)

    def test_zero_neighbors_creates_zero_ended_draft(self):
        harness = DiscoveryApplicationHarness((), ())
        before_revision = harness.context().state.working_revision

        result = place(harness, self.target)

        self.assertEqual(200, result.status_code)
        self.assertEqual([], result.body["state"]["edges"])
        self.assertEqual([], result.body["state"]["routes"])
        self.assertTrue(result.body["state"]["dirty"])
        self.assertEqual(before_revision + 1, result.body["working_revision"])
        self.assertEqual([], result.body["state"]["wire_cells"][0]["channels"][0]["connections"])
        self.assertEqual("INCOMPLETE", result.body["discovery"]["components"][0]["status"])

    def test_one_neighbor_updates_both_cells_reciprocally(self):
        west = GridPos(1, 0, 2)
        harness = DiscoveryApplicationHarness((), (single(1, 2),))

        result = place(harness, self.target)
        state = harness.context().state

        self.assertEqual({CardinalDirection.WEST}, connections(state, self.target))
        self.assertEqual({CardinalDirection.EAST}, connections(state, west))
        self.assertEqual(2, len(result.body["placement"]["affected_cells"]))
        self.assertEqual(2, len(result.body["wire_diff"]))

    def test_two_collinear_neighbors_create_straight(self):
        harness = DiscoveryApplicationHarness((), (single(1, 2), single(3, 2)))

        result = place(harness, self.target)
        state = harness.context().state

        self.assertEqual(200, result.status_code)
        self.assertEqual(
            {CardinalDirection.WEST, CardinalDirection.EAST},
            connections(state, self.target),
        )
        self.assertEqual({CardinalDirection.EAST}, connections(state, GridPos(1, 0, 2)))
        self.assertEqual({CardinalDirection.WEST}, connections(state, GridPos(3, 0, 2)))

    def test_two_perpendicular_neighbors_create_corner(self):
        harness = DiscoveryApplicationHarness((), (single(2, 1), single(3, 2)))

        result = place(harness, self.target)

        self.assertEqual(200, result.status_code)
        self.assertEqual(
            {CardinalDirection.NORTH, CardinalDirection.EAST},
            connections(harness.context().state, self.target),
        )

    def test_three_neighbors_are_ambiguous_and_atomic(self):
        harness = DiscoveryApplicationHarness(
            (), (single(2, 1), single(3, 2), single(2, 3))
        )
        before = harness.context().state

        result = place(harness, self.target)

        self.assertEqual(422, result.status_code)
        self.assertEqual("AMBIGUOUS_PLACEMENT", result.body["error_code"])
        self.assertEqual(before, harness.context().state)

    def test_four_neighbors_do_not_create_implicit_cross(self):
        harness = DiscoveryApplicationHarness(
            (), (single(2, 1), single(3, 2), single(2, 3), single(1, 2))
        )

        result = place(harness, self.target)

        self.assertEqual(422, result.status_code)
        self.assertEqual("AMBIGUOUS_PLACEMENT", result.body["error_code"])
        self.assertFalse(any(
            cell.position == self.target for cell in harness.context().state.wire_cells
        ))

    def test_explicit_cross_has_two_independent_channels(self):
        harness = DiscoveryApplicationHarness(
            (), (single(2, 1), single(3, 2), single(2, 3), single(1, 2))
        )

        result = place(harness, self.target, intent="CROSS")
        target = cell_at(harness.context().state, self.target)

        self.assertEqual(200, result.status_code)
        self.assertEqual((WireChannel.NS, WireChannel.EW), tuple(
            channel.channel for channel in target.channels
        ))
        self.assertEqual(2, len(result.body["discovery"]["components"]))
        vertices = [
            {(step["position"]["x"], step["position"]["z"], step["channel"])
             for step in component["vertices"]}
            for component in result.body["discovery"]["components"]
        ]
        self.assertTrue(any((2, 2, "NS") in component for component in vertices))
        self.assertTrue(any((2, 2, "EW") in component for component in vertices))
        self.assertFalse(any(
            (2, 2, "NS") in component and (2, 2, "EW") in component
            for component in vertices
        ))

    def test_existing_neighbor_connection_is_preserved(self):
        west = single(1, 2, CardinalDirection.WEST)
        harness = DiscoveryApplicationHarness((), (west,))

        result = place(harness, self.target)

        self.assertEqual(200, result.status_code)
        self.assertEqual(
            {CardinalDirection.WEST, CardinalDirection.EAST},
            connections(harness.context().state, west.position),
        )

    def test_full_neighbor_that_cannot_reciprocate_is_rejected(self):
        west = single(
            1, 2, CardinalDirection.NORTH, CardinalDirection.WEST
        )
        harness = DiscoveryApplicationHarness((), (west,))
        before = harness.context().state

        result = place(harness, self.target)

        self.assertEqual(422, result.status_code)
        self.assertEqual("INVALID_CONNECTION", result.body["error_code"])
        self.assertEqual(before, harness.context().state)

    def test_program_port_is_used_without_creating_edge(self):
        harness = DiscoveryApplicationHarness((("START", 0, 0, "EAST"),), ())

        result = place(harness, GridPos(1, 0, 0))

        self.assertEqual(200, result.status_code)
        self.assertEqual(
            {CardinalDirection.WEST},
            connections(harness.context().state, GridPos(1, 0, 0)),
        )
        self.assertEqual((), harness.context().state.edges)

    def test_if_three_ports_accept_wire_and_bind_three_control_routes(self):
        harness = DiscoveryApplicationHarness(
            (
                ("START", 2, 0, "SOUTH"),
                ("IF", 2, 2, "NORTH"),
                ("STOP", 0, 2, "WEST"),
                ("STOP", 4, 2, "EAST"),
            ),
            (),
        )

        place(harness, GridPos(2, 0, 1))
        place(harness, GridPos(1, 0, 2))
        place(harness, GridPos(3, 0, 2))
        result = harness.bind()

        self.assertEqual(200, result.status_code)
        self.assertEqual(3, len(result.body["state"]["edges"]))
        self.assertEqual(3, len(result.body["state"]["routes"]))
        self.assertEqual(
            {("out", "in"), ("true", "in"), ("false", "in")},
            {
                (edge["source_port_key"], edge["target_port_key"])
                for edge in result.body["state"]["edges"]
            },
        )
        self.assertTrue(all(
            route["status"] == "VALID"
            for route in result.body["state"]["routes"]
        ))

    def test_if_back_side_is_not_treated_as_a_port(self):
        harness = DiscoveryApplicationHarness((("IF", 2, 2, "NORTH"),), ())

        result = place(harness, GridPos(2, 0, 3))

        self.assertEqual(200, result.status_code)
        self.assertEqual(
            set(), connections(harness.context().state, GridPos(2, 0, 3))
        )
        self.assertEqual([], result.body["discovery"]["candidates"])
        self.assertEqual(
            "INCOMPLETE", result.body["discovery"]["components"][0]["status"]
        )

    def test_occupied_target_is_rejected(self):
        harness = DiscoveryApplicationHarness((), (single(2, 2),))
        before = harness.context().state

        result = place(harness, self.target)

        self.assertEqual(422, result.status_code)
        self.assertEqual("OCCUPIED_CELL", result.body["error_code"])
        self.assertEqual(before, harness.context().state)

    def test_stage_boundary_is_rejected(self):
        harness = DiscoveryApplicationHarness((), ())

        result = place(harness, GridPos(5, 0, 0))

        self.assertEqual(422, result.status_code)
        self.assertEqual("STAGE_BOUNDARY", result.body["error_code"])
        self.assertEqual((), harness.context().state.wire_cells)

    def test_stale_revision_and_epoch_are_rejected(self):
        harness = DiscoveryApplicationHarness((), ())
        context = harness.context()

        stale_revision = place(
            harness, self.target, revision=context.state.working_revision + 1
        )
        stale_epoch = place(harness, self.target, epoch=str(uuid.uuid4()))

        self.assertEqual("STALE_WORKING_REVISION", stale_revision.body["error_code"])
        self.assertEqual("STALE_WORKSPACE_EPOCH", stale_epoch.body["error_code"])
        self.assertEqual((), harness.context().state.wire_cells)

    def test_request_id_replay_is_idempotent(self):
        harness = DiscoveryApplicationHarness((), ())
        request_id = uuid.uuid4()
        command = placement_command(harness, self.target, request_id=request_id)

        first = harness.app.place_wire(command)
        replay = harness.app.place_wire(command)

        self.assertEqual(first, replay)
        self.assertEqual(1, len(harness.context().state.wire_cells))
        self.assertEqual(first.body["working_revision"], harness.context().state.working_revision)

    def test_bound_edge_and_route_are_unchanged(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, cells)
        bound = harness.bind()
        before = harness.context().state

        result = place(harness, GridPos(0, 0, 4))
        after = harness.context().state

        self.assertEqual(200, result.status_code)
        self.assertEqual(before.edges, after.edges)
        self.assertEqual(before.routes, after.routes)
        self.assertEqual(before.working_revision + 1, after.working_revision)

    def test_discovery_is_recomputed_after_placement(self):
        harness = DiscoveryApplicationHarness((), (single(1, 2),))

        result = place(harness, self.target)

        self.assertEqual("INCOMPLETE", result.body["discovery"]["components"][0]["status"])
        self.assertEqual("OPEN_WIRE", result.body["discovery"]["diagnostics"][0]["code"])

    def test_explicit_cross_failure_rolls_back_all_neighbor_updates(self):
        harness = DiscoveryApplicationHarness(
            (), (single(2, 1), single(3, 2), single(2, 3))
        )
        before = harness.context().state

        result = place(harness, self.target, intent="CROSS")

        self.assertEqual(422, result.status_code)
        self.assertEqual("INVALID_CONNECTION", result.body["error_code"])
        self.assertEqual(before, harness.context().state)

    def test_unknown_intent_is_rejected_by_versioned_parser(self):
        harness = DiscoveryApplicationHarness((), ())
        context = harness.context()
        raw = {
            "schema_version": 1,
            "request_id": str(uuid.uuid4()),
            "stage_id": harness.stage_id,
            "player_uuid": harness.owner,
            "workspace_epoch": str(context.workspace_epoch),
            "expected_working_revision": context.state.working_revision,
            "operation": "WIRE_PLACE_INTENT",
            "payload": {
                "position": {"x": 2, "y": 0, "z": 2},
                "intent": "JUNCTION",
            },
        }

        with self.assertRaisesRegex(ValueError, "placement intent"):
            parse_graph_command(
                raw,
                expected_stage_id=harness.stage_id,
                expected_operation="WIRE_PLACE_INTENT",
            )


if __name__ == "__main__":
    unittest.main()
