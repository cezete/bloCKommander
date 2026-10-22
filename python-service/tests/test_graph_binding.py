import unittest
from copy import deepcopy
from dataclasses import FrozenInstanceError
from uuid import UUID

from graph_validation_fixtures import (
    END_IF_ID,
    IF_ID,
    OTHER_STAGE_ID,
    PORT_CATALOG,
    PRINT_ID,
    SET_ID,
    STAGE_ID,
    START_ID,
    STOP_ID,
    UNKNOWN_ID,
    edge,
    node,
    straight_cell,
    workspace,
)
from hangar.graph.binding import BindEdgesCommand, RouteCandidate, bind_edges
from hangar.graph.diagnostics import DiagnosticCode, ValidationReport
from hangar.graph.model import (
    CardinalDirection,
    EdgeRoute,
    GridPos,
    PortRef,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.state import GraphStatus, GraphWorkspaceState, SaveStatus
from hangar.graph.validation import validate_graph


PLAYER_ID = UUID("00000000-0000-0000-0000-000000009001")
EDGE_IDS = tuple(UUID(f"00000000-0000-0000-0000-{value:012d}") for value in range(9101, 9120))
ROUTE_IDS = tuple(UUID(f"00000000-0000-0000-0000-{value:012d}") for value in range(9201, 9220))


class FixedFactory:
    def __init__(self, values):
        self.values = iter(values)
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return next(self.values)


def candidate(
    source_id=START_ID,
    source_key="out",
    target_id=STOP_ID,
    target_key="in",
    *,
    position=GridPos(1, 0, 0),
    channel=WireChannel.SINGLE,
    route_id=None,
    steps=None,
):
    return RouteCandidate(
        PortRef(source_id, source_key),
        PortRef(target_id, target_key),
        (WireStep(position, channel),) if steps is None else steps,
        route_id,
    )


def new_binding_state(*, revision=4, dirty=False, saved_version=6):
    return GraphWorkspaceState(
        stage_id=STAGE_ID,
        nodes=(node(START_ID, "START", 0), node(STOP_ID, "STOP", 2)),
        edges=(),
        wire_cells=(straight_cell(),),
        routes=(),
        working_revision=revision,
        validated_revision=None,
        saved_version=saved_version,
        dirty=dirty,
        last_validation=None,
    )


def command(state, candidates, *, revision=None, stage_id=STAGE_ID):
    return BindEdgesCommand(
        stage_id,
        PLAYER_ID,
        state.working_revision if revision is None else revision,
        tuple(candidates),
    )


def execute(state, candidates, *, edge_ids=EDGE_IDS, route_ids=ROUTE_IDS, **command_args):
    edge_factory = FixedFactory(edge_ids)
    route_factory = FixedFactory(route_ids)
    result = bind_edges(
        state,
        command(state, candidates, **command_args),
        PORT_CATALOG,
        edge_id_factory=edge_factory,
        route_id_factory=route_factory,
    )
    return result, edge_factory, route_factory


class GraphBindingTest(unittest.TestCase):
    def test_binding_models_are_immutable(self):
        item = candidate()
        with self.assertRaises(FrozenInstanceError):
            item.route_id = ROUTE_IDS[0]
        cmd = command(new_binding_state(), (item,))
        with self.assertRaises(FrozenInstanceError):
            cmd.expected_working_revision = 99

    def test_bind_one_new_valid_connection(self):
        result, _, _ = execute(new_binding_state(), (candidate(),))
        self.assertTrue(result.accepted)
        self.assertEqual(len(result.state.edges), 1)
        self.assertEqual(len(result.state.routes), 1)
        self.assertEqual(result.created_edge_ids, (EDGE_IDS[0],))

    def test_bind_multiple_valid_connections_atomically(self):
        nodes = (
            node(START_ID, "START", 0),
            node(IF_ID, "IF", 2),
            node(SET_ID, "SET", 4),
            node(PRINT_ID, "PRINT", 6),
            node(END_IF_ID, "END_IF", 8),
            node(STOP_ID, "STOP", 10),
        )
        specs = (
            (START_ID, "out", IF_ID, "in"),
            (IF_ID, "true", SET_ID, "in"),
            (SET_ID, "out", END_IF_ID, "in_true"),
            (IF_ID, "false", PRINT_ID, "in"),
            (PRINT_ID, "out", END_IF_ID, "in_false"),
            (END_IF_ID, "out", STOP_ID, "in"),
        )
        cells = tuple(straight_cell(GridPos(index + 20, 0, 0)) for index in range(6))
        candidates = tuple(
            candidate(*spec, position=GridPos(index + 20, 0, 0))
            for index, spec in enumerate(specs)
        )
        state = workspace(nodes=nodes, wire_cells=cells, revision=3)
        result, edge_factory, route_factory = execute(state, candidates)
        self.assertTrue(result.accepted)
        self.assertEqual(len(result.state.edges), 6)
        self.assertEqual(len(result.state.routes), 6)
        self.assertEqual(edge_factory.calls, 6)
        self.assertEqual(route_factory.calls, 6)

    def test_existing_graph_edge_is_reused(self):
        existing = edge(1101, START_ID, "out", STOP_ID, "in")
        state = GraphWorkspaceState(
            STAGE_ID,
            new_binding_state().nodes,
            (existing,),
            (straight_cell(),),
            (),
            4,
            None,
            6,
            False,
            None,
        )
        result, edge_factory, _ = execute(state, (candidate(),))
        self.assertTrue(result.accepted)
        self.assertEqual(result.reused_edge_ids, (existing.edge_id,))
        self.assertEqual(result.state.edges, (existing,))
        self.assertEqual(edge_factory.calls, 0)

    def test_edge_id_is_preserved_during_reroute(self):
        state, existing_edge, _ = self._routed_state()
        rerouted = candidate(position=GridPos(2, 0, 0))
        state = GraphWorkspaceState(
            state.stage_id,
            state.nodes,
            state.edges,
            state.wire_cells + (straight_cell(GridPos(2, 0, 0)),),
            state.routes,
            state.working_revision,
            None,
            state.saved_version,
            False,
            None,
        )
        result, _, _ = execute(state, (rerouted,))
        self.assertEqual(result.state.edges[0].edge_id, existing_edge.edge_id)

    def test_route_id_is_preserved_during_same_route_update(self):
        state, _, old_route = self._routed_state()
        result, _, route_factory = execute(state, (candidate(),))
        self.assertTrue(result.accepted)
        self.assertEqual(result.state.routes[0].route_id, old_route.route_id)
        self.assertEqual(route_factory.calls, 0)

    def test_explicit_route_id_is_preserved(self):
        route_id = UUID("00000000-0000-0000-0000-000000009300")
        result, _, route_factory = execute(
            new_binding_state(),
            (candidate(route_id=route_id),),
        )
        self.assertTrue(result.accepted)
        self.assertEqual(result.state.routes[0].route_id, route_id)
        self.assertEqual(route_factory.calls, 0)

    def test_new_route_id_is_allocated_for_new_route(self):
        result, _, route_factory = execute(new_binding_state(), (candidate(),))
        self.assertEqual(result.state.routes[0].route_id, ROUTE_IDS[0])
        self.assertEqual(route_factory.calls, 1)

    def test_repeated_binding_is_idempotent(self):
        first, _, _ = execute(new_binding_state(), (candidate(),))
        second, edge_factory, route_factory = execute(first.state, (candidate(),))
        self.assertTrue(second.accepted)
        self.assertEqual(second.state, first.state)
        self.assertEqual(second.state.edges, first.state.edges)
        self.assertEqual(second.state.routes, first.state.routes)
        self.assertEqual(edge_factory.calls, 0)
        self.assertEqual(route_factory.calls, 0)

    def test_repeated_binding_does_not_increment_revision(self):
        first, _, _ = execute(new_binding_state(), (candidate(),))
        second, _, _ = execute(first.state, (candidate(),))
        self.assertEqual(second.state.working_revision, first.state.working_revision)

    def test_old_original_command_is_rejected_after_success(self):
        state = new_binding_state()
        stale_command = command(state, (candidate(),))
        first, _, _ = execute(state, (candidate(),))
        result = bind_edges(
            first.state,
            stale_command,
            PORT_CATALOG,
            edge_id_factory=FixedFactory(EDGE_IDS),
            route_id_factory=FixedFactory(ROUTE_IDS),
        )
        self.assertFalse(result.accepted)
        self.assertIn(DiagnosticCode.STALE_WORKING_REVISION, self._codes(result))

    def test_wrong_stage_id_is_rejected(self):
        state = new_binding_state()
        result, _, _ = execute(state, (candidate(),), stage_id=OTHER_STAGE_ID)
        self.assertFalse(result.accepted)
        self.assertIn(DiagnosticCode.CROSS_STAGE_EDGE, self._codes(result))
        self.assertIs(result.state, state)

    def test_wrong_working_revision_is_rejected(self):
        state = new_binding_state()
        result, _, _ = execute(state, (candidate(),), revision=3)
        self.assertFalse(result.accepted)
        self.assertIn(DiagnosticCode.STALE_WORKING_REVISION, self._codes(result))

    def test_unknown_source_node_is_rejected(self):
        result, _, _ = execute(new_binding_state(), (candidate(source_id=UNKNOWN_ID),))
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, self._codes(result))

    def test_unknown_target_node_is_rejected(self):
        result, _, _ = execute(new_binding_state(), (candidate(target_id=UNKNOWN_ID),))
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, self._codes(result))

    def test_unknown_source_port_is_rejected(self):
        result, _, _ = execute(new_binding_state(), (candidate(source_key="missing"),))
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, self._codes(result))

    def test_unknown_target_port_is_rejected(self):
        result, _, _ = execute(new_binding_state(), (candidate(target_key="missing"),))
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, self._codes(result))

    def test_out_to_out_is_rejected(self):
        other = UUID("00000000-0000-0000-0000-000000000107")
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(other, "START", 2)),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        result, _, _ = execute(state, (candidate(target_id=other, target_key="out"),))
        self.assertIn(DiagnosticCode.INVALID_PORT_DIRECTION, self._codes(result))

    def test_in_to_in_is_rejected(self):
        other = UUID("00000000-0000-0000-0000-000000000108")
        state = workspace(
            nodes=(node(STOP_ID, "STOP", 0), node(other, "STOP", 2)),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        item = candidate(STOP_ID, "in", other, "in")
        result, _, _ = execute(state, (item,))
        self.assertIn(DiagnosticCode.INVALID_PORT_DIRECTION, self._codes(result))

    def test_max_edges_overflow_is_rejected(self):
        existing = edge(1101, START_ID, "out", STOP_ID, "in")
        state = workspace(
            nodes=(
                node(START_ID, "START", 0),
                node(IF_ID, "IF", 2),
                node(STOP_ID, "STOP", 4),
            ),
            edges=(existing,),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        result, _, _ = execute(state, (candidate(target_id=IF_ID, target_key="in"),))
        self.assertIn(DiagnosticCode.PORT_CARDINALITY_VIOLATION, self._codes(result))

    def test_duplicate_candidate_is_rejected(self):
        item = candidate()
        result, _, _ = execute(new_binding_state(), (item, item))
        self.assertIn(DiagnosticCode.DUPLICATE_EDGE, self._codes(result))

    def test_two_candidates_on_exclusive_port_are_rejected(self):
        other_stop = UUID("00000000-0000-0000-0000-000000000109")
        state = workspace(
            nodes=(
                node(START_ID, "START", 0),
                node(STOP_ID, "STOP", 2),
                node(other_stop, "STOP", 4),
            ),
            wire_cells=(straight_cell(), straight_cell(GridPos(3, 0, 0))),
            revision=4,
        )
        items = (
            candidate(),
            candidate(target_id=other_stop, position=GridPos(3, 0, 0)),
        )
        result, _, _ = execute(state, items)
        self.assertIn(DiagnosticCode.PORT_CARDINALITY_VIOLATION, self._codes(result))

    def test_non_adjacent_physical_route_is_rejected(self):
        steps = (
            WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),
            WireStep(GridPos(3, 0, 0), WireChannel.SINGLE),
        )
        state = GraphWorkspaceState(
            STAGE_ID,
            new_binding_state().nodes,
            (),
            (straight_cell(), straight_cell(GridPos(3, 0, 0))),
            (),
            4,
            None,
            6,
            False,
            None,
        )
        result, _, _ = execute(state, (candidate(steps=steps),))
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, self._codes(result))

    def test_missing_wire_cell_is_rejected(self):
        state = GraphWorkspaceState(
            STAGE_ID,
            new_binding_state().nodes,
            (),
            (),
            (),
            4,
            None,
            6,
            False,
            None,
        )
        result, _, _ = execute(state, (candidate(),))
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, self._codes(result))

    def test_invalid_cross_channel_is_rejected(self):
        result, _, _ = execute(
            new_binding_state(),
            (candidate(channel=WireChannel.NS),),
        )
        self.assertIn(DiagnosticCode.INVALID_CROSS_CHANNEL, self._codes(result))

    def test_one_valid_and_one_invalid_candidate_rejects_all(self):
        result, _, _ = execute(
            new_binding_state(),
            (candidate(), candidate(target_key="missing")),
        )
        self.assertFalse(result.accepted)
        self.assertEqual(result.state.edges, ())
        self.assertEqual(result.state.routes, ())

    def test_late_route_failure_after_id_allocation_is_still_atomic(self):
        state = workspace(
            nodes=(
                node(START_ID, "START", 0),
                node(IF_ID, "IF", 2),
                node(SET_ID, "SET", 4),
            ),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        items = (
            candidate(START_ID, "out", IF_ID, "in"),
            candidate(
                IF_ID,
                "true",
                SET_ID,
                "in",
                position=GridPos(3, 0, 0),
            ),
        )
        result, edge_factory, route_factory = execute(state, items)
        self.assertFalse(result.accepted)
        self.assertGreater(edge_factory.calls, 0)
        self.assertGreater(route_factory.calls, 0)
        self.assertIs(result.state, state)
        self.assertEqual(result.state.edges, ())
        self.assertEqual(result.state.routes, ())

    def test_rejection_returns_original_state_identity(self):
        state = new_binding_state()
        result, _, _ = execute(state, (candidate(target_key="missing"),))
        self.assertIs(result.state, state)

    def test_rejection_does_not_modify_existing_route(self):
        state, _, route = self._routed_state()
        result, _, _ = execute(state, (candidate(target_key="missing"),))
        self.assertEqual(result.state.routes, (route,))

    def test_rejection_does_not_increment_revision(self):
        state = new_binding_state()
        result, _, _ = execute(state, (candidate(target_key="missing"),))
        self.assertEqual(result.state.working_revision, state.working_revision)

    def test_rejection_does_not_change_dirty_flag(self):
        state = new_binding_state(dirty=False)
        result, _, _ = execute(state, (candidate(target_key="missing"),))
        self.assertFalse(result.state.dirty)

    def test_rejection_preserves_saved_and_validated_versions(self):
        base = new_binding_state(revision=4, dirty=False, saved_version=11)
        state = GraphWorkspaceState(
            base.stage_id,
            base.nodes,
            base.edges,
            base.wire_cells,
            base.routes,
            4,
            3,
            11,
            False,
            ValidationReport(3, ()),
        )
        result, _, _ = execute(state, (candidate(target_key="missing"),))
        self.assertEqual(result.state.saved_version, 11)
        self.assertEqual(result.state.validated_revision, 3)
        self.assertEqual(result.state.last_validation, state.last_validation)

    def test_successful_change_increments_revision_once(self):
        state = new_binding_state(revision=8)
        result, _, _ = execute(state, (candidate(),))
        self.assertEqual(result.state.working_revision, 9)

    def test_successful_change_marks_state_dirty(self):
        result, _, _ = execute(new_binding_state(dirty=False), (candidate(),))
        self.assertIs(result.state.save_status, SaveStatus.DIRTY)

    def test_success_does_not_change_saved_version(self):
        state = new_binding_state(saved_version=6)
        result, _, _ = execute(state, (candidate(),))
        self.assertEqual(result.state.saved_version, 6)

    def test_success_does_not_automatically_mark_stage_valid(self):
        result, _, _ = execute(new_binding_state(), (candidate(),))
        self.assertIs(result.state.graph_status, GraphStatus.DRAFT)
        self.assertIsNone(result.state.last_validation)

    def test_unrelated_missing_required_ports_do_not_block_partial_binding(self):
        state = workspace(
            nodes=(
                node(START_ID, "START", 0),
                node(IF_ID, "IF", 2),
                node(SET_ID, "SET", 4),
            ),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        item = candidate(START_ID, "out", IF_ID, "in")
        result, _, _ = execute(state, (item,))
        self.assertTrue(result.accepted)

    def test_unrelated_broken_route_does_not_block_valid_binding(self):
        old_edge = edge(5001, IF_ID, "true", SET_ID, "in")
        broken_route = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000005001"),
            STAGE_ID,
            old_edge.edge_id,
            PortRef(IF_ID, "true"),
            PortRef(SET_ID, "in"),
            (WireStep(GridPos(9, 0, 0), WireChannel.SINGLE),),
            RouteStatus.BROKEN,
        )
        state = workspace(
            nodes=(
                node(START_ID, "START", 0),
                node(IF_ID, "IF", 2),
                node(SET_ID, "SET", 4),
            ),
            edges=(old_edge,),
            wire_cells=(straight_cell(), straight_cell(GridPos(9, 0, 0))),
            routes=(broken_route,),
            revision=4,
        )
        result, _, _ = execute(state, (candidate(START_ID, "out", IF_ID, "in"),))
        self.assertTrue(result.accepted)
        self.assertIn(old_edge, result.state.edges)
        self.assertIn(broken_route, result.state.routes)

    def test_full_validation_still_reports_partial_stage_gaps(self):
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(IF_ID, "IF", 2)),
            wire_cells=(straight_cell(),),
            revision=4,
        )
        result, _, _ = execute(
            state,
            (candidate(START_ID, "out", IF_ID, "in"),),
        )
        report = validate_graph(result.state, PORT_CATALOG)
        self.assertTrue(report.has_blocking_errors)
        self.assertIn(DiagnosticCode.PORT_CARDINALITY_VIOLATION, tuple(d.code for d in report.diagnostics))

    def test_fixed_uuid_factories_are_used_deterministically(self):
        result, edge_factory, route_factory = execute(new_binding_state(), (candidate(),))
        self.assertEqual(result.created_edge_ids, (EDGE_IDS[0],))
        self.assertEqual(result.state.routes[0].route_id, ROUTE_IDS[0])
        self.assertEqual((edge_factory.calls, route_factory.calls), (1, 1))

    def test_candidate_input_order_does_not_change_semantic_id_assignment(self):
        nodes = (
            node(START_ID, "START", 0),
            node(IF_ID, "IF", 2),
            node(SET_ID, "SET", 4),
        )
        cells = (straight_cell(), straight_cell(GridPos(3, 0, 0)))
        state = workspace(nodes=nodes, wire_cells=cells, revision=4)
        first = candidate(START_ID, "out", IF_ID, "in")
        second = candidate(
            IF_ID,
            "true",
            SET_ID,
            "in",
            position=GridPos(3, 0, 0),
        )
        forward, _, _ = execute(state, (first, second))
        reverse, _, _ = execute(state, (second, first))
        self.assertTrue(forward.accepted)
        self.assertEqual(forward.state.edges, reverse.state.edges)
        self.assertEqual(forward.state.routes, reverse.state.routes)

    def test_original_state_remains_unchanged_after_success(self):
        state = new_binding_state()
        original = deepcopy(state)
        result, _, _ = execute(state, (candidate(),))
        self.assertTrue(result.accepted)
        self.assertEqual(state, original)
        self.assertEqual(state.edges, ())

    def test_port_catalog_remains_unchanged(self):
        snapshot = deepcopy(PORT_CATALOG)
        execute(new_binding_state(), (candidate(),))
        self.assertEqual(PORT_CATALOG, snapshot)

    def test_rejection_diagnostics_are_deterministic(self):
        first = candidate(target_key="missing")
        second = candidate(source_key="missing")
        state = new_binding_state()
        result_a, _, _ = execute(state, (first, second))
        result_b, _, _ = execute(state, (second, first))
        self.assertEqual(result_a.diagnostics, result_b.diagnostics)

    def test_empty_binding_is_idempotently_accepted(self):
        state = new_binding_state()
        result, edge_factory, route_factory = execute(state, ())
        self.assertTrue(result.accepted)
        self.assertEqual(result.state, state)
        self.assertEqual(result.state.working_revision, state.working_revision)
        self.assertEqual((edge_factory.calls, route_factory.calls), (0, 0))

    @staticmethod
    def _codes(result):
        return tuple(item.code for item in result.diagnostics)

    @staticmethod
    def _routed_state():
        existing_edge = edge(1101, START_ID, "out", STOP_ID, "in")
        route = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000006001"),
            STAGE_ID,
            existing_edge.edge_id,
            PortRef(START_ID, "out"),
            PortRef(STOP_ID, "in"),
            (WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),),
            RouteStatus.VALID,
        )
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(STOP_ID, "STOP", 2)),
            edges=(existing_edge,),
            wire_cells=(straight_cell(),),
            routes=(route,),
            revision=4,
        )
        return state, existing_edge, route


if __name__ == "__main__":
    unittest.main()
