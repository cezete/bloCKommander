"""Versioned LET transport over the shared Program and Stage workspaces."""
from collections import OrderedDict
import hashlib
import json
import re
from threading import Lock

import psycopg2
from flask import current_app, g, jsonify, request

from .block_editor_http import EditorHttpFailure as HttpFailure, parse_body, parse_uuid
from .builder import HallValidationError
from .declaration_http import BASIS_VERSION, basis_dto, digest
from .declaration_service import DeclarationTarget
from .declarations import DeclarationError
from .graph.model import CardinalDirection, GridPos
from .graph.snapshot import thaw_json
from .graph_application import GRAPH_SECRET_HEADER, GraphTransportAuthError
from .hall_projection import project_stage
from .program_http import link_dto, stage_dto
from .repository import HallConflict, HallForbidden, HallNotFound
from .transport_context import TransportContextError
from .value_model import ValueDomainError


API_VERSION = "ck-value-v1"
VALUE_ENDPOINTS = {"let_get", "let_patch"}
_VERSION_FIELDS = {"expected_stage_workspace_epoch", "expected_working_revision", "expected_resolution_basis"}
_BASIS_DIGEST = re.compile(re.escape(BASIS_VERSION) + r":[0-9a-f]{64}")
_COMMON = {"api_version", "player_uuid", "request_id"} | _VERSION_FIELDS
_FIELDS = {
    "place": {"type_key", "coordinate_space", "x", "y", "z", "orientation"},
    "patch": {"target_id"},
    "ensure": {"program_block_instance_id", "owner_role"},
    "delete": set(),
}


