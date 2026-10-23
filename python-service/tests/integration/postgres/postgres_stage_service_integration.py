"""Fail-closed StageService → PostgreSQL → StageService CK-03/B3 tests."""
from __future__ import annotations

import unittest

from hangar.graph import ProgramValidationStatus, RouteStatus
from hangar.repository import HallRepository, StageVersionConflict
from hangar.stage import StageService
from postgres_graph_integration import (
    OWNER_ID,
    STAGE_ID,
    assert_database_identity,
    connect,
    create_stage,
    full_snapshot,
    repository,
    reset_schema,
)


class StageServicePostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema()
        repository().migrate()
        create_stage()
        repository().save_full_stage_snapshot(str(STAGE_ID), 0, full_snapshot())
        self.service = StageService(HallRepository(connect=connect))
        self.owner = str(OWNER_ID)
        self.workspace = self.service._workspace(str(STAGE_ID))

    def versioned_payload(self, **extra):
        result = {
            "player_uuid": self.owner,
            "workspace_epoch": str(self.workspace.workspace_epoch),
            "expected_working_revision": self.workspace.snapshot.working_revision,
        }
        result.update(extra)
        return result

    def test_full_service_save_and_rebuild_round_trip(self):
        source_ids = (
            tuple(item.instance_id for item in self.workspace.snapshot.blocks),
            tuple(item.edge_id for item in self.workspace.snapshot.edges),
            tuple(item.route_id for item in self.workspace.snapshot.routes),
        )
        self.assertIsNotNone(self.workspace.snapshot.last_graph_validation)
        start = self.workspace.snapshot.blocks[0]
        self.service.update(
            str(STAGE_ID),
            str(start.instance_id),
            {"player_uuid": self.owner, "label": "StageService SAVE"},
        )
        save_revision = self.workspace.snapshot.working_revision
        saved = self.service.save(str(STAGE_ID), self.versioned_payload())
        self.assertEqual(saved["saved_version"], 2)
        self.assertEqual(saved["working_revision"], save_revision)
        self.assertFalse(saved["dirty"])

        self.service.update(
            str(STAGE_ID),
            str(start.instance_id),
            {"player_uuid": self.owner, "label": "Discard this"},
        )
        dirty_revision = self.workspace.snapshot.working_revision
        old_epoch = self.workspace.workspace_epoch
        rebuilt = self.service.rebuild(
            str(STAGE_ID), self.versioned_payload(confirm_discard=True)
        )
        self.assertEqual(rebuilt["saved_version"], 2)
        self.assertEqual(rebuilt["working_revision"], dirty_revision + 1)
        self.assertNotEqual(self.workspace.workspace_epoch, old_epoch)
        self.assertFalse(rebuilt["dirty"])
        self.assertEqual(rebuilt["graph_persistence"], "FULL")
        self.assertEqual(self.workspace.snapshot.blocks[0].label, "StageService SAVE")
        self.assertEqual(
            (
                tuple(item.instance_id for item in self.workspace.snapshot.blocks),
                tuple(item.edge_id for item in self.workspace.snapshot.edges),
                tuple(item.route_id for item in self.workspace.snapshot.routes),
            ),
            source_ids,
        )
        self.assertEqual(len(self.workspace.snapshot.wire_cells), 3)
        self.assertIn(RouteStatus.BROKEN, {item.status for item in self.workspace.snapshot.routes})
        self.assertEqual(
            self.workspace.snapshot.program_validation_status,
            ProgramValidationStatus.NOT_CHECKED,
        )
        self.assertIsNotNone(self.workspace.snapshot.last_graph_validation)

        fresh_service = StageService(HallRepository(connect=connect))
        fresh = fresh_service._workspace(str(STAGE_ID))
        self.assertEqual(fresh.saved_version, 2)
        self.assertEqual(fresh.snapshot.blocks[0].label, "StageService SAVE")
        self.assertEqual(
            tuple(item.edge_id for item in fresh.snapshot.edges), source_ids[1]
        )

    def test_real_database_cas_conflict_keeps_workspace_unchanged(self):
        start = self.workspace.snapshot.blocks[0]
        self.service.update(
            str(STAGE_ID),
            str(start.instance_id),
            {"player_uuid": self.owner, "label": "LOCAL LOSER"},
        )
        before = (
            self.workspace.snapshot,
            self.workspace.saved_version,
            self.workspace.dirty,
            self.workspace.workspace_epoch,
        )
        repository().save_full_stage_snapshot(
            str(STAGE_ID), 1, full_snapshot(label="EXTERNAL WINNER")
        )
        with self.assertRaises(StageVersionConflict):
            self.service.save(str(STAGE_ID), self.versioned_payload())
        self.assertEqual(
            (
                self.workspace.snapshot,
                self.workspace.saved_version,
                self.workspace.dirty,
                self.workspace.workspace_epoch,
            ),
            before,
        )
        persisted = repository().load_full_stage_snapshot(str(STAGE_ID))
        self.assertEqual(persisted.saved_version, 2)
        self.assertEqual(persisted.snapshot.blocks[0].label, "EXTERNAL WINNER")


if __name__ == "__main__":
    assert_database_identity()
    unittest.main()
