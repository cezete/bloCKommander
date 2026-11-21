"""C1 saved-domain tests; run only through the identity-guarded values suite."""
import logging
import unittest
from uuid import uuid4
from dataclasses import replace
import psycopg2
from ck_webhook import create_app
from hangar.program import ProgramService
from hangar.stage import StageService
from hangar.declaration_service import DeclarationService, DeclarationTarget
from hangar.declarations import VariableType, Mutability
from hangar.graph import GridPos, CardinalDirection, GraphEdge, EdgeKind
from hangar.graph.placement import WirePlacementIntent
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.value_http import API_VERSION
from hangar.value_service import ValueService
from hangar.repository import SnapshotCompatibilityError
from hangar.transport_context import CONTEXT_HEADERS
from postgres_graph_integration import (HALL_ID, OWNER_ID, PROGRAM_ID, STAGE_ID, assert_database_identity,
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
        result = self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), 'RESULT', GridPos(1, 0, 3))
        stage = self.programs.stage(PROGRAM_ID, self.rhs.stage_id)
        self.values._commit(stage, edges=(GraphEdge(uuid4(), self.rhs.stage_id, ref, 'out', result, 'in', EdgeKind.VALUE),))
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

    def test_static_zero_division_saves_and_reloads_as_semantic_error(self):
        ids = self.build()
        self.values.set_literal(self.rhs, OWNER_ID, self.basis(self.rhs), ids[1], 0)
        self.values.set_operator(self.rhs, OWNER_ID, self.basis(self.rhs), ids[2], 'DIVIDE')
        self.assertIn('DIVISION_BY_ZERO', {d.code for d in self.values.view(self.rhs, OWNER_ID).analysis.diagnostics})
        receipt = self.programs.save(PROGRAM_ID, str(OWNER_ID))
        restored = self.restart().view(self.rhs, OWNER_ID).analysis
        self.assertTrue(receipt.changed)
        self.assertIn('DIVISION_BY_ZERO', {d.code for d in restored.diagnostics})
        self.assertFalse(restored.valid); self.assertFalse(restored.result_valid)
        self.assertFalse(self.programs.load(PROGRAM_ID).dirty)

    def test_bound_type_mismatch_saves_declaration_and_expression_together(self):
        ids = self.build()
        root = self.programs.root(PROGRAM_ID)
        text = self.declarations.create(self.root, OWNER_ID, root.workspace_epoch, root.revision,
            name='text', variable_type=VariableType.STRING, mutability=Mutability.MUTABLE).declaration
        stage = self.programs.stage(PROGRAM_ID, self.rhs.stage_id)
        self.programs.stages.delete(str(self.rhs.stage_id), str(ids[1]), {
            'player_uuid': str(OWNER_ID), 'workspace_epoch': str(stage.workspace_epoch),
            'expected_working_revision': stage.revision})
        ref = self.values.place_block(self.rhs, OWNER_ID, self.basis(self.rhs), 'REF',
                                     GridPos(10, 0, 8), orientation=CardinalDirection.WEST)
        self.values.set_ref(self.rhs, OWNER_ID, self.basis(self.rhs), ref, text.declaration_id)
        self.assertTrue(self.values.bind(self.rhs, OWNER_ID, self.basis(self.rhs)).accepted)
        self.assertIn('TYPE_MISMATCH', {d.code for d in self.values.view(self.rhs, OWNER_ID).analysis.diagnostics})
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        restored = self.restart().view(self.rhs, OWNER_ID).analysis
        self.assertIn('TYPE_MISMATCH', {d.code for d in restored.diagnostics})
        self.assertFalse(restored.result_valid)
        self.assertFalse(self.programs.load(PROGRAM_ID).dirty)
        self.assertEqual([(str(text.declaration_id),)], self.sql(
            "SELECT semantic_config->>'target_id' FROM blockcommander.stage_blocks WHERE instance_id=%s", (str(ref),)))

    def test_unknown_format_load_and_save_fail_closed(self):
        self.programs.save(PROGRAM_ID, str(OWNER_ID))
        # Simulate a future writer in this disposable schema, bypassing only its guard.
        self.sql("ALTER TABLE blockcommander.stages DISABLE TRIGGER value_writer_guard; "
                 "ALTER TABLE blockcommander.stages DISABLE TRIGGER value_format_consistency")
        self.sql('UPDATE blockcommander.stages SET value_schema_version=2 WHERE stage_id=%s', (str(self.rhs.stage_id),))
        with self.assertRaises(SnapshotCompatibilityError): self.repo.load_full_stage_snapshot(str(self.rhs.stage_id))
        with self.assertRaises(SnapshotCompatibilityError): self.programs.save(PROGRAM_ID, str(OWNER_ID))


