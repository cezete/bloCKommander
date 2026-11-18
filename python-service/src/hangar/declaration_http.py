"""Versioned trusted-server transport for B1 declarations; no persistence or retries.

Program RLock nesting and lazy Stage I/O follow DeclarationService.resolve and
ProgramService.locked_stage. No network response is sent while holding that lock.
"""
import hashlib
import json
import re
from dataclasses import dataclass

import psycopg2
from flask import current_app, g, request

from .block_editor_http import EditorHttpFailure, parse_body, parse_uuid
from .declaration_service import DeclarationTarget
from .declarations import DeclarationError, Mutability, VariableType
from .graph_application import GRAPH_SECRET_HEADER, GraphTransportAuthError
from .program import ProgramReloadRequired
from .repository import HallConflict, HallForbidden, HallNotFound, SnapshotCompatibilityError
from .transport_context import TransportContextError

API_VERSION = "ck-declarations-v1"
BASIS_VERSION = "ck-resolution-basis-v1"
PAGE_SIZE = 16
MAX_REQUEST_BYTES = 64 * 1024  # shared strict parse_body limit
MAX_RESPONSE_BYTES = 16 * 1024
DECLARATION_ENDPOINTS = frozenset("declaration_" + name for name in
    ("own", "symbols", "editor", "create", "update", "delete"))
_FIELDS = {"name", "type_key", "mutability"}
_COMMON = {"api_version", "request_id", "player_uuid", "expected_stage_workspace_epoch",
           "expected_working_revision", "expected_resolution_basis"}
_DIGEST = re.compile(r"ck-resolution-basis-v1:[0-9a-f]{64}\Z")


@dataclass(frozen=True)
class HttpFailure(Exception):
    code: str
    status: int = 400


def scope_dto(scope):
    return {"kind": scope.kind, "owner_id": str(scope.owner_id)}


def basis_dto(basis):
    return {"basis_version": BASIS_VERSION, "program_id": str(basis.program_id),
        "query_scope": scope_dto(basis.query_scope), "policy_version": basis.policy_version,
        "program_workspace_epoch": str(basis.program_workspace_epoch),
        "hierarchy_revision": basis.hierarchy_revision,
        "scopes": [{"scope": scope_dto(s.scope), "workspace_epoch": str(s.workspace_epoch),
                    "working_revision": s.working_revision} for s in basis.scopes]}


