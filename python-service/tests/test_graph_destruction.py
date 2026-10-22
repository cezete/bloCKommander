import unittest
from copy import deepcopy
from dataclasses import FrozenInstanceError, replace
from uuid import UUID

from hangar.graph.destruction import (
    ConnectionSelection,
    confirm_destruction,
    preview_destruction,
)
from hangar.graph.diagnostics import DiagnosticCode, ValidationReport
from hangar.graph.model import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GridPos,
    PortRef,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.state import GraphStatus, GraphWorkspaceState, SaveStatus, replace_content


STAGE_ID = UUID("00000000-0000-0000-0000-000000000100")
OTHER_STAGE_ID = UUID("00000000-0000-0000-0000-000000000200")
PLAYER_ID = UUID("00000000-0000-0000-0000-000000009001")
EDGE_A = UUID("00000000-0000-0000-0000-000000007001")
EDGE_B = UUID("00000000-0000-0000-0000-000000007002")
ROUTE_A = UUID("00000000-0000-0000-0000-000000008001")
ROUTE_B = UUID("00000000-0000-0000-0000-000000008002")
P1 = GridPos(1, 0, 0)
P2 = GridPos(2, 0, 0)


def graph_edge(edge_id=EDGE_A):
    if edge_id == EDGE_A:
        return GraphEdge(
            edge_id,
            STAGE_ID,
            UUID("00000000-0000-0000-0000-000000000101"),
            "out",
            UUID("00000000-0000-0000-0000-000000000106"),
            "in",
            EdgeKind.CONTROL,
        )
    return GraphEdge(
        edge_id,
        STAGE_ID,
        UUID("00000000-0000-0000-0000-000000000102"),
        "true",
        UUID("00000000-0000-0000-0000-000000000103"),
        "in",
        EdgeKind.CONTROL,
    )


def route(
    route_id=ROUTE_A,
    edge_id=EDGE_A,
    *,
    position=P1,
    channel=WireChannel.SINGLE,
    status=RouteStatus.VALID,
):
    source = (
        PortRef(UUID("00000000-0000-0000-0000-000000000101"), "out")
        if edge_id in (EDGE_A, None)
        else PortRef(UUID("00000000-0000-0000-0000-000000000102"), "true")
    )
    target = (
        PortRef(UUID("00000000-0000-0000-0000-000000000106"), "in")
        if edge_id in (EDGE_A, None)
        else PortRef(UUID("00000000-0000-0000-0000-000000000103"), "in")
    )
    return EdgeRoute(
        route_id,
        STAGE_ID,
        edge_id,
        source,
        target,
        (WireStep(position, channel),),
        status,
    )


def single_cell(position=P1, connections=None):
    if connections is None:
        connections = frozenset({CardinalDirection.EAST, CardinalDirection.WEST})
    return WireCell(
        position,
        (WireChannelState(WireChannel.SINGLE, connections),),
    )


def cross_cell(position=P1):
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


def state(*, edges=(), routes=(), cells=(), revision=5, dirty=False, saved=9, validated=False):
    return GraphWorkspaceState(
        STAGE_ID,
        (),
        tuple(edges),
        tuple(cells),
        tuple(routes),
        revision,
        revision if validated else None,
        saved,
        dirty,
        ValidationReport(revision, ()) if validated else None,
    )


def selection(workspace, *, position=P1, channel=None, stage_id=STAGE_ID, revision=None):
    return ConnectionSelection(
        stage_id,
        PLAYER_ID,
        workspace.working_revision if revision is None else revision,
        position,
        channel,
    )


def bound_state():
    return state(edges=(graph_edge(),), routes=(route(),), cells=(single_cell(),))


def cross_state():
    return state(
        edges=(graph_edge(EDGE_A), graph_edge(EDGE_B)),
        routes=(
            route(ROUTE_A, EDGE_A, channel=WireChannel.NS),
            route(ROUTE_B, EDGE_B, channel=WireChannel.EW),
        ),
        cells=(cross_cell(),),
    )


