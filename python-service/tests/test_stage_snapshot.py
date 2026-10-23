import unittest
from dataclasses import FrozenInstanceError, replace
from uuid import UUID

from graph_validation_fixtures import bound_route, edge, straight_cell
from hangar.graph import (
    CardinalDirection,
    GridPos,
    PORT_CATALOG_VERSION,
    ProgramValidationStatus,
    StageBlockSnapshot,
    StageSnapshot,
    ValidationReport,
)


STAGE_ID = UUID("00000000-0000-0000-0000-000000004001")
BLOCK_ID = UUID("00000000-0000-0000-0000-000000004002")


def block():
    return StageBlockSnapshot(
        BLOCK_ID,
        "START",
        GridPos(1, 2, 3),
        CardinalDirection.NORTH,
        "Indulás",
        "nem gráfos metaadat",
        "START",
        "LIME_GLAZED_TERRACOTTA",
        {"front": "label"},
    )


def snapshot(**changes):
    values = dict(
        stage_id=STAGE_ID,
        blocks=(block(),),
        edges=(),
        routes=(),
        wire_cells=(),
        working_revision=0,
        validated_revision=None,
        last_graph_validation=None,
        program_validation_status=ProgramValidationStatus.NOT_CHECKED,
        port_catalog_version=PORT_CATALOG_VERSION,
    )
    values.update(changes)
    return StageSnapshot(**values)


class StageSnapshotTest(unittest.TestCase):
    def test_empty_snapshot(self):
        empty = snapshot(blocks=())
        self.assertEqual(empty.graph_nodes(), ())
        self.assertFalse(empty.has_unpersisted_graph_content)

    def test_node_projection_keeps_identity_and_layout(self):
        node = snapshot().graph_nodes()[0]
        self.assertEqual((node.instance_id, node.position, node.orientation), (BLOCK_ID, GridPos(1, 2, 3), CardinalDirection.NORTH))

    def test_snapshot_and_block_are_immutable(self):
        value = snapshot()
        with self.assertRaises(FrozenInstanceError):
            value.working_revision = 4
        with self.assertRaises(FrozenInstanceError):
            value.blocks[0].label = "más"
        self.assertIsInstance(value.blocks[0].display_definition, tuple)

    def test_non_graph_metadata_survives_content_change(self):
        value = snapshot()
        moved = replace(value.blocks[0], position=GridPos(4, 2, 3))
        changed = value.with_content_change(blocks=(moved,))
        self.assertEqual(changed.blocks[0].label, "Indulás")
        self.assertEqual(changed.blocks[0].description, "nem gráfos metaadat")
        self.assertEqual(changed.working_revision, 1)

    def test_idempotent_content_change_keeps_revision(self):
        value = snapshot()
        self.assertIs(value.with_content_change(blocks=value.blocks), value)

    def test_edge_route_and_wire_content_is_retained(self):
        graph_edge = edge(4100, BLOCK_ID, "out", UUID(int=4101), "in", STAGE_ID)
        graph_route = bound_route(edge_id=graph_edge.edge_id, stage_id=STAGE_ID)
        value = snapshot(edges=(graph_edge,), routes=(graph_route,), wire_cells=(straight_cell(),))
        self.assertEqual((value.edges, value.routes, value.wire_cells), ((graph_edge,), (graph_route,), (straight_cell(),)))
        self.assertTrue(value.has_unpersisted_graph_content)

    def test_validation_state_is_part_of_snapshot(self):
        report = ValidationReport(3, ())
        value = snapshot(
            working_revision=3,
            validated_revision=3,
            last_graph_validation=report,
            program_validation_status=ProgramValidationStatus.VALID,
        )
        self.assertIs(value.last_graph_validation, report)
        self.assertEqual(value.validated_revision, 3)
        self.assertIs(value.program_validation_status, ProgramValidationStatus.VALID)

    def test_legacy_projection_is_a_detached_metadata_copy(self):
        value = snapshot()
        projected = value.blocks[0].as_legacy_dict(STAGE_ID)
        projected["display_definition"]["front"] = "changed"
        again = value.blocks[0].as_legacy_dict(STAGE_ID)
        self.assertEqual(again["display_definition"], {"front": "label"})


if __name__ == "__main__":
    unittest.main()
