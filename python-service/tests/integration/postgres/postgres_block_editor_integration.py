"""Editor domain against the existing disposable, identity-checked B2-T PostgreSQL.

Run only through run-tests.ps1 -Suites block_editor. No production/default DSN.
"""
import unittest
from dataclasses import replace
from unittest.mock import patch
from uuid import uuid4

from hangar.block_editor import (BlockEditorError, BlockEditorService, EditorApplyCommand,
                                EditorTarget)
from hangar.graph import (CK_CONTROL_PORT_CATALOG, to_graph_workspace,
                         to_stage_snapshot, prepare_graph_workspace_update, replace_content)
from hangar.program import ProgramService
from hangar.stage import StageService
from postgres_graph_integration import (OWNER_ID, PROGRAM_ID, STAGE_ID,
    assert_database_identity, create_stage, repository, reset_schema)
from postgres_program_save_integration import stage_snapshot, stable_id


class BlockEditorPostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        assert_database_identity()  # Fail closed before the destructive test-schema reset.
        reset_schema()
        self.repo = repository()
        self.repo.migrate()
        create_stage()
        self.repo.save_full_stage_snapshot(str(STAGE_ID), 0,
            stage_snapshot(STAGE_ID, "root-v1", with_program_block=True))
        self.programs = ProgramService(self.repo, StageService(self.repo))
        self.editor = BlockEditorService(self.programs)
        self.target = EditorTarget(PROGRAM_ID, STAGE_ID, stable_id(STAGE_ID, "start"))

    def command(self, changes):
        current = self.editor.get(self.target, OWNER_ID)
        return EditorApplyCommand(self.target, OWNER_ID, uuid4(), current.schema_ref,
            current.version_context.stage_workspace_epoch,
            current.version_context.working_revision, changes)

    def restarted(self):
        repo = repository()
        return BlockEditorService(ProgramService(repo, StageService(repo)))

    def test_apply_save_reload_preserves_metadata_and_graph(self):
        before = self.programs.root(PROGRAM_ID).snapshot
        self.editor.apply(self.command({"label": "😀 saved", "description": "e\u0301 text"}))
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        fresh = self.restarted()
        result = fresh.get(self.target, OWNER_ID)
        self.assertEqual(("😀 saved", "e\u0301 text"), (result.values.label, result.values.description))
        after = fresh.programs.root(PROGRAM_ID).snapshot
        self.assertEqual(before.graph_nodes(), after.graph_nodes())
        self.assertEqual((before.edges, before.routes, before.wire_cells),
                         (after.edges, after.routes, after.wire_cells))
        # Exercise the real adapter after persistence without losing editor metadata.
        stage = fresh.programs.root(PROGRAM_ID)
        state = to_graph_workspace(stage, CK_CONTROL_PORT_CATALOG,
            known_block_types={t["type_key"] for t in self.repo.list_block_types()}).state
        changed = replace_content(state, nodes=state.nodes)
        rebuilt = to_stage_snapshot(stage.snapshot,
            prepare_graph_workspace_update(state, changed, operation_accepted=True))
        self.assertEqual(after.blocks, rebuilt.blocks)

    def test_unsaved_edit_is_not_persisted_and_old_epoch_is_rejected(self):
        command = self.command({"label": "unsaved"})
        self.editor.apply(command)
        fresh = self.restarted()
        self.assertEqual("root-v1", fresh.get(self.target, OWNER_ID).values.label)
        with self.assertRaises(BlockEditorError) as caught:
            fresh.apply(command)
        self.assertEqual("STALE_WORKSPACE_EPOCH", caught.exception.code)

    def test_failed_save_keeps_workspace_and_old_persisted_values(self):
        self.editor.apply(self.command({"label": "dirty"}))
        before = self.programs.root(PROGRAM_ID).snapshot
        with patch.object(self.repo, "save_program_snapshot", side_effect=RuntimeError("test save failure")):
            with self.assertRaises(RuntimeError):
                self.programs.save(PROGRAM_ID, str(OWNER_ID))
        self.assertIs(before, self.programs.root(PROGRAM_ID).snapshot)
        self.assertTrue(self.editor.get(self.target, OWNER_ID).version_context.program_dirty)
        self.assertEqual("root-v1", self.restarted().get(self.target, OWNER_ID).values.label)

    def test_legacy_coercion_and_editor_cas(self):
        stale = self.command({"label": "stale"})
        self.programs.stages.update(str(STAGE_ID), str(self.target.instance_id),
            {"player_uuid": str(OWNER_ID), "label": 42, "description": None})
        result = self.editor.get(self.target, OWNER_ID)
        self.assertEqual(("42", "None"), (result.values.label, result.values.description))
        with self.assertRaises(BlockEditorError) as caught:
            self.editor.apply(stale)
        self.assertEqual("STALE_WORKING_REVISION", caught.exception.code)

    def test_wrong_stage_and_unsaved_child_are_program_scoped(self):
        with self.assertRaises(BlockEditorError) as caught:
            self.editor.get(replace(self.target, stage_id=uuid4()), OWNER_ID)
        self.assertEqual("STAGE_NOT_FOUND", caught.exception.code)
        child = self.programs.ensure_child(PROGRAM_ID, STAGE_ID,
            stable_id(STAGE_ID, "program"), str(OWNER_ID))
        self.programs.stages.place(str(child.snapshot.stage_id), {
            "player_uuid": str(OWNER_ID), "type_key": "STOP", "x": 0, "y": 0, "z": 0})
        self.target = EditorTarget(PROGRAM_ID, child.snapshot.stage_id,
            child.snapshot.blocks[0].instance_id)
        self.editor.apply(self.command({"label": "child"}))
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        self.assertEqual("child", self.restarted().get(self.target, OWNER_ID).values.label)


if __name__ == "__main__":
    assert_database_identity()
    unittest.main()