class GraphDestructionTest(unittest.TestCase):
    def test_selection_and_preview_are_immutable(self):
        workspace = bound_state()
        selected = selection(workspace)
        with self.assertRaises(FrozenInstanceError):
            selected.channel = WireChannel.SINGLE
        preview = preview_destruction(workspace, selected)
        with self.assertRaises(FrozenInstanceError):
            preview.allowed = False

    def test_unambiguous_bound_route_preview(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        self.assertTrue(preview.allowed)
        self.assertEqual(preview.route_id, ROUTE_A)
        self.assertEqual(preview.edge_id, EDGE_A)
        self.assertEqual(preview.affected_steps, workspace.routes[0].steps)
        self.assertEqual(preview.cell_changes[0].after, None)

    def test_unambiguous_unbound_route_preview(self):
        unbound = route(edge_id=None)
        workspace = state(routes=(unbound,), cells=(single_cell(),))
        preview = preview_destruction(workspace, selection(workspace))
        self.assertTrue(preview.allowed)
        self.assertEqual(preview.route_id, ROUTE_A)
        self.assertIsNone(preview.edge_id)

    def test_nonexistent_selection_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace, position=P2))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, self._codes(preview))

    def test_ambiguous_selection_is_rejected(self):
        first = route(ROUTE_A, None)
        second = route(ROUTE_B, None)
        workspace = state(routes=(first, second), cells=(single_cell(),))
        preview = preview_destruction(workspace, selection(workspace))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.AMBIGUOUS_CONNECTION, self._codes(preview))

    def test_cross_without_channel_is_rejected(self):
        workspace = cross_state()
        preview = preview_destruction(workspace, selection(workspace))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.AMBIGUOUS_CONNECTION, self._codes(preview))

    def test_cross_ns_selection_resolves_ns_route(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.NS),
        )
        self.assertTrue(preview.allowed)
        self.assertEqual(preview.route_id, ROUTE_A)
        self.assertEqual(preview.edge_id, EDGE_A)

    def test_cross_ew_selection_resolves_ew_route(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.EW),
        )
        self.assertTrue(preview.allowed)
        self.assertEqual(preview.route_id, ROUTE_B)
        self.assertEqual(preview.edge_id, EDGE_B)

    def test_delete_ns_preserves_ew_connection(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.NS),
        )
        result = confirm_destruction(workspace, preview, confirmed=True)
        self.assertTrue(result.applied)
        self.assertEqual(tuple(edge.edge_id for edge in result.state.edges), (EDGE_B,))
        self.assertEqual(tuple(item.route_id for item in result.state.routes), (ROUTE_B,))
        self.assertEqual(result.state.routes[0].steps[0].channel, WireChannel.SINGLE)

    def test_delete_ew_preserves_ns_connection(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.EW),
        )
        result = confirm_destruction(workspace, preview, confirmed=True)
        self.assertEqual(tuple(edge.edge_id for edge in result.state.edges), (EDGE_A,))
        self.assertEqual(tuple(item.route_id for item in result.state.routes), (ROUTE_A,))
        self.assertEqual(result.state.routes[0].steps[0].channel, WireChannel.SINGLE)

    def test_cross_converts_to_valid_single_cell(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.NS),
        )
        result = confirm_destruction(workspace, preview, confirmed=True)
        cell = result.state.wire_cells[0]
        self.assertEqual(len(cell.channels), 1)
        self.assertEqual(cell.channels[0].channel, WireChannel.SINGLE)
        self.assertEqual(
            cell.channels[0].connections,
            frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
        )

    def test_bound_route_deletes_its_graph_edge(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        result = confirm_destruction(workspace, preview, confirmed=True)
        self.assertTrue(result.applied)
        self.assertEqual(result.state.edges, ())
        self.assertEqual(result.state.routes, ())
        self.assertEqual(result.state.wire_cells, ())

    def test_unbound_route_does_not_delete_graph_edges(self):
        unrelated = graph_edge(EDGE_B)
        workspace = state(
            edges=(unrelated,),
            routes=(route(edge_id=None),),
            cells=(single_cell(),),
        )
        preview = preview_destruction(workspace, selection(workspace))
        result = confirm_destruction(workspace, preview, confirmed=True)
        self.assertEqual(result.state.edges, (unrelated,))
        self.assertEqual(result.state.routes, ())

    def test_other_graph_edges_are_preserved(self):
        workspace = state(
            edges=(graph_edge(EDGE_A), graph_edge(EDGE_B)),
            routes=(route(),),
            cells=(single_cell(),),
        )
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertEqual(result.state.edges, (graph_edge(EDGE_B),))

    def test_other_routes_are_preserved(self):
        other = route(ROUTE_B, EDGE_B, position=P2)
        workspace = state(
            edges=(graph_edge(EDGE_A), graph_edge(EDGE_B)),
            routes=(route(), other),
            cells=(single_cell(), single_cell(P2)),
        )
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertEqual(result.state.routes, (other,))

    def test_edge_shared_by_multiple_routes_is_rejected(self):
        workspace = state(
            edges=(graph_edge(),),
            routes=(route(), route(ROUTE_B, EDGE_A, position=P2)),
            cells=(single_cell(), single_cell(P2)),
        )
        preview = preview_destruction(workspace, selection(workspace))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.AMBIGUOUS_CONNECTION, self._codes(preview))

    def test_broken_route_assignment_is_rejected(self):
        workspace = state(
            routes=(route(edge_id=EDGE_A),),
            cells=(single_cell(),),
        )
        preview = preview_destruction(workspace, selection(workspace))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, self._codes(preview))

    def test_confirmed_false_changes_nothing(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        result = confirm_destruction(workspace, preview, confirmed=False)
        self.assertFalse(result.applied)
        self.assertIs(result.state, workspace)
        self.assertEqual(result.diagnostics, ())

    def test_confirmed_true_applies_valid_preview(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        self.assertTrue(confirm_destruction(workspace, preview, confirmed=True).applied)

    def test_stale_preview_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        changed = replace_content(workspace, wire_cells=workspace.wire_cells + (single_cell(P2),))
        result = confirm_destruction(changed, preview, confirmed=True)
        self.assertFalse(result.applied)
        self.assertIs(result.state, changed)
        self.assertIn(DiagnosticCode.STALE_WORKING_REVISION, self._codes(result))

    def test_preview_with_wrong_stage_is_rejected_at_confirmation(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        tampered = replace(preview, stage_id=OTHER_STAGE_ID)
        result = confirm_destruction(workspace, tampered, confirmed=True)
        self.assertFalse(result.applied)
        self.assertIs(result.state, workspace)
        self.assertIn(DiagnosticCode.CROSS_STAGE_EDGE, self._codes(result))

    def test_wrong_selection_stage_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, stage_id=OTHER_STAGE_ID),
        )
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.CROSS_STAGE_EDGE, self._codes(preview))

    def test_wrong_selection_revision_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace, revision=4))
        self.assertFalse(preview.allowed)
        self.assertIn(DiagnosticCode.STALE_WORKING_REVISION, self._codes(preview))

    def test_changed_target_route_at_same_revision_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        changed_route = replace(workspace.routes[0], status=RouteStatus.BROKEN)
        changed = state(
            edges=workspace.edges,
            routes=(changed_route,),
            cells=workspace.wire_cells,
            revision=workspace.working_revision,
        )
        result = confirm_destruction(changed, preview, confirmed=True)
        self.assertFalse(result.applied)
        self.assertIs(result.state, changed)

    def test_changed_target_cell_at_same_revision_is_rejected(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        changed = state(
            edges=workspace.edges,
            routes=workspace.routes,
            cells=(single_cell(connections=frozenset({CardinalDirection.EAST})),),
            revision=workspace.working_revision,
        )
        result = confirm_destruction(changed, preview, confirmed=True)
        self.assertFalse(result.applied)
        self.assertIs(result.state, changed)

    def test_preview_does_not_modify_state(self):
        workspace = cross_state()
        original = deepcopy(workspace)
        preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.NS),
        )
        self.assertEqual(workspace, original)

    def test_failed_confirmation_returns_original_state(self):
        workspace = bound_state()
        invalid = replace(
            preview_destruction(workspace, selection(workspace)),
            allowed=False,
        )
        result = confirm_destruction(workspace, invalid, confirmed=True)
        self.assertIs(result.state, workspace)
        self.assertFalse(result.applied)

    def test_success_increments_revision_exactly_once(self):
        workspace = bound_state()
        preview = preview_destruction(workspace, selection(workspace))
        result = confirm_destruction(workspace, preview, confirmed=True)
        self.assertEqual(result.state.working_revision, workspace.working_revision + 1)

    def test_success_marks_state_dirty(self):
        workspace = bound_state()
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertIs(result.state.save_status, SaveStatus.DIRTY)

    def test_success_does_not_change_saved_version(self):
        workspace = bound_state()
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertEqual(result.state.saved_version, workspace.saved_version)

    def test_success_makes_previous_validation_historical(self):
        workspace = state(
            edges=(graph_edge(),),
            routes=(route(),),
            cells=(single_cell(),),
            validated=True,
        )
        self.assertIs(workspace.graph_status, GraphStatus.VALID)
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertIs(result.state.graph_status, GraphStatus.DRAFT)
        self.assertEqual(result.state.validated_revision, workspace.validated_revision)
        self.assertEqual(result.state.last_validation, workspace.last_validation)

    def test_unrelated_broken_route_is_preserved(self):
        broken = route(ROUTE_B, EDGE_B, position=P2, status=RouteStatus.BROKEN)
        workspace = state(
            edges=(graph_edge(EDGE_A), graph_edge(EDGE_B)),
            routes=(route(), broken),
            cells=(single_cell(), single_cell(P2)),
        )
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertIn(broken, result.state.routes)
        self.assertIn(graph_edge(EDGE_B), result.state.edges)

    def test_deletion_does_not_require_valid_stage(self):
        workspace = bound_state()
        self.assertIs(workspace.graph_status, GraphStatus.DRAFT)
        result = confirm_destruction(
            workspace,
            preview_destruction(workspace, selection(workspace)),
            confirmed=True,
        )
        self.assertTrue(result.applied)

    def test_ambiguous_diagnostics_are_deterministic(self):
        first = route(ROUTE_A, None)
        second = route(ROUTE_B, None)
        forward = state(routes=(first, second), cells=(single_cell(),))
        reverse = state(routes=(second, first), cells=(single_cell(),))
        first_preview = preview_destruction(forward, selection(forward))
        second_preview = preview_destruction(reverse, selection(reverse))
        self.assertEqual(first_preview.diagnostics, second_preview.diagnostics)

    def test_cross_preview_records_route_conversion(self):
        workspace = cross_state()
        preview = preview_destruction(
            workspace,
            selection(workspace, channel=WireChannel.NS),
        )
        self.assertEqual(len(preview.route_changes), 1)
        self.assertEqual(preview.route_changes[0].route_id, ROUTE_B)
        self.assertEqual(
            preview.route_changes[0].after.steps[0].channel,
            WireChannel.SINGLE,
        )

    @staticmethod
    def _codes(item):
        return tuple(diagnostic.code for diagnostic in item.diagnostics)


if __name__ == "__main__":
    unittest.main()
