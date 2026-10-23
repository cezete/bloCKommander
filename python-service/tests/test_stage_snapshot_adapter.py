import unittest
from dataclasses import replace
from uuid import UUID

from graph_validation_fixtures import cross_cell, straight_cell
from hangar.graph import (
    AdapterIssueCode,
    CardinalDirection,
    CK_CONTROL_PORT_CATALOG,
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    GraphNode,
    GraphWorkspaceMappingError,
    GridPos,
    PORT_CATALOG_VERSION,
    PortRef,
    ProgramValidationStatus,
    RouteStatus,
    StageBlockSnapshot,
    StageSnapshot,
    ValidationReport,
    WireChannel,
    WireStep,
    prepare_graph_workspace_update,
    replace_content,
    to_graph_workspace,
    to_stage_snapshot,
)
from hangar.stage import StageWorkspace


STAGE_ID = UUID("00000000-0000-0000-0000-000000005001")
START_ID = UUID("00000000-0000-0000-0000-000000005002")
STOP_ID = UUID("00000000-0000-0000-0000-000000005003")
EDGE_ID = UUID("00000000-0000-0000-0000-000000005004")
ROUTE_ID = UUID("00000000-0000-0000-0000-000000005005")
RUNTIME_TYPES = {"START", "STOP", "IF", "FOR", "FUN"}


def stage_block(instance_id, type_key, position, label):
    return StageBlockSnapshot(
        instance_id,
        type_key,
        position,
        CardinalDirection.NORTH,
        label,
        f"{label} description",
        type_key,
        "STONE",
        {"front": "label"},
    )


def full_snapshot():
    source = PortRef(START_ID, "out")
    target = PortRef(STOP_ID, "in")
    graph_edge = GraphEdge(EDGE_ID, STAGE_ID, START_ID, "out", STOP_ID, "in", EdgeKind.CONTROL)
    graph_route = EdgeRoute(
        ROUTE_ID,
        STAGE_ID,
        EDGE_ID,
        source,
        target,
        (WireStep(GridPos(1, 0, 0), WireChannel.SINGLE),),
        RouteStatus.VALID,
    )
    return StageSnapshot(
        STAGE_ID,
        (
            stage_block(START_ID, "START", GridPos(0, 0, 0), "Indulás"),
            stage_block(STOP_ID, "STOP", GridPos(2, 0, 0), "Megállás"),
        ),
        (graph_edge,),
        (graph_route,),
        (straight_cell(),),
        7,
        None,
        None,
        ProgramValidationStatus.NOT_CHECKED,
        PORT_CATALOG_VERSION,
    )


def workspace(value=None):
    value = value or full_snapshot()
    return StageWorkspace(
        {"stage_id": str(STAGE_ID), "owner_uuid": str(UUID(int=55))},
        value,
        3,
        True,
    )