class ValueHttpPostgreSQLTest(unittest.TestCase):
    def setUp(self):
        assert_database_identity()
        reset_schema()
        self.repo = repository()
        self.repo.migrate()
        create_stage()
        initial = replace(stage_snapshot(STAGE_ID, "Root"), blocks=(), edges=(), routes=(), wire_cells=())
        self.repo.save_full_stage_snapshot(str(STAGE_ID), 0, initial)
        self.client = self.new_client()
        self.program_url = f"/api/v1/programs/{PROGRAM_ID}"
        self.stage_url = f"{self.program_url}/stages/{STAGE_ID}"
        self.legacy_url = f"/api/v1/stages/{STAGE_ID}"
        mount = self.repo.resolve_working_copy_context(HALL_ID)
        self.headers = {GRAPH_SECRET_HEADER: "isolated-value-postgres",
                        **{CONTEXT_HEADERS[key]: value for key, value in mount.as_dict().items()}}

    def new_client(self):
        return create_app(repository=self.repo, migrate=False,
                          graph_shared_secret="isolated-value-postgres").test_client()

    def call(self, method, path, status=200, body=None):
        result = self.client.open(path, method=method, headers=self.headers, json=body)
        self.assertEqual(status, result.status_code, result.get_data(as_text=True))
        return result.get_json()

    def read(self, path):
        return self.call("GET", f"{path}?player_uuid={OWNER_ID}")

    def command(self, version=API_VERSION, **fields):
        page = self.read(self.stage_url + "/symbols")
        return dict(api_version=version, player_uuid=str(OWNER_ID), request_id=str(uuid4()),
                    expected_stage_workspace_epoch=page["version_context"]["stage_workspace_epoch"],
                    expected_working_revision=page["version_context"]["working_revision"],
                    expected_resolution_basis=page["resolution_basis_digest"], **fields)

    def create_let_and_child(self):
        declaration = self.call("POST", self.stage_url + "/declarations", 201,
            self.command("ck-declarations-v1", declaration=dict(name="counter", type_key="INTEGER", mutability="IMMUTABLE")))
        declaration_id = declaration["declaration"]["declaration_id"]
        let = self.call("POST", self.legacy_url + "/blocks", 201,
            self.command(type_key="LET", coordinate_space="STAGE_RELATIVE", x=4, y=0, z=5, orientation="EAST"))["let"]
        self.let_url = f'{self.stage_url}/blocks/{let["instance_id"]}/value'
        self.call("PATCH", self.let_url, body=self.command(target_id=declaration_id))
        ensured = self.call("POST", self.stage_url + "/children/ensure", 201,
            self.command(owner_role="LET_RHS", program_block_instance_id=let["instance_id"]))
        return let["instance_id"], declaration_id, ensured["child"]["stage_id"]

    def graph_command(self, path, operation, payload):
        state = self.read(self.legacy_url + "/graph")["state"]
        return self.call("POST", self.legacy_url + "/graph" + path, body=dict(
            schema_version=1, request_id=str(uuid4()), player_uuid=str(OWNER_ID),
            stage_id=str(STAGE_ID), workspace_epoch=state["workspace_epoch"],
            expected_working_revision=state["working_revision"], operation=operation, payload=payload))

    def save(self, status=200):
        return self.call("POST", self.program_url + "/save", status,
                         {"player_uuid": str(OWNER_ID), "expected_saved_version": 0})

    def test_http_save_reload_rebuild_and_control_route_round_trip(self):
        let, target, child = self.create_let_and_child()
        for kind, x in (("START", 2), ("STOP", 6)):
            self.call("POST", self.legacy_url + "/blocks", 201, dict(
                player_uuid=str(OWNER_ID), type_key=kind, coordinate_space="STAGE_RELATIVE",
                x=x, y=0, z=5, orientation="EAST"))
        for x in (3, 5):
            self.graph_command("/wire/place", "WIRE_PLACE_INTENT",
                               {"position": {"x": x, "y": 0, "z": 5}, "intent": "AUTO"})
        bound = self.graph_command("/bind", "BIND_EDGES", {})["state"]
        self.assertEqual({"CONTROL"}, {edge["edge_kind"] for edge in bound["edges"]})
        self.assertEqual(2, len(bound["edges"]))
        self.assertEqual(2, len(bound["routes"]))
        self.assertEqual(2, len(bound["wire_cells"]))
        receipt = self.save()["receipt"]
        self.assertEqual({str(STAGE_ID), child}, {stage["stage_id"] for stage in receipt["stages"]})

        # New Flask app means a new WH workspace with the same persisted database.
        self.client = self.new_client()
        restored = self.read(self.let_url)
        self.assertEqual(let, restored["let"]["instance_id"])
        self.assertEqual(target, restored["let"]["target_id"])
        self.assertEqual(child, restored["let"]["rhs_link"]["child_stage_id"])
        self.assertNotEqual(bound["workspace_epoch"], restored["stage_workspace_epoch"])
        self.assertFalse(restored["aggregate_dirty"])
        child_graph = self.read(f"/api/v1/stages/{child}/graph")
        self.assertEqual("VALUE_BODY", child_graph["state"]["stage_kind"])
        self.assertEqual([], child_graph["state"]["nodes"])
        self.assertEqual([], child_graph["state"]["edges"])
        before = self.read(self.legacy_url + "/graph")["state"]
        self.call("POST", self.legacy_url + "/rebuild", body=dict(
            player_uuid=str(OWNER_ID), workspace_epoch=before["workspace_epoch"],
            expected_working_revision=before["working_revision"]))
        rebuilt = self.read(self.legacy_url + "/graph")["state"]
        self.assertNotEqual(before["workspace_epoch"], rebuilt["workspace_epoch"])
        self.assertTrue(rebuilt["compatible_for_graph_mutation"])
        for field in ("edges", "routes", "wire_cells"):
            self.assertEqual(bound[field], rebuilt[field])
        removed = self.graph_command("/wire", "WIRE_LAYOUT",
            {"changes": [{"position": {"x": 3, "y": 0, "z": 5}, "cell": None}]})["state"]
        self.assertEqual(1, len(removed["wire_cells"]))
        deleted = self.call("POST", f"{self.legacy_url}/blocks/{let}/delete", body=self.command())
        self.assertEqual("RETAINED", deleted["link"]["state"])
        self.call("POST", self.program_url + "/save", body={
            "player_uuid": str(OWNER_ID), "expected_saved_version": receipt["saved_version"],
        })
        self.client = self.new_client()
        retained = self.call("GET", self.program_url + f"/stages/{child}")["stage"]
        self.assertEqual("VALUE_BODY", retained["kind"])
        self.assertEqual("RETAINED", retained["parents"][0]["state"])
        self.assertEqual([], retained["blocks"])
        symbols = self.read(self.stage_url + "/symbols")
        self.assertIn(target, {row["declaration"]["declaration_id"] for row in symbols["rows"]})

    def test_http_save_rolls_back_and_preserves_dirty_workspace(self):
        let, _, child = self.create_let_and_child()
        with connect() as conn, conn.cursor() as cur:
            cur.execute("""CREATE FUNCTION blockcommander.c2_test_fail() RETURNS trigger LANGUAGE plpgsql
                AS $$ BEGIN RAISE EXCEPTION 'injected link failure'; END $$""")
            cur.execute("""CREATE TRIGGER c2_test_fail BEFORE INSERT ON blockcommander.program_stage_links
                FOR EACH ROW EXECUTE FUNCTION blockcommander.c2_test_fail()""")
        self.assertEqual("REPOSITORY_ERROR", self.save(503)["error_code"])
        self.assertTrue(self.read(self.let_url)["aggregate_dirty"])
        self.assertEqual(0, self.read(self.let_url)["program_saved_version"])
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM blockcommander.stage_blocks WHERE instance_id=%s", (let,))
            self.assertEqual(0, cur.fetchone()[0])
            cur.execute("SELECT COUNT(*) FROM blockcommander.stages WHERE stage_id=%s", (child,))
            self.assertEqual(0, cur.fetchone()[0])
            cur.execute("SELECT COUNT(*) FROM blockcommander.program_stage_links")
            self.assertEqual(0, cur.fetchone()[0])
            cur.execute("DROP TRIGGER c2_test_fail ON blockcommander.program_stage_links")
        self.assertFalse(self.save()["receipt"]["aggregate_dirty"])


if __name__ == '__main__':
    assert_database_identity(); logging.disable(logging.CRITICAL); unittest.main()
