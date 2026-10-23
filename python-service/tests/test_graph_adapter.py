import copy
import unittest
from dataclasses import FrozenInstanceError, replace
from threading import RLock
from uuid import UUID

import hangar.graph.adapter as adapter_module
from graph_validation_fixtures import PORT_CATALOG, START_ID, STOP_ID, straight_cell
from hangar.graph import (
    AdapterIssueCode,
    BindEdgesCommand,
    CardinalDirection,
    EdgeIndicatorStatus,
    GraphStatus,
    GraphWorkspaceMappingError,
    PortRef,
    ProgramValidationStatus,
    RouteCandidate,
    SaveStatus,
    ValidationReport,
    WireChannel,
    WireStep,
    bind_edges,
    prepare_graph_workspace_update,
    record_validation,
    replace_content,
    to_graph_workspace,
    to_stage_status_dto,
)
from hangar.stage import StageWorkspace


STAGE_ID = UUID("00000000-0000-0000-0000-000000003001")
PLAYER_ID = UUID("00000000-0000-0000-0000-000000003002")
EDGE_ID = UUID("00000000-0000-0000-0000-000000003003")
ROUTE_ID = UUID("00000000-0000-0000-0000-000000003004")
KNOWN_TYPES = frozenset(PORT_CATALOG)


class FixedFactory:
    def __init__(self, value):
        self.value = value
        self.calls = 0

    def __call__(self):
        self.calls += 1
        return self.value


class CountingLock:
    def __init__(self):
        self.entries = 0

    def __enter__(self):
        self.entries += 1
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False


def block(
    instance_id=START_ID,
    type_key="START",
    *,
    x=0,
    y=0,
    z=0,
    orientation="NORTH",
):
    return {
        "instance_id": str(instance_id),
        "stage_id": str(STAGE_ID),
        "type_key": type_key,
        "rel_x": x,
        "rel_y": y,
        "rel_z": z,
        "orientation": orientation,
        "label": type_key,
        "description": None,
    }


def workspace(*, blocks=None, revision=4, saved=2, dirty=False, lock=None):
    return StageWorkspace(
        stage={"stage_id": str(STAGE_ID), "owner_uuid": str(PLAYER_ID)},
        blocks=[] if blocks is None else blocks,
        saved_version=saved,
        dirty=dirty,
        revision=revision,
        lock=RLock() if lock is None else lock,
    )


def mapping(source, *, catalog=PORT_CATALOG, known=KNOWN_TYPES):
    return to_graph_workspace(source, catalog, known_block_types=known)


def start_stop_workspace(**overrides):
    values = {
        "blocks": [
            block(),
            block(STOP_ID, "STOP", x=2, orientation="SOUTH"),
        ]
    }
    values.update(overrides)
    return workspace(**values)


def bindable_states():
    mapped = mapping(start_stop_workspace()).state
    with_wire = replace_content(mapped, wire_cells=(straight_cell(),))
    candidate = RouteCandidate(
        PortRef(START_ID, "out"),
        PortRef(STOP_ID, "in"),
        (WireStep(straight_cell().position, WireChannel.SINGLE),),
    )
    result = bind_edges(
        with_wire,
        BindEdgesCommand(
            STAGE_ID,
            PLAYER_ID,
            with_wire.working_revision,
            (candidate,),
        ),
        PORT_CATALOG,
        edge_id_factory=FixedFactory(EDGE_ID),
        route_id_factory=FixedFactory(ROUTE_ID),
    )
    if not result.accepted:
        raise AssertionError(result.diagnostics)
    return with_wire, result.state, candidate


