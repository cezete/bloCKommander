import json
import unittest
from dataclasses import replace
from unittest.mock import patch
from uuid import UUID, uuid4

import psycopg2

from ck_webhook import create_app
from hangar.block_editor_http import API_VERSION, MAX_BODY_BYTES
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.program import ProgramWorkspaceState
from test_program_service import FakeProgramRepository, PROGRAM, ROOT, OWNER, HALL, OTHER_PROGRAM, OTHER_ROOT
from test_stage_snapshot_adapter import full_snapshot


class BlockEditorHttpTest(unittest.TestCase):
    SECRET = "editor-http-test-only"

    def setUp(self):
        self.repo = FakeProgramRepository()
        self.repo.types = [t for t in self.repo.types if t["type_key"] not in ("CLASS", "INTERFACE")]
        value = full_snapshot()
        self.repo.snapshots[ROOT] = replace(value, stage_id=ROOT,
            edges=tuple(replace(e, stage_id=ROOT) for e in value.edges),
            routes=tuple(replace(r, stage_id=ROOT) for r in value.routes))
        self.app = create_app(repository=self.repo, migrate=False, graph_shared_secret=self.SECRET)
        self.client = self.app.test_client()
        self.programs = self.app.extensions["blockcommander_program_service"]
        self.stage = self.programs.root(PROGRAM)
        self.editor = self.app.extensions["blockcommander_block_editor"]
        self.instance = self.stage.snapshot.blocks[0].instance_id
        self.url = self.path(self.instance)
        context = self.repo.resolve_working_copy_context(HALL)
        self.headers = {GRAPH_SECRET_HEADER: self.SECRET,
            "X-BlockCommander-Hall-Id": str(context.hall_id),
            "X-BlockCommander-Program-Id": str(context.program_id),
            "X-BlockCommander-Working-Copy-Id": str(context.working_copy_id)}

    def path(self, instance, program=PROGRAM, stage=ROOT):
        return f"/api/v1/programs/{program}/stages/{stage}/blocks/{instance}/editor"

    def body(self, **overrides):
        value = {"api_version": API_VERSION, "request_id": str(uuid4()), "player_uuid": str(OWNER),
            "schema_id": "ck.block.START.metadata", "schema_version": 1,
            "expected_stage_workspace_epoch": str(self.stage.workspace_epoch),
            "expected_working_revision": self.stage.revision,
            "changes": {"label": "HTTP edited"}}
        value.update(overrides)
        return value

    def get(self, **kwargs):
        return self.client.get(kwargs.pop("url", self.url),
            query_string=kwargs.pop("query_string", {"player_uuid": str(OWNER)}),
            headers=kwargs.pop("headers", self.headers), **kwargs)

    def apply(self, body=None, **kwargs):
        return self.client.post(kwargs.pop("url", self.url) + "/apply",
            json=self.body() if body is None else body, headers=kwargs.pop("headers", self.headers), **kwargs)

    def reject(self, operation, status, code):
        before = (self.stage.snapshot, self.stage.dirty, self.stage.saved_version,
                  self.programs.load(PROGRAM).saved_version, self.repo.program_save_calls)
        response = operation()
        self.assertEqual(status, response.status_code, response.get_data(as_text=True))
        body = response.get_json()
        self.assertEqual(API_VERSION, body["api_version"])
        self.assertEqual(code, body["error_code"])
        self.assertNotIn("values", body)
        self.assertNotIn("transport_context", body)
        self.assertEqual(before, (self.stage.snapshot, self.stage.dirty, self.stage.saved_version,
                                 self.programs.load(PROGRAM).saved_version, self.repo.program_save_calls))
        return body

    def test_get_start_and_stop_schema_values_context(self):
        for block in self.stage.snapshot.blocks:
            response = self.get(url=self.path(block.instance_id))
            self.assertEqual(200, response.status_code)
            body = response.get_json()
            self.assertEqual(API_VERSION, body["api_version"])
            self.assertEqual(block.label, body["values"]["label"])
            self.assertEqual(f"ck.block.{block.type_key}.metadata", body["schema"]["schema_id"])
            self.assertEqual(2, len(body["schema"]["fields"]))
            self.assertTrue(body["permissions"]["apply"])
            self.assertEqual(str(block.instance_id), body["target"]["instance_id"])
            self.assertEqual("explicit", body["transport_context"]["mode"])
            for key, header in [("hall_id", "Hall"), ("program_id", "Program"), ("working_copy_id", "Working-Copy")]:
                self.assertEqual(self.headers[f"X-BlockCommander-{header}-Id"], body["transport_context"][key])
            self.assertEqual(str(ROOT), body["transport_context"]["stage_id"])
        self.assertFalse(self.stage.dirty)

    def test_apply_both_fields_then_get_and_explicit_null(self):
        command = self.body(changes={"label": " Trimmed ", "description": " Details "})
        before = self.stage.revision
        response = self.apply(command)
        self.assertEqual(200, response.status_code)
        body = response.get_json()
        self.assertEqual(command["request_id"], body["request_id"])
        self.assertTrue(body["modified"])
        self.assertEqual(before + 1, body["version_context"]["working_revision"])
        self.assertEqual({"label": "Trimmed", "description": "Details"}, body["values"])
        self.assertEqual(body["values"], self.get().get_json()["values"])
        cleared = self.apply(self.body(changes={"description": None}))
        self.assertEqual(200, cleared.status_code)
        self.assertIsNone(cleared.get_json()["values"]["description"])
        self.assertEqual(0, self.repo.program_save_calls)
        self.assertNotIn("state", body)
        self.assertNotIn("stage", body)

    def test_noop_still_has_authoritative_versions(self):
        before = self.stage.snapshot
        result = self.apply(self.body(changes={"label": " Indulás "})).get_json()
        self.assertFalse(result["modified"])
        self.assertIs(before, self.stage.snapshot)
        self.assertFalse(result["version_context"]["program_dirty"])

    def test_adapter_forwards_raw_values_without_normalization(self):
        changes = {"label": "  Raw  ", "description": None}
        with patch.object(self.editor, "apply", wraps=self.editor.apply) as apply:
            response = self.apply(self.body(changes=changes))
            self.assertEqual(200, response.status_code)
            self.assertEqual(changes, dict(apply.call_args.args[0].changes))
        self.assertEqual("Raw", response.get_json()["values"]["label"])

    def test_valid_json_emoji_pairs_and_unicode_are_domain_values(self):
        response = self.apply(self.body(changes={"label": "😀" * 32, "description": "e\u0301\u200b"}))
        self.assertEqual(200, response.status_code)
        self.assertEqual("😀" * 32, response.get_json()["values"]["label"])
        self.assertEqual("e\u0301\u200b", response.get_json()["values"]["description"])

    def test_auth_precedes_every_parse_and_context_lookup(self):
        for secret in (None, "wrong"):
            headers = {} if secret is None else {GRAPH_SECRET_HEADER: secret}
            with patch.object(self.repo, "resolve_working_copy_context", side_effect=AssertionError("must not resolve")):
                self.reject(lambda: self.get(headers=headers, url=self.path("invalid")), 401, "INVALID_GRAPH_AUTH")
                self.reject(lambda: self.apply(headers=headers), 401, "INVALID_GRAPH_AUTH")

    def test_unconfigured_auth_is_fail_closed(self):
        client = create_app(repository=self.repo, migrate=False, graph_shared_secret="").test_client()
        for method, suffix in (("get", ""), ("post", "/apply")):
            response = getattr(client, method)(self.url + suffix, headers=self.headers)
            self.assertEqual(503, response.status_code)
            self.assertEqual("GRAPH_AUTH_NOT_CONFIGURED", response.get_json()["error_code"])

    def test_missing_and_partial_context_get_apply_parity(self):
        for headers, code, status in [({GRAPH_SECRET_HEADER: self.SECRET}, "CONTEXT_REQUIRED", 409),
            ({k: v for k, v in self.headers.items() if k != "X-BlockCommander-Working-Copy-Id"}, "INCOMPLETE_CONTEXT", 400),
            ({**self.headers, "X-BlockCommander-Hall-Id": "bad"}, "INVALID_CONTEXT_ID", 400)]:
            self.reject(lambda: self.get(headers=headers), status, code)
            self.reject(lambda: self.apply(headers=headers), status, code)

    def test_url_program_header_mismatch_get_apply(self):
        url = self.path(self.instance, program=OTHER_PROGRAM)
        self.reject(lambda: self.get(url=url), 409, "CONTEXT_MISMATCH")
        self.reject(lambda: self.apply(url=url), 409, "CONTEXT_MISMATCH")

    def test_hall_mount_and_working_copy_mismatch_get_apply(self):
        other_hall = uuid4()
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT, mounted_hall=other_hall)
        for header, value, code in [("X-BlockCommander-Hall-Id", str(other_hall), "FOREIGN_WORKING_COPY"),
                                    ("X-BlockCommander-Working-Copy-Id", str(uuid4()), "FOREIGN_WORKING_COPY")]:
            headers = {**self.headers, header: value}
            self.reject(lambda: self.get(headers=headers), 409, code)
            self.reject(lambda: self.apply(headers=headers), 409, code)

    def test_hall_program_mismatch(self):
        # Keep the Hall's genuine WC while claiming a different Program.
        headers = {**self.headers, "X-BlockCommander-Program-Id": str(OTHER_PROGRAM)}
        url = self.path(self.instance, program=OTHER_PROGRAM)
        self.reject(lambda: self.get(headers=headers, url=url), 409, "HALL_PROGRAM_MISMATCH")
        self.reject(lambda: self.apply(headers=headers, url=url), 409, "HALL_PROGRAM_MISMATCH")

    def test_owner_rejected_and_no_values_or_context_leak(self):
        self.reject(lambda: self.get(query_string={"player_uuid": str(uuid4())}), 403, "FORBIDDEN")
        self.reject(lambda: self.apply(self.body(player_uuid=str(uuid4()))), 403, "FORBIDDEN")

    def test_missing_instance_and_foreign_stage(self):
        self.reject(lambda: self.get(url=self.path(uuid4())), 404, "INSTANCE_NOT_FOUND")
        self.reject(lambda: self.apply(url=self.path(uuid4())), 404, "INSTANCE_NOT_FOUND")
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT)
        self.reject(lambda: self.apply(url=self.path(self.instance, stage=OTHER_ROOT)), 409, "STAGE_PROGRAM_MISMATCH")

    def test_url_uuid_is_strict_before_domain(self):
        for name in ("program", "stage", "instance"):
            values = {"program": PROGRAM, "stage": ROOT, "instance": self.instance}
            values[name] = "invalid"
            url = self.path(**values)
            self.reject(lambda: self.get(url=url), 400, "INVALID_UUID")
            self.reject(lambda: self.apply(url=url), 400, "INVALID_UUID")
        self.reject(lambda: self.get(url=self.path(self.instance.hex)), 400, "INVALID_UUID")

    def test_malformed_and_non_object_json(self):
        for raw in ("{", "[]", "null", '"text"', "{} trailing", ""):
            expected = "INVALID_BODY" if raw in ("[]", "null", '"text"') else "INVALID_JSON"
            self.reject(lambda: self.client.post(self.url + "/apply", data=raw,
                content_type="application/json", headers=self.headers), 400, expected)

    def test_duplicate_keys_at_every_depth_are_rejected(self):
        for raw in ('{"api_version":"a","api_version":"b"}',
                    '{"changes":{"label":"a","label":"b"}}',
                    '{"changes":{"description":{"x":1,"x":2}}}'):
            self.reject(lambda: self.client.post(self.url + "/apply", data=raw,
                content_type="application/json", headers=self.headers), 400, "DUPLICATE_JSON_KEY")

    def test_nonfinite_float_exponent_and_oversize_integer(self):
        for token in ("NaN", "Infinity", "-Infinity", "1e9999", "1.0", "9223372036854775808", "9" * 100):
            raw = '{"expected_working_revision":' + token + '}'
            self.reject(lambda: self.client.post(self.url + "/apply", data=raw,
                content_type="application/json", headers=self.headers), 400, "INVALID_NUMBER")

    def test_body_size_content_type_and_invalid_encoding(self):
        self.reject(lambda: self.client.post(self.url + "/apply", data="x" * (MAX_BODY_BYTES + 1),
            content_type="application/json", headers=self.headers), 413, "BODY_TOO_LARGE")
        self.reject(lambda: self.client.post(self.url + "/apply", data="{}", headers=self.headers),
            400, "INVALID_CONTENT_TYPE")
        self.reject(lambda: self.client.post(self.url + "/apply", data=b'{"x":"\xff"}',
            content_type="application/json", headers=self.headers), 400, "INVALID_JSON")

    def test_missing_required_fields_and_unknown_top_level(self):
        for field in self.body():
            body = self.body()
            del body[field]
            self.reject(lambda: self.apply(body), 400, "MISSING_FIELD")
        self.reject(lambda: self.apply({**self.body(), "extra": True}), 400, "UNKNOWN_FIELD")

    def test_invalid_and_unsupported_api_version(self):
        for version, status, code in [(None, 400, "INVALID_FIELD"), (1, 400, "INVALID_FIELD"),
                                      ("ck-block-editor-v2", 409, "UNSUPPORTED_API_VERSION")]:
            self.reject(lambda: self.apply(self.body(api_version=version)), status, code)

    def test_uuid_fields_and_revision_schema_number_types(self):
        for field in ("request_id", "player_uuid", "expected_stage_workspace_epoch"):
            for value in ("invalid", 1, None):
                self.reject(lambda: self.apply(self.body(**{field: value})), 400, "INVALID_UUID")
        for field in ("schema_version", "expected_working_revision"):
            for value in (True, "1", -1, None):
                self.reject(lambda: self.apply(self.body(**{field: value})), 400, "INVALID_FIELD")
        for value in (None, "", 42):
            self.reject(lambda: self.apply(self.body(schema_id=value)), 400, "INVALID_FIELD")

    def test_changes_shape_empty_and_unknown_domain_field(self):
        for value in (None, [], "label"):
            self.reject(lambda: self.apply(self.body(changes=value)), 400, "INVALID_CHANGES")
        self.reject(lambda: self.apply(self.body(changes={})), 400, "EMPTY_CHANGES")
        self.reject(lambda: self.apply(self.body(changes={"unknown": "x"})), 400, "UNKNOWN_FIELD")

    def test_domain_string_validation_and_atomic_patch(self):
        for changes, field_code in [({"label": 42}, "INVALID_TYPE"),
            ({"label": "x" * 33}, "STRING_TOO_LONG"), ({"label": None}, "NULL_NOT_ALLOWED"),
            ({"label": " \t"}, "FIELD_REQUIRED"),
            ({"label": "valid", "description": "x" * 257}, "STRING_TOO_LONG"),
            ({"label": "x\x00"}, "INVALID_CHARACTER"), ({"label": "\ud800"}, "INVALID_UNICODE")]:
            body = self.reject(lambda: self.apply(self.body(changes=changes)), 422, "VALIDATION_FAILED")
            self.assertEqual(field_code, body["diagnostics"][0]["code"])

    def test_stale_epoch_revision_and_request_correlation(self):
        for kwargs, code in [({"expected_stage_workspace_epoch": str(uuid4())}, "STALE_WORKSPACE_EPOCH"),
                             ({"expected_working_revision": 999}, "STALE_WORKING_REVISION")]:
            command = self.body(**kwargs)
            result = self.reject(lambda: self.apply(command), 409, code)
            self.assertEqual(command["request_id"], result["request_id"])

    def test_schema_mismatch_and_version(self):
        self.reject(lambda: self.apply(self.body(schema_version=2)), 409, "UNSUPPORTED_SCHEMA_VERSION")
        self.reject(lambda: self.apply(self.body(schema_id="ck.block.STOP.metadata")), 409, "SCHEMA_MISMATCH")

    def test_incompatible_and_reload_required(self):
        self.stage.graph_snapshot_version = 99
        self.reject(self.get, 409, "SNAPSHOT_INCOMPATIBLE")
        self.reject(self.apply, 409, "SNAPSHOT_INCOMPATIBLE")
        self.stage.graph_snapshot_version = 1
        self.programs.load(PROGRAM).state = ProgramWorkspaceState.RELOAD_REQUIRED
        self.reject(self.get, 409, "RELOAD_REQUIRED")
        self.reject(self.apply, 409, "RELOAD_REQUIRED")

    def test_no_second_context_source_and_get_query_is_unambiguous(self):
        self.reject(lambda: self.apply({**self.body(), "program_id": str(PROGRAM)}), 400, "CONTEXT_LOCATION_INVALID")
        self.reject(lambda: self.get(query_string={"hall_id": str(HALL), "player_uuid": str(OWNER)}), 400, "CONTEXT_LOCATION_INVALID")
        self.reject(lambda: self.get(query_string=[("player_uuid", str(OWNER)), ("player_uuid", str(uuid4()))]),
            400, "DUPLICATE_QUERY_FIELD")
        self.reject(lambda: self.get(query_string={}), 400, "MISSING_FIELD")
        self.reject(lambda: self.get(data="{}"), 400, "INVALID_BODY")

    def test_preflight_failure_never_calls_domain_or_legacy(self):
        stages = self.app.extensions["blockcommander_stage_service"]
        with patch.object(self.editor, "get") as get, patch.object(self.editor, "apply") as apply, \
                patch.object(stages, "update") as legacy:
            self.reject(lambda: self.get(headers={}), 401, "INVALID_GRAPH_AUTH")
            self.reject(lambda: self.apply(self.body(api_version="other")), 409, "UNSUPPORTED_API_VERSION")
            get.assert_not_called()
            apply.assert_not_called()
            legacy.assert_not_called()

    def test_internal_and_database_errors_are_sanitized(self):
        for exc, code, status in [(RuntimeError("private SQL/password"), "INTERNAL_ERROR", 500),
                                  (psycopg2.OperationalError("private DSN"), "REPOSITORY_ERROR", 503)]:
            with patch.object(self.editor, "apply", side_effect=exc), patch.object(self.app.logger, "exception"):
                body = self.reject(self.apply, status, code)
                self.assertNotIn("private", json.dumps(body))

    def test_http_apply_program_save_reload_and_legacy_update(self):
        result = self.apply()
        self.assertEqual(200, result.status_code)
        saved = self.client.post(f"/api/v1/programs/{PROGRAM}/save", headers=self.headers,
            json={"player_uuid": str(OWNER), "expected_saved_version": 0})
        self.assertEqual(200, saved.status_code)
        fresh = create_app(repository=self.repo, migrate=False, graph_shared_secret=self.SECRET).test_client()
        result = fresh.get(self.url, query_string={"player_uuid": str(OWNER)}, headers=self.headers)
        self.assertEqual("HTTP edited", result.get_json()["values"]["label"])
        legacy = self.client.post(f"/api/v1/stages/{ROOT}/blocks/{self.instance}",
            headers=self.headers, json={"player_uuid": str(OWNER), "label": 42, "description": None})
        self.assertEqual(200, legacy.status_code)
        self.assertEqual(("42", "None"), (self.get().get_json()["values"]["label"],
                                         self.get().get_json()["values"]["description"]))


if __name__ == "__main__":
    unittest.main()
