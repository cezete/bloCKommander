"""C1 saved-domain tests; run only through the identity-guarded values suite."""
import logging
import unittest
from uuid import uuid4
from dataclasses import replace
import psycopg2
from hangar.program import ProgramService
from hangar.stage import StageService
from hangar.declaration_service import DeclarationService, DeclarationTarget
from hangar.declarations import VariableType, Mutability
from hangar.graph import GridPos, CardinalDirection, GraphEdge, EdgeKind
from hangar.graph.placement import WirePlacementIntent
from hangar.value_service import ValueService
from hangar.repository import SnapshotCompatibilityError
from postgres_graph_integration import (OWNER_ID, PROGRAM_ID, STAGE_ID, assert_database_identity,
                                        connect, create_stage, repository, reset_schema)
from postgres_program_save_integration import stage_snapshot


class ValuePostgreSQLTest(unittest.TestCase):
    def setUp(self):
        assert_database_identity(); reset_schema()
        self.repo = repository(); self.repo.migrate(); create_stage()
        initial = stage_snapshot(STAGE_ID, 'Root', with_program_block=True)
        initial = replace(initial, blocks=tuple(replace(b, orientation=CardinalDirection.EAST) for b in initial.blocks))
        self.repo.save_full_stage_snapshot(str(STAGE_ID), 0, initial)
        self.programs = ProgramService(self.repo, StageService(self.repo))
        self.declarations = DeclarationService(self.programs)
        self.values = ValueService(self.programs, declarations=self.declarations)
        self.root = DeclarationTarget(PROGRAM_ID, STAGE_ID)
        stage = self.programs.root(PROGRAM_ID)
        self.x = self.declarations.create(self.root, OWNER_ID, stage.workspace_epoch, stage.revision,
            name='x', variable_type=VariableType.INTEGER, mutability=Mutability.IMMUTABLE).declaration
        self.let = self.values.place_block(self.root, OWNER_ID, self.basis(self.root), 'LET', GridPos(8, 0, 1))
        self.values.set_let_target(self.root, OWNER_ID, self.basis(self.root), self.let, self.x.declaration_id)
        self.rhs = self.values.ensure_rhs(self.root, OWNER_ID, self.basis(self.root), self.let)

    def basis(self, target): return self.values.view(target, OWNER_ID).basis

    def sql(self, statement, args=()):
        with connect() as conn, conn.cursor() as cur:
            cur.execute(statement, args)
            return cur.fetchall() if cur.description else None

    def restart(self): return ValueService(ProgramService(self.repo, StageService(self.repo)))

    def build(self):
        positions = [('LITERAL', GridPos(6, 0, 8), CardinalDirection.EAST),
                     ('LITERAL', GridPos(10, 0, 8), CardinalDirection.WEST),
                     ('OPERATOR', GridPos(8, 0, 8), CardinalDirection.NORTH),
                     ('RESULT', GridPos(8, 0, 6), CardinalDirection.NORTH)]
        ids = [self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), kind, pos, orientation=direction)
               for kind, pos, direction in positions]
        for bid, number in zip(ids, (2, 3)):
            self.values.set_literal(self.rhs, OWNER_ID, self.basis(self.rhs), bid, number)
        self.values.set_operator(self.rhs, OWNER_ID, self.basis(self.rhs), ids[2], 'ADD')
        for pos in (GridPos(7, 0, 8), GridPos(9, 0, 8), GridPos(8, 0, 7)):
            self.values.place_wire(self.rhs, OWNER_ID, self.basis(self.rhs), pos, WirePlacementIntent.AUTO)
        self.assertEqual((), self.values.view(self.rhs, OWNER_ID).snapshot.edges)
        bound = self.values.bind(self.rhs, OWNER_ID, self.basis(self.rhs))
        self.assertTrue(bound.accepted, bound.diagnostics)
        self.assertEqual(3, len(bound.state.edges))
        return ids

    def test_empty_value_stage_atomic_save_and_restart(self):
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        view = self.restart().view(self.rhs, OWNER_ID)
        self.assertEqual((), view.snapshot.blocks)
        self.assertEqual(1, view.snapshot.value_schema_version)
        self.assertEqual('MISSING_RESULT', view.analysis.diagnostics[0].code)
        self.assertEqual([('LET_RHS', 'VALUE_BODY')], self.sql(
            'SELECT owner_role,child_stage_kind FROM blockcommander.program_stage_links'))
        self.assertFalse(self.programs.save(PROGRAM_ID, str(OWNER_ID)).changed)

    def test_complete_value_graph_real_roundtrip_rebuild_and_control_regression(self):
        self.build(); self.programs.save(PROGRAM_ID, str(OWNER_ID))
        fresh = self.restart(); view = fresh.view(self.rhs, OWNER_ID)
        self.assertTrue(view.analysis.valid, view.analysis.diagnostics)
        self.assertEqual('2 + 3', view.analysis.expression)
        self.assertEqual({'VALUE'}, {e.edge_kind.value for e in view.snapshot.edges})
        self.assertEqual([('CONTROL',)], self.sql('SELECT edge_kind FROM blockcommander.graph_edges WHERE stage_id=%s', (str(STAGE_ID),)))
        child = fresh.programs.stage(PROGRAM_ID, self.rhs.stage_id)
        fresh.programs.stages.rebuild(str(self.rhs.stage_id), {'player_uuid': str(OWNER_ID)})
        self.assertEqual('2 + 3', fresh.view(self.rhs, OWNER_ID).analysis.expression)
        self.assertEqual('VALUE_BODY', child.stage['stage_kind'])

    def test_semantically_broken_ref_saved_without_losing_uuid(self):
        ref = self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), 'REF', GridPos(1, 0, 1))
        self.values.set_ref(self.rhs, OWNER_ID, self.basis(self.rhs), ref, self.x.declaration_id)
        root = self.programs.root(PROGRAM_ID)
        self.declarations.delete(self.root, OWNER_ID, root.workspace_epoch, root.revision, self.x.declaration_id)
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        view = self.restart().view(self.rhs, OWNER_ID)
        self.assertIn('BROKEN_REF', {d.code for d in view.analysis.diagnostics})
        self.assertEqual([(str(self.x.declaration_id),)], self.sql(
            "SELECT semantic_config->>'target_id' FROM blockcommander.stage_blocks WHERE instance_id=%s", (str(ref),)))

    def test_old_writers_cannot_delete_content_or_hierarchy(self):
        self.build(); self.programs.save(PROGRAM_ID, str(OWNER_ID))
        before = self.sql('SELECT stage_id,saved_version FROM blockcommander.stages ORDER BY stage_id')
        statements = [
            ('DELETE FROM blockcommander.stage_blocks WHERE stage_id=%s', (str(self.rhs.stage_id),)),
            ('DELETE FROM blockcommander.program_stage_links WHERE program_id=%s', (str(PROGRAM_ID),)),
            ('UPDATE blockcommander.stages SET saved_version=saved_version+1 WHERE stage_id=%s', (str(self.rhs.stage_id),)),
            ('UPDATE blockcommander.programs SET saved_version=saved_version+1 WHERE program_id=%s', (str(PROGRAM_ID),))]
        for query, args in statements:
            with self.assertRaises(psycopg2.Error): self.sql(query, args)
        self.assertEqual(before, self.sql('SELECT stage_id,saved_version FROM blockcommander.stages ORDER BY stage_id'))

    def test_technical_failure_rolls_back_entire_new_tree(self):
        self.build()
        # Fail after content replacement, while committing the hierarchy.
        self.sql("""CREATE FUNCTION blockcommander.c1_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'isolated C1 injected failure'; END; $$;
            CREATE TRIGGER c1_test_fail BEFORE INSERT ON blockcommander.program_stage_links
            FOR EACH ROW EXECUTE FUNCTION blockcommander.c1_test_fail()""")
        with self.assertRaises(psycopg2.Error): self.programs.save(PROGRAM_ID, str(OWNER_ID))
        self.assertEqual([(0,)], self.sql('SELECT count(*) FROM blockcommander.stages WHERE stage_id=%s', (str(self.rhs.stage_id),)))
        self.assertEqual([(0,)], self.sql("SELECT count(*) FROM blockcommander.stage_blocks WHERE type_key='LET'"))
        self.assertTrue(self.programs.load(PROGRAM_ID).dirty)

    def test_cycle_multiple_results_and_missing_operands_still_roundtrip(self):
        ids = []
        for index in (1, 2):
            bid = self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), 'OPERATOR', GridPos(index, 0, 1))
            self.values.set_operator(self.rhs, OWNER_ID, self.basis(self.rhs), bid, 'ADD')
            ids.append(bid)
        for index in (3, 4):
            self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), 'RESULT', GridPos(index, 0, 1))
        stage = self.programs.stage(PROGRAM_ID, self.rhs.stage_id)
        edges = tuple(GraphEdge(uuid4(), self.rhs.stage_id, source, 'out', target, 'lhs', EdgeKind.VALUE)
                      for source, target in (ids, ids[::-1]))
        self.values._commit(stage, edges=edges)
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        codes = {d.code for d in self.restart().view(self.rhs, OWNER_ID).analysis.diagnostics}
        self.assertTrue({'VALUE_CYCLE', 'MULTIPLE_RESULTS', 'MISSING_OPERAND'} <= codes)

    def test_value_format_downgrade_is_forbidden_even_for_current_writer(self):
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        with self.assertRaises(psycopg2.Error):
            self.sql("SELECT set_config('blockcommander.value_writer','1',true); "
                     "UPDATE blockcommander.stages SET value_schema_version=0 WHERE stage_id=%s", (str(self.rhs.stage_id),))
        self.assertEqual([(1,)], self.sql('SELECT value_schema_version FROM blockcommander.stages WHERE stage_id=%s', (str(self.rhs.stage_id),)))

    def test_unknown_format_load_and_save_fail_closed(self):
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        # Simulate a future writer in this disposable schema, bypassing only its guard.
        self.sql("ALTER TABLE blockcommander.stages DISABLE TRIGGER value_writer_guard; "
                 "ALTER TABLE blockcommander.stages DISABLE TRIGGER value_format_consistency")
        self.sql('UPDATE blockcommander.stages SET value_schema_version=2 WHERE stage_id=%s', (str(self.rhs.stage_id),))
        with self.assertRaises(SnapshotCompatibilityError): self.repo.load_full_stage_snapshot(str(self.rhs.stage_id))
        with self.assertRaises(SnapshotCompatibilityError): self.programs.save(PROGRAM_ID, str(OWNER_ID))


if __name__ == '__main__':
    assert_database_identity(); logging.disable(logging.CRITICAL); unittest.main()