class GraphWorkspaceAdapterTest(unittest.TestCase):
    def test_empty_stage_maps_to_empty_node_view(self):
        result = mapping(workspace())
        self.assertEqual(result.state.nodes, ())
        self.assertEqual(result.state.edges, ())

    def test_start_block_maps_to_graph_node(self):
        node = mapping(workspace(blocks=[block()])).state.nodes[0]
        self.assertEqual((node.instance_id, node.type_key), (START_ID, "START"))

    def test_stop_block_maps_to_graph_node(self):
        node = mapping(workspace(blocks=[block(STOP_ID, "STOP")])).state.nodes[0]
        self.assertEqual((node.instance_id, node.type_key), (STOP_ID, "STOP"))

    def test_multiple_blocks_keep_stable_instance_ids(self):
        nodes = mapping(start_stop_workspace()).state.nodes
        self.assertEqual({item.instance_id for item in nodes}, {START_ID, STOP_ID})

    def test_relative_coordinates_map_to_grid_position(self):
        node = mapping(workspace(blocks=[block(x=3, y=1, z=7)])).state.nodes[0]
        self.assertEqual((node.position.x, node.position.y, node.position.z), (3, 1, 7))

    def test_all_known_orientations_convert_explicitly(self):
        for value in CardinalDirection:
            with self.subTest(value=value):
                node = mapping(workspace(blocks=[block(orientation=value.value)])).state.nodes[0]
                self.assertIs(node.orientation, value)

    def test_unknown_orientation_is_rejected(self):
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[block(orientation="UP")]))
        self.assertIn(AdapterIssueCode.UNKNOWN_ORIENTATION, self._codes(caught.exception))

    def test_lowercase_orientation_is_not_silently_normalized(self):
        with self.assertRaises(GraphWorkspaceMappingError):
            mapping(workspace(blocks=[block(orientation="north")]))

    def test_missing_required_block_field_is_rejected(self):
        item = block()
        del item["rel_z"]
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[item]))
        self.assertIn(AdapterIssueCode.MISSING_BLOCK_FIELD, self._codes(caught.exception))

    def test_invalid_instance_id_is_rejected(self):
        item = block()
        item["instance_id"] = "not-a-uuid"
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[item]))
        self.assertIn(AdapterIssueCode.INVALID_INSTANCE_ID, self._codes(caught.exception))

    def test_unknown_block_type_is_rejected(self):
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[block(type_key="UNKNOWN")]))
        self.assertIn(AdapterIssueCode.UNKNOWN_BLOCK_TYPE, self._codes(caught.exception))

    def test_known_type_without_port_catalog_is_rejected(self):
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(
                workspace(blocks=[block()]),
                catalog={},
                known=frozenset({"START"}),
            )
        self.assertIn(AdapterIssueCode.MISSING_PORT_CATALOG, self._codes(caught.exception))

    def test_graph_workspace_state_is_immutable(self):
        state = mapping(workspace(blocks=[block()])).state
        with self.assertRaises(FrozenInstanceError):
            state.dirty = True

    def test_mapping_does_not_modify_stage_workspace(self):
        source = start_stop_workspace()
        stage_before = copy.deepcopy(source.stage)
        blocks_before = copy.deepcopy(source.blocks)
        scalar_before = (source.revision, source.saved_version, source.dirty)
        mapping(source)
        self.assertEqual(source.stage, stage_before)
        self.assertEqual(source.blocks, blocks_before)
        self.assertEqual((source.revision, source.saved_version, source.dirty), scalar_before)

    def test_same_input_maps_deterministically(self):
        source = start_stop_workspace()
        self.assertEqual(mapping(source), mapping(source))

    def test_duplicate_graph_node_id_is_rejected(self):
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[block(), block()]))
        self.assertIn(AdapterIssueCode.DUPLICATE_INSTANCE_ID, self._codes(caught.exception))

    def test_working_revision_is_preserved(self):
        self.assertEqual(mapping(workspace(revision=17)).state.working_revision, 17)

    def test_saved_version_is_preserved(self):
        self.assertEqual(mapping(workspace(saved=9)).state.saved_version, 9)

    def test_dirty_flag_is_preserved(self):
        self.assertTrue(mapping(workspace(dirty=True)).state.dirty)

    def test_invalid_stage_id_is_rejected(self):
        source = workspace()
        source.stage["stage_id"] = "invalid"
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(source)
        self.assertIn(AdapterIssueCode.INVALID_STAGE_ID, self._codes(caught.exception))

    def test_non_integer_relative_position_is_rejected(self):
        item = block()
        item["rel_x"] = 1.5
        with self.assertRaises(GraphWorkspaceMappingError) as caught:
            mapping(workspace(blocks=[item]))
        self.assertIn(AdapterIssueCode.INVALID_POSITION, self._codes(caught.exception))

    def test_three_dimensional_status_dto(self):
        state = mapping(workspace(dirty=True)).state
        dto = to_stage_status_dto(state)
        self.assertIs(dto.edge_validation_status, EdgeIndicatorStatus.CHECK_REQUIRED)
        self.assertIs(dto.program_validation_status, ProgramValidationStatus.NOT_CHECKED)
        self.assertIs(dto.save_status, SaveStatus.DIRTY)

    def test_status_dto_is_serializable_to_primitive_values(self):
        payload = to_stage_status_dto(mapping(workspace()).state).as_dict()
        self.assertEqual(payload["stage_id"], str(STAGE_ID))
        self.assertEqual(payload["program_validation_status"], "NOT_CHECKED")

    def test_generic_valid_report_does_not_mean_program_valid(self):
        state = mapping(workspace()).state
        graph_valid = record_validation(
            state,
            ValidationReport(state.working_revision, ()),
            validated=True,
        )
        self.assertIs(graph_valid.graph_status, GraphStatus.VALID)
        self.assertIs(
            to_stage_status_dto(graph_valid).program_validation_status,
            ProgramValidationStatus.NOT_CHECKED,
        )

    def test_program_status_defaults_to_not_checked(self):
        dto = to_stage_status_dto(mapping(workspace()).state)
        self.assertIs(dto.program_validation_status, ProgramValidationStatus.NOT_CHECKED)

    def test_explicit_program_status_is_independent(self):
        dto = to_stage_status_dto(
            mapping(workspace()).state,
            program_validation_status=ProgramValidationStatus.NOT_APPLICABLE,
        )
        self.assertIs(dto.program_validation_status, ProgramValidationStatus.NOT_APPLICABLE)

    def test_clean_and_dirty_statuses_remain_distinct(self):
        clean = to_stage_status_dto(mapping(workspace(dirty=False)).state)
        dirty = to_stage_status_dto(mapping(workspace(dirty=True)).state)
        self.assertIs(clean.save_status, SaveStatus.CLEAN)
        self.assertIs(dirty.save_status, SaveStatus.DIRTY)

    def test_complete_snapshot_mapping_permits_graph_mutation(self):
        result = mapping(start_stop_workspace())
        self.assertTrue(result.graph_content_complete)
        self.assertTrue(result.permits_graph_mutation)
        self.assertEqual(result.issues, ())

    def test_idempotent_domain_result_has_no_revision_change(self):
        current, bound, candidate = bindable_states()
        repeated = bind_edges(
            bound,
            BindEdgesCommand(STAGE_ID, PLAYER_ID, bound.working_revision, (candidate,)),
            PORT_CATALOG,
            edge_id_factory=FixedFactory(UUID(int=9001)),
            route_id_factory=FixedFactory(UUID(int=9002)),
        )
        plan = prepare_graph_workspace_update(
            bound,
            repeated.state,
            operation_accepted=repeated.accepted,
        )
        self.assertTrue(plan.accepted)
        self.assertFalse(plan.requires_apply)
        self.assertEqual(plan.resulting_working_revision, bound.working_revision)

    def test_rejected_domain_result_has_no_applicable_change(self):
        current = mapping(start_stop_workspace()).state
        rejected = prepare_graph_workspace_update(
            current,
            current,
            operation_accepted=False,
        )
        self.assertFalse(rejected.accepted)
        self.assertFalse(rejected.requires_apply)
        self.assertIsNone(rejected.target_state)

    def test_successful_domain_result_produces_immutable_plan(self):
        current, bound, _ = bindable_states()
        plan = prepare_graph_workspace_update(current, bound, operation_accepted=True)
        self.assertTrue(plan.accepted)
        self.assertTrue(plan.requires_apply)
        self.assertIs(plan.target_state, bound)
        with self.assertRaises(FrozenInstanceError):
            plan.accepted = False

    def test_plan_preserves_graph_edges(self):
        current, bound, _ = bindable_states()
        plan = prepare_graph_workspace_update(current, bound, operation_accepted=True)
        self.assertEqual(plan.target_state.edges, bound.edges)

    def test_plan_preserves_edge_routes(self):
        current, bound, _ = bindable_states()
        plan = prepare_graph_workspace_update(current, bound, operation_accepted=True)
        self.assertEqual(plan.target_state.routes, bound.routes)

    def test_plan_preserves_wire_cells(self):
        current, bound, _ = bindable_states()
        plan = prepare_graph_workspace_update(current, bound, operation_accepted=True)
        self.assertEqual(plan.target_state.wire_cells, bound.wire_cells)

    def test_changed_plan_is_applicable_to_snapshot_contract(self):
        current, bound, _ = bindable_states()
        plan = prepare_graph_workspace_update(current, bound, operation_accepted=True)
        self.assertTrue(plan.can_apply_to_current_stage_workspace)
        self.assertEqual(plan.issues, ())

    def test_domain_operation_cannot_change_saved_version(self):
        current, bound, _ = bindable_states()
        invalid = replace(bound, saved_version=current.saved_version + 1)
        plan = prepare_graph_workspace_update(current, invalid, operation_accepted=True)
        self.assertFalse(plan.accepted)
        self.assertEqual(plan.issues[0].code, AdapterIssueCode.INVALID_RESULT_SAVED_VERSION)

    def test_content_change_must_mark_result_dirty(self):
        current, bound, _ = bindable_states()
        invalid = replace(bound, dirty=False)
        plan = prepare_graph_workspace_update(current, invalid, operation_accepted=True)
        self.assertFalse(plan.accepted)
        self.assertEqual(plan.issues[0].code, AdapterIssueCode.INVALID_RESULT_DIRTY_FLAG)

    def test_unchanged_content_cannot_clear_dirty_flag(self):
        current, _, _ = bindable_states()
        dirty = replace(current, dirty=True)
        invalid = replace(dirty, dirty=False)
        plan = prepare_graph_workspace_update(dirty, invalid, operation_accepted=True)
        self.assertFalse(plan.accepted)
        self.assertEqual(plan.issues[0].code, AdapterIssueCode.INVALID_RESULT_DIRTY_FLAG)

    def test_cross_stage_result_is_rejected(self):
        current = mapping(workspace()).state
        invalid = replace(current, stage_id=UUID(int=999))
        plan = prepare_graph_workspace_update(current, invalid, operation_accepted=True)
        self.assertFalse(plan.accepted)
        self.assertEqual(plan.issues[0].code, AdapterIssueCode.CROSS_STAGE_RESULT)

    def test_adapter_does_not_acquire_stage_lock(self):
        lock = CountingLock()
        mapping(workspace(lock=lock))
        self.assertEqual(lock.entries, 0)

    def test_adapter_has_no_stage_service_or_registry_dependency(self):
        self.assertFalse(hasattr(adapter_module, "StageService"))
        self.assertFalse(hasattr(adapter_module, "_workspaces"))

    def test_adapter_has_no_repository_or_http_dependency(self):
        names = set(vars(adapter_module))
        self.assertNotIn("HallRepository", names)
        self.assertNotIn("requests", names)
        self.assertNotIn("Flask", names)

    @staticmethod
    def _codes(error):
        return tuple(item.code for item in error.issues)


if __name__ == "__main__":
    unittest.main()
