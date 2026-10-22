import unittest
from dataclasses import FrozenInstanceError
from uuid import UUID

from hangar.graph.diagnostics import Diagnostic, DiagnosticCode, Severity, ValidationReport
from hangar.graph.model import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GraphNode,
    GridPos,
    PortRef,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.state import (
    EdgeIndicatorStatus,
    GraphStatus,
    GraphWorkspaceState,
    SaveStatus,
    mark_saved,
    record_validation,
    replace_content,
)


STAGE_ID = UUID("00000000-0000-0000-0000-000000001001")
NODE_A = UUID("00000000-0000-0000-0000-000000001101")
NODE_B = UUID("00000000-0000-0000-0000-000000001102")
EDGE_A = UUID("00000000-0000-0000-0000-000000001201")
EDGE_B = UUID("00000000-0000-0000-0000-000000001202")
ROUTE_A = UUID("00000000-0000-0000-0000-000000001301")


def make_node(instance_id=NODE_A, x=0):
    return GraphNode(
        instance_id,
        STAGE_ID,
        "command_node",
        GridPos(x, 0, 0),
        CardinalDirection.NORTH,
    )


def make_edge(edge_id=EDGE_A):
    return GraphEdge(
        edge_id,
        STAGE_ID,
        NODE_A,
        "control_out",
        NODE_B,
        "control_in",
        EdgeKind.CONTROL,
    )


def make_wire(x=1):
    channel = WireChannelState(
        WireChannel.SINGLE,
        frozenset({CardinalDirection.EAST, CardinalDirection.WEST}),
    )
    return WireCell(GridPos(x, 0, 0), (channel,))


def make_route(status=RouteStatus.VALID):
    return EdgeRoute(
        ROUTE_A,
        STAGE_ID,
        EDGE_A,
        PortRef(NODE_A, "control_out"),
        PortRef(NODE_B, "control_in"),
        (WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),),
        status,
    )


def make_state(**overrides):
    values = {
        "stage_id": STAGE_ID,
        "nodes": (make_node(),),
        "edges": (),
        "wire_cells": (),
        "routes": (),
        "working_revision": 0,
        "validated_revision": None,
        "saved_version": 0,
        "dirty": False,
        "last_validation": None,
    }
    values.update(overrides)
    return GraphWorkspaceState(**values)


def error_report(revision):
    diagnostic = Diagnostic(
        DiagnosticCode.BROKEN_ROUTE,
        Severity.ERROR,
        "Broken route",
        edge_ids=(EDGE_A,),
    )
    return ValidationReport(revision, (diagnostic,))


def valid_state(dirty=True):
    state = make_state(working_revision=2, dirty=dirty)
    return record_validation(state, ValidationReport(2, ()), validated=True)


