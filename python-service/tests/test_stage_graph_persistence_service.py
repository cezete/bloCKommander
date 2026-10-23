import threading
import time
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor

from graph_validation_fixtures import straight_cell
from hangar.graph import (
    EdgeKind,
    EdgeRoute,
    GraphEdge,
    PortRef,
    ProgramValidationStatus,
    RouteStatus,
    WireChannel,
    WireStep,
    replace_content,
)
from hangar.repository import (
    HallConflict,
    HallForbidden,
    SnapshotCompatibilityError,
    StageVersionConflict,
)
from hangar.stage import StageService
from tests.test_stage_graph_service import runtime_types
from tests.test_stage_service import FakeStageRepository


class BlockingFullSaveRepository(FakeStageRepository):
    def __init__(self):
        super().__init__(graph_aware=True)
        self.save_entered = threading.Event()
        self.release_save = threading.Event()

    def save_full_stage_snapshot(self, stage_id, expected_version, snapshot):
        self.save_entered.set()
        if not self.release_save.wait(5):
            raise TimeoutError("test did not release repository SAVE")
        return super().save_full_stage_snapshot(stage_id, expected_version, snapshot)


class StageGraphPersistenceServiceTest(unittest.TestCase):
    def setUp(self):
        self.repo = FakeStageRepository(graph_aware=True)
        self.repo.types = runtime_types()
        self.service = StageService(self.repo)
        self.stage_id = self.repo.stage["stage_id"]
        self.owner = self.repo.stage["owner_uuid"]
        self._place_start_stop()

    def _place_start_stop(self):
        self.service.place(
            self.stage_id,
            {
                "player_uuid": self.owner,
                "type_key": "START",
                "x": 11,
                "y": 65,
                "z": 21,
                "orientation": "NORTH",
            },
        )
        self.service.place(
            self.stage_id,
            {
                "player_uuid": self.owner,
                "type_key": "STOP",
                "x": 13,
                "y": 65,
                "z": 21,
                "orientation": "NORTH",
            },
        )

    def _full_draft_broken_snapshot(self):
        workspace = self.service._workspace(self.stage_id)
        start = next(item for item in workspace.snapshot.blocks if item.type_key == "START")
        stop = next(item for item in workspace.snapshot.blocks if item.type_key == "STOP")
        edge = GraphEdge(
            uuid.UUID(int=7301),
            workspace.snapshot.stage_id,
            start.instance_id,
            "out",
            stop.instance_id,
            "in",
            EdgeKind.CONTROL,
        )
        route = EdgeRoute(
            uuid.UUID(int=7302),
            workspace.snapshot.stage_id,
            edge.edge_id,
            PortRef(start.instance_id, "out"),
            PortRef(stop.instance_id, "in"),
            (WireStep(straight_cell().position, WireChannel.SINGLE),),
            RouteStatus.VALID,
        )
        broken = EdgeRoute(
            uuid.UUID(int=7303),
            workspace.snapshot.stage_id,
            None,
            None,
            None,
            (WireStep(type(straight_cell().position)(4, 0, 0), WireChannel.SINGLE),),
            RouteStatus.BROKEN,
        )
        workspace.snapshot = workspace.snapshot.with_content_change(
            edges=(edge,), routes=(route, broken), wire_cells=(straight_cell(),)
        )
        workspace.dirty = True
        return workspace

    def _versioned_payload(self, workspace=None, **extra):
        workspace = workspace or self.service._workspace(self.stage_id)
        result = {
            "player_uuid": self.owner,
            "workspace_epoch": str(workspace.workspace_epoch),
            "expected_working_revision": workspace.snapshot.working_revision,
        }
        result.update(extra)
        return result

    def test_graph_save_is_exactly_one_version_clean_and_revision_stable(self):
        workspace = self._full_draft_broken_snapshot()
        before_snapshot = workspace.snapshot
        before_revision = before_snapshot.working_revision
        result = self.service.save(self.stage_id, self._versioned_payload(workspace))
        self.assertEqual(result["saved_version"], 1)
        self.assertEqual(result["working_revision"], before_revision)
        self.assertFalse(result["dirty"])
        self.assertIs(workspace.snapshot, before_snapshot)
        self.assertIs(self.repo.full_saved, before_snapshot)
        self.assertEqual(workspace.snapshot.program_validation_status, ProgramValidationStatus.NOT_CHECKED)
        self.assertEqual(workspace.snapshot.routes[1].status, RouteStatus.BROKEN)

    def test_save_repository_failure_and_cas_conflict_leave_all_memory_unchanged(self):
        workspace = self._full_draft_broken_snapshot()
        before = (
            workspace.stage.copy(),
            workspace.snapshot,
            workspace.saved_version,
            workspace.dirty,
            workspace.workspace_epoch,
        )
        self.repo.fail_save = True
        with self.assertRaises(RuntimeError):
            self.service.save(self.stage_id, self._versioned_payload(workspace))
        self.assertEqual(
            (workspace.stage, workspace.snapshot, workspace.saved_version, workspace.dirty, workspace.workspace_epoch),
            before,
        )
        self.repo.fail_save = False
        self.repo.stage["saved_version"] = 1
        original = self.repo.save_full_stage_snapshot

        def conflict(stage_id, expected_version, snapshot):
            if expected_version != self.repo.stage["saved_version"]:
                raise StageVersionConflict("stale")
            return original(stage_id, expected_version, snapshot)

        self.repo.save_full_stage_snapshot = conflict
        with self.assertRaises(StageVersionConflict):
            self.service.save(self.stage_id, self._versioned_payload(workspace))
        self.assertEqual(
            (workspace.stage, workspace.snapshot, workspace.saved_version, workspace.dirty, workspace.workspace_epoch),
            before,
        )

    def test_rebuild_revalidates_graph_uses_new_epoch_and_preserves_broken_draft(self):
        workspace = self._full_draft_broken_snapshot()
        self.service.save(self.stage_id, self._versioned_payload(workspace))
        old_epoch = workspace.workspace_epoch
        old_revision = workspace.snapshot.working_revision
        first_block = workspace.snapshot.blocks[0]
        self.service.update(
            self.stage_id,
            str(first_block.instance_id),
            {"player_uuid": self.owner, "label": "Unsaved"},
        )
        with self.assertRaises(HallConflict):
            self.service.rebuild(self.stage_id, {"player_uuid": self.owner})
        rebuilt = self.service.rebuild(
            self.stage_id,
            {"player_uuid": self.owner, "confirm_discard": True},
        )
        self.assertNotEqual(workspace.workspace_epoch, old_epoch)
        self.assertEqual(rebuilt["working_revision"], old_revision + 2)
        self.assertFalse(rebuilt["dirty"])
        self.assertEqual(rebuilt["graph_persistence"], "FULL")
        self.assertIsNotNone(workspace.snapshot.last_graph_validation)
        self.assertEqual(workspace.snapshot.program_validation_status, ProgramValidationStatus.NOT_CHECKED)
        self.assertIn(RouteStatus.BROKEN, {item.status for item in workspace.snapshot.routes})
        with self.assertRaises(HallConflict):
            self.service.apply_graph_operation(
                self.stage_id,
                {
                    "player_uuid": self.owner,
                    "workspace_epoch": str(old_epoch),
                    "expected_working_revision": workspace.snapshot.working_revision,
                },
                lambda state: state,
            )

    def test_failed_rebuild_is_atomic(self):
        workspace = self._full_draft_broken_snapshot()
        before = (
            workspace.stage,
            workspace.snapshot,
            workspace.saved_version,
            workspace.dirty,
            workspace.workspace_epoch,
            workspace.graph_snapshot_version,
            workspace.compatible_for_graph_mutation,
            workspace.compatibility_issues,
        )
        self.repo.load_full_stage_snapshot = lambda _stage_id: (_ for _ in ()).throw(RuntimeError("load failed"))
        with self.assertRaises(RuntimeError):
            self.service.rebuild(
                self.stage_id,
                {"player_uuid": self.owner, "confirm_discard": True},
            )
        self.assertEqual(
            (
                workspace.stage,
                workspace.snapshot,
                workspace.saved_version,
                workspace.dirty,
                workspace.workspace_epoch,
                workspace.graph_snapshot_version,
                workspace.compatible_for_graph_mutation,
                workspace.compatibility_issues,
            ),
            before,
        )

    def test_incompatible_and_legacy_snapshots_are_not_graph_mutable(self):
        incompatible_repo = FakeStageRepository(graph_aware=True)
        incompatible_repo.types = runtime_types()
        incompatible_repo.stage["graph_snapshot_version"] = 99
        service = StageService(incompatible_repo)
        workspace = service._workspace(incompatible_repo.stage["stage_id"])
        view = service.view(workspace)
        self.assertEqual(view["graph_persistence"], "READ_ONLY_INCOMPATIBLE")
        with self.assertRaises(SnapshotCompatibilityError):
            service.apply_graph_operation(
                incompatible_repo.stage["stage_id"],
                {
                    "player_uuid": incompatible_repo.stage["owner_uuid"],
                    "workspace_epoch": str(workspace.workspace_epoch),
                    "expected_working_revision": workspace.snapshot.working_revision,
                },
                lambda state: state,
            )
        legacy_repo = FakeStageRepository()
        legacy_repo.types = runtime_types()
        legacy_service = StageService(legacy_repo)
        legacy_workspace = legacy_service._workspace(legacy_repo.stage["stage_id"])
        with self.assertRaises(SnapshotCompatibilityError):
            legacy_service.apply_graph_operation(
                legacy_repo.stage["stage_id"],
                {
                    "player_uuid": legacy_repo.stage["owner_uuid"],
                    "workspace_epoch": str(legacy_workspace.workspace_epoch),
                    "expected_working_revision": 0,
                },
                lambda state: state,
            )
        port_repo = FakeStageRepository(graph_aware=True)
        port_repo.types = runtime_types()
        port_repo.stage["port_catalog_version"] = "future-catalog"
        port_service = StageService(port_repo)
        port_view = port_service.view(
            port_service._workspace(port_repo.stage["stage_id"])
        )
        self.assertEqual(port_view["graph_persistence"], "READ_ONLY_INCOMPATIBLE")

    def test_rebuild_keeps_owner_check(self):
        workspace = self._full_draft_broken_snapshot()
        before = (workspace.snapshot, workspace.workspace_epoch, workspace.dirty)
        with self.assertRaises(HallForbidden):
            self.service.rebuild(
                self.stage_id,
                {
                    "player_uuid": str(uuid.uuid4()),
                    "confirm_discard": True,
                },
            )
        self.assertEqual(
            (workspace.snapshot, workspace.workspace_epoch, workspace.dirty), before
        )

    def test_stale_save_and_rebuild_envelopes_are_rejected(self):
        workspace = self._full_draft_broken_snapshot()
        stale = self._versioned_payload(workspace, workspace_epoch=str(uuid.uuid4()))
        with self.assertRaises(HallConflict):
            self.service.save(self.stage_id, stale)
        stale = self._versioned_payload(workspace, expected_working_revision=-1)
        with self.assertRaises(HallConflict):
            self.service.rebuild(self.stage_id, stale)

    def test_save_holds_stage_lock_against_graph_operation(self):
        repo = BlockingFullSaveRepository()
        repo.types = runtime_types()
        service = StageService(repo)
        stage_id = repo.stage["stage_id"]
        owner = repo.stage["owner_uuid"]
        service.place(stage_id, {
            "player_uuid": owner, "type_key": "START", "x": 11, "y": 65, "z": 21, "orientation": "NORTH"
        })
        workspace = service._workspace(stage_id)
        payload = {
            "player_uuid": owner,
            "workspace_epoch": str(workspace.workspace_epoch),
            "expected_working_revision": workspace.snapshot.working_revision,
        }
        with ThreadPoolExecutor(max_workers=2) as executor:
            saving = executor.submit(service.save, stage_id, payload)
            self.assertTrue(repo.save_entered.wait(2))
            graphing = executor.submit(
                service.apply_graph_operation,
                stage_id,
                payload,
                lambda state: replace_content(state, wire_cells=(straight_cell(),)),
            )
            time.sleep(0.1)
            self.assertFalse(graphing.done())
            repo.release_save.set()
            saving.result(5)
            graphing.result(5)
        self.assertTrue(workspace.dirty)
        self.assertEqual(str(workspace.workspace_epoch), payload["workspace_epoch"])

    def test_save_holds_stage_lock_against_rebuild(self):
        repo = BlockingFullSaveRepository()
        repo.types = runtime_types()
        service = StageService(repo)
        stage_id = repo.stage["stage_id"]
        owner = repo.stage["owner_uuid"]
        service.place(stage_id, {
            "player_uuid": owner, "type_key": "START", "x": 11, "y": 65, "z": 21, "orientation": "NORTH"
        })
        workspace = service._workspace(stage_id)
        payload = {
            "player_uuid": owner,
            "workspace_epoch": str(workspace.workspace_epoch),
            "expected_working_revision": workspace.snapshot.working_revision,
        }
        with ThreadPoolExecutor(max_workers=2) as executor:
            saving = executor.submit(service.save, stage_id, payload)
            self.assertTrue(repo.save_entered.wait(2))
            rebuilding = executor.submit(
                service.rebuild,
                stage_id,
                {"player_uuid": owner, "confirm_discard": True},
            )
            time.sleep(0.1)
            self.assertFalse(rebuilding.done())
            repo.release_save.set()
            saving.result(5)
            rebuilding.result(5)
        self.assertFalse(workspace.dirty)
        self.assertNotEqual(str(workspace.workspace_epoch), payload["workspace_epoch"])


if __name__ == "__main__":
    unittest.main()
