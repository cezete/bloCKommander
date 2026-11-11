"""Domain tests and isolated component integration using the existing fake repository."""
import threading
import unittest
from dataclasses import FrozenInstanceError, replace
from uuid import UUID, uuid4

from hangar.block_editor import (BlockEditorError, BlockEditorService, EditorApplyCommand,
                                EditorTarget, SchemaReference)
from hangar.graph import (CK_CONTROL_PORT_CATALOG, prepare_graph_workspace_update,
                         replace_content, to_graph_workspace, to_stage_snapshot)
from hangar.program import ProgramService, ProgramWorkspaceState
from hangar.stage import StageService
from test_program_service import (FakeProgramRepository, OWNER, PROGRAM, ROOT, OTHER_PROGRAM,
                                  OTHER_ROOT, HALL, RecordingLock, block)
from test_stage_snapshot_adapter import full_snapshot
from graph_validation_fixtures import cross_cell


class EditorFixture(unittest.TestCase):
    def setUp(self):
        self.repo = FakeProgramRepository()
        # The broader Program fixture includes future CLASS/INTERFACE entries;
        # the actual runtime catalog used by Graph has neither type.
        self.repo.types = [t for t in self.repo.types if t["type_key"] not in ("CLASS", "INTERFACE")]
        value = full_snapshot()
        self.repo.snapshots[ROOT] = replace(value, stage_id=ROOT,
            blocks=(*value.blocks, block(ROOT, "program", x=5)),
            edges=tuple(replace(e, stage_id=ROOT) for e in value.edges),
            routes=tuple(replace(r, stage_id=ROOT) for r in value.routes))
        self.stages = StageService(self.repo)
        self.programs = ProgramService(self.repo, self.stages)
        self.editor = BlockEditorService(self.programs)
        self.stage = self.programs.root(PROGRAM)
        self.program = self.programs.load(PROGRAM)
        self.target = EditorTarget(PROGRAM, ROOT, self.stage.snapshot.blocks[0].instance_id)

    def command(self, changes=None, **overrides):
        values = dict(target=self.target, player_uuid=OWNER, request_id=uuid4(),
            schema_ref=SchemaReference("ck.block.START.metadata", 1),
            expected_stage_workspace_epoch=self.stage.workspace_epoch,
            expected_working_revision=self.stage.revision,
            changes={"label": "Edited"} if changes is None else changes)
        values.update(overrides)
        return EditorApplyCommand(**values)

    def assert_error(self, code, operation):
        old = (self.stage.snapshot, self.stage.dirty, self.stage.saved_version, self.program.saved_version)
        with self.assertRaises(BlockEditorError) as caught:
            operation()
        self.assertEqual(code, caught.exception.code)
        self.assertEqual(old, (self.stage.snapshot, self.stage.dirty, self.stage.saved_version,
                               self.program.saved_version))


