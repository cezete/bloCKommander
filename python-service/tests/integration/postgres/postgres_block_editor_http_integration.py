"""Editor HTTP on the identity-checked disposable B2-T PostgreSQL only."""
import unittest
from uuid import uuid4

from ck_webhook import create_app
from hangar.block_editor_http import API_VERSION
from hangar.graph_application import GRAPH_SECRET_HEADER
from postgres_graph_integration import (HALL_ID, OWNER_ID, PROGRAM_ID, STAGE_ID,
    assert_database_identity, create_stage, repository, reset_schema)
from postgres_program_save_integration import stage_snapshot, stable_id


class BlockEditorHttpPostgreSQLIntegrationTest(unittest.TestCase):
    SECRET = "isolated-editor-http"

    def setUp(self):
        assert_database_identity()
        reset_schema()
        self.repo = repository()
        self.repo.migrate()
        create_stage()
        self.repo.save_full_stage_snapshot(str(STAGE_ID), 0,
            stage_snapshot(STAGE_ID, "root-v1", with_program_block=True))
        self.app = create_app(repository=self.repo, migrate=False, graph_shared_secret=self.SECRET)
        self.client = self.app.test_client()
        context = self.repo.resolve_working_copy_context(HALL_ID)
        self.headers = {GRAPH_SECRET_HEADER: self.SECRET,
            "X-BlockCommander-Hall-Id": str(context.hall_id),
            "X-BlockCommander-Program-Id": str(context.program_id),
            "X-BlockCommander-Working-Copy-Id": str(context.working_copy_id)}
        self.url = self.path(stable_id(STAGE_ID, "start"))

    def path(self, instance):
        return f"/api/v1/programs/{PROGRAM_ID}/stages/{STAGE_ID}/blocks/{instance}/editor"

    def get(self, client=None, url=None):
        return (client or self.client).get(url or self.url,
            query_string={"player_uuid": str(OWNER_ID)}, headers=self.headers)

    def command(self, **overrides):
        current = self.get().get_json()
        value = {"api_version": API_VERSION, "player_uuid": str(OWNER_ID), "request_id": str(uuid4()),
            "schema_id": current["schema_ref"]["schema_id"], "schema_version": 1,
            "expected_stage_workspace_epoch": current["version_context"]["stage_workspace_epoch"],
            "expected_working_revision": current["version_context"]["working_revision"],
            "changes": {"label": "HTTP saved", "description": None}}
        value.update(overrides)
        return value

    def test_get_start_stop_context_and_shared_services(self):
        for kind in ("start", "stop"):
            response = self.get(url=self.path(stable_id(STAGE_ID, kind)))
            self.assertEqual(200, response.status_code)
            body = response.get_json()
            self.assertEqual(f"ck.block.{kind.upper()}.metadata", body["schema"]["schema_id"])
            self.assertEqual(str(HALL_ID), body["transport_context"]["hall_id"])
            self.assertEqual(str(STAGE_ID), body["transport_context"]["stage_id"])
        self.assertIs(self.app.extensions["blockcommander_block_editor"].programs,
                      self.app.extensions["blockcommander_program_service"])

    def test_apply_explicit_save_reload_and_graph_preservation(self):
        command = self.command()
        graph_before = self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot
        response = self.client.post(self.url + "/apply", headers=self.headers, json=command)
        self.assertEqual(200, response.status_code)
        self.assertTrue(response.get_json()["modified"])
        stored_before_save = self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot
        self.assertEqual("root-v1", next(b.label for b in stored_before_save.blocks if b.type_key == "START"))
        saved = self.client.post(f"/api/v1/programs/{PROGRAM_ID}/save", headers=self.headers,
            json={"player_uuid": str(OWNER_ID), "expected_saved_version": 0})
        self.assertEqual(200, saved.status_code)
        fresh = create_app(repository=repository(), migrate=False, graph_shared_secret=self.SECRET).test_client()
        reloaded = self.get(client=fresh).get_json()
        self.assertEqual({"label": "HTTP saved", "description": None}, reloaded["values"])
        graph_after = self.repo.load_full_stage_snapshot(str(STAGE_ID)).snapshot
        self.assertEqual(graph_before.graph_nodes(), graph_after.graph_nodes())
        self.assertEqual((graph_before.edges, graph_before.routes, graph_before.wire_cells),
                         (graph_after.edges, graph_after.routes, graph_after.wire_cells))

    def test_stale_and_invalid_patch_leave_workspace_and_database_unchanged(self):
        for overrides, status in [({"expected_working_revision": 999}, 409),
                                  ({"changes": {"label": "valid", "description": 42}}, 422)]:
            before = self.get().get_json()
            stored = self.repo.load_full_stage_snapshot(str(STAGE_ID))
            response = self.client.post(self.url + "/apply", headers=self.headers, json=self.command(**overrides))
            self.assertEqual(status, response.status_code)
            after = self.get().get_json()
            self.assertEqual((before["values"], before["version_context"]), (after["values"], after["version_context"]))
            self.assertEqual(stored, self.repo.load_full_stage_snapshot(str(STAGE_ID)))

    def test_auth_context_and_duplicate_json_fail_before_mutation(self):
        before = self.get().get_json()
        for method, suffix in (("get", ""), ("post", "/apply")):
            response = getattr(self.client, method)(self.url + suffix)
            self.assertEqual(401, response.status_code)
            response = getattr(self.client, method)(self.url + suffix,
                headers={GRAPH_SECRET_HEADER: self.SECRET})
            self.assertEqual(409, response.status_code)
            self.assertEqual("CONTEXT_REQUIRED", response.get_json()["error_code"])
        response = self.client.post(self.url + "/apply", headers=self.headers, content_type="application/json",
            data='{"changes":{"label":"a","label":"b"}}')
        self.assertEqual(400, response.status_code)
        self.assertEqual("DUPLICATE_JSON_KEY", response.get_json()["error_code"])
        after = self.get().get_json()
        self.assertEqual((before["values"], before["version_context"]), (after["values"], after["version_context"]))

    def test_legacy_update_is_still_compatible_and_invalidates_editor_cas(self):
        command = self.command()
        response = self.client.post(f"/api/v1/stages/{STAGE_ID}/blocks/{stable_id(STAGE_ID, 'start')}",
            headers=self.headers, json={"player_uuid": str(OWNER_ID), "label": 42, "description": None})
        self.assertEqual(200, response.status_code)
        self.assertEqual({"label": "42", "description": "None"}, self.get().get_json()["values"])
        response = self.client.post(self.url + "/apply", headers=self.headers, json=command)
        self.assertEqual(409, response.status_code)
        self.assertEqual("STALE_WORKING_REVISION", response.get_json()["error_code"])


if __name__ == "__main__":
    assert_database_identity()
    unittest.main()
