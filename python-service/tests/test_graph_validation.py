import unittest
from copy import deepcopy
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
    bound_route,
    cross_cell,
    demo_edges,
    demo_nodes,
    edge,
    full_demo_state,
    node,
    port,
    start_stop_state,
    straight_cell,
    workspace,
)
from hangar.graph.diagnostics import Diagnostic, DiagnosticCode, Severity, ValidationReport
from hangar.graph.model import (
    CardinalDirection,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GridPos,
    LocalSide,
    PortDefinition,
    PortDirection,
    PortKind,
    PortRef,
    RouteStatus,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
)
from hangar.graph.state import GraphStatus
from hangar.graph.validation import DEFERRED_RULE_GROUPS, validate_graph


def codes(report):
    return tuple(item.code for item in report.diagnostics)


class GraphValidationTest(unittest.TestCase):
    def test_empty_model_is_valid_for_generic_rules(self):
        report = validate_graph(workspace(), PORT_CATALOG)
        self.assertEqual(report.diagnostics, ())
        self.assertFalse(report.has_blocking_errors)

    def test_valid_start_to_stop_connection(self):
        report = validate_graph(start_stop_state(), PORT_CATALOG)
        self.assertEqual(report.diagnostics, ())

    def test_valid_if_has_both_outputs(self):
        report = validate_graph(full_demo_state(), PORT_CATALOG)
        violations = {
            ref.port_key
            for diagnostic in report.diagnostics
            if diagnostic.code is DiagnosticCode.PORT_CARDINALITY_VIOLATION
            for ref in diagnostic.port_refs
            if ref.instance_id == IF_ID
        }
        self.assertNotIn("true", violations)
        self.assertNotIn("false", violations)

    def test_valid_end_if_has_both_inputs(self):
        report = validate_graph(full_demo_state(), PORT_CATALOG)
        violations = {
            ref.port_key
            for diagnostic in report.diagnostics
            if diagnostic.code is DiagnosticCode.PORT_CARDINALITY_VIOLATION
            for ref in diagnostic.port_refs
            if ref.instance_id == END_IF_ID
        }
        self.assertNotIn("in_true", violations)
        self.assertNotIn("in_false", violations)

    def test_complete_demo_passes_generic_validation(self):
        report = validate_graph(full_demo_state(), PORT_CATALOG)
        self.assertEqual(report.diagnostics, ())

    def test_unknown_source_node_is_reported(self):
        connection = edge(3001, UNKNOWN_ID, "out", STOP_ID, "in")
        state = workspace(nodes=(node(STOP_ID, "STOP", 2),), edges=(connection,))
        report = validate_graph(state, PORT_CATALOG)
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, codes(report))
        self.assertIn(UNKNOWN_ID, report.diagnostics[0].node_ids)

    def test_unknown_target_node_is_reported(self):
        connection = edge(3002, START_ID, "out", UNKNOWN_ID, "in")
        state = workspace(nodes=(node(START_ID, "START", 0),), edges=(connection,))
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, codes(validate_graph(state, PORT_CATALOG)))

    def test_unknown_source_port_is_reported(self):
        connection = edge(3003, START_ID, "missing", STOP_ID, "in")
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(STOP_ID, "STOP", 2)),
            edges=(connection,),
        )
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, codes(validate_graph(state, PORT_CATALOG)))

    def test_unknown_target_port_is_reported(self):
        connection = edge(3004, START_ID, "out", STOP_ID, "missing")
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(STOP_ID, "STOP", 2)),
            edges=(connection,),
        )
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, codes(validate_graph(state, PORT_CATALOG)))

    def test_out_to_out_is_rejected(self):
        other_start = UUID("00000000-0000-0000-0000-000000000107")
        connection = edge(3005, START_ID, "out", other_start, "out")
        state = workspace(
            nodes=(node(START_ID, "START", 0), node(other_start, "START", 2)),
            edges=(connection,),
        )
        self.assertIn(
            DiagnosticCode.INVALID_PORT_DIRECTION,
            codes(validate_graph(state, PORT_CATALOG)),
        )

    def test_in_to_in_is_rejected(self):
        other_stop = UUID("00000000-0000-0000-0000-000000000108")
        connection = edge(3006, STOP_ID, "in", other_stop, "in")
        state = workspace(
            nodes=(node(STOP_ID, "STOP", 0), node(other_stop, "STOP", 2)),
            edges=(connection,),
        )
        self.assertIn(
            DiagnosticCode.INVALID_PORT_DIRECTION,
            codes(validate_graph(state, PORT_CATALOG)),
        )

    def test_incompatible_port_kind_is_rejected(self):
        incompatible = PortDefinition(
            "out",
            "DATA",  # The current enum has no DATA yet; the validator still rejects it.
            PortDirection.OUT,
            LocalSide.FRONT,
            0,
            1,
            1,
        )
        catalog = dict(PORT_CATALOG)
        catalog["START"] = (incompatible,)
        self.assertIn(
            DiagnosticCode.INCOMPATIBLE_PORT_KIND,
            codes(validate_graph(start_stop_state(), catalog)),
        )

    def test_too_many_edges_on_port_is_reported(self):
        second_start = UUID("00000000-0000-0000-0000-000000000109")
        nodes = (
            node(START_ID, "START", 0),
            node(second_start, "START", 1),
            node(STOP_ID, "STOP", 3),
        )
        edges = (
            edge(3007, START_ID, "out", STOP_ID, "in"),
            edge(3008, second_start, "out", STOP_ID, "in"),
        )
        report = validate_graph(workspace(nodes=nodes, edges=edges), PORT_CATALOG)
        self.assertIn(DiagnosticCode.PORT_CARDINALITY_VIOLATION, codes(report))

    def test_missing_required_edge_is_reported(self):
        state = workspace(nodes=(node(START_ID, "START", 0),))
        self.assertIn(
            DiagnosticCode.PORT_CARDINALITY_VIOLATION,
            codes(validate_graph(state, PORT_CATALOG)),
        )

    def test_duplicate_semantic_edge_is_reported(self):
        state = start_stop_state()
        duplicate = GraphEdge(
            UUID("00000000-0000-0000-0000-000000009999"),
            STAGE_ID,
            START_ID,
            "out",
            STOP_ID,
            "in",
            EdgeKind.CONTROL,
        )
        state = workspace(nodes=state.nodes, edges=state.edges + (duplicate,))
        report = validate_graph(state, PORT_CATALOG)
        self.assertIn(DiagnosticCode.DUPLICATE_EDGE, codes(report))

    def test_cross_stage_edge_is_reported(self):
        target = node(STOP_ID, "STOP", 2, stage_id=OTHER_STAGE_ID)
        connection = edge(3010, START_ID, "out", STOP_ID, "in")
        state = workspace(
            nodes=(node(START_ID, "START", 0), target),
            edges=(connection,),
        )
        self.assertIn(DiagnosticCode.CROSS_STAGE_EDGE, codes(validate_graph(state, PORT_CATALOG)))

    def test_unknown_block_type_is_reported(self):
        state = workspace(nodes=(node(START_ID, "MYSTERY", 0),))
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, codes(validate_graph(state, PORT_CATALOG)))

    def test_duplicate_catalog_port_key_is_reported(self):
        catalog = dict(PORT_CATALOG)
        catalog["START"] = PORT_CATALOG["START"] + PORT_CATALOG["START"]
        report = validate_graph(workspace(), catalog)
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, codes(report))

    def test_duplicate_catalog_resolution_is_order_independent(self):
        alternative = PortDefinition(
            "out",
            PortKind.CONTROL,
            PortDirection.IN,
            LocalSide.BACK,
            1,
            0,
            1,
        )
        forward_catalog = dict(PORT_CATALOG)
        reverse_catalog = dict(PORT_CATALOG)
        forward_catalog["START"] = (PORT_CATALOG["START"][0], alternative)
        reverse_catalog["START"] = tuple(reversed(forward_catalog["START"]))
        state = start_stop_state()
        self.assertEqual(
            validate_graph(state, forward_catalog),
            validate_graph(state, reverse_catalog),
        )

    def test_incomplete_route_is_open_wire(self):
        route = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000004001"),
            STAGE_ID,
            None,
            PortRef(START_ID, "out"),
            None,
            (),
            RouteStatus.INCOMPLETE,
        )
        report = validate_graph(workspace(routes=(route,)), PORT_CATALOG)
        self.assertIn(DiagnosticCode.OPEN_WIRE, codes(report))

    def test_unbound_route_marked_valid_is_still_open_wire(self):
        route = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000004003"),
            STAGE_ID,
            None,
            PortRef(START_ID, "out"),
            PortRef(STOP_ID, "in"),
            (WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),),
            RouteStatus.VALID,
        )
        state = workspace(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(DiagnosticCode.OPEN_WIRE, codes(validate_graph(state, PORT_CATALOG)))

    def test_broken_route_is_reported(self):
        route = bound_route(status=RouteStatus.BROKEN)
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, codes(validate_graph(state, PORT_CATALOG)))

    def test_ambiguous_route_is_reported(self):
        route = bound_route(status=RouteStatus.AMBIGUOUS)
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(
            DiagnosticCode.AMBIGUOUS_CONNECTION,
            codes(validate_graph(state, PORT_CATALOG)),
        )

    def test_illegal_route_is_reported(self):
        route = bound_route(status=RouteStatus.ILLEGAL)
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(DiagnosticCode.ILLEGAL_JUNCTION, codes(validate_graph(state, PORT_CATALOG)))

    def test_route_endpoint_mismatch_is_reported(self):
        route = bound_route(source_port=PortRef(IF_ID, "true"))
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, codes(validate_graph(state, PORT_CATALOG)))

    def test_cross_channels_remain_independent(self):
        ns_route = bound_route(
            steps=(WireStep(GridPos(1, 0, 0), WireChannel.NS),),
        )
        ew_route = EdgeRoute(
            UUID("00000000-0000-0000-0000-000000004002"),
            STAGE_ID,
            ns_route.edge_id,
            ns_route.source_port,
            ns_route.target_port,
            (WireStep(GridPos(1, 0, 0), WireChannel.EW),),
            RouteStatus.VALID,
        )
        state = start_stop_state(
            wire_cells=(cross_cell(),),
            routes=(ns_route, ew_route),
        )
        report = validate_graph(state, PORT_CATALOG)
        self.assertNotIn(DiagnosticCode.INVALID_CROSS_CHANNEL, codes(report))

    def test_invalid_wire_step_channel_is_reported(self):
        route = bound_route(
            steps=(WireStep(GridPos(1, 0, 0), WireChannel.NS),),
        )
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(
            DiagnosticCode.INVALID_CROSS_CHANNEL,
            codes(validate_graph(state, PORT_CATALOG)),
        )

    def test_unknown_route_edge_is_reported(self):
        route = bound_route(
            edge_id=UUID("00000000-0000-0000-0000-000000008888"),
        )
        state = start_stop_state(wire_cells=(straight_cell(),), routes=(route,))
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, codes(validate_graph(state, PORT_CATALOG)))

    def test_non_adjacent_route_steps_are_reported(self):
        steps = (
            WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),
            WireStep(GridPos(3, 0, 0), WireChannel.SINGLE),
        )
        route = bound_route(steps=steps)
        state = start_stop_state(
            wire_cells=(straight_cell(), straight_cell(GridPos(3, 0, 0))),
            routes=(route,),
        )
        self.assertIn(DiagnosticCode.BROKEN_ROUTE, codes(validate_graph(state, PORT_CATALOG)))

    def test_multiple_diagnostics_are_collected(self):
        connection = edge(3011, UNKNOWN_ID, "out", STOP_ID, "missing")
        state = workspace(nodes=(node(STOP_ID, "STOP", 2),), edges=(connection,))
        report = validate_graph(state, PORT_CATALOG)
        self.assertGreaterEqual(len(report.diagnostics), 3)
        self.assertIn(DiagnosticCode.UNKNOWN_NODE, codes(report))
        self.assertIn(DiagnosticCode.UNKNOWN_PORT, codes(report))

    def test_diagnostic_order_is_deterministic(self):
        edges = demo_edges()
        broken = workspace(nodes=demo_nodes(), edges=tuple(reversed(edges[:-2])))
        forward = validate_graph(broken, PORT_CATALOG)
        reverse_catalog = dict(reversed(tuple(PORT_CATALOG.items())))
        reverse = validate_graph(broken, reverse_catalog)
        self.assertEqual(forward, reverse)

    def test_input_state_remains_unchanged(self):
        state = full_demo_state()
        original = deepcopy(state)
        validate_graph(state, PORT_CATALOG)
        self.assertEqual(state, original)

    def test_port_catalog_remains_unchanged(self):
        snapshot = deepcopy(PORT_CATALOG)
        validate_graph(full_demo_state(), PORT_CATALOG)
        self.assertEqual(PORT_CATALOG, snapshot)

    def test_validation_does_not_change_working_revision(self):
        state = full_demo_state()
        report = validate_graph(state, PORT_CATALOG)
        self.assertEqual(state.working_revision, 7)
        self.assertEqual(report.revision, 7)

    def test_validation_does_not_record_state_status(self):
        state = full_demo_state()
        report = validate_graph(state, PORT_CATALOG)
        self.assertFalse(report.has_blocking_errors)
        self.assertIs(state.graph_status, GraphStatus.DRAFT)
        self.assertIsNone(state.last_validation)

    def test_validation_does_not_delete_graph_edges(self):
        state = full_demo_state()
        edge_ids = tuple(item.edge_id for item in state.edges)
        validate_graph(state, PORT_CATALOG)
        self.assertEqual(tuple(item.edge_id for item in state.edges), edge_ids)

    def test_validation_does_not_create_graph_edges(self):
        state = workspace(nodes=demo_nodes())
        validate_graph(state, PORT_CATALOG)
        self.assertEqual(state.edges, ())

    def test_warning_alone_does_not_block(self):
        warning = Diagnostic(DiagnosticCode.OPEN_WIRE, Severity.WARNING, "warning")
        self.assertFalse(ValidationReport(0, (warning,)).has_blocking_errors)

    def test_deferred_structural_rules_do_not_claim_full_program_validity(self):
        state = full_demo_state()
        report = validate_graph(state, PORT_CATALOG)
        self.assertFalse(report.has_blocking_errors)
        self.assertTrue(DEFERRED_RULE_GROUPS)
        self.assertIs(state.graph_status, GraphStatus.DRAFT)

    def test_invalid_cross_is_rejected_by_constructor_before_validation(self):
        with self.assertRaises(ValueError):
            WireCell(
                GridPos(1, 0, 0),
                (
                    WireChannelState(
                        WireChannel.NS,
                        frozenset({CardinalDirection.NORTH}),
                    ),
                ),
            )

    def test_junction_is_rejected_by_constructor_before_validation(self):
        with self.assertRaises(ValueError):
            WireChannelState(
                WireChannel.SINGLE,
                frozenset(
                    {
                        CardinalDirection.NORTH,
                        CardinalDirection.EAST,
                        CardinalDirection.SOUTH,
                    }
                ),
            )


if __name__ == "__main__":
    unittest.main()
