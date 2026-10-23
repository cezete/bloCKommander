import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from graph_validation_fixtures import straight_cell
from hangar.graph import (
    BindEdgesCommand,
    CK_CONTROL_PORT_CATALOG,
    GraphWorkspaceState,
    PortRef,
    RouteCandidate,
    WireChannel,
    WireStep,
    bind_edges,
    replace_content,
)
from hangar.repository import HallConflict, HallForbidden
from hangar.stage import GraphOperationRejected, StageService
from tests.test_stage_service import FakeStageRepository


def runtime_types():
    return [
        {
            "type_key": type_key,
            "display_name": type_key,
            "material_name": "STONE",
            "default_label": type_key,
            "default_description": type_key.lower(),
            "display_definition": {},
        }
        for type_key in ("START", "STOP", "IF", "FOR", "FUN")
    ]


@dataclass(frozen=True)
class RejectedResult:
    accepted: bool
    state: GraphWorkspaceState
    diagnostics: tuple[str, ...]


class StageGraphServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo = FakeStageRepository(graph_aware=True)
        self.repo.types = runtime_types()
        self.service = StageService(self.repo)
        self.stage_id = self.repo.stage["stage_id"]
        self.owner = self.repo.stage["owner_uuid"]
        self.service.place(self.stage_id, self.place_payload("START", 11))
        self.service.place(self.stage_id, self.place_payload("STOP", 13))

    def place_payload(self, type_key, x):
        return {
            "player_uuid": self.owner,
            "type_key": type_key,
            "x": x,
            "y": 65,
            "z": 21,
            "orientation": "NORTH",
        }

    def command_payload(self, **changes):
        view = self.service.view(self.service._workspace(self.stage_id))
        payload = {
            "player_uuid": self.owner,
            "workspace_epoch": view["workspace_epoch"],
            "expected_working_revision": view["working_revision"],
        }
        payload.update(changes)
        return payload

    @staticmethod
    def add_wire(state):
        return replace_content(state, wire_cells=(straight_cell(),))

    def test_owner_only_graph_operation(self):
        payload = self.command_payload(player_uuid=str(uuid.uuid4()))
        before = self.service._workspace(self.stage_id).snapshot
        with self.assertRaises(HallForbidden):
            self.service.apply_graph_operation(self.stage_id, payload, self.add_wire)
        self.assertIs(self.service._workspace(self.stage_id).snapshot, before)

    def test_stale_epoch_and_revision_are_rejected(self):
        payload = self.command_payload(workspace_epoch=str(uuid.uuid4()))
        with self.assertRaises(HallConflict):
            self.service.apply_graph_operation(self.stage_id, payload, self.add_wire)
        payload = self.command_payload(expected_working_revision=-1)
        with self.assertRaises(HallConflict):
            self.service.apply_graph_operation(self.stage_id, payload, self.add_wire)

    def test_success_swaps_complete_snapshot_once(self):
        workspace = self.service._workspace(self.stage_id)
        before = workspace.snapshot
        view = self.service.apply_graph_operation(
            self.stage_id, self.command_payload(), self.add_wire
        )
        self.assertIsNot(workspace.snapshot, before)
        self.assertEqual(workspace.snapshot.wire_cells, (straight_cell(),))
        self.assertEqual(view["working_revision"], before.working_revision + 1)
        self.assertTrue(view["dirty"])
        self.assertEqual(view["graph_persistence"], "FULL")

    def test_real_binding_result_is_applied_with_stable_ids(self):
        self.service.apply_graph_operation(self.stage_id, self.command_payload(), self.add_wire)
        snapshot = self.service._workspace(self.stage_id).snapshot
        start = next(item for item in snapshot.blocks if item.type_key == "START")
        stop = next(item for item in snapshot.blocks if item.type_key == "STOP")
        edge_id = uuid.UUID(int=6101)
        route_id = uuid.UUID(int=6102)

        def bind(state):
            command = BindEdgesCommand(
                state.stage_id,
                uuid.UUID(self.owner),
                state.working_revision,
                (
                    RouteCandidate(
                        PortRef(start.instance_id, "out"),
                        PortRef(stop.instance_id, "in"),
                        (WireStep(straight_cell().position, WireChannel.SINGLE),),
                    ),
                ),
            )
            return bind_edges(
                state,
                command,
                CK_CONTROL_PORT_CATALOG,
                edge_id_factory=lambda: edge_id,
                route_id_factory=lambda: route_id,
            )

        self.service.apply_graph_operation(self.stage_id, self.command_payload(), bind)
        result = self.service._workspace(self.stage_id).snapshot
        self.assertEqual((result.edges[0].edge_id, result.routes[0].route_id), (edge_id, route_id))

    def test_rejected_or_failing_operation_never_mutates(self):
        workspace = self.service._workspace(self.stage_id)
        before = workspace.snapshot

        def rejected(state):
            return RejectedResult(False, state, ("no",))

        with self.assertRaises(GraphOperationRejected):
            self.service.apply_graph_operation(self.stage_id, self.command_payload(), rejected)
        self.assertIs(workspace.snapshot, before)

        def failing(_state):
            raise RuntimeError("domain failure")

        with self.assertRaises(RuntimeError):
            self.service.apply_graph_operation(self.stage_id, self.command_payload(), failing)
        self.assertIs(workspace.snapshot, before)

    def test_idempotent_operation_keeps_revision_and_snapshot(self):
        workspace = self.service._workspace(self.stage_id)
        before = workspace.snapshot
        view = self.service.apply_graph_operation(
            self.stage_id, self.command_payload(), lambda state: state
        )
        self.assertIs(workspace.snapshot, before)
        self.assertEqual(view["working_revision"], before.working_revision)

    def test_parallel_operations_are_serialized_by_revision(self):
        payload = self.command_payload()
        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [
                executor.submit(
                    self.service.apply_graph_operation,
                    self.stage_id,
                    payload,
                    self.add_wire,
                )
                for _ in range(2)
            ]
        successes = sum(future.exception() is None for future in futures)
        conflicts = sum(isinstance(future.exception(), HallConflict) for future in futures)
        self.assertEqual((successes, conflicts), (1, 1))

    def test_graph_snapshot_is_saved_through_full_repository_api(self):
        self.service.apply_graph_operation(self.stage_id, self.command_payload(), self.add_wire)
        before = self.service._workspace(self.stage_id).snapshot
        saved=self.service.save(self.stage_id, {"player_uuid": self.owner})
        self.assertIs(self.service._workspace(self.stage_id).snapshot, before)
        self.assertIs(self.repo.full_saved, before)
        self.assertFalse(saved["dirty"])

    def test_block_metadata_edit_preserves_graph_and_is_fully_saved(self):
        self.service.apply_graph_operation(self.stage_id, self.command_payload(), self.add_wire)
        workspace = self.service._workspace(self.stage_id)
        instance_id = str(workspace.snapshot.blocks[0].instance_id)
        self.service.update(
            self.stage_id,
            instance_id,
            {"player_uuid": self.owner, "label": "Megőrzött"},
        )
        self.assertEqual(workspace.snapshot.wire_cells, (straight_cell(),))
        self.assertEqual(next(item for item in workspace.snapshot.blocks if str(item.instance_id) == instance_id).label, "Megőrzött")
        self.service.save(self.stage_id, {"player_uuid": self.owner})
        self.assertEqual(self.repo.full_saved.wire_cells, (straight_cell(),))

    def test_legacy_save_does_not_increment_working_revision(self):
        before = self.command_payload()["expected_working_revision"]
        saved = self.service.save(self.stage_id, {"player_uuid": self.owner})
        self.assertEqual(saved["working_revision"], before)
        self.assertFalse(saved["dirty"])

    def test_rebuild_changes_epoch_and_requires_explicit_graph_discard(self):
        self.service.apply_graph_operation(self.stage_id, self.command_payload(), self.add_wire)
        before = self.service.view(self.service._workspace(self.stage_id))
        with self.assertRaises(HallConflict):
            self.service.rebuild(self.stage_id, {"player_uuid": self.owner})
        rebuilt = self.service.rebuild(
            self.stage_id,
            {"player_uuid": self.owner, "confirm_discard": True},
        )
        self.assertNotEqual(rebuilt["workspace_epoch"], before["workspace_epoch"])
        self.assertEqual(rebuilt["working_revision"], before["working_revision"] + 1)
        self.assertEqual(rebuilt["graph_persistence"], "FULL")


if __name__ == "__main__":
    unittest.main()
