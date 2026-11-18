"""Declaration HTTP acceptance only on the existing identity-guarded B2-T DB."""
import threading
import unittest
from uuid import uuid4

from ck_webhook import create_app
from hangar.declaration_http import API_VERSION
from hangar.graph_application import GRAPH_SECRET_HEADER
from postgres_graph_integration import (HALL_ID, OWNER_ID, PROGRAM_ID, STAGE_ID,
    assert_database_identity, connect, create_stage, repository, reset_schema)
from postgres_program_save_integration import stage_snapshot, program_fingerprint


class DeclarationHttpPostgreSQLTest(unittest.TestCase):
    SECRET = 'isolated-declaration-http'

    def setUp(self):
        assert_database_identity()
        reset_schema()
        self.repo = repository(); self.repo.migrate(); create_stage()
        self.repo.save_full_stage_snapshot(str(STAGE_ID), 0,
            stage_snapshot(STAGE_ID, 'Root', with_program_block=True))
        self.app = create_app(repository=self.repo, migrate=False, graph_shared_secret=self.SECRET)
        self.client = self.app.test_client()
        self.programs = self.app.extensions['blockcommander_program_service']
        ctx = self.repo.resolve_working_copy_context(HALL_ID)
        self.headers = {GRAPH_SECRET_HEADER: self.SECRET,
            'X-BlockCommander-Hall-Id': str(ctx.hall_id),
            'X-BlockCommander-Program-Id': str(ctx.program_id),
            'X-BlockCommander-Working-Copy-Id': str(ctx.working_copy_id)}

    def base(self, stage=STAGE_ID):
        return f'/api/v1/programs/{PROGRAM_ID}/stages/{stage}'

    def get(self, stage=STAGE_ID, suffix='declaration-editor', client=None, **query):
        return (client or self.client).get(self.base(stage) + '/' + suffix,
            headers=self.headers, query_string=dict(player_uuid=str(OWNER_ID), **query))

    def command(self, stage=STAGE_ID, name='counter'):
        response = self.get(stage)
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        state = response.get_json()
        return dict(api_version=API_VERSION, request_id=str(uuid4()), player_uuid=str(OWNER_ID),
            expected_stage_workspace_epoch=state['version_context']['stage_workspace_epoch'],
            expected_working_revision=state['version_context']['working_revision'],
            expected_resolution_basis=state['resolution_basis_digest'],
            declaration=dict(name=name, type_key='INTEGER', mutability='MUTABLE'))

    def create(self, stage=STAGE_ID, name='counter'):
        response = self.client.post(self.base(stage) + '/declarations', headers=self.headers, json=self.command(stage, name))
        self.assertEqual(201, response.status_code, response.get_data(as_text=True))
        return response.get_json()

    def saved_rows(self):
        with connect() as conn, conn.cursor() as cur:
            cur.execute('SELECT declaration_id::text,stage_id::text,name,type_key,mutability FROM blockcommander.stage_declarations ORDER BY declaration_id')
            return cur.fetchall()

    def child(self):
        view = self.programs.stages.place(str(STAGE_ID), dict(player_uuid=str(OWNER_ID), type_key='PROGRAM', x=8, y=0, z=0))
        bid = next(b['instance_id'] for b in view['blocks'] if b['rel_x'] == 8)
        return self.programs.ensure_child(PROGRAM_ID, STAGE_ID, bid, str(OWNER_ID)).snapshot.stage_id

    def test_crud_explicit_program_save_reload_preserves_graph_and_uuid(self):
        initial = self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot
        before = program_fingerprint(PROGRAM_ID)
        created = self.create(); did = created['declaration_id']
        self.assertEqual([], self.saved_rows())
        self.assertEqual(before, program_fingerprint(PROGRAM_ID))
        command = self.command(); command.pop('declaration'); command['changes'] = dict(name='renamed', type_key='BOOLEAN')
        response = self.client.patch(self.base() + '/declarations/' + did, headers=self.headers, json=command)
        self.assertEqual(200, response.status_code)
        self.assertEqual(did, response.get_json()['declaration_id'])
        command = self.command(); command.pop('declaration'); command['changes'] = dict(name='renamed')
        response = self.client.patch(self.base() + '/declarations/' + did, headers=self.headers, json=command)
        self.assertFalse(response.get_json()['modified'])
        saved = self.client.post(f'/api/v1/programs/{PROGRAM_ID}/save', headers=self.headers,
            json=dict(player_uuid=str(OWNER_ID), expected_saved_version=0))
        self.assertEqual(200, saved.status_code, saved.get_data(as_text=True))
        self.assertEqual(did, self.saved_rows()[0][0])
        fresh = create_app(repository=repository(), migrate=False, graph_shared_secret=self.SECRET).test_client()
        reloaded = self.get(client=fresh).get_json()
        self.assertEqual(did, reloaded['rows'][0]['declaration']['declaration_id'])
        self.assertFalse(reloaded['version_context']['program_dirty'])
        stored = self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot
        self.assertEqual((initial.blocks, initial.edges, initial.routes, initial.wire_cells),
                         (stored.blocks, stored.edges, stored.routes, stored.wire_cells))
        command = self.command(); command.pop('declaration')
        response = self.client.delete(self.base() + '/declarations/' + did, headers=self.headers, json=command)
        self.assertEqual(200, response.status_code)
        self.assertEqual(1, len(self.saved_rows()))  # deletion remains unsaved

    def test_scope_shadowing_stale_parent_and_retained(self):
        outer = self.create(); child = self.child(); old = self.command(child)
        child_revision = self.programs.stage(PROGRAM_ID, child).revision
        self.create(name='parentChanged')
        response = self.client.post(self.base(child) + '/declarations', headers=self.headers, json=old)
        self.assertEqual('STALE_RESOLUTION_BASIS', response.get_json()['error_code'])
        self.assertEqual(child_revision, self.programs.stage(PROGRAM_ID, child).revision)
        local = self.create(child)
        symbols = self.get(child, 'symbols').get_json()['rows']
        self.assertEqual(['LOCAL', 'INHERITED', 'SHADOWED'], [r['group'] for r in symbols])
        self.assertEqual(outer['declaration_id'], symbols[-1]['declaration']['declaration_id'])
        self.assertEqual(local['declaration_id'], symbols[-1]['shadowed_by'])
        command = self.command(child)
        link = next(l for l in self.programs.load(PROGRAM_ID).links if l.child_stage_id == child)
        self.programs.retain_child(PROGRAM_ID, STAGE_ID, link.program_block_instance_id, str(OWNER_ID))
        value = self.get(child).get_json()
        self.assertEqual('UNAVAILABLE', value['scope_status'])
        self.assertEqual(1, len(value['rows']))
        response = self.client.post(self.base(child) + '/declarations', headers=self.headers, json=command)
        self.assertEqual(409, response.status_code)
        self.assertEqual([], self.saved_rows())

    def test_invalid_requests_leave_database_and_workspace_unchanged(self):
        self.create()
        stored = program_fingerprint(PROGRAM_ID)
        stage = self.programs.root(PROGRAM_ID)
        before = (stage.snapshot, stage.dirty)
        valid = self.command()
        for changes, status in [(dict(expected_working_revision=999), 409),
                (dict(expected_stage_workspace_epoch=str(uuid4())), 409),
                (dict(player_uuid=str(uuid4())), 403),
                (dict(declaration=dict(name='invalid name', type_key='STRING', mutability='MUTABLE')), 422),
                ({}, 422)]:
            response = self.client.post(self.base() + '/declarations', headers=self.headers, json=dict(valid, **changes))
            self.assertEqual(status, response.status_code, response.get_data(as_text=True))
            self.assertEqual(before, (stage.snapshot, stage.dirty))
            self.assertEqual(stored, program_fingerprint(PROGRAM_ID))
        for headers, status in [({}, 401), ({GRAPH_SECRET_HEADER: self.SECRET}, 409),
                ({**self.headers, 'X-BlockCommander-Working-Copy-Id': str(uuid4())}, 409)]:
            response = self.client.post(self.base() + '/declarations', headers=headers, json=valid)
            self.assertEqual(status, response.status_code)
            self.assertEqual(before, (stage.snapshot, stage.dirty))
        response = self.client.post(self.base() + '/declarations', headers=self.headers,
            content_type='application/json', data='{"request_id":"a","request_id":"b"}')
        self.assertEqual(400, response.status_code)
        self.assertEqual(stored, program_fingerprint(PROGRAM_ID))

    def test_paging_version_fence_and_get_does_not_persist(self):
        for i in range(18): self.create(name=f'v{i:02}')
        before = program_fingerprint(PROGRAM_ID)
        first = self.get(suffix='declarations').get_json()
        second = self.get(suffix='declarations', page=1, page_token=first['page']['token'])
        self.assertEqual(['v16', 'v17'], [r['declaration']['name'] for r in second.get_json()['rows']])
        self.assertEqual(before, program_fingerprint(PROGRAM_ID))
        self.create(name='another')
        response = self.get(suffix='declarations', page=1, page_token=first['page']['token'])
        self.assertEqual('STALE_PAGE', response.get_json()['error_code'])

    def test_restart_rejects_old_epoch_and_unknown_snapshot_format_explicit(self):
        command = self.command()
        fresh = create_app(repository=repository(), migrate=False, graph_shared_secret=self.SECRET).test_client()
        response = fresh.post(self.base() + '/declarations', headers=self.headers, json=command)
        self.assertEqual('STALE_WORKSPACE_EPOCH', response.get_json()['error_code'])
        # Isolated fixture only: simulate a future declaration format, then restore
        # the trigger immediately. Production migrations/writer contract unchanged.
        with connect() as conn, conn.cursor() as cur:
            cur.execute('ALTER TABLE blockcommander.stages DISABLE TRIGGER stage_declaration_writer_guard')
            cur.execute('UPDATE blockcommander.stages SET declaration_schema_version=77 WHERE stage_id=%s', (str(STAGE_ID),))
            cur.execute('ALTER TABLE blockcommander.stages ENABLE TRIGGER stage_declaration_writer_guard')
        unknown = create_app(repository=repository(), migrate=False, graph_shared_secret=self.SECRET).test_client()
        response = self.get(client=unknown)
        self.assertEqual(409, response.status_code, response.get_data(as_text=True))
        self.assertEqual('SNAPSHOT_INCOMPATIBLE', response.get_json()['error_code'])
        self.assertNotIn('rows', response.get_json())

    def test_concurrent_http_cas_one_commit_and_no_saved_rows(self):
        command = self.command(); barrier = threading.Barrier(2); responses = []
        def run(name):
            body = dict(command, request_id=str(uuid4()), declaration=dict(name=name, type_key='INTEGER', mutability='MUTABLE'))
            barrier.wait()
            with self.app.test_client() as client:
                responses.append(client.post(self.base() + '/declarations', headers=self.headers, json=body))
        threads = [threading.Thread(target=run, args=(name,)) for name in ('a', 'b')]
        for t in threads: t.start()
        for t in threads: t.join(10); self.assertFalse(t.is_alive())
        self.assertCountEqual([201, 409], [r.status_code for r in responses])
        self.assertEqual([], self.saved_rows())


if __name__ == '__main__':
    assert_database_identity()
    unittest.main()