def digest(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")
    return BASIS_VERSION + ":" + hashlib.sha256(encoded).hexdigest()


def version_dto(view):
    return {"stage_workspace_epoch": str(view.stage_workspace_epoch),
        "working_revision": view.working_revision, "stage_saved_version": view.stage_saved_version,
        "program_saved_version": view.program_saved_version,
        "stage_dirty": view.stage_dirty, "program_dirty": view.program_dirty}


def symbol_dto(symbol, *, shadowed=False):
    return {"declaration": symbol.declaration.as_dict(), "origin_scope": scope_dto(symbol.origin_scope),
        "distance": symbol.distance, "source": "SHADOWED" if shadowed else symbol.source,
        "can_edit_declaration": False if shadowed else symbol.can_edit_declaration,
        "shadowed_by": str(symbol.shadowed_by) if shadowed else None}


class DeclarationHttpAdapter:
    def __init__(self, service, contexts, auth):
        self.service, self.contexts, self.auth = service, contexts, auth

    @staticmethod
    def _response(body, status=200):
        encoded = current_app.json.dumps(body)
        if len(encoded.encode("utf-8")) > MAX_RESPONSE_BYTES:
            raise HttpFailure("RESPONSE_TOO_LARGE", 409)
        return current_app.response_class(encoded, status=status, mimetype="application/json")

    def _execute(self, operation):
        try:
            return operation()
        except (HttpFailure, EditorHttpFailure) as exc:
            code, status = exc.code, exc.status
        except (GraphTransportAuthError, TransportContextError) as exc:
            code, status = exc.code, exc.status_code
        except DeclarationError as exc:
            code = exc.code
            status = {"FORBIDDEN": 403, "STAGE_NOT_FOUND": 404, "DECLARATION_NOT_FOUND": 404,
                "STALE_WORKSPACE_EPOCH": 409, "STALE_WORKING_REVISION": 409,
                "SCOPE_UNAVAILABLE": 409, "STAGE_IDENTITY_MISMATCH": 409,
                "DUPLICATE_DECLARATION_NAME": 422,
                "INVALID_DECLARATION_NAME": 422, "UNKNOWN_VARIABLE_TYPE": 422,
                "INVALID_MUTABILITY": 422, "INVALID_CHANGES": 400,
                "INVALID_IDENTITY": 400, "INVALID_REVISION": 400}.get(code, 500)
            if status == 500:
                code = "INTERNAL_ERROR"
        except HallForbidden:
            code, status = "FORBIDDEN", 403
        except HallNotFound:
            code, status = "STAGE_NOT_FOUND", 404
        except ProgramReloadRequired:
            code, status = "RELOAD_REQUIRED", 409
        except SnapshotCompatibilityError:
            code, status = "SNAPSHOT_INCOMPATIBLE", 409
        except HallConflict:
            code, status = "WORKSPACE_CONFLICT", 409
        except psycopg2.Error:
            code, status = "REPOSITORY_UNAVAILABLE", 503
        except Exception:
            # Do not serialize exception strings (SQL, paths, credentials).
            code, status = "INTERNAL_ERROR", 500
        body = {"api_version": API_VERSION, "status": "error", "error_code": code,
                "message": "Declaration request rejected.", "retry_automatically": False}
        if getattr(g, "declaration_request_id", None) is not None:
            body["request_id"] = str(g.declaration_request_id)
        return current_app.response_class(current_app.json.dumps(body), status=status, mimetype="application/json")

    def preflight(self):
        def prepare():
            self.auth.verify(request.headers.get(GRAPH_SECRET_HEADER))
            g.declaration_target = DeclarationTarget(**{key: parse_uuid(request.view_args[key], key)
                for key in ("program_id", "stage_id")})
            if self.contexts.explicit(request.headers) is None:
                raise HttpFailure("CONTEXT_REQUIRED", 409)
            read = request.method in ("GET", "HEAD")
            allowed = {"player_uuid", "page", "page_token"} if read else set()
            if set(request.args) - allowed:
                raise HttpFailure("UNKNOWN_QUERY_FIELD")
            if any(len(request.args.getlist(key)) != 1 for key in request.args):
                raise HttpFailure("DUPLICATE_QUERY_FIELD")
            if read:
                if request.content_length or request.stream.read(1):
                    raise HttpFailure("INVALID_BODY")
                g.declaration_player = parse_uuid(request.args.get("player_uuid"), "player_uuid")
                raw = request.args.get("page", "0")
                if not re.fullmatch(r"0|[1-9][0-9]{0,8}", raw):
                    raise HttpFailure("INVALID_PAGE")
                g.declaration_page = int(raw)
                token = request.args.get("page_token")
                if token is not None and _DIGEST.fullmatch(token) is None:
                    raise HttpFailure("INVALID_PAGE_TOKEN")
                if g.declaration_page and token is None:
                    raise HttpFailure("PAGE_TOKEN_REQUIRED", 409)
                g.declaration_page_token = token
            else:
                body = parse_body()
                if "request_id" in body:
                    g.declaration_request_id = parse_uuid(body["request_id"], "request_id")
                payload = {"declaration"} if request.method == "POST" else {"changes"} if request.method == "PATCH" else set()
                if set(body) != _COMMON | payload:
                    raise HttpFailure("INVALID_FIELDS")
                if body["api_version"] != API_VERSION:
                    raise HttpFailure("UNSUPPORTED_API_VERSION", 409)
                g.declaration_player = parse_uuid(body["player_uuid"], "player_uuid")
                g.declaration_epoch = parse_uuid(body["expected_stage_workspace_epoch"], "expected_stage_workspace_epoch")
                rev = body["expected_working_revision"]
                if type(rev) is not int or rev < 0:
                    raise HttpFailure("INVALID_REVISION")
                token = body["expected_resolution_basis"]
                if type(token) is not str or _DIGEST.fullmatch(token) is None:
                    raise HttpFailure("INVALID_BASIS")
                if payload:
                    fields = body[next(iter(payload))]
                    if (type(fields) is not dict or not fields or set(fields) - _FIELDS or
                            (request.method == "POST" and set(fields) != _FIELDS)):
                        raise HttpFailure("INVALID_CHANGES")
                    fields = dict(fields)
                    for key, enum, code in (("type_key", VariableType, "UNKNOWN_VARIABLE_TYPE"),
                                            ("mutability", Mutability, "INVALID_MUTABILITY")):
                        if key in fields:
                            try:
                                fields[key] = enum(fields[key])
                            except (ValueError, TypeError):
                                raise HttpFailure(code, 422) from None
                    if "type_key" in fields:
                        fields["variable_type"] = fields.pop("type_key")
                    g.declaration_fields = fields
                if "declaration_id" in request.view_args:
                    g.declaration_id = parse_uuid(request.view_args["declaration_id"], "declaration_id")
                g.declaration_body = body
            target = g.declaration_target
            g.transport_context = self.contexts.resolve(request.headers,
                program_id=target.program_id, stage_id=target.stage_id)
        return self._execute(prepare)

    def _snapshot(self):
        """Caller holds Program lock; immutable view and basis share one instant."""
        own = self.service.list_own(g.declaration_target, g.declaration_player)
        try:
            resolution = self.service.resolve(g.declaration_target, g.declaration_player)
        except DeclarationError as exc:
            if exc.code != "SCOPE_UNAVAILABLE":
                raise
            resolution = None
        return own, resolution

    def _envelope(self, view):
        return {"api_version": API_VERSION, "status": "ok",
            "target": {"program_id": str(view.target.program_id), "stage_id": str(view.target.stage_id)},
            "transport_context": g.transport_context.as_dict(), "version_context": version_dto(view)}

    def read(self, **_args):
        def operation():
            program = self.service.programs.load(g.declaration_target.program_id)
            with program.lock:
                view, resolution = self._snapshot()
                kind = request.endpoint.removeprefix("declaration_")
                if resolution is None and kind == "symbols":
                    raise HttpFailure("SCOPE_UNAVAILABLE", 409)
                basis = basis_dto(resolution.basis) if resolution else None
                envelope = self._envelope(view)
                writable = resolution is not None
                if writable:
                    # Ask B1 itself, rather than maintain a second compatibility
                    # gate in the transport. This check has no mutation side effects.
                    with self.service._locked(g.declaration_target, g.declaration_player) as (aggregate, stage):
                        try:
                            self.service._check(g.declaration_target, aggregate, stage,
                                                view.stage_workspace_epoch, view.working_revision)
                        except SnapshotCompatibilityError:
                            writable = False
                # Saved/dirty status and Hall identity also bind pages; SAVE alone
                # need not advance a working revision. No server-side cursor cache.
                token = digest({"endpoint": kind, "basis": basis, **envelope,
                    "program_workspace_epoch": str(program.workspace_epoch),
                    "hierarchy_revision": program.hierarchy_revision})
                if g.declaration_page_token is not None and g.declaration_page_token != token:
                    raise HttpFailure("STALE_PAGE", 409)
                own = sorted(view.declarations, key=lambda d: (d.name, str(d.declaration_id)))
                symbols = [] if resolution is None else [
                    symbol_dto(s) for s in sorted(resolution.visible,
                        key=lambda s: (s.distance != 0, s.declaration.name, str(s.declaration.declaration_id)))] + [
                    symbol_dto(s, shadowed=True) for s in resolution.shadowed]
                rows = ([{"group": "OWN", "declaration": d.as_dict()} for d in own] if kind in ("own", "editor") else [])
                if kind in ("symbols", "editor"):
                    rows += [{"group": s["source"], **s} for s in symbols]
                page = g.declaration_page
                if page and page * PAGE_SIZE >= len(rows):
                    raise HttpFailure("PAGE_OUT_OF_RANGE")
                envelope.update(resolution_basis=basis,
                    resolution_basis_digest=digest(basis) if basis else None,
                    scope_status="AVAILABLE" if resolution else "UNAVAILABLE",
                    scope_error_code=None if resolution else "SCOPE_UNAVAILABLE",
                    program_workspace_epoch=str(program.workspace_epoch), hierarchy_revision=program.hierarchy_revision,
                    capabilities={"read_own": True, "resolve": resolution is not None,
                                  "create": writable, "edit_local": writable},
                    page={"index": page, "size": PAGE_SIZE, "total_rows": len(rows), "token": token,
                          "has_next": (page + 1) * PAGE_SIZE < len(rows)},
                    rows=rows[page * PAGE_SIZE:(page + 1) * PAGE_SIZE])
                # Serialize under lock, send afterwards. No mutable workspace escapes.
                response = self._response(envelope)
            return response
        return self._execute(operation)

    def mutate(self, **_args):
        def operation():
            program = self.service.programs.load(g.declaration_target.program_id)
            with program.lock:
                view, resolution = self._snapshot()
                if resolution is None:
                    raise HttpFailure("SCOPE_UNAVAILABLE", 409)
                # Preserve precise local CAS reasons, including no-op requests.
                if view.stage_workspace_epoch != g.declaration_epoch:
                    raise HttpFailure("STALE_WORKSPACE_EPOCH", 409)
                if view.working_revision != g.declaration_body["expected_working_revision"]:
                    raise HttpFailure("STALE_WORKING_REVISION", 409)
                if digest(basis_dto(resolution.basis)) != g.declaration_body["expected_resolution_basis"]:
                    raise HttpFailure("STALE_RESOLUTION_BASIS", 409)
                # Receipts have a fixed schema, UUIDs, bounded enum/name fields and
                # no scope chain/list. Reserve their conservative maximum BEFORE CRUD.
                if MAX_RESPONSE_BYTES < 4096:
                    raise HttpFailure("RESPONSE_TOO_LARGE", 409)
                args = (g.declaration_target, g.declaration_player, g.declaration_epoch, view.working_revision)
                method = request.method
                if method == "POST":
                    result = self.service.create(*args, **g.declaration_fields)
                elif method == "PATCH":
                    result = self.service.update(*args, g.declaration_id, g.declaration_fields)
                else:
                    result = self.service.delete(*args, g.declaration_id)
                body = self._envelope(result.view)
                body.update(request_id=str(g.declaration_request_id), operation={"POST": "CREATE", "PATCH": "UPDATE", "DELETE": "DELETE"}[method],
                    declaration_id=str(result.declaration.declaration_id), declaration=result.declaration.as_dict(),
                    modified=result.modified, previous_version=version_dto(view),
                    mutation_scope="PYTHON_WORKING_COPY_ONLY", persisted=False,
                    request_id_semantics="CORRELATION_ONLY", retry_automatically=False)
                response = self._response(body, 201 if method == "POST" else 200)
            return response
        return self._execute(operation)

    def register(self, app):
        base = "/api/v1/programs/<program_id>/stages/<stage_id>"
        for suffix, name in (("declarations", "own"), ("symbols", "symbols"), ("declaration-editor", "editor")):
            app.add_url_rule(base + "/" + suffix, "declaration_" + name, self.read, methods=["GET"])
        app.add_url_rule(base + "/declarations", "declaration_create", self.mutate, methods=["POST"])
        for method, name in (("PATCH", "update"), ("DELETE", "delete")):
            app.add_url_rule(base + "/declarations/<declaration_id>", "declaration_" + name, self.mutate, methods=[method])
