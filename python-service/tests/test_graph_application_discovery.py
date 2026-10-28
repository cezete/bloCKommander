import unittest
import uuid
from unittest.mock import patch

from hangar.graph import (
    BindingResult,
    CardinalDirection,
    Diagnostic,
    DiagnosticCode,
    GridPos,
    Severity,
    WireCell,
    WireChannel,
    WireChannelState,
    replace_content,
)
from hangar.graph_application import (
    DISCOVERY_SCHEMA_VERSION,
    GraphApplicationService,
    parse_graph_command,
)
from hangar.stage import StageService
from tests.test_stage_graph_service import runtime_types
from tests.test_stage_service import FakeStageRepository


def single(x, z, *directions):
    return WireCell(
        GridPos(x, 0, z),
        (WireChannelState(WireChannel.SINGLE, frozenset(directions)),),
    )


def cross(x, z):
    return WireCell(
        GridPos(x, 0, z),
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


class DiscoveryApplicationHarness:
    def __init__(self, block_specs, cells):
        self.repo = FakeStageRepository(graph_aware=True)
        self.repo.types = runtime_types()
        self.stages = StageService(self.repo)
        self.stage_id = self.repo.stage["stage_id"]
        self.owner = self.repo.stage["owner_uuid"]
        for type_key, x, z, orientation in block_specs:
            self.stages.place(self.stage_id, {
                "player_uuid": self.owner,
                "type_key": type_key,
                "x": 11 + x,
                "y": 65,
                "z": 21 + z,
                "orientation": orientation,
            })
        if cells:
            context = self.context()
            self.stages.apply_graph_operation(
                self.stage_id,
                {
                    "player_uuid": self.owner,
                    "workspace_epoch": str(context.workspace_epoch),
                    "expected_working_revision": context.state.working_revision,
                },
                lambda state: replace_content(state, wire_cells=tuple(cells)),
            )
        self.app = GraphApplicationService(self.stages)

    def context(self):
        return self.stages.read_graph_context(self.stage_id, self.owner)

    def command(
        self, operation, *, revision=None, epoch=None, request_id=None,
        position=None, cell=None,
    ):
        context = self.context()
        payload = {}
        if operation == "WIRE_LAYOUT":
            payload = {"changes": [{
                "position": {
                    "x": position.x,
                    "y": position.y,
                    "z": position.z,
                },
                "cell": None if cell is None else {
                    "channels": [{
                        "channel": channel.channel.value,
                        "connections": sorted(
                            direction.value for direction in channel.connections
                        ),
                    } for channel in cell.channels],
                },
            }]}
        raw = {
            "schema_version": 1,
            "request_id": str(request_id or uuid.uuid4()),
            "stage_id": self.stage_id,
            "player_uuid": self.owner,
            "workspace_epoch": epoch or str(context.workspace_epoch),
            "expected_working_revision": (
                context.state.working_revision if revision is None else revision
            ),
            "operation": operation,
            "payload": payload,
        }
        return parse_graph_command(
            raw,
            expected_stage_id=self.stage_id,
            expected_operation=operation,
        )

    def bind(self, **kwargs):
        return self.app.bind_edges(self.command("BIND_EDGES", **kwargs))

    def wire(self, position, cell, **kwargs):
        command = self.command(
            "WIRE_LAYOUT", position=position, cell=cell, **kwargs
        )
        return self.app.apply_wire(command)


def horizontal_lane(z=0):
    blocks = (
        ("START", 0, z, "EAST"),
        ("STOP", 4, z, "EAST"),
    )
    cells = tuple(
        single(x, z, CardinalDirection.WEST, CardinalDirection.EAST)
        for x in (1, 2, 3)
    )
    return blocks, cells


class GraphApplicationDiscoveryTest(unittest.TestCase):
    def test_complete_connection_has_additive_read_only_discovery_dto(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, cells)
        before = harness.context().state

        response = harness.app.graph_state(harness.stage_id, harness.owner)

        self.assertEqual(200, response.status_code)
        self.assertFalse(response.body["modified"])
        discovery = response.body["discovery"]
        self.assertEqual(DISCOVERY_SCHEMA_VERSION, discovery["schema_version"])
        self.assertEqual("COMPLETE", discovery["components"][0]["status"])
        self.assertEqual(1, len(discovery["candidates"]))
        self.assertEqual(3, len(discovery["candidates"][0]["steps"]))
        self.assertEqual(before, harness.context().state)

    def test_multiple_independent_connections_are_returned_and_bound_atomically(self):
        first_blocks, first_cells = horizontal_lane(0)
        second_blocks, second_cells = horizontal_lane(2)
        harness = DiscoveryApplicationHarness(
            first_blocks + second_blocks,
            first_cells + second_cells,
        )

        result = harness.bind()

        self.assertEqual(200, result.status_code)
        self.assertEqual(2, len(result.body["discovery"]["candidates"]))
        self.assertEqual(2, len(result.body["binding"]["candidate_ids"]))
        self.assertEqual(2, len(result.body["binding"]["created_edge_ids"]))
        self.assertEqual(2, len(result.body["state"]["edges"]))
        self.assertEqual(2, len(result.body["state"]["routes"]))

    def test_repeated_stage_bind_reuses_both_edges_and_routes(self):
        first_blocks, first_cells = horizontal_lane(0)
        second_blocks, second_cells = horizontal_lane(2)
        harness = DiscoveryApplicationHarness(
            first_blocks + second_blocks,
            first_cells + second_cells,
        )
        first = harness.bind()
        revision = first.body["working_revision"]
        route_ids = [item["route_id"] for item in first.body["state"]["routes"]]

        second = harness.bind()

        self.assertEqual(200, second.status_code)
        self.assertFalse(second.body["modified"])
        self.assertEqual(revision, second.body["working_revision"])
        self.assertEqual(2, len(second.body["binding"]["reused_edge_ids"]))
        self.assertEqual(route_ids, [item["route_id"] for item in second.body["state"]["routes"]])

    def test_incomplete_component_is_structured_separately_from_bind_diagnostics(self):
        blocks = (("START", 0, 0, "EAST"),)
        cells = (
            single(1, 0, CardinalDirection.WEST, CardinalDirection.EAST),
            single(2, 0, CardinalDirection.WEST, CardinalDirection.EAST),
        )
        harness = DiscoveryApplicationHarness(blocks, cells)

        state = harness.app.graph_state(harness.stage_id, harness.owner)
        bind = harness.bind()

        self.assertEqual("INCOMPLETE", state.body["discovery"]["components"][0]["status"])
        self.assertEqual("OPEN_WIRE", state.body["discovery"]["diagnostics"][0]["code"])
        self.assertEqual(422, bind.status_code)
        self.assertEqual("NO_BINDABLE_CONNECTIONS", bind.body["error_code"])
        self.assertEqual("OPEN_WIRE", bind.body["discovery"]["diagnostics"][0]["code"])
        self.assertEqual((), harness.context().state.edges)

    def test_cross_channels_are_two_components_and_two_candidates_in_dto(self):
        blocks = (
            ("START", 0, 2, "EAST"),
            ("STOP", 4, 2, "EAST"),
            ("START", 2, 0, "SOUTH"),
            ("STOP", 2, 4, "SOUTH"),
        )
        cells = (
            single(1, 2, CardinalDirection.WEST, CardinalDirection.EAST),
            cross(2, 2),
            single(3, 2, CardinalDirection.WEST, CardinalDirection.EAST),
            single(2, 1, CardinalDirection.NORTH, CardinalDirection.SOUTH),
            single(2, 3, CardinalDirection.NORTH, CardinalDirection.SOUTH),
        )
        harness = DiscoveryApplicationHarness(blocks, cells)

        discovery = harness.app.graph_state(
            harness.stage_id, harness.owner
        ).body["discovery"]

        self.assertEqual(2, len(discovery["components"]))
        self.assertEqual(2, len(discovery["candidates"]))
        middle_channels = {
            candidate["steps"][1]["channel"] for candidate in discovery["candidates"]
        }
        self.assertEqual({"NS", "EW"}, middle_channels)

    def test_valid_components_bind_while_unrelated_incomplete_component_is_reported(self):
        valid_blocks, valid_cells = horizontal_lane(0)
        blocks = valid_blocks + (("START", 0, 2, "EAST"),)
        cells = valid_cells + (
            single(1, 2, CardinalDirection.WEST, CardinalDirection.EAST),
        )
        harness = DiscoveryApplicationHarness(blocks, cells)

        result = harness.bind()

        self.assertEqual(200, result.status_code)
        self.assertEqual(1, len(result.body["state"]["edges"]))
        self.assertEqual(1, len(result.body["state"]["routes"]))
        self.assertEqual(1, len(result.body["discovery"]["diagnostics"]))
        self.assertEqual(
            "OPEN_WIRE", result.body["discovery"]["diagnostics"][0]["code"]
        )

    def test_broken_physical_route_does_not_delete_existing_edge_or_route(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, cells)
        bound = harness.bind()
        edge_id = bound.body["state"]["edges"][0]["edge_id"]
        route_id = bound.body["state"]["routes"][0]["route_id"]
        context = harness.context()
        harness.stages.apply_graph_operation(
            harness.stage_id,
            {
                "player_uuid": harness.owner,
                "workspace_epoch": str(context.workspace_epoch),
                "expected_working_revision": context.state.working_revision,
            },
            lambda state: replace_content(
                state,
                wire_cells=tuple(cell for cell in state.wire_cells if cell.position.x != 2),
            ),
        )

        result = harness.bind()

        self.assertEqual(422, result.status_code)
        self.assertEqual("NO_BINDABLE_CONNECTIONS", result.body["error_code"])
        current = harness.context().state
        self.assertEqual(edge_id, str(current.edges[0].edge_id))
        self.assertEqual(route_id, str(current.routes[0].route_id))

    def test_multi_candidate_domain_rejection_is_atomic(self):
        first_blocks, first_cells = horizontal_lane(0)
        second_blocks, second_cells = horizontal_lane(2)
        harness = DiscoveryApplicationHarness(
            first_blocks + second_blocks,
            first_cells + second_cells,
        )
        before = harness.context().state
        diagnostic = Diagnostic(
            DiagnosticCode.AMBIGUOUS_CONNECTION,
            Severity.ERROR,
            "rejected",
        )
        with patch(
            "hangar.graph_application.bind_edges",
            side_effect=lambda state, *args, **kwargs: BindingResult(
                False, state, (diagnostic,)
            ),
        ):
            result = harness.bind()

        self.assertEqual(422, result.status_code)
        self.assertEqual("GRAPH_OPERATION_REJECTED", result.body["error_code"])
        self.assertEqual(2, len(result.body["discovery"]["candidates"]))
        self.assertEqual(before, harness.context().state)

    def test_wire_layout_builds_multicell_straight_one_cell_per_command(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, ())

        first = harness.wire(cells[0].position, cells[0])
        second = harness.wire(cells[1].position, cells[1])
        third = harness.wire(cells[2].position, cells[2])

        self.assertEqual("INCOMPLETE", first.body["discovery"]["components"][0]["status"])
        self.assertEqual("INCOMPLETE", second.body["discovery"]["components"][0]["status"])
        self.assertEqual("COMPLETE", third.body["discovery"]["components"][0]["status"])
        self.assertEqual(1, len(third.body["discovery"]["candidates"]))
        self.assertEqual([], third.body["state"]["edges"])

    def test_wire_layout_builds_corner_and_reports_complete_candidate(self):
        blocks = (
            ("START", 0, 0, "SOUTH"),
            ("STOP", 2, 2, "SOUTH"),
        )
        cells = (
            single(0, 1, CardinalDirection.NORTH, CardinalDirection.EAST),
            single(1, 1, CardinalDirection.WEST, CardinalDirection.EAST),
            single(2, 1, CardinalDirection.WEST, CardinalDirection.SOUTH),
        )
        harness = DiscoveryApplicationHarness(blocks, ())

        for cell in cells:
            result = harness.wire(cell.position, cell)

        self.assertEqual("COMPLETE", result.body["discovery"]["components"][0]["status"])
        self.assertEqual(
            [
                {"x": 0, "y": 0, "z": 1},
                {"x": 1, "y": 0, "z": 1},
                {"x": 2, "y": 0, "z": 1},
            ],
            [step["position"] for step in result.body["discovery"]["candidates"][0]["steps"]],
        )

    def test_wire_layout_builds_cross_as_two_independent_candidates(self):
        blocks = (
            ("START", 0, 2, "EAST"),
            ("STOP", 4, 2, "EAST"),
            ("START", 2, 0, "SOUTH"),
            ("STOP", 2, 4, "SOUTH"),
        )
        cells = (
            single(1, 2, CardinalDirection.WEST, CardinalDirection.EAST),
            cross(2, 2),
            single(3, 2, CardinalDirection.WEST, CardinalDirection.EAST),
            single(2, 1, CardinalDirection.NORTH, CardinalDirection.SOUTH),
            single(2, 3, CardinalDirection.NORTH, CardinalDirection.SOUTH),
        )
        harness = DiscoveryApplicationHarness(blocks, ())

        for cell in cells:
            result = harness.wire(cell.position, cell)

        self.assertEqual(2, len(result.body["discovery"]["components"]))
        self.assertEqual(2, len(result.body["discovery"]["candidates"]))
        self.assertEqual([], result.body["state"]["edges"])

    def test_inconsistent_neighbor_channels_are_saved_as_draft_and_diagnosed(self):
        harness = DiscoveryApplicationHarness((), ())
        east_west = single(
            1, 0, CardinalDirection.WEST, CardinalDirection.EAST
        )
        north_south = single(
            2, 0, CardinalDirection.NORTH, CardinalDirection.SOUTH
        )

        harness.wire(east_west.position, east_west)
        result = harness.wire(north_south.position, north_south)

        self.assertEqual(200, result.status_code)
        self.assertEqual(2, len(result.body["state"]["wire_cells"]))
        self.assertEqual(2, len(result.body["discovery"]["components"]))
        self.assertTrue(all(
            component["status"] == "INCOMPLETE"
            for component in result.body["discovery"]["components"]
        ))
        self.assertEqual(2, len(result.body["discovery"]["diagnostics"]))

    def test_wire_add_and_remove_preserve_bound_edge_and_route(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, ())
        for cell in cells:
            harness.wire(cell.position, cell)
        bound = harness.bind()
        edge_id = bound.body["state"]["edges"][0]["edge_id"]
        route_id = bound.body["state"]["routes"][0]["route_id"]

        removed = harness.wire(cells[1].position, None)

        self.assertEqual(2, len(removed.body["state"]["wire_cells"]))
        self.assertEqual(edge_id, removed.body["state"]["edges"][0]["edge_id"])
        self.assertEqual(route_id, removed.body["state"]["routes"][0]["route_id"])
        self.assertEqual("INCOMPLETE", removed.body["discovery"]["components"][0]["status"])

    def test_wire_layout_epoch_revision_and_request_id_fencing_still_apply(self):
        blocks, cells = horizontal_lane()
        harness = DiscoveryApplicationHarness(blocks, ())
        request_id = uuid.uuid4()
        command = harness.command(
            "WIRE_LAYOUT",
            request_id=request_id,
            position=cells[0].position,
            cell=cells[0],
        )

        first = harness.app.apply_wire(command)
        repeated = harness.app.apply_wire(command)
        stale = harness.wire(
            cells[1].position,
            cells[1],
            revision=first.body["working_revision"] - 1,
        )

        self.assertEqual(first, repeated)
        self.assertEqual(409, stale.status_code)
        self.assertEqual("STALE_WORKING_REVISION", stale.body["error_code"])
        self.assertEqual(1, len(harness.context().state.wire_cells))


if __name__ == "__main__":
    unittest.main()
