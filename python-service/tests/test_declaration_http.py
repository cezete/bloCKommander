import json
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4

import psycopg2

from ck_webhook import create_app
from hangar.declaration_http import API_VERSION, BASIS_VERSION, basis_dto, digest
from hangar.declaration_service import DeclarationTarget
from hangar.declarations import Mutability, VariableType
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.program import ProgramWorkspaceState
from hangar.repository import HallNotFound, HallForbidden, SnapshotCompatibilityError
from test_program_service import FakeProgramRepository, PROGRAM, ROOT, OWNER, HALL, OTHER_PROGRAM, OTHER_ROOT


class DeclarationHttpTest(unittest.TestCase):
    def setUp(self):
        self.repo = FakeProgramRepository()
        self.repo.types = [t for t in self.repo.types if t['type_key'] not in ('CLASS', 'INTERFACE')]
        self.app = create_app(repository=self.repo, migrate=False, graph_shared_secret='isolated-declarations')
        self.client = self.app.test_client()
        self.programs = self.app.extensions['blockcommander_program_service']
        self.service = self.app.extensions['blockcommander_declarations']
        self.stage = self.programs.root(PROGRAM)
        self.program = self.programs.load(PROGRAM)
        ctx = self.repo.resolve_working_copy_context(HALL)
        self.headers = {GRAPH_SECRET_HEADER: 'isolated-declarations',
            'X-BlockCommander-Hall-Id': str(ctx.hall_id),
            'X-BlockCommander-Program-Id': str(ctx.program_id),
            'X-BlockCommander-Working-Copy-Id': str(ctx.working_copy_id)}

    def base(self, stage=ROOT, program=PROGRAM):
        return f'/api/v1/programs/{program}/stages/{stage}'

    def get(self, suffix='declaration-editor', stage=ROOT, **kwargs):
        return self.client.get(self.base(stage) + '/' + suffix, headers=kwargs.pop('headers', self.headers),
            query_string=kwargs.pop('query_string', {'player_uuid': str(OWNER)}), **kwargs)

    def command(self, stage=ROOT, **overrides):
        response = self.get(stage=stage)
        self.assertEqual(200, response.status_code, response.get_data(as_text=True))
        value = response.get_json()
        body = dict(api_version=API_VERSION, request_id=str(uuid4()), player_uuid=str(OWNER),
            expected_stage_workspace_epoch=value['version_context']['stage_workspace_epoch'],
            expected_working_revision=value['version_context']['working_revision'],
            expected_resolution_basis=value['resolution_basis_digest'],
            declaration=dict(name='counter', type_key='INTEGER', mutability='MUTABLE'))
        body.update(overrides)
        return body

    def mutate(self, body=None, method='POST', declaration_id=None, stage=ROOT, **kwargs):
        url = self.base(stage) + '/declarations' + (f'/{declaration_id}' if declaration_id is not None else '')
        return self.client.open(url, method=method, headers=kwargs.pop('headers', self.headers),
            json=self.command(stage) if body is None else body, **kwargs)

    def create(self, name='counter', stage=ROOT):
        result = self.mutate(self.command(stage, declaration=dict(name=name, type_key='INTEGER', mutability='MUTABLE')), stage=stage)
        self.assertEqual(201, result.status_code, result.get_data(as_text=True))
        return result.get_json()

    def child(self):
        placed = self.programs.stages.place(str(ROOT), dict(player_uuid=str(OWNER), type_key='PROGRAM', x=3, y=0, z=0))
        bid = next(b['instance_id'] for b in placed['blocks'] if b['rel_x'] == 3)
        return self.programs.ensure_child(PROGRAM, ROOT, bid, str(OWNER)).snapshot.stage_id

    def state(self):
        return tuple((sid, ws.snapshot, ws.dirty, ws.workspace_epoch, ws.saved_version)
                     for sid, ws in self.program.stage_workspaces.items()), self.program.saved_version, self.program.dirty, self.repo.program_save_calls

    def reject(self, operation, status, code):
        before = self.state()
        response = operation()
        self.assertEqual(status, response.status_code, response.get_data(as_text=True))
        self.assertEqual(code, response.get_json()['error_code'])
        self.assertEqual(before, self.state())
        return response.get_json()

    def test_shared_service_and_get_is_read_only(self):
        self.assertIs(self.programs, self.service.programs)
        before = self.state()
        for suffix in ('declarations', 'symbols', 'declaration-editor'):
            result = self.get(suffix).get_json()
            self.assertEqual(API_VERSION, result['api_version'])
            self.assertEqual([], result['rows'])
            self.assertEqual(str(HALL), result['transport_context']['hall_id'])
            self.assertEqual(1, result['resolution_basis']['policy_version'])
            self.assertTrue(result['capabilities']['create'])
        self.assertEqual(before, self.state())

    def test_crud_receipts_uuid_revision_and_noop(self):
        rev = self.stage.revision
        created = self.create()
        did = created['declaration_id']
        self.assertEqual(rev + 1, self.stage.revision)
        self.assertEqual('PYTHON_WORKING_COPY_ONLY', created['mutation_scope'])
        self.assertFalse(created['persisted'])
        self.assertEqual(rev, created['previous_version']['working_revision'])
        body = self.command(); body.pop('declaration'); body['changes'] = dict(name='renamed', type_key='BOOLEAN', mutability='IMMUTABLE')
        updated = self.mutate(body, 'PATCH', did).get_json()
        self.assertEqual(did, updated['declaration_id'])
        self.assertEqual('BOOLEAN', updated['declaration']['type_key'])
        body = self.command(); body.pop('declaration'); body['changes'] = dict(name='renamed')
        noop = self.mutate(body, 'PATCH', did).get_json()
        self.assertFalse(noop['modified'])
        self.assertEqual(rev + 2, self.stage.revision)
        body = self.command(); body.pop('declaration')
        deleted = self.mutate(body, 'DELETE', did).get_json()
        self.assertTrue(deleted['modified'])
        self.assertEqual(rev + 3, self.stage.revision)
        self.assertEqual(0, self.repo.program_save_calls)

    def test_symbols_local_inherited_shadowed_and_consistent_snapshot(self):
        root = self.create(); self.create('outer')
        child = self.child(); local = self.create(stage=child)
        value = self.get(stage=child).get_json()
        self.assertEqual(['OWN', 'LOCAL', 'INHERITED', 'SHADOWED'], [r['group'] for r in value['rows']])
        self.assertEqual(local['declaration_id'], value['rows'][-1]['shadowed_by'])
        self.assertEqual(root['declaration_id'], value['rows'][-1]['declaration']['declaration_id'])
        self.assertFalse(value['rows'][-1]['can_edit_declaration'])
        self.assertEqual(value['version_context']['working_revision'], value['resolution_basis']['scopes'][0]['working_revision'])

    def test_parent_change_rejects_unchanged_child_revision(self):
        self.create(); child = self.child(); old = self.command(child)
        rev = self.programs.stage(PROGRAM, child).revision
        self.create('parentChanged')
        self.assertEqual(rev, self.programs.stage(PROGRAM, child).revision)
        self.reject(lambda: self.mutate(old, stage=child), 409, 'STALE_RESOLUTION_BASIS')

    def test_epoch_revision_and_unknown_receipt_no_retry(self):
        body = self.command(expected_stage_workspace_epoch=str(uuid4()))
        self.reject(lambda: self.mutate(body), 409, 'STALE_WORKSPACE_EPOCH')
        body = self.command(expected_working_revision=99)
        self.reject(lambda: self.mutate(body), 409, 'STALE_WORKING_REVISION')
        body = self.command()
        with patch.object(self.service, 'create', wraps=self.service.create) as create:
            self.assertEqual(201, self.mutate(body).status_code)  # discard reply: client UNKNOWN
            self.assertEqual(200, self.get().status_code)
            self.assertEqual(1, create.call_count)
            error = self.reject(lambda: self.mutate(body), 409, 'STALE_WORKING_REVISION')
            self.assertFalse(error['retry_automatically'])
            self.assertEqual(body['request_id'], error['request_id'])

    def test_request_id_is_correlation_not_deduplication(self):
        body = self.command(); self.assertEqual(201, self.mutate(body).status_code)
        next_body = self.command(declaration=dict(name='next', type_key='STRING', mutability='MUTABLE'))
        next_body['request_id'] = body['request_id']
        self.assertEqual(201, self.mutate(next_body).status_code)

    def test_paging_deterministic_and_bound_to_basis_and_save(self):
        for i in range(18):
            self.service.create(DeclarationTarget(PROGRAM, ROOT), OWNER, self.stage.workspace_epoch, self.stage.revision,
                name=f'v{i:02}', variable_type=VariableType.INTEGER, mutability=Mutability.MUTABLE)
        first = self.get('declarations').get_json()
        self.assertEqual(16, len(first['rows']))
        query = dict(player_uuid=str(OWNER), page='1', page_token=first['page']['token'])
        second = self.get('declarations', query_string=query).get_json()
        self.assertEqual(['v16', 'v17'], [r['declaration']['name'] for r in second['rows']])
        self.reject(lambda: self.get('declarations', query_string=dict(player_uuid=str(OWNER), page=1)), 409, 'PAGE_TOKEN_REQUIRED')
        self.programs.save(PROGRAM, str(OWNER))
        self.reject(lambda: self.get('declarations', query_string=query), 409, 'STALE_PAGE')
        query['page_token'] = self.get('declarations').get_json()['page']['token']
        self.create('new')
        self.reject(lambda: self.get('declarations', query_string=query), 409, 'STALE_PAGE')

    def test_retained_read_is_explicit_and_writes_rejected(self):
        child = self.child(); self.create(stage=child); command = self.command(child)
        link = next(l for l in self.program.links if l.child_stage_id == child)
        self.programs.retain_child(PROGRAM, ROOT, link.program_block_instance_id, str(OWNER))
        value = self.get(stage=child).get_json()
        self.assertEqual('UNAVAILABLE', value['scope_status'])
        self.assertIsNone(value['resolution_basis'])
        self.assertFalse(value['capabilities']['create'])
        self.assertEqual(1, len(value['rows']))
        self.reject(lambda: self.get('symbols', stage=child), 409, 'SCOPE_UNAVAILABLE')
        self.reject(lambda: self.mutate(command, stage=child), 409, 'SCOPE_UNAVAILABLE')

    def test_auth_first_all_methods_and_unconfigured(self):
        body = self.command()
        for method, suffix in [('GET', '/symbols'), ('POST', '/declarations'), ('PATCH', '/declarations/bad'), ('DELETE', '/declarations/bad')]:
            for headers in ({}, {GRAPH_SECRET_HEADER: 'wrong'}):
                with patch.object(self.repo, 'resolve_working_copy_context', side_effect=AssertionError('must not resolve')):
                    self.reject(lambda: self.client.open(self.base(stage='invalid') + suffix, method=method,
                        headers=headers, data='invalid'), 401, 'INVALID_GRAPH_AUTH')
        app = create_app(repository=self.repo, migrate=False, graph_shared_secret='')
        response = app.test_client().post(self.base() + '/declarations', json=body)
        self.assertEqual(503, response.status_code)
        self.assertEqual('GRAPH_AUTH_NOT_CONFIGURED', response.get_json()['error_code'])

    def test_context_owner_and_foreign_declaration(self):
        command = self.command()
        self.reject(lambda: self.get(headers={GRAPH_SECRET_HEADER: 'isolated-declarations'}), 409, 'CONTEXT_REQUIRED')
        self.reject(lambda: self.get(headers={**self.headers, 'X-BlockCommander-Working-Copy-Id': str(uuid4())}), 409, 'FOREIGN_WORKING_COPY')
        self.reject(lambda: self.get(query_string=dict(player_uuid=str(uuid4()))), 403, 'FORBIDDEN')
        command['player_uuid'] = str(uuid4())
        self.reject(lambda: self.mutate(command), 403, 'FORBIDDEN')
        child = self.child(); root = self.create(); command = self.command(child)
        command.pop('declaration'); command['changes'] = dict(name='bad')
        self.reject(lambda: self.mutate(command, 'PATCH', root['declaration_id'], child), 404, 'DECLARATION_NOT_FOUND')
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT)
        response = self.client.get(self.base(OTHER_ROOT, OTHER_PROGRAM) + '/symbols', headers=self.headers,
            query_string=dict(player_uuid=str(OWNER)))
        self.assertEqual(409, response.status_code)
        response = self.get(stage=OTHER_ROOT)
        self.assertEqual(409, response.status_code)

    def test_invalid_fields_domain_json_uuid_and_size_are_atomic(self):
        for declaration, code in [(dict(name=' x', type_key='INTEGER', mutability='MUTABLE'), 'INVALID_DECLARATION_NAME'),
                (dict(name='x', type_key='FLOAT', mutability='MUTABLE'), 'UNKNOWN_VARIABLE_TYPE'),
                (dict(name='x', type_key='INTEGER', mutability=[]), 'INVALID_MUTABILITY')]:
            self.reject(lambda: self.mutate(self.command(declaration=declaration)), 422, code)
        self.create()
        self.reject(lambda: self.mutate(), 422, 'DUPLICATE_DECLARATION_NAME')
        for raw, code, status in [('{"a":1,"a":2}', 'DUPLICATE_JSON_KEY', 400),
                ('{', 'INVALID_JSON', 400), ('[]', 'INVALID_BODY', 400),
                ('{"a":1.2}', 'INVALID_NUMBER', 400), ('x' * 65537, 'BODY_TOO_LARGE', 413)]:
            self.reject(lambda: self.client.post(self.base() + '/declarations', headers=self.headers,
                data=raw, content_type='application/json'), status, code)
        self.reject(lambda: self.mutate(self.command(request_id='not-uuid')), 400, 'INVALID_UUID')
        self.reject(lambda: self.mutate(self.command(hall_id=str(HALL))), 400, 'INVALID_FIELDS')
        self.reject(lambda: self.mutate(self.command(expected_working_revision=True)), 400, 'INVALID_REVISION')
        with patch('hangar.declaration_http.MAX_RESPONSE_BYTES', 100):
            self.reject(lambda: self.get(), 409, 'RESPONSE_TOO_LARGE')
            command = dict(api_version=API_VERSION, request_id=str(uuid4()), player_uuid=str(OWNER),
                expected_stage_workspace_epoch=str(self.stage.workspace_epoch), expected_working_revision=self.stage.revision,
                expected_resolution_basis=digest(basis_dto(self.service.resolve(DeclarationTarget(PROGRAM, ROOT), OWNER).basis)),
                declaration=dict(name='bounded', type_key='INTEGER', mutability='MUTABLE'))
            self.reject(lambda: self.mutate(command), 409, 'RESPONSE_TOO_LARGE')

    def test_exception_mapping_sanitized(self):
        for exc, status, code in [(HallNotFound('secret SQL path'), 404, 'STAGE_NOT_FOUND'),
                (HallForbidden('secret'), 403, 'FORBIDDEN'), (SnapshotCompatibilityError('secret'), 409, 'SNAPSHOT_INCOMPATIBLE'),
                (psycopg2.OperationalError('password secret'), 503, 'REPOSITORY_UNAVAILABLE'),
                (RuntimeError('secret stack'), 500, 'INTERNAL_ERROR')]:
            with patch.object(self.service, 'resolve', side_effect=exc):
                body = self.reject(lambda: self.get(), status, code)
                self.assertNotIn('secret', json.dumps(body))
        self.program.state = ProgramWorkspaceState.RELOAD_REQUIRED
        self.reject(lambda: self.get(), 409, 'RELOAD_REQUIRED')

    def test_digest_canonical_and_complete(self):
        value = basis_dto(self.service.resolve(DeclarationTarget(PROGRAM, ROOT), OWNER).basis)
        self.assertEqual(digest(value), digest(dict(reversed(list(value.items())))))
        self.assertTrue(digest(value).startswith(BASIS_VERSION + ':'))
        for key in ('policy_version', 'hierarchy_revision', 'program_workspace_epoch', 'scopes'):
            changed = dict(value); changed[key] = None
            self.assertNotEqual(digest(value), digest(changed))

    def test_concurrent_cas_one_winner(self):
        body = self.command(); barrier = threading.Barrier(2); statuses = []
        def run(name):
            local = dict(body, declaration=dict(name=name, type_key='INTEGER', mutability='MUTABLE'), request_id=str(uuid4()))
            barrier.wait()
            with self.app.test_client() as client:
                statuses.append(client.post(self.base() + '/declarations', headers=self.headers, json=local).status_code)
        threads = [threading.Thread(target=run, args=(n,)) for n in ('a', 'b')]
        for t in threads: t.start()
        for t in threads: t.join(5); self.assertFalse(t.is_alive())
        self.assertCountEqual([201, 409], statuses)

    def test_editor_snapshot_blocks_writer_between_own_and_resolution(self):
        attempted = threading.Event(); completed = threading.Event(); errors = []
        original = self.service.list_own
        epoch, revision = self.stage.workspace_epoch, self.stage.revision
        def writer():
            attempted.set()
            try:
                self.service.create(DeclarationTarget(PROGRAM, ROOT), OWNER, epoch, revision,
                    name='concurrent', variable_type=VariableType.INTEGER, mutability=Mutability.MUTABLE)
            except Exception as exc:
                errors.append(exc)
            finally:
                completed.set()
        thread = None
        def own(*args):
            nonlocal thread
            value = original(*args)
            thread = threading.Thread(target=writer)
            thread.start()
            self.assertTrue(attempted.wait(2))
            self.assertFalse(completed.wait(.05))
            return value
        with patch.object(self.service, 'list_own', side_effect=own):
            response = self.get()
        thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(200, response.status_code)
        value = response.get_json()
        self.assertEqual([], value['rows'])
        self.assertEqual(revision, value['resolution_basis']['scopes'][0]['working_revision'])
        self.assertEqual(revision, value['version_context']['working_revision'])
        self.assertEqual(revision + 1, self.stage.revision)

    def test_clean_noop_and_incompatible_mutation_capability(self):
        created = self.create(); self.programs.save(PROGRAM, str(OWNER))
        body = self.command(); body.pop('declaration'); body['changes'] = dict(name='counter')
        before = self.state()
        result = self.mutate(body, 'PATCH', created['declaration_id']).get_json()
        self.assertFalse(result['modified'])
        self.assertFalse(result['version_context']['program_dirty'])
        self.assertEqual(before, self.state())
        body = self.command()
        self.stage.graph_snapshot_version = 0
        value = self.get().get_json()
        self.assertFalse(value['capabilities']['create'])
        self.assertTrue(value['capabilities']['read_own'])
        self.reject(lambda: self.mutate(body), 409, 'SNAPSHOT_INCOMPATIBLE')

    def test_parent_change_invalidates_page_and_endpoint_tokens_do_not_mix(self):
        child = self.child(); self.create(stage=child)
        value = self.get(stage=child).get_json()
        query = dict(player_uuid=str(OWNER), page=0, page_token=value['page']['token'])
        self.reject(lambda: self.get('declarations', stage=child, query_string=query), 409, 'STALE_PAGE')
        self.create('parent')
        self.reject(lambda: self.get(stage=child, query_string=query), 409, 'STALE_PAGE')

    def test_strict_query_and_missing_fields_target_uuid(self):
        for query, code in [([('player_uuid', str(OWNER)), ('player_uuid', str(OWNER))], 'DUPLICATE_QUERY_FIELD'),
                (dict(player_uuid=str(OWNER), hall_id=str(HALL)), 'UNKNOWN_QUERY_FIELD'),
                (dict(player_uuid=str(OWNER), page='-1'), 'INVALID_PAGE'),
                (dict(player_uuid=str(OWNER), page_token='invalid'), 'INVALID_PAGE_TOKEN')]:
            self.reject(lambda: self.get(query_string=query), 400, code)
        self.reject(lambda: self.get(data='x'), 400, 'INVALID_BODY')
        self.reject(lambda: self.get(stage='invalid'), 400, 'INVALID_UUID')
        body = self.command(); body.pop('request_id')
        self.reject(lambda: self.mutate(body), 400, 'INVALID_FIELDS')
        body = self.command(); body.pop('declaration'); body['changes'] = dict(scope_ref=str(ROOT))
        self.reject(lambda: self.mutate(body, 'PATCH', uuid4()), 400, 'INVALID_CHANGES')
        body = self.command(); body.pop('declaration'); body['changes'] = dict(name='valid')
        self.reject(lambda: self.mutate(body, 'PATCH', 'bad-uuid'), 400, 'INVALID_UUID')


if __name__ == '__main__':
    unittest.main()