class BlockEditorTest(EditorFixture):
    def test_get_is_authoritative_detached_and_does_not_mutate(self):
        old = self.stage.snapshot
        result = self.editor.get(self.target, OWNER)
        self.assertIs(old, self.stage.snapshot)
        self.assertFalse(self.stage.dirty)
        self.assertTrue(result.permissions.apply)
        self.assertEqual("Indulás", result.values.label)
        raw = result.as_dict()
        raw["values"]["label"] = "tampered"
        raw["schema"]["fields"].clear()
        self.assertEqual("Indulás", result.as_dict()["values"]["label"])
        with self.assertRaises(FrozenInstanceError):
            result.values.label = "bad"
        self.editor.apply(self.command())
        self.assertEqual("Indulás", result.values.label)

    def test_two_fields_increment_once_and_preserve_graph_identity(self):
        before = self.stage.snapshot
        result = self.editor.apply(self.command({"label": " New ", "description": " Details "}))
        after = self.stage.snapshot
        self.assertTrue(result.modified)
        self.assertEqual(before.working_revision + 1, after.working_revision)
        self.assertTrue(result.editor.version_context.program_dirty)
        self.assertEqual(("New", "Details"), (result.editor.values.label, result.editor.values.description))
        self.assertEqual(before.graph_nodes(), after.graph_nodes())
        self.assertIs(before.edges, after.edges)
        self.assertIs(before.routes, after.routes)
        self.assertIs(before.wire_cells, after.wire_cells)
        self.assertEqual(self.repo.program_save_calls, 0)

    def test_missing_description_is_preserved_and_null_clears(self):
        original = self.stage.snapshot.blocks[0].description
        self.editor.apply(self.command())
        self.assertEqual(original, self.stage.snapshot.blocks[0].description)
        result = self.editor.apply(self.command({"description": None}))
        self.assertIsNone(result.editor.values.description)

    def test_stop_schema_and_values(self):
        stop = self.stage.snapshot.blocks[1]
        target = replace(self.target, instance_id=stop.instance_id)
        result = self.editor.get(target, OWNER)
        self.assertEqual("ck.block.STOP.metadata", result.schema.schema_id)
        applied = self.editor.apply(self.command({"label": "End"}, target=target,
            schema_ref=result.schema_ref))
        self.assertEqual("End", applied.editor.values.label)

    def test_invalid_patch_is_atomic(self):
        self.assert_error("VALIDATION_FAILED", lambda: self.editor.apply(
            self.command({"label": "valid", "description": "x" * 257})))

    def test_noop_clean_and_dirty_keeps_snapshot_and_versions(self):
        for dirty in (False, True):
            self.stage.dirty = dirty
            before = self.stage.snapshot
            result = self.editor.apply(self.command({"label": " Indulás "}))
            self.assertFalse(result.modified)
            self.assertIs(before, self.stage.snapshot)
            self.assertEqual(dirty, result.editor.version_context.stage_dirty)
            self.assertEqual(dirty, result.editor.version_context.program_dirty)
            self.assertEqual(0, result.editor.version_context.program_saved_version)

    def test_stale_epoch_even_when_noop(self):
        self.assert_error("STALE_WORKSPACE_EPOCH", lambda: self.editor.apply(
            self.command({"label": "Indulás"}, expected_stage_workspace_epoch=uuid4())))

    def test_stale_revision_even_when_noop(self):
        old = self.command({"label": "Indulás"})
        self.editor.apply(self.command({"description": "new"}))
        self.assert_error("STALE_WORKING_REVISION", lambda: self.editor.apply(old))

    def test_owner_on_read_and_write(self):
        for action in (lambda: self.editor.get(self.target, uuid4()),
                       lambda: self.editor.apply(self.command(player_uuid=uuid4()))):
            self.assert_error("FORBIDDEN", action)

    def test_stage_membership_and_program_identity(self):
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT)
        for target in (replace(self.target, program_id=OTHER_PROGRAM),
                       replace(self.target, stage_id=OTHER_ROOT),
                       replace(self.target, stage_id=uuid4()),
                       replace(self.target, program_id=uuid4())):
            self.assert_error("STAGE_NOT_FOUND", lambda: self.editor.get(target, OWNER))
            self.assert_error("STAGE_NOT_FOUND", lambda: self.editor.apply(self.command(target=target)))

    def test_deleted_instance_read_and_apply(self):
        target = replace(self.target, instance_id=uuid4())
        self.assert_error("INSTANCE_NOT_FOUND", lambda: self.editor.get(target, OWNER))
        self.assert_error("INSTANCE_NOT_FOUND", lambda: self.editor.apply(self.command(target=target)))

    def test_schema_mismatch_version_and_unsupported_block(self):
        for ref, code in [(SchemaReference("wrong", 1), "SCHEMA_MISMATCH"),
                          (SchemaReference("ck.block.START.metadata", 2), "UNSUPPORTED_SCHEMA_VERSION")]:
            self.assert_error(code, lambda: self.editor.apply(self.command(schema_ref=ref)))
        target = replace(self.target, instance_id=self.stage.snapshot.blocks[2].instance_id)
        self.assert_error("UNSUPPORTED_BLOCK_TYPE", lambda: self.editor.get(target, OWNER))
        self.assert_error("UNSUPPORTED_BLOCK_TYPE", lambda: self.editor.apply(self.command(target=target)))

    def test_unknown_and_empty_changes(self):
        for changes, code in [({}, "EMPTY_CHANGES"), ({"other": "x"}, "UNKNOWN_FIELD")]:
            self.assert_error(code, lambda: self.editor.apply(self.command(changes)))

    def test_incompatible_workspace_on_read_and_apply(self):
        for attribute, value in [("graph_snapshot_version", 99), ("compatible_for_graph_mutation", False)]:
            old = getattr(self.stage, attribute)
            setattr(self.stage, attribute, value)
            self.assert_error("SNAPSHOT_INCOMPATIBLE", lambda: self.editor.get(self.target, OWNER))
            self.assert_error("SNAPSHOT_INCOMPATIBLE", lambda: self.editor.apply(self.command()))
            setattr(self.stage, attribute, old)
        self.stage.snapshot = replace(self.stage.snapshot, port_catalog_version="unknown")
        self.assert_error("SNAPSHOT_INCOMPATIBLE", lambda: self.editor.get(self.target, OWNER))

    def test_reload_required(self):
        self.program.state = ProgramWorkspaceState.RELOAD_REQUIRED
        self.assert_error("RELOAD_REQUIRED", lambda: self.editor.get(self.target, OWNER))
        self.assert_error("RELOAD_REQUIRED", lambda: self.editor.apply(self.command()))

    def test_command_is_detached_and_revision_is_strict(self):
        changes = {"label": "Captured"}
        command = self.command(changes)
        changes["label"] = "mutated"
        self.assertEqual("Captured", self.editor.apply(command).editor.values.label)
        for value in (True, -1, 1.0, "1"):
            with self.assertRaises(BlockEditorError):
                self.command(expected_working_revision=value)
        with self.assertRaises(BlockEditorError):
            SchemaReference(None, 1)
        with self.assertRaises(BlockEditorError):
            EditorTarget(str(PROGRAM), ROOT, uuid4())

    def test_program_then_stage_lock_order(self):
        events = []
        self.program.lock = RecordingLock("program", events)
        self.stage.lock = RecordingLock("stage", events)
        self.editor.get(self.target, OWNER)
        self.assertEqual(["program", "stage"], events)
        events.clear()
        self.editor.apply(self.command())
        self.assertEqual(["program", "stage"], events)

    def test_concurrent_apply_has_one_winner(self):
        commands = [self.command({"label": name}) for name in ("first", "second")]
        barrier = threading.Barrier(2)
        outcomes = []
        def run(command):
            barrier.wait(timeout=5)
            try:
                outcomes.append(self.editor.apply(command).modified)
            except BlockEditorError as error:
                outcomes.append(error.code)
        threads = [threading.Thread(target=run, args=(command,)) for command in commands]
        for thread in threads: thread.start()
        for thread in threads:
            thread.join(timeout=5)
            self.assertFalse(thread.is_alive())
        self.assertCountEqual([True, "STALE_WORKING_REVISION"], outcomes)
        self.assertEqual(1, self.stage.revision)


