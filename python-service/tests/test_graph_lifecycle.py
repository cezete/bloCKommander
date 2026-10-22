import unittest
from copy import deepcopy
from uuid import UUID

from graph_validation_fixtures import (
    IF_ID,
    PORT_CATALOG,
    SET_ID,
    STAGE_ID,
    START_ID,
    STOP_ID,
    node,
    straight_cell,
)
from hangar.graph import (
    BindEdgesCommand,
    CardinalDirection,
    ConnectionSelection,
    DiagnosticCode,
    EdgeIndicatorStatus,
    GraphStatus,
    GraphWorkspaceState,
    GridPos,
    PortRef,
    RouteCandidate,
    SaveStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
    bind_edges,
    confirm_destruction,
    mark_saved,
    preview_destruction,
    record_validation,
    replace_content,
    validate_graph,
)


PLAYER_ID = UUID("00000000-0000-0000-0000-000000009901")
EDGE_IDS = tuple(
    UUID(f"00000000-0000-0000-0000-{value:012d}")
    for value in range(9901, 9921)
)
ROUTE_IDS = tuple(
    UUID(f"00000000-0000-0000-0000-{value:012d}")
    for value in range(9951, 9971)
)
P1 = GridPos(1, 0, 0)
P2 = GridPos(1, 0, 1)
PX = GridPos(10, 0, 10)


class FixedFactory:
    def __init__(self, values):
        self._values = iter(values)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return next(self._values)


def candidate(
    source_id=START_ID,
    source_key="out",
    target_id=STOP_ID,
    target_key="in",
    *,
    position=P1,
    channel=WireChannel.SINGLE,
):
    return RouteCandidate(
        PortRef(source_id, source_key),
        PortRef(target_id, target_key),
        (WireStep(position, channel),),
    )


def initial_state(*, wire_cells=None, nodes=None):
    return GraphWorkspaceState(
        stage_id=STAGE_ID,
        nodes=(
            node(START_ID, "START", 0),
            node(STOP_ID, "STOP", 2),
        )
        if nodes is None
        else tuple(nodes),
        edges=(),
        wire_cells=(straight_cell(P1),) if wire_cells is None else tuple(wire_cells),
        routes=(),
        working_revision=0,
        validated_revision=None,
        saved_version=0,
        dirty=False,
        last_validation=None,
    )


def bind(state, candidates, *, edge_ids=EDGE_IDS, route_ids=ROUTE_IDS):
    edge_factory = FixedFactory(edge_ids)
    route_factory = FixedFactory(route_ids)
    result = bind_edges(
        state,
        BindEdgesCommand(
            STAGE_ID,
            PLAYER_ID,
            state.working_revision,
            tuple(candidates),
        ),
        PORT_CATALOG,
        edge_id_factory=edge_factory,
        route_id_factory=route_factory,
    )
    return result, edge_factory, route_factory


def selection(state, *, position=P1, channel=None):
    return ConnectionSelection(
        STAGE_ID,
        PLAYER_ID,
        state.working_revision,
        position,
        channel,
    )


