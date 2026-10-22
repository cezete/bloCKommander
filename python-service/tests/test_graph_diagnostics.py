import unittest
from dataclasses import FrozenInstanceError
from uuid import UUID

from hangar.graph.diagnostics import (
    Diagnostic,
    DiagnosticCode,
    Severity,
    ValidationReport,
    sort_diagnostics,
)
from hangar.graph.model import GridPos, PortRef


NODE_A = UUID("00000000-0000-0000-0000-000000000101")
NODE_B = UUID("00000000-0000-0000-0000-000000000102")
EDGE_A = UUID("00000000-0000-0000-0000-000000000201")
EDGE_B = UUID("00000000-0000-0000-0000-000000000202")


class GraphDiagnosticsTest(unittest.TestCase):
    def test_all_required_diagnostic_codes_exist_in_contract_order(self):
        self.assertEqual(
            [code.name for code in DiagnosticCode],
            [
                "UNKNOWN_NODE",
                "UNKNOWN_PORT",
                "INVALID_PORT_DIRECTION",
                "INCOMPATIBLE_PORT_KIND",
                "PORT_CARDINALITY_VIOLATION",
                "CROSS_STAGE_EDGE",
                "OPEN_WIRE",
                "AMBIGUOUS_CONNECTION",
                "BROKEN_ROUTE",
                "ILLEGAL_JUNCTION",
                "INVALID_CROSS_CHANNEL",
                "MISSING_IF_BRANCH",
                "INVALID_IF_MERGE",
                "UNREACHABLE_NODE",
                "ILLEGAL_CYCLE",
                "DUPLICATE_EDGE",
                "STALE_WORKING_REVISION",
            ],
        )

    def test_severity_order_is_error_warning_info(self):
        self.assertEqual(
            [(severity.name, severity.value) for severity in Severity],
            [("ERROR", 0), ("WARNING", 1), ("INFO", 2)],
        )

    def test_diagnostic_is_frozen(self):
        diagnostic = self._diagnostic(DiagnosticCode.UNKNOWN_NODE)
        with self.assertRaises(FrozenInstanceError):
            diagnostic.message = "changed"

    def test_validation_report_is_frozen(self):
        report = ValidationReport(0, ())
        with self.assertRaises(FrozenInstanceError):
            report.revision = 1

    def test_error_is_blocking(self):
        report = ValidationReport(0, (self._diagnostic(DiagnosticCode.UNKNOWN_NODE),))
        self.assertTrue(report.has_blocking_errors)

    def test_warning_is_not_blocking(self):
        report = ValidationReport(
            0,
            (self._diagnostic(DiagnosticCode.OPEN_WIRE, Severity.WARNING),),
        )
        self.assertFalse(report.has_blocking_errors)

    def test_info_is_not_blocking(self):
        report = ValidationReport(
            0,
            (self._diagnostic(DiagnosticCode.OPEN_WIRE, Severity.INFO),),
        )
        self.assertFalse(report.has_blocking_errors)

    def test_one_error_blocks_among_non_blocking_diagnostics(self):
        report = ValidationReport(
            0,
            (
                self._diagnostic(DiagnosticCode.OPEN_WIRE, Severity.WARNING),
                self._diagnostic(DiagnosticCode.BROKEN_ROUTE, Severity.ERROR),
                self._diagnostic(DiagnosticCode.UNREACHABLE_NODE, Severity.INFO),
            ),
        )
        self.assertTrue(report.has_blocking_errors)

    def test_sort_is_independent_of_input_order(self):
        items = (
            self._diagnostic(DiagnosticCode.BROKEN_ROUTE, Severity.INFO),
            self._diagnostic(DiagnosticCode.UNKNOWN_PORT, Severity.WARNING),
            self._diagnostic(DiagnosticCode.UNKNOWN_NODE, Severity.ERROR),
        )
        self.assertEqual(sort_diagnostics(items), sort_diagnostics(reversed(items)))

    def test_sort_uses_severity_then_code_order(self):
        warning = self._diagnostic(DiagnosticCode.UNKNOWN_NODE, Severity.WARNING)
        later_error = self._diagnostic(DiagnosticCode.BROKEN_ROUTE, Severity.ERROR)
        earlier_error = self._diagnostic(DiagnosticCode.UNKNOWN_PORT, Severity.ERROR)
        self.assertEqual(
            sort_diagnostics((warning, later_error, earlier_error)),
            (earlier_error, later_error, warning),
        )

    def test_same_code_sorts_by_node_uuid(self):
        second = self._diagnostic(DiagnosticCode.UNKNOWN_NODE, node_ids=(NODE_B,))
        first = self._diagnostic(DiagnosticCode.UNKNOWN_NODE, node_ids=(NODE_A,))
        self.assertEqual(sort_diagnostics((second, first)), (first, second))

    def test_sort_uses_port_reference(self):
        second = self._diagnostic(
            DiagnosticCode.UNKNOWN_PORT,
            port_refs=(PortRef(NODE_A, "z_port"),),
        )
        first = self._diagnostic(
            DiagnosticCode.UNKNOWN_PORT,
            port_refs=(PortRef(NODE_A, "a_port"),),
        )
        self.assertEqual(sort_diagnostics((second, first)), (first, second))

    def test_sort_uses_edge_uuid(self):
        second = self._diagnostic(DiagnosticCode.BROKEN_ROUTE, edge_ids=(EDGE_B,))
        first = self._diagnostic(DiagnosticCode.BROKEN_ROUTE, edge_ids=(EDGE_A,))
        self.assertEqual(sort_diagnostics((second, first)), (first, second))

    def test_sort_uses_wire_position_xyz(self):
        second = self._diagnostic(
            DiagnosticCode.OPEN_WIRE,
            wire_positions=(GridPos(2, 0, 0),),
        )
        first = self._diagnostic(
            DiagnosticCode.OPEN_WIRE,
            wire_positions=(GridPos(1, 9, 9),),
        )
        self.assertEqual(sort_diagnostics((second, first)), (first, second))

    def test_message_is_final_contract_tie_breaker(self):
        later = self._diagnostic(DiagnosticCode.OPEN_WIRE, message="z message")
        earlier = self._diagnostic(DiagnosticCode.OPEN_WIRE, message="a message")
        self.assertEqual(sort_diagnostics((later, earlier)), (earlier, later))

    def test_sort_keeps_duplicate_diagnostics(self):
        diagnostic = self._diagnostic(DiagnosticCode.DUPLICATE_EDGE)
        self.assertEqual(sort_diagnostics((diagnostic, diagnostic)), (diagnostic, diagnostic))

    def test_sort_is_deterministic_when_only_suggestion_differs(self):
        first = Diagnostic(
            DiagnosticCode.OPEN_WIRE,
            Severity.ERROR,
            "same message",
            suggestion="a suggestion",
        )
        second = Diagnostic(
            DiagnosticCode.OPEN_WIRE,
            Severity.ERROR,
            "same message",
            suggestion="z suggestion",
        )
        self.assertEqual(
            sort_diagnostics((second, first)),
            sort_diagnostics((first, second)),
        )

    def test_validation_report_canonicalizes_diagnostic_order(self):
        later = self._diagnostic(DiagnosticCode.BROKEN_ROUTE)
        earlier = self._diagnostic(DiagnosticCode.UNKNOWN_NODE)
        self.assertEqual(
            ValidationReport(2, (later, earlier)).diagnostics,
            (earlier, later),
        )

    def test_negative_report_revision_is_rejected(self):
        with self.assertRaises(ValueError):
            ValidationReport(-1, ())

    @staticmethod
    def _diagnostic(
        code,
        severity=Severity.ERROR,
        message="diagnostic",
        **references,
    ):
        return Diagnostic(code=code, severity=severity, message=message, **references)


if __name__ == "__main__":
    unittest.main()
