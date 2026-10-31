import unittest
from dataclasses import FrozenInstanceError

from hangar.graph import (
    BlockPortStatus,
    CK_CONTROL_PORT_CATALOG,
    LocalSide,
    PORT_CATALOG_VERSION,
    PortCatalogIssueCode,
    PortCatalogLookupError,
    PortDirection,
)


class PortCatalogTest(unittest.TestCase):
    def test_catalog_version_is_explicit(self):
        self.assertEqual(PORT_CATALOG_VERSION, "ck-control-v2")
        self.assertEqual(CK_CONTROL_PORT_CATALOG.version, PORT_CATALOG_VERSION)

    def test_start_stop_and_if_ports_match_contract(self):
        actual = {
            type_key: tuple((p.port_key, p.direction, p.local_side) for p in CK_CONTROL_PORT_CATALOG[type_key])
            for type_key in ("START", "STOP", "IF")
        }
        self.assertEqual(actual["START"], (("out", PortDirection.OUT, LocalSide.FRONT),))
        self.assertEqual(actual["STOP"], (("in", PortDirection.IN, LocalSide.BACK),))
        self.assertEqual(
            actual["IF"],
            (
                ("in", PortDirection.IN, LocalSide.FRONT),
                ("true", PortDirection.OUT, LocalSide.RIGHT),
                ("false", PortDirection.OUT, LocalSide.LEFT),
            ),
        )

    def test_every_specified_port_is_required_single_control_edge(self):
        for type_key in ("START", "STOP", "IF"):
            for port in CK_CONTROL_PORT_CATALOG[type_key]:
                self.assertEqual((port.min_edges, port.max_edges), (1, 1))
                self.assertEqual(port.port_kind.value, "CONTROL")

    def test_if_branch_labels_and_directions_match_the_physical_markers(self):
        ports = {item.port_key: item for item in CK_CONTROL_PORT_CATALOG["IF"]}
        self.assertEqual(
            (ports["in"].direction, ports["in"].branch_label),
            (PortDirection.IN, None),
        )
        self.assertEqual(
            (ports["false"].local_side, ports["false"].direction,
             ports["false"].branch_label),
            (LocalSide.LEFT, PortDirection.OUT, "0"),
        )
        self.assertEqual(
            (ports["true"].local_side, ports["true"].direction,
             ports["true"].branch_label),
            (LocalSide.RIGHT, PortDirection.OUT, "1"),
        )

    def test_for_and_fun_are_unspecified_without_invented_ports(self):
        for type_key in ("FOR", "FUN"):
            self.assertIs(CK_CONTROL_PORT_CATALOG.status(type_key), BlockPortStatus.UNSPECIFIED)
            with self.assertRaises(PortCatalogLookupError) as caught:
                CK_CONTROL_PORT_CATALOG[type_key]
            self.assertIs(caught.exception.issue.code, PortCatalogIssueCode.UNSPECIFIED_BLOCK_TYPE)

    def test_unknown_type_has_structured_error(self):
        with self.assertRaises(PortCatalogLookupError) as caught:
            CK_CONTROL_PORT_CATALOG.specification("MISSING")
        self.assertIs(caught.exception.issue.code, PortCatalogIssueCode.UNKNOWN_BLOCK_TYPE)

    def test_runtime_catalog_consistency_is_deterministic(self):
        compatible = CK_CONTROL_PORT_CATALOG.check_runtime_types(
            ["STOP", "IF", "START", "FUN", "FOR"]
        )
        self.assertTrue(compatible.compatible)
        self.assertEqual(compatible.issues, ())
        mismatch = CK_CONTROL_PORT_CATALOG.check_runtime_types(["START", "EXTRA"])
        self.assertFalse(mismatch.compatible)
        self.assertEqual(
            tuple(item.type_key for item in mismatch.issues),
            ("EXTRA", "FOR", "FUN", "IF", "STOP"),
        )

    def test_catalog_order_and_specifications_are_immutable(self):
        self.assertEqual(tuple(CK_CONTROL_PORT_CATALOG), ("IF", "START", "STOP"))
        with self.assertRaises(FrozenInstanceError):
            CK_CONTROL_PORT_CATALOG.specifications[0].status = BlockPortStatus.SPECIFIED


if __name__ == "__main__":
    unittest.main()