class BlockEditorComponentIntegrationTest(EditorFixture):
    """Real services/adapters + an isolated in-memory repository, not PostgreSQL."""

    def reload_editor(self):
        stages = StageService(self.repo)
        return BlockEditorService(ProgramService(self.repo, stages))

    def test_apply_save_repository_reload_and_restart_epoch(self):
        old = self.editor.get(self.target, OWNER)
        self.editor.apply(self.command({"label": "Saved", "description": "😀 text"}))
        receipt = self.programs.save(PROGRAM, str(OWNER))
        self.assertTrue(receipt.changed)
        restarted = self.reload_editor()
        loaded = restarted.get(self.target, OWNER)
        self.assertEqual(("Saved", "😀 text"), (loaded.values.label, loaded.values.description))
        self.assertNotEqual(old.version_context.stage_workspace_epoch, loaded.version_context.stage_workspace_epoch)
        self.assertFalse(loaded.version_context.program_dirty)
        with self.assertRaises(BlockEditorError) as caught:
            restarted.apply(self.command(expected_stage_workspace_epoch=old.version_context.stage_workspace_epoch))
        self.assertEqual("STALE_WORKSPACE_EPOCH", caught.exception.code)

    def test_unsaved_change_not_visible_to_reloaded_service(self):
        old = self.editor.get(self.target, OWNER).values
        self.editor.apply(self.command())
        self.assertEqual(old, self.reload_editor().get(self.target, OWNER).values)
        self.assertEqual(0, self.repo.program_save_calls)

    def test_save_failure_preserves_workspace_and_old_repository(self):
        self.editor.apply(self.command())
        before = self.stage.snapshot
        self.repo.fail_save = True
        with self.assertRaises(RuntimeError):
            self.programs.save(PROGRAM, str(OWNER))
        self.assertIs(before, self.stage.snapshot)
        self.assertTrue(self.stage.dirty)
        self.assertEqual("Indulás", self.reload_editor().get(self.target, OWNER).values.label)

    def test_legacy_coercion_and_old_editor_cas(self):
        command = self.command()
        self.stages.update(str(ROOT), str(self.target.instance_id),
            {"player_uuid": str(OWNER), "label": 42, "description": None})
        result = self.editor.get(self.target, OWNER)
        self.assertEqual(("42", "None"), (result.values.label, result.values.description))
        self.assert_error("STALE_WORKING_REVISION", lambda: self.editor.apply(command))

    def test_graph_adapter_and_persistence_keep_all_graph_data(self):
        before = self.stage.snapshot
        self.editor.apply(self.command())
        state = to_graph_workspace(self.stage, CK_CONTROL_PORT_CATALOG,
            known_block_types={t["type_key"] for t in self.repo.types}).state
        changed = replace_content(state, wire_cells=(cross_cell(),))
        self.stage.snapshot = to_stage_snapshot(self.stage.snapshot,
            prepare_graph_workspace_update(state, changed, operation_accepted=True))
        self.assertEqual("Edited", self.stage.snapshot.blocks[0].label)
        expected = self.stage.snapshot
        self.programs.save(PROGRAM, str(OWNER))
        restarted = self.reload_editor()
        restarted.get(self.target, OWNER)
        actual = restarted.programs.root(PROGRAM).snapshot
        self.assertEqual(expected.blocks, actual.blocks)
        self.assertEqual((before.edges, before.routes), (actual.edges, actual.routes))
        self.assertEqual(expected.wire_cells, actual.wire_cells)

    def test_shared_workspace_from_two_hall_mounts(self):
        other_hall = uuid4()
        self.repo.mounts[other_hall] = PROGRAM
        first = self.programs.for_hall(HALL)
        second = self.programs.for_hall(other_hall)
        self.assertIs(first, second)
        self.editor.apply(self.command())
        self.assertEqual("Edited", self.editor.get(self.target, OWNER).values.label)
        self.assertTrue(second.dirty)

    def test_unsaved_child_participates_in_program_save(self):
        child = self.programs.ensure_child(PROGRAM, ROOT, self.stage.snapshot.blocks[2].instance_id, str(OWNER))
        self.stages.place(str(child.snapshot.stage_id), {"player_uuid": str(OWNER),
            "type_key": "START", "x": 0, "y": 0, "z": 0, "orientation": "NORTH"})
        target = EditorTarget(PROGRAM, child.snapshot.stage_id, child.snapshot.blocks[0].instance_id)
        current = self.editor.get(target, OWNER)
        command = self.command({"label": "child saved"}, target=target,
            expected_stage_workspace_epoch=current.version_context.stage_workspace_epoch,
            expected_working_revision=current.version_context.working_revision)
        self.editor.apply(command)
        self.programs.save(PROGRAM, str(OWNER))
        self.assertEqual("child saved", self.reload_editor().get(target, OWNER).values.label)


if __name__ == "__main__":
    unittest.main()