class GraphWorkspaceStateTest(unittest.TestCase):
    def test_state_is_frozen(self):
        state = make_state()
        with self.assertRaises(FrozenInstanceError):
            state.dirty = True

    def test_new_unvalidated_stage_is_draft(self):
        self.assertIs(make_state().graph_status, GraphStatus.DRAFT)

    def test_new_unchecked_stage_requires_check(self):
        self.assertIs(
            make_state().indicator_status,
            EdgeIndicatorStatus.CHECK_REQUIRED,
        )

    def test_dirty_state_has_dirty_save_status(self):
        self.assertIs(make_state(dirty=True).save_status, SaveStatus.DIRTY)

    def test_clean_state_has_clean_save_status(self):
        self.assertIs(make_state(dirty=False).save_status, SaveStatus.CLEAN)

    def test_current_successful_validation_is_valid(self):
        self.assertIs(valid_state().graph_status, GraphStatus.VALID)

    def test_current_successful_validation_sets_ok_indicator(self):
        self.assertIs(valid_state().indicator_status, EdgeIndicatorStatus.OK)

    def test_current_error_is_draft_with_error_indicator(self):
        state = make_state(working_revision=3)
        failed = record_validation(state, error_report(3), validated=False)
        self.assertIs(failed.graph_status, GraphStatus.DRAFT)
        self.assertIs(failed.indicator_status, EdgeIndicatorStatus.ERRORS)

    def test_warning_and_info_allow_valid_state(self):
        report = ValidationReport(
            0,
            (
                Diagnostic(DiagnosticCode.OPEN_WIRE, Severity.WARNING, "warning"),
                Diagnostic(DiagnosticCode.UNREACHABLE_NODE, Severity.INFO, "info"),
            ),
        )
        state = record_validation(make_state(), report, validated=True)
        self.assertIs(state.graph_status, GraphStatus.VALID)

    def test_old_successful_validation_is_draft(self):
        old_report = ValidationReport(1, ())
        state = make_state(
            working_revision=2,
            validated_revision=1,
            last_validation=old_report,
        )
        self.assertIs(state.graph_status, GraphStatus.DRAFT)

    def test_old_report_requires_check(self):
        state = make_state(working_revision=2, last_validation=ValidationReport(1, ()))
        self.assertIs(state.indicator_status, EdgeIndicatorStatus.CHECK_REQUIRED)

    def test_wire_change_invalidates_valid_state(self):
        changed = replace_content(valid_state(), wire_cells=(make_wire(),))
        self.assertIs(changed.graph_status, GraphStatus.DRAFT)
        self.assertIs(changed.indicator_status, EdgeIndicatorStatus.CHECK_REQUIRED)

    def test_edge_change_invalidates_valid_state(self):
        changed = replace_content(valid_state(), edges=(make_edge(),))
        self.assertIs(changed.graph_status, GraphStatus.DRAFT)

    def test_node_change_invalidates_valid_state(self):
        changed = replace_content(valid_state(), nodes=(make_node(), make_node(NODE_B, 2)))
        self.assertIs(changed.graph_status, GraphStatus.DRAFT)

    def test_route_change_invalidates_valid_state(self):
        changed = replace_content(valid_state(), routes=(make_route(),))
        self.assertIs(changed.graph_status, GraphStatus.DRAFT)

    def test_content_change_increments_revision_exactly_once(self):
        state = make_state(working_revision=7)
        changed = replace_content(state, edges=(make_edge(),))
        self.assertEqual(changed.working_revision, 8)
        self.assertTrue(changed.dirty)

    def test_identical_content_does_not_increment_revision(self):
        state = make_state()
        unchanged = replace_content(state, nodes=state.nodes)
        self.assertEqual(unchanged.working_revision, state.working_revision)
        self.assertEqual(unchanged, state)
        self.assertIsNot(unchanged, state)

    def test_edge_enumeration_order_does_not_change_content(self):
        first = make_edge(EDGE_A)
        second = make_edge(EDGE_B)
        state = make_state(edges=(first, second))
        reordered = replace_content(state, edges=(second, first))
        self.assertEqual(reordered.working_revision, state.working_revision)
        self.assertEqual(reordered, state)
        self.assertIsNot(reordered, state)

    def test_multiple_collection_changes_increment_only_once(self):
        state = make_state(working_revision=4)
        changed = replace_content(
            state,
            nodes=(make_node(), make_node(NODE_B, 2)),
            edges=(make_edge(),),
            wire_cells=(make_wire(),),
            routes=(make_route(),),
        )
        self.assertEqual(changed.working_revision, 5)

    def test_failed_validation_preserves_all_content(self):
        state = make_state(
            edges=(make_edge(),),
            wire_cells=(make_wire(),),
            routes=(make_route(RouteStatus.BROKEN),),
            working_revision=3,
            dirty=True,
        )
        failed = record_validation(state, error_report(3), validated=False)
        self.assertEqual(
            (failed.nodes, failed.edges, failed.wire_cells, failed.routes),
            (state.nodes, state.edges, state.wire_cells, state.routes),
        )
        self.assertEqual(failed.working_revision, state.working_revision)
        self.assertEqual(failed.saved_version, state.saved_version)
        self.assertEqual(failed.dirty, state.dirty)

    def test_stale_report_is_rejected(self):
        with self.assertRaises(ValueError):
            record_validation(
                make_state(working_revision=2),
                ValidationReport(1, ()),
                validated=True,
            )

    def test_future_report_is_rejected(self):
        with self.assertRaises(ValueError):
            record_validation(
                make_state(working_revision=2),
                ValidationReport(3, ()),
                validated=True,
            )

    def test_success_flag_with_error_report_is_rejected(self):
        with self.assertRaises(ValueError):
            record_validation(make_state(), error_report(0), validated=True)

    def test_failure_flag_without_error_is_rejected(self):
        with self.assertRaises(ValueError):
            record_validation(make_state(), ValidationReport(0, ()), validated=False)

    def test_non_boolean_validation_flag_is_rejected(self):
        with self.assertRaises(TypeError):
            record_validation(make_state(), ValidationReport(0, ()), validated=1)

    def test_draft_can_be_saved(self):
        saved = mark_saved(make_state(dirty=True), 1)
        self.assertIs(saved.graph_status, GraphStatus.DRAFT)
        self.assertIs(saved.save_status, SaveStatus.CLEAN)

    def test_save_does_not_increment_working_revision(self):
        state = make_state(working_revision=8, dirty=True)
        self.assertEqual(mark_saved(state, 1).working_revision, 8)

    def test_save_increments_saved_version(self):
        state = make_state(saved_version=4, dirty=True)
        self.assertEqual(mark_saved(state, 5).saved_version, 5)

    def test_save_does_not_make_draft_valid(self):
        saved = mark_saved(make_state(dirty=True), 1)
        self.assertIs(saved.graph_status, GraphStatus.DRAFT)

    def test_valid_save_remains_valid_and_becomes_clean(self):
        state = valid_state(dirty=True)
        saved = mark_saved(state, 1)
        self.assertIs(saved.graph_status, GraphStatus.VALID)
        self.assertIs(saved.save_status, SaveStatus.CLEAN)
        self.assertEqual(saved.validated_revision, state.validated_revision)
        self.assertEqual(saved.last_validation, state.last_validation)

    def test_invalid_saved_version_step_is_rejected(self):
        state = make_state(saved_version=4, dirty=True)
        for invalid_version in (3, 4, 6):
            with self.subTest(invalid_version=invalid_version):
                with self.assertRaises(ValueError):
                    mark_saved(state, invalid_version)

    def test_non_integer_saved_version_is_rejected(self):
        with self.assertRaises(TypeError):
            mark_saved(make_state(), 1.0)

    def test_original_state_is_unchanged_after_content_transition(self):
        state = valid_state()
        original = state
        changed = replace_content(state, edges=(make_edge(),))
        self.assertEqual(state, original)
        self.assertIsNot(changed, state)

    def test_original_state_is_unchanged_after_validation_transition(self):
        state = make_state()
        original = state
        validated = record_validation(state, ValidationReport(0, ()), validated=True)
        self.assertEqual(state, original)
        self.assertIsNone(state.last_validation)
        self.assertIsNot(validated, state)

    def test_original_state_is_unchanged_after_save_transition(self):
        state = make_state(dirty=True)
        original = state
        saved = mark_saved(state, 1)
        self.assertEqual(state, original)
        self.assertTrue(state.dirty)
        self.assertIsNot(saved, state)

    def test_content_collections_must_be_tuples(self):
        with self.assertRaises(TypeError):
            make_state(nodes=[make_node()])

    def test_negative_revisions_are_rejected(self):
        with self.assertRaises(ValueError):
            make_state(working_revision=-1)
        with self.assertRaises(ValueError):
            make_state(saved_version=-1)
        with self.assertRaises(ValueError):
            make_state(validated_revision=-1)

    def test_future_validated_revision_is_rejected(self):
        with self.assertRaises(ValueError):
            make_state(working_revision=1, validated_revision=2)

    def test_future_historical_report_is_rejected(self):
        with self.assertRaises(ValueError):
            make_state(working_revision=1, last_validation=ValidationReport(2, ()))


if __name__ == "__main__":
    unittest.main()