def cross_cell():
    return WireCell(
        PX,
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


def cross_bound_state():
    nodes = (
        node(START_ID, "START", 0),
        node(STOP_ID, "STOP", 2),
        node(IF_ID, "START", 20),
        node(SET_ID, "STOP", 22),
    )
    state = initial_state(wire_cells=(cross_cell(),), nodes=nodes)
    result, _, _ = bind(
        state,
        (
            candidate(position=PX, channel=WireChannel.NS),
            candidate(IF_ID, "out", SET_ID, "in", position=PX, channel=WireChannel.EW),
        ),
    )
    if not result.accepted:
        raise AssertionError(result.diagnostics)
    return result.state


class GraphLifecycleIntegrationTest(unittest.TestCase):
    def test_complete_start_stop_lifecycle(self):
        original = initial_state()
        original_snapshot = deepcopy(original)

        binding, _, _ = bind(original, (candidate(),))
        self.assertTrue(binding.accepted)
        bound = binding.state
        edge_id = bound.edges[0].edge_id
        route_id = bound.routes[0].route_id
        self.assertEqual((len(bound.edges), len(bound.routes)), (1, 1))
        self.assertEqual(bound.routes[0].edge_id, edge_id)
        self.assertEqual(bound.working_revision, original.working_revision + 1)
        self.assertIs(bound.save_status, SaveStatus.DIRTY)
        self.assertEqual(bound.saved_version, original.saved_version)
        self.assertEqual(original, original_snapshot)

        report = validate_graph(bound, PORT_CATALOG)
        self.assertEqual(report.revision, bound.working_revision)
        self.assertFalse(report.has_blocking_errors)
        self.assertIs(bound.graph_status, GraphStatus.DRAFT)
        validated = record_validation(bound, report, validated=True)
        self.assertIs(validated.graph_status, GraphStatus.VALID)

        saved = mark_saved(validated, validated.saved_version + 1)
        self.assertEqual(saved.working_revision, validated.working_revision)
        self.assertEqual(saved.saved_version, validated.saved_version + 1)
        self.assertIs(saved.save_status, SaveStatus.CLEAN)

        preview = preview_destruction(saved, selection(saved))
        self.assertTrue(preview.allowed)
        self.assertEqual((preview.edge_id, preview.route_id), (edge_id, route_id))
        self.assertEqual(tuple(change.after for change in preview.cell_changes), (None,))
        self.assertEqual(saved, mark_saved(validated, validated.saved_version + 1))

        cancelled = confirm_destruction(saved, preview, confirmed=False)
        self.assertFalse(cancelled.applied)
        self.assertIs(cancelled.state, saved)
        self.assertEqual((len(saved.edges), len(saved.routes)), (1, 1))

        destroyed = confirm_destruction(saved, preview, confirmed=True)
        self.assertTrue(destroyed.applied)
        self.assertEqual((destroyed.state.edges, destroyed.state.routes), ((), ()))
        self.assertEqual(destroyed.state.wire_cells, ())
        self.assertEqual(destroyed.state.working_revision, saved.working_revision + 1)
        self.assertEqual(destroyed.state.saved_version, saved.saved_version)
        self.assertIs(destroyed.state.save_status, SaveStatus.DIRTY)
        self.assertIs(destroyed.state.graph_status, GraphStatus.DRAFT)
        self.assertEqual(destroyed.state.validated_revision, saved.validated_revision)
        self.assertEqual(destroyed.state.last_validation, saved.last_validation)
        self.assertEqual((len(saved.edges), len(saved.routes)), (1, 1))

    def test_save_simulation_then_destruction_keeps_saved_version(self):
        binding, _, _ = bind(initial_state(), (candidate(),))
        report = validate_graph(binding.state, PORT_CATALOG)
        validated = record_validation(binding.state, report, validated=True)
        saved = mark_saved(validated, 1)

        preview = preview_destruction(saved, selection(saved))
        destroyed = confirm_destruction(saved, preview, confirmed=True)

        self.assertTrue(destroyed.applied)
        self.assertEqual(destroyed.state.saved_version, 1)
        self.assertEqual(destroyed.state.working_revision, saved.working_revision + 1)
        self.assertIs(destroyed.state.save_status, SaveStatus.DIRTY)
        self.assertIs(destroyed.state.indicator_status, EdgeIndicatorStatus.CHECK_REQUIRED)

    def test_cancelled_destruction_preserves_validated_saved_state(self):
        binding, _, _ = bind(initial_state(), (candidate(),))
        report = validate_graph(binding.state, PORT_CATALOG)
        saved = mark_saved(record_validation(binding.state, report, validated=True), 1)
        preview = preview_destruction(saved, selection(saved))

        cancelled = confirm_destruction(saved, preview, confirmed=False)

        self.assertIs(cancelled.state, saved)
        self.assertFalse(cancelled.applied)
        self.assertEqual(cancelled.state.edges, saved.edges)
        self.assertEqual(cancelled.state.routes, saved.routes)
        self.assertEqual(cancelled.state.working_revision, saved.working_revision)
        self.assertIs(cancelled.state.graph_status, GraphStatus.VALID)
        self.assertIs(cancelled.state.save_status, SaveStatus.CLEAN)

    def test_stale_preview_is_rejected_without_partial_change(self):
        binding, _, _ = bind(initial_state(), (candidate(),))
        bound = binding.state
        preview = preview_destruction(bound, selection(bound))
        changed = replace_content(bound, wire_cells=bound.wire_cells + (straight_cell(P2),))
        snapshot = deepcopy(changed)

        rejected = confirm_destruction(changed, preview, confirmed=True)

        self.assertFalse(rejected.applied)
        self.assertIs(rejected.state, changed)
        self.assertEqual(rejected.state, snapshot)
        self.assertIn(
            DiagnosticCode.STALE_WORKING_REVISION,
            tuple(item.code for item in rejected.diagnostics),
        )

    def test_rebind_after_destruction_uses_new_physical_route(self):
        first, _, _ = bind(initial_state(), (candidate(),))
        old_edge_id = first.state.edges[0].edge_id
        destroyed = confirm_destruction(
            first.state,
            preview_destruction(first.state, selection(first.state)),
            confirmed=True,
        ).state
        redrawn = replace_content(destroyed, wire_cells=(straight_cell(P2),))

        rebound, _, _ = bind(
            redrawn,
            (candidate(position=P2),),
            edge_ids=EDGE_IDS[1:],
            route_ids=ROUTE_IDS[1:],
        )

        self.assertTrue(rebound.accepted)
        self.assertNotEqual(rebound.state.edges[0].edge_id, old_edge_id)
        self.assertEqual(rebound.state.routes[0].steps[0].position, P2)
        self.assertEqual(rebound.state.working_revision, redrawn.working_revision + 1)
        self.assertFalse(validate_graph(rebound.state, PORT_CATALOG).has_blocking_errors)

    def test_reroute_preserves_edge_and_route_identifiers(self):
        first, _, _ = bind(initial_state(), (candidate(),))
        edge_id = first.state.edges[0].edge_id
        route_id = first.state.routes[0].route_id
        with_new_layout = replace_content(
            first.state,
            wire_cells=first.state.wire_cells + (straight_cell(P2),),
        )

        rerouted, edge_factory, route_factory = bind(
            with_new_layout,
            (candidate(position=P2),),
        )

        self.assertTrue(rerouted.accepted)
        self.assertEqual(rerouted.state.edges[0].edge_id, edge_id)
        self.assertEqual(rerouted.state.routes[0].route_id, route_id)
        self.assertEqual(rerouted.state.routes[0].steps[0].position, P2)
        self.assertEqual((edge_factory.calls, route_factory.calls), (0, 0))
        self.assertFalse(validate_graph(rerouted.state, PORT_CATALOG).has_blocking_errors)

    def test_failed_binding_can_be_followed_by_corrected_binding(self):
        state = initial_state()
        failed, _, _ = bind(state, (candidate(target_key="missing"),))
        self.assertFalse(failed.accepted)
        self.assertIs(failed.state, state)
        self.assertEqual(failed.state.working_revision, 0)

        corrected, _, _ = bind(state, (candidate(),))

        self.assertTrue(corrected.accepted)
        self.assertEqual(corrected.state.working_revision, 1)
        self.assertFalse(validate_graph(corrected.state, PORT_CATALOG).has_blocking_errors)

    def test_destroy_cross_ns_preserves_valid_ew_route(self):
        state = cross_bound_state()
        ew_route = next(route for route in state.routes if route.steps[0].channel is WireChannel.EW)
        preview = preview_destruction(
            state,
            selection(state, position=PX, channel=WireChannel.NS),
        )

        destroyed = confirm_destruction(state, preview, confirmed=True)

        self.assertTrue(destroyed.applied)
        self.assertEqual(tuple(route.route_id for route in destroyed.state.routes), (ew_route.route_id,))
        self.assertEqual(destroyed.state.routes[0].steps[0].channel, WireChannel.SINGLE)
        self.assertEqual(destroyed.state.wire_cells[0].channels[0].channel, WireChannel.SINGLE)
        codes = tuple(item.code for item in validate_graph(destroyed.state, PORT_CATALOG).diagnostics)
        self.assertNotIn(DiagnosticCode.BROKEN_ROUTE, codes)
        self.assertNotIn(DiagnosticCode.INVALID_CROSS_CHANNEL, codes)

    def test_destroy_cross_ew_preserves_valid_ns_route(self):
        state = cross_bound_state()
        ns_route = next(route for route in state.routes if route.steps[0].channel is WireChannel.NS)
        preview = preview_destruction(
            state,
            selection(state, position=PX, channel=WireChannel.EW),
        )

        destroyed = confirm_destruction(state, preview, confirmed=True)

        self.assertTrue(destroyed.applied)
        self.assertEqual(tuple(route.route_id for route in destroyed.state.routes), (ns_route.route_id,))
        self.assertEqual(destroyed.state.routes[0].steps[0].channel, WireChannel.SINGLE)
        self.assertEqual(destroyed.state.wire_cells[0].channels[0].channel, WireChannel.SINGLE)
        codes = tuple(item.code for item in validate_graph(destroyed.state, PORT_CATALOG).diagnostics)
        self.assertNotIn(DiagnosticCode.BROKEN_ROUTE, codes)
        self.assertNotIn(DiagnosticCode.INVALID_CROSS_CHANNEL, codes)

    def test_validation_report_becomes_historical_after_content_change(self):
        binding, _, _ = bind(initial_state(), (candidate(),))
        report = validate_graph(binding.state, PORT_CATALOG)
        validated = record_validation(binding.state, report, validated=True)
        self.assertIs(validated.graph_status, GraphStatus.VALID)

        changed = replace_content(
            validated,
            wire_cells=validated.wire_cells + (straight_cell(P2),),
        )

        self.assertEqual(changed.validated_revision, validated.validated_revision)
        self.assertEqual(changed.last_validation, report)
        self.assertIs(changed.graph_status, GraphStatus.DRAFT)
        self.assertIs(changed.indicator_status, EdgeIndicatorStatus.CHECK_REQUIRED)

    def test_repeated_binding_is_idempotent_across_validation_boundary(self):
        first, _, _ = bind(initial_state(), (candidate(),))
        report = validate_graph(first.state, PORT_CATALOG)
        validated = record_validation(first.state, report, validated=True)

        repeated, edge_factory, route_factory = bind(validated, (candidate(),))

        self.assertTrue(repeated.accepted)
        self.assertEqual(repeated.state, validated)
        self.assertEqual(repeated.state.working_revision, validated.working_revision)
        self.assertEqual((edge_factory.calls, route_factory.calls), (0, 0))
        self.assertIs(repeated.state.graph_status, GraphStatus.VALID)

    def test_valid_and_invalid_candidates_are_rejected_atomically(self):
        state = initial_state()
        snapshot = deepcopy(state)
        invalid_target = UUID("00000000-0000-0000-0000-000000009999")

        result, edge_factory, route_factory = bind(
            state,
            (
                candidate(),
                candidate(START_ID, "out", invalid_target, "in", position=P2),
            ),
        )

        self.assertFalse(result.accepted)
        self.assertIs(result.state, state)
        self.assertEqual(result.state, snapshot)
        self.assertEqual((result.state.edges, result.state.routes), ((), ()))
        self.assertEqual((edge_factory.calls, route_factory.calls), (0, 0))
        self.assertIn(
            DiagnosticCode.UNKNOWN_NODE,
            tuple(item.code for item in result.diagnostics),
        )
        self.assertTrue(validate_graph(result.state, PORT_CATALOG).has_blocking_errors)


if __name__ == "__main__":
    unittest.main()