class ValueHttpAdapter:
    def __init__(self, values, contexts, auth, *, receipt_limit=1024):
        self.values = values
        self.programs = values.programs
        self.contexts = contexts
        self.auth = auth
        self.receipt_limit = receipt_limit
        self._receipts = OrderedDict()
        self._receipt_lock = Lock()

    def register(self, app):
        base = "/api/v1/programs/<program_id>/stages/<stage_id>/blocks/<instance_id>/value"
        app.add_url_rule(base, "let_get", self.get, methods=["GET"])
        app.add_url_rule(base, "let_patch", self.patch, methods=["PATCH"])

    @staticmethod
    def dispatch(endpoint, body):
        if not isinstance(body, dict):
            return None
        versioned = body.get("api_version") == API_VERSION
        if endpoint == "place_stage_block" and (versioned or str(body.get("type_key", "")).upper() == "LET"):
            return "place"
        if endpoint == "ensure_program_child" and (versioned or body.get("owner_role") == "LET_RHS"):
            return "ensure"
        if endpoint == "delete_stage_block" and (
                versioned or "expected_resolution_basis" in body):
            return "delete"
        return None

    @staticmethod
    def failure(code, message, status, *, field=None):
        diagnostic = {"code": code, "severity": "ERROR", "message": message}
        if field is not None:
            diagnostic["field_name"] = field
        body = dict(api_version=API_VERSION, status="error", error_code=code,
                    message=message, diagnostics=[diagnostic], retry_automatically=False)
        if hasattr(g, "value_request_id"):
            body["request_id"] = str(g.value_request_id)
        return jsonify(body), status

    def _execute(self, operation):
        try:
            return operation()
        except HttpFailure as exc:
            return self.failure(exc.code, exc.message, exc.status, field=exc.field)
        except TransportContextError as exc:
            return self.failure(exc.code, exc.message, exc.status_code)
        except GraphTransportAuthError as exc:
            return self.failure(exc.code, str(exc), exc.status_code)
        except (ValueDomainError, DeclarationError) as exc:
            status = {
                "BLOCK_NOT_FOUND": 404, "OCCUPIED_POSITION": 409, "STALE_VALUE_BASIS": 409,
                "INCOMPATIBLE_SNAPSHOT": 409, "SCOPE_UNAVAILABLE": 409, "VALUE_HTTP_REQUIRED": 409,
                "CONTROL_STAGE_REQUIRED": 409, "REQUEST_ID_CONFLICT": 409, "INVALID_PLACEMENT": 400,
            }.get(exc.code, 422)
            return self.failure(exc.code, str(exc), status)
        except HallForbidden as exc:
            return self.failure("FORBIDDEN", str(exc), 403)
        except HallNotFound as exc:
            return self.failure("STAGE_NOT_FOUND", str(exc), 404)
        except HallConflict as exc:
            return self.failure("CONFLICT", str(exc), 409)
        except HallValidationError as exc:
            return self.failure("INVALID_VALUE_REQUEST", str(exc), 422)
        except psycopg2.Error:
            return self.failure("REPOSITORY_ERROR", "VALUE repository operation failed.", 503)
        except Exception:
            current_app.logger.exception("LET request failed")
            return self.failure("INTERNAL_ERROR", "Unexpected LET operation failure.", 500)

    def get(self, **route):
        return self._execute(lambda: self._handle("get", route))

    def patch(self, **route):
        return self._execute(lambda: self._handle("patch", route))

    def extended(self, operation, route):
        return self._execute(lambda: self._handle(operation, route, buffered=True))

    def _handle(self, operation, route, *, buffered=False):
        self.auth.verify(request.headers.get(GRAPH_SECRET_HEADER))
        if self.contexts.explicit(request.headers) is None:
            raise HttpFailure("CONTEXT_REQUIRED", "Explicit Hall, Program and WorkingCopy context is required.", 409)
        stage_id = parse_uuid(route["stage_id"], "stage_id")
        program_id = parse_uuid(route["program_id"], "program_id") if "program_id" in route else None
        block_id = parse_uuid(route["instance_id"], "instance_id") if "instance_id" in route else None

        if operation == "get":
            if set(request.args) != {"player_uuid"} or len(request.args.getlist("player_uuid")) != 1:
                raise HttpFailure("INVALID_QUERY", "Expected one player_uuid query parameter.")
            if request.content_length or request.stream.read(1):
                raise HttpFailure("INVALID_BODY", "LET GET does not accept a body.")
            player = parse_uuid(request.args["player_uuid"], "player_uuid")
        else:
            if request.args:
                raise HttpFailure("INVALID_QUERY", "LET mutations do not accept query parameters.")
            body = parse_body(request.get_data() if buffered else None)
            player, epoch = self._parse_command(operation, body)
            if operation == "ensure":
                block_id = parse_uuid(body["program_block_instance_id"], "program_block_instance_id")

        context = self.contexts.resolve(request.headers, program_id=program_id, stage_id=stage_id)
        target = DeclarationTarget(context.program_id, stage_id)
        hall = self.programs.repository.get(str(context.mount.hall_id))
        if str(hall["owner_uuid"]) != str(player):
            raise HallForbidden("Only the Hall owner may access LET configuration.")

        with self.programs.locked_stage(target.program_id, stage_id, player) as (program, stage):
            if stage.stage["stage_kind"] not in ("ROOT", "PROGRAM_BODY"):
                raise ValueDomainError("CONTROL_STAGE_REQUIRED", "LET requires a control Stage.")
            if operation == "get":
                block = self._let_block(stage, block_id)
                return jsonify(self._envelope(context, program, stage, self.values.view(target, player).basis,
                                              let=self._let_dto(program, stage_id, block)))

            if epoch != stage.workspace_epoch:
                raise ValueDomainError("STALE_VALUE_BASIS", "The Stage workspace epoch changed.")
            key = (context.mount, stage_id, epoch, player, g.value_request_id)
            fingerprint = hashlib.sha256(json.dumps(
                [operation, str(block_id), body], sort_keys=True, separators=(",", ":"),
            ).encode("utf-8")).digest()
            with self._receipt_lock:
                recorded = self._receipts.get(key)
                if recorded is not None:
                    self._receipts.move_to_end(key)
            if recorded is not None:
                prior, receipt, status = recorded
                if prior != fingerprint:
                    raise ValueDomainError("REQUEST_ID_CONFLICT", "This request_id has a different payload.")
                return jsonify(receipt), status

            def write():
                basis = self.values.view(target, player).basis
                if (body["expected_working_revision"] != basis.working_revision or
                        body["expected_resolution_basis"] != digest(basis_dto(basis.resolution_basis))):
                    raise ValueDomainError("STALE_VALUE_BASIS", "Reload the Stage and visible declarations.")
                fields, status = self._mutate(operation, body, target, player, basis, block_id, program, stage, hall)
                response = self._envelope(context, program, stage, self.values.view(target, player).basis, **fields)
                response["request_id"] = str(g.value_request_id)
                return jsonify(response), status

            response, status = self._execute(write)
            with self._receipt_lock:
                self._receipts[key] = (fingerprint, response.get_json(), status)
                if len(self._receipts) > self.receipt_limit:
                    self._receipts.popitem(last=False)
            return response, status

    @staticmethod
    def _parse_command(operation, body):
        if "request_id" in body:
            g.value_request_id = parse_uuid(body["request_id"], "request_id")
        if set(body) != _COMMON | _FIELDS[operation]:
            raise HttpFailure("INVALID_FIELDS", "Unexpected or missing LET request fields.")
        if body["api_version"] != API_VERSION:
            raise HttpFailure("UNSUPPORTED_API_VERSION", "Unsupported LET API version.", 409)
        if operation == "ensure" and body["owner_role"] != "LET_RHS":
            raise HttpFailure("INVALID_OWNER_ROLE", "LET children require the LET_RHS owner role.")
        revision = body["expected_working_revision"]
        if type(revision) is not int or revision < 0:
            raise HttpFailure("INVALID_REVISION", "Working revision must be a nonnegative integer.")
        token = body["expected_resolution_basis"]
        if type(token) is not str or _BASIS_DIGEST.fullmatch(token) is None:
            raise HttpFailure("INVALID_BASIS", "Expected a declaration resolution basis digest.")
        if operation == "patch":
            parse_uuid(body["target_id"], "target_id")
        return (parse_uuid(body["player_uuid"], "player_uuid"),
                parse_uuid(body["expected_stage_workspace_epoch"], "expected_stage_workspace_epoch"))

    @staticmethod
    def _let_block(stage, block_id):
        block = next((item for item in stage.snapshot.blocks if item.instance_id == block_id), None)
        if block is None:
            raise ValueDomainError("BLOCK_NOT_FOUND", "No such block in the Stage.")
        if block.type_key != "LET":
            raise ValueDomainError("BLOCK_TYPE_MISMATCH", "The block is not a LET.")
        return block

    @staticmethod
    def _rhs_link(program, stage_id, block_id):
        return next((link for link in program.links if link.parent_stage_id == stage_id and
                     link.program_block_instance_id == block_id and link.owner_role == "LET_RHS"), None)

    def _let_dto(self, program, stage_id, block):
        link = self._rhs_link(program, stage_id, block.instance_id)
        config = thaw_json(block.semantic_config)
        return dict(instance_id=str(block.instance_id), type_key="LET", semantic_config=config,
                    target_id=config.get("target_id"),
                    rhs_link=link_dto(link) if link and link.link_state == "ACTIVE" else None)

    @staticmethod
    def _envelope(context, program, stage_workspace, basis, **fields):
        resolution = basis_dto(basis.resolution_basis)
        return dict(api_version=API_VERSION, status="ok", transport_context=context.as_dict(),
                    program_id=str(program.program_id), stage_id=str(stage_workspace.snapshot.stage_id),
                    stage_kind=stage_workspace.stage["stage_kind"], stage_workspace_epoch=str(basis.workspace_epoch),
                    working_revision=basis.working_revision, stage_saved_version=stage_workspace.saved_version,
                    stage_dirty=stage_workspace.dirty, program_saved_version=program.saved_version,
                    program_workspace_epoch=str(program.workspace_epoch), hierarchy_revision=program.hierarchy_revision,
                    aggregate_dirty=program.dirty, resolution_basis=resolution,
                    resolution_basis_digest=digest(resolution), **fields)

    def _mutate(self, operation, body, target, player, basis, block_id, program, stage, hall):
        stage_id = target.stage_id
        if operation == "place":
            if body["type_key"] != "LET" or body["coordinate_space"] != "STAGE_RELATIVE":
                raise ValueDomainError("INVALID_PLACEMENT", "Expected LET and Stage-relative coordinates.")
            try:
                orientation = CardinalDirection(body["orientation"])
            except (ValueError, TypeError) as exc:
                raise ValueDomainError("INVALID_ORIENTATION", "Expected a cardinal orientation.") from exc
            block_id = self.values.place_block(target, player, basis, "LET",
                GridPos(body["x"], body["y"], body["z"]), orientation=orientation)
            block = self._let_block(stage, block_id)
            return dict(let=self._let_dto(program, stage_id, block),
                        stage=self._projection(stage, program, hall)), 201

        block = self._let_block(stage, block_id)
        if operation == "patch":
            self.values.set_let_target(target, player, basis, block_id, parse_uuid(body["target_id"], "target_id"))
            block = self._let_block(stage, block_id)
            return dict(let=self._let_dto(program, stage_id, block)), 200
        if operation == "ensure":
            previous = self._rhs_link(program, stage_id, block_id)
            child = self.values.ensure_rhs(target, player, basis, block_id)
            link = self._rhs_link(program, stage_id, block_id)
            created = previous is None
            return dict(created=created, link=link_dto(link), child=stage_dto(program, child.stage_id)), 201 if created else 200

        self.programs.stages.delete(str(stage_id), str(block_id), {
            "player_uuid": str(player), "workspace_epoch": str(basis.workspace_epoch),
            "expected_working_revision": basis.working_revision,
        })
        link = self._rhs_link(program, stage_id, block_id)
        return dict(deleted_instance_id=str(block_id), link=link_dto(link) if link else None,
                    stage=self._projection(stage, program, hall)), 200

    def _projection(self, stage, program, hall):
        view = self.programs.stages.view(stage)
        return project_stage({**view, "program_dirty": program.dirty}, stage.stage, hall)