class StageSnapshotAdapterTest(unittest.TestCase):
    def mapping(self, value=None):
        return to_graph_workspace(
            workspace(value),
            CK_CONTROL_PORT_CATALOG,
            known_block_types=RUNTIME_TYPES,
        )

    def test_full_mapping_is_lossless_and_mutable_by_domain(self):
        mapped = self.mapping()
        source = full_snapshot()
        self.assertTrue(mapped.graph_content_complete)
        self.assertTrue(mapped.permits_graph_mutation)
        self.assertEqual((mapped.state.edges, mapped.state.routes, mapped.state.wire_cells), (source.edges, source.routes, source.wire_cells))
        self.assertEqual((mapped.state.working_revision, mapped.state.saved_version, mapped.state.dirty), (7, 3, True))

    def test_validation_report_and_revision_are_mapped(self):
        report = ValidationReport(7, ())
        value = replace(full_snapshot(), validated_revision=7, last_graph_validation=report)
        mapped = self.mapping(value).state
        self.assertEqual(mapped.validated_revision, 7)
        self.assertIs(mapped.last_validation, report)

    def test_round_trip_preserves_metadata_and_stable_ids(self):
        current = full_snapshot()
        state = self.mapping(current).state
        changed = replace_content(state, wire_cells=(cross_cell(),))
        plan = prepare_graph_workspace_update(state, changed, operation_accepted=True)
        rebuilt = to_stage_snapshot(current, plan)
        self.assertEqual((rebuilt.edges[0].edge_id, rebuilt.routes[0].route_id), (EDGE_ID, ROUTE_ID))
        self.assertEqual((rebuilt.blocks[0].label, rebuilt.blocks[0].description), ("Indulás", "Indulás description"))
        self.assertEqual(rebuilt.wire_cells, (cross_cell(),))

    def test_node_layout_change_updates_block_without_losing_metadata(self):
        current = full_snapshot()
        state = self.mapping(current).state
        moved = replace(state.nodes[0], position=GridPos(9, 0, 0))
        changed = replace_content(state, nodes=(moved, state.nodes[1]))
        rebuilt = to_stage_snapshot(current, prepare_graph_workspace_update(state, changed, operation_accepted=True))
        item = next(block for block in rebuilt.blocks if block.instance_id == START_ID)
        self.assertEqual((item.position, item.label), (GridPos(9, 0, 0), "Indulás"))

    def test_node_deletion_removes_its_block_only(self):
        current = full_snapshot()
        state = self.mapping(current).state
        changed = replace_content(state, nodes=(state.nodes[1],), edges=(), routes=())
        rebuilt = to_stage_snapshot(current, prepare_graph_workspace_update(state, changed, operation_accepted=True))
        self.assertEqual(tuple(item.instance_id for item in rebuilt.blocks), (STOP_ID,))

    def test_new_node_without_metadata_is_rejected(self):
        current = full_snapshot()
        state = self.mapping(current).state
        added = GraphNode(UUID(int=5999), STAGE_ID, "START", GridPos(5, 0, 0), CardinalDirection.NORTH)
        changed = replace_content(state, nodes=(*state.nodes, added))
        plan = prepare_graph_workspace_update(state, changed, operation_accepted=True)
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            to_stage_snapshot(current, plan)
        self.assertEqual(caught.exception.issues[0].code, AdapterIssueCode.MISSING_NEW_BLOCK_METADATA)

    def test_new_node_with_metadata_is_added(self):
        current = full_snapshot()
        state = self.mapping(current).state
        new_id = UUID(int=5998)
        added = GraphNode(new_id, STAGE_ID, "START", GridPos(5, 0, 0), CardinalDirection.SOUTH)
        changed = replace_content(state, nodes=(*state.nodes, added))
        plan = prepare_graph_workspace_update(state, changed, operation_accepted=True)
        metadata = stage_block(new_id, "START", GridPos(5, 0, 0), "Új START")
        rebuilt = to_stage_snapshot(current, plan, new_block_metadata=(metadata,))
        self.assertEqual(next(item for item in rebuilt.blocks if item.instance_id == new_id).label, "Új START")

    def test_catalog_version_mismatch_blocks_mutation(self):
        mismatch = replace(full_snapshot(), port_catalog_version="ck-control-v0")
        mapped = self.mapping(mismatch)
        self.assertFalse(mapped.permits_graph_mutation)
        self.assertIn(AdapterIssueCode.PORT_CATALOG_VERSION_MISMATCH, {item.code for item in mapped.issues})

    def test_unspecified_block_type_blocks_graph_mutation(self):
        value = replace(
            full_snapshot(),
            blocks=(stage_block(UUID(int=5777), "FOR", GridPos(0, 0, 0), "FOR"),),
            edges=(),
            routes=(),
            wire_cells=(),
        )
        mapped = self.mapping(value)
        self.assertFalse(mapped.permits_graph_mutation)
        self.assertIn(AdapterIssueCode.UNSPECIFIED_BLOCK_TYPE, {item.code for item in mapped.issues})

    def test_rejected_and_idempotent_results_do_not_change_snapshot(self):
        current = full_snapshot()
        state = self.mapping(current).state
        rejected = prepare_graph_workspace_update(state, state, operation_accepted=False)
        with self.assertRaises(GraphWorkspaceMappingError):
            to_stage_snapshot(current, rejected)
        idempotent = prepare_graph_workspace_update(state, state, operation_accepted=True)
        self.assertFalse(idempotent.requires_apply)
        self.assertEqual(to_stage_snapshot(current, idempotent), current)


if __name__ == "__main__":
    unittest.main()
