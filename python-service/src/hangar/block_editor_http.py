"""Strict editor transport adapter; domain rules remain in block_editor.

The shared secret authenticates a trusted server forwarding player identity,
not the player or a browser. No legacy-context fallback is allowed here.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from uuid import UUID

import psycopg2
from flask import current_app, g, jsonify, request
from werkzeug.exceptions import BadRequest, RequestEntityTooLarge

from .block_editor import BlockEditorService, EditorApplyCommand, EditorTarget, SchemaReference
from .block_editor_schema import BlockEditorError
from .graph_application import GRAPH_SECRET_HEADER, GraphTransportAuthError
from .transport_context import TransportContextError


API_VERSION = "ck-block-editor-v1"
EDITOR_ENDPOINTS = frozenset({"block_editor_get", "block_editor_apply"})
MAX_BODY_BYTES = 64 * 1024
_UUID = re.compile(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\Z")
_CONTEXT_FIELDS = {"hall_id", "program_id", "working_copy_id", "transport_context"}
_REQUIRED = {"api_version", "request_id", "player_uuid", "schema_id", "schema_version",
             "expected_stage_workspace_epoch", "expected_working_revision", "changes"}


@dataclass(frozen=True)
class EditorHttpFailure(Exception):
    code: str
    message: str
    status: int = 400
    field: str | None = None


def parse_uuid(value, field):
    if type(value) is not str or _UUID.fullmatch(value) is None:
        raise EditorHttpFailure("INVALID_UUID", "Expected a hyphenated UUID string.", field=field)
    return UUID(value)


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise EditorHttpFailure("DUPLICATE_JSON_KEY", "Duplicate JSON keys are not allowed.")
        result[key] = value
    return result


def _integer(raw):
    if len(raw) > 20:
        raise EditorHttpFailure("INVALID_NUMBER", "JSON integers must fit a signed 64-bit value.")
    value = int(raw)
    if not -(2**63) <= value < 2**63:
        raise EditorHttpFailure("INVALID_NUMBER", "JSON integers must fit a signed 64-bit value.")
    return value


def _unsupported_number(_raw):
    raise EditorHttpFailure("INVALID_NUMBER", "Floating point and non-finite JSON numbers are unsupported.")


def parse_body():
    if not request.is_json:
        raise EditorHttpFailure("INVALID_CONTENT_TYPE", "A JSON content type is required.")
    if request.content_length is not None and request.content_length > MAX_BODY_BYTES:
        raise EditorHttpFailure("BODY_TOO_LARGE", "Editor body exceeds 64 KiB.", 413)
    try:
        raw = request.stream.read(MAX_BODY_BYTES + 1)
        if len(raw) > MAX_BODY_BYTES:
            raise EditorHttpFailure("BODY_TOO_LARGE", "Editor body exceeds 64 KiB.", 413)
        body = json.loads(raw.decode("utf-8"), object_pairs_hook=_pairs,
                          parse_int=_integer, parse_float=_unsupported_number,
                          parse_constant=_unsupported_number)
    except RequestEntityTooLarge as exc:
        raise EditorHttpFailure("BODY_TOO_LARGE", "Editor body exceeds its transport limit.", 413) from exc
    except (ValueError, UnicodeError, RecursionError, BadRequest) as exc:
        raise EditorHttpFailure("INVALID_JSON", "Invalid UTF-8 JSON body.") from exc
    if type(body) is not dict:
        raise EditorHttpFailure("INVALID_BODY", "The body must be a JSON object.")
    return body


def parse_apply(body, target):
    # Correlation is accepted only from a syntactically valid UUID, never raw input.
    if "request_id" in body:
        g.block_editor_request_id = parse_uuid(body["request_id"], "request_id")
    if _CONTEXT_FIELDS.intersection(body):
        raise EditorHttpFailure("CONTEXT_LOCATION_INVALID", "Context belongs in the identity headers.")
    unknown = set(body) - _REQUIRED
    if unknown:
        raise EditorHttpFailure("UNKNOWN_FIELD", "Unknown top-level field.", field=sorted(unknown)[0])
    missing = _REQUIRED - set(body)
    if missing:
        raise EditorHttpFailure("MISSING_FIELD", "Missing required field.", field=sorted(missing)[0])
    if type(body["api_version"]) is not str:
        raise EditorHttpFailure("INVALID_FIELD", "api_version must be a string.", field="api_version")
    if body["api_version"] != API_VERSION:
        raise EditorHttpFailure("UNSUPPORTED_API_VERSION", "Unsupported editor API version.", 409, "api_version")
    if type(body["schema_id"]) is not str or not body["schema_id"]:
        raise EditorHttpFailure("INVALID_FIELD", "schema_id must be a nonempty string.", field="schema_id")
    for field, minimum in (("schema_version", 1), ("expected_working_revision", 0)):
        if type(body[field]) is not int or body[field] < minimum:
            raise EditorHttpFailure("INVALID_FIELD", "Expected an integer in range.", field=field)
    if type(body["changes"]) is not dict:
        raise EditorHttpFailure("INVALID_CHANGES", "changes must be an object.", field="changes")
    if not body["changes"]:
        raise EditorHttpFailure("EMPTY_CHANGES", "At least one field is required.", field="changes")
    return EditorApplyCommand(
        target, parse_uuid(body["player_uuid"], "player_uuid"), g.block_editor_request_id,
        SchemaReference(body["schema_id"], body["schema_version"]),
        parse_uuid(body["expected_stage_workspace_epoch"], "expected_stage_workspace_epoch"),
        body["expected_working_revision"], body["changes"],
    )


_DOMAIN_STATUS = {
    "FORBIDDEN": 403, "STAGE_NOT_FOUND": 404, "INSTANCE_NOT_FOUND": 404,
    "RELOAD_REQUIRED": 409, "SNAPSHOT_INCOMPATIBLE": 409, "STAGE_IDENTITY_MISMATCH": 409,
    "UNSUPPORTED_BLOCK_TYPE": 409, "SCHEMA_MISMATCH": 409, "UNSUPPORTED_SCHEMA_VERSION": 409,
    "STALE_WORKSPACE_EPOCH": 409, "STALE_WORKING_REVISION": 409,
    "VALIDATION_FAILED": 422, "INVALID_ID": 400, "INVALID_TARGET": 400,
    "INVALID_COMMAND": 400, "INVALID_SCHEMA": 400, "INVALID_REVISION": 400,
    "INVALID_CHANGES": 400, "EMPTY_CHANGES": 400, "UNKNOWN_FIELD": 400,
}


class BlockEditorHttpAdapter:
    def __init__(self, editor: BlockEditorService, contexts, auth):
        self.editor, self.contexts, self.auth = editor, contexts, auth

    @staticmethod
    def failure(code, message, status, *, diagnostics=None, field=None):
        if diagnostics is None:
            diagnostic = {"code": code, "message": message}
            if field is not None:
                diagnostic["field"] = field
            diagnostics = [diagnostic]
        body = {"api_version": API_VERSION, "status": "error", "error_code": code,
                "message": message, "diagnostics": diagnostics}
        request_id = getattr(g, "block_editor_request_id", None)
        if request_id is not None:
            body["request_id"] = str(request_id)
        return jsonify(body), status

    def _execute(self, operation):
        try:
            return operation()
        except EditorHttpFailure as exc:
            return self.failure(exc.code, exc.message, exc.status, field=exc.field)
        except GraphTransportAuthError as exc:
            return self.failure(exc.code, str(exc), exc.status_code)
        except TransportContextError as exc:
            return self.failure(exc.code, exc.message, exc.status_code)
        except BlockEditorError as exc:
            status = _DOMAIN_STATUS.get(exc.code, 500)
            if status == 500:
                return self.failure("INTERNAL_ERROR", "Unexpected editor failure.", 500)
            return self.failure(exc.code, str(exc), status,
                                diagnostics=[d.as_dict() for d in exc.diagnostics] or None)
        except psycopg2.Error:
            return self.failure("REPOSITORY_ERROR", "Editor repository is unavailable.", 503)
        except Exception:
            current_app.logger.exception("Block editor request failed")
            return self.failure("INTERNAL_ERROR", "Unexpected editor failure.", 500)

    def preflight(self):
        def prepare():
            self.auth.verify(request.headers.get(GRAPH_SECRET_HEADER))
            target = EditorTarget(**{name: parse_uuid(request.view_args[name], name)
                for name in ("program_id", "stage_id", "instance_id")})
            if self.contexts.explicit(request.headers) is None:
                raise TransportContextError(409, "CONTEXT_REQUIRED", "Explicit editor context is required.")
            if _CONTEXT_FIELDS.intersection(request.args):
                raise EditorHttpFailure("CONTEXT_LOCATION_INVALID", "Context belongs in the identity headers.")
            allowed = {"player_uuid"} if request.method in ("GET", "HEAD") else set()
            if set(request.args) - allowed:
                raise EditorHttpFailure("UNKNOWN_FIELD", "Unexpected query field.")
            if any(len(request.args.getlist(key)) != 1 for key in request.args):
                raise EditorHttpFailure("DUPLICATE_QUERY_FIELD", "Duplicate query fields are not allowed.")
            if request.method in ("GET", "HEAD"):
                if request.content_length or request.stream.read(1):
                    raise EditorHttpFailure("INVALID_BODY", "Editor GET has no request body.")
                if "player_uuid" not in request.args:
                    raise EditorHttpFailure("MISSING_FIELD", "player_uuid is required.", field="player_uuid")
                g.block_editor_player = parse_uuid(request.args["player_uuid"], "player_uuid")
            else:
                g.block_editor_command = parse_apply(parse_body(), target)
            g.transport_context = self.contexts.resolve(request.headers,
                program_id=target.program_id, stage_id=target.stage_id)
            g.block_editor_target = target
        return self._execute(prepare)

    def get(self, **_args):
        return self._execute(lambda: jsonify(api_version=API_VERSION, status="ok",
            **self.editor.get(g.block_editor_target, g.block_editor_player).as_dict()))

    def apply(self, **_args):
        return self._execute(lambda: jsonify(api_version=API_VERSION, status="ok",
            **self.editor.apply(g.block_editor_command).as_dict()))

    def register(self, app):
        base = "/api/v1/programs/<program_id>/stages/<stage_id>/blocks/<instance_id>/editor"
        app.add_url_rule(base, "block_editor_get", self.get, methods=["GET"])
        app.add_url_rule(base + "/apply", "block_editor_apply", self.apply, methods=["POST"])
