"""Owner-only editor GET/APPLY on the existing shared Program/Stage workspace.

No routes, sessions, idempotency receipts, persistence or implicit SAVE live here.
"""
from __future__ import annotations

from collections.abc import Mapping
from contextlib import contextmanager
from dataclasses import dataclass, replace
from types import MappingProxyType
from uuid import UUID

from .block_editor_schema import (BlockEditorError, BlockEditorSchema, FieldDiagnostic,
                                  schema_for, validate_changes)
from .graph.port_catalog import PORT_CATALOG_VERSION
from .graph.snapshot import GRAPH_SNAPSHOT_VERSION
from .program import ProgramReloadRequired, ProgramService
from .repository import HallForbidden, HallNotFound


def _uuid(value, field):
    if not isinstance(value, UUID):
        raise BlockEditorError("INVALID_ID", f"{field} must be a UUID.")


@dataclass(frozen=True)
class EditorTarget:
    program_id: UUID
    stage_id: UUID
    instance_id: UUID

    def __post_init__(self):
        for name in ("program_id", "stage_id", "instance_id"):
            _uuid(getattr(self, name), name)

    def as_dict(self):
        return {name: str(getattr(self, name))
                for name in ("program_id", "stage_id", "instance_id")}


@dataclass(frozen=True)
class SchemaReference:
    schema_id: str
    schema_version: int

    def __post_init__(self):
        if type(self.schema_id) is not str or not self.schema_id:
            raise BlockEditorError("INVALID_SCHEMA", "A nonempty schema ID is required.")

    def as_dict(self):
        return {"schema_id": self.schema_id, "schema_version": self.schema_version}


@dataclass(frozen=True)
class EditorValues:
    label: str
    description: str | None

    def as_dict(self):
        return {"label": self.label, "description": self.description}


@dataclass(frozen=True)
class EditorPermissions:
    read: bool = True
    apply: bool = True
    reason_codes: tuple[str, ...] = ()

    def as_dict(self):
        return {"read": self.read, "apply": self.apply, "reason_codes": list(self.reason_codes)}


@dataclass(frozen=True)
class VersionContext:
    stage_workspace_epoch: UUID
    working_revision: int
    stage_saved_version: int
    program_saved_version: int
    stage_dirty: bool
    program_dirty: bool

    def as_dict(self):
        return {"stage_workspace_epoch": str(self.stage_workspace_epoch),
                "working_revision": self.working_revision,
                "stage_saved_version": self.stage_saved_version,
                "program_saved_version": self.program_saved_version,
                "stage_dirty": self.stage_dirty, "program_dirty": self.program_dirty}


@dataclass(frozen=True)
class EditorGetResult:
    target: EditorTarget
    schema: BlockEditorSchema
    values: EditorValues
    version_context: VersionContext
    permissions: EditorPermissions = EditorPermissions()

    @property
    def schema_ref(self):
        return SchemaReference(self.schema.schema_id, self.schema.schema_version)

    def as_dict(self):
        return {"target": {**self.target.as_dict(), "block_type": self.schema.block_type},
                "schema": self.schema.as_dict(), "schema_ref": self.schema_ref.as_dict(),
                "values": self.values.as_dict(), "permissions": self.permissions.as_dict(),
                "version_context": self.version_context.as_dict()}


@dataclass(frozen=True)
class EditorApplyCommand:
    target: EditorTarget
    player_uuid: UUID
    request_id: UUID
    schema_ref: SchemaReference
    expected_stage_workspace_epoch: UUID
    expected_working_revision: int
    changes: Mapping[str, object]

    def __post_init__(self):
        if not isinstance(self.target, EditorTarget) or not isinstance(self.schema_ref, SchemaReference):
            raise BlockEditorError("INVALID_COMMAND", "Typed target and schema reference required.")
        for name in ("player_uuid", "request_id", "expected_stage_workspace_epoch"):
            _uuid(getattr(self, name), name)
        if type(self.expected_working_revision) is not int or self.expected_working_revision < 0:
            raise BlockEditorError("INVALID_REVISION", "Revision must be a nonnegative integer.")
        if not isinstance(self.changes, Mapping):
            raise BlockEditorError("INVALID_CHANGES", "Changes must be an object.")
        object.__setattr__(self, "changes", MappingProxyType(dict(self.changes)))


@dataclass(frozen=True)
class EditorApplyResult:
    request_id: UUID
    modified: bool
    editor: EditorGetResult

    def as_dict(self):
        return {**self.editor.as_dict(), "request_id": str(self.request_id), "modified": self.modified}


class BlockEditorService:
    def __init__(self, programs: ProgramService):
        self.programs = programs

    @contextmanager
    def _locked(self, target, player_uuid):
        if not isinstance(target, EditorTarget):
            raise BlockEditorError("INVALID_TARGET", "Typed editor target required.")
        _uuid(player_uuid, "player_uuid")
        try:
            with self.programs.locked_stage(target.program_id, target.stage_id, player_uuid) as pair:
                yield pair
        except HallForbidden as exc:
            raise BlockEditorError("FORBIDDEN", str(exc)) from exc
        except ProgramReloadRequired as exc:
            raise BlockEditorError("RELOAD_REQUIRED", str(exc)) from exc
        except HallNotFound as exc:
            raise BlockEditorError("STAGE_NOT_FOUND", "Program or Stage was not found.") from exc

    @staticmethod
    def _block(stage, target):
        if stage.snapshot.stage_id != target.stage_id:
            raise BlockEditorError("STAGE_IDENTITY_MISMATCH", "Snapshot belongs to another Stage.")
        block = next((b for b in stage.snapshot.blocks if b.instance_id == target.instance_id), None)
        if block is None:
            raise BlockEditorError("INSTANCE_NOT_FOUND", "Block instance was not found.")
        return block

    @staticmethod
    def _compatible(stage):
        if (stage.graph_snapshot_version != GRAPH_SNAPSHOT_VERSION
                or stage.snapshot.port_catalog_version != PORT_CATALOG_VERSION
                or not stage.compatible_for_graph_mutation):
            raise BlockEditorError("SNAPSHOT_INCOMPATIBLE", "Workspace compatibility must be resolved first.")

    @staticmethod
    def _result(target, program, stage, block, schema):
        return EditorGetResult(target, schema, EditorValues(block.label, block.description),
            VersionContext(stage.workspace_epoch, stage.revision, stage.saved_version,
                           program.saved_version, stage.dirty, program.dirty))

    def get(self, target: EditorTarget, player_uuid: UUID) -> EditorGetResult:
        with self._locked(target, player_uuid) as (program, stage):
            block = self._block(stage, target)
            schema = schema_for(block.type_key)
            self._compatible(stage)
            return self._result(target, program, stage, block, schema)

    def apply(self, command: EditorApplyCommand) -> EditorApplyResult:
        if not isinstance(command, EditorApplyCommand):
            raise BlockEditorError("INVALID_COMMAND", "Typed editor command required.")
        with self._locked(command.target, command.player_uuid) as (program, stage):
            block = self._block(stage, command.target)
            schema = schema_for(block.type_key, schema_id=command.schema_ref.schema_id,
                                schema_version=command.schema_ref.schema_version)
            self._compatible(stage)
            if command.expected_stage_workspace_epoch != stage.workspace_epoch:
                raise BlockEditorError("STALE_WORKSPACE_EPOCH", "Reload the editor after workspace replacement.")
            if command.expected_working_revision != stage.revision:
                raise BlockEditorError("STALE_WORKING_REVISION", "Reload the editor after a concurrent edit.")
            changes = validate_changes(schema, command.changes)
            updated = replace(block, **changes)
            modified = updated != block
            if modified:
                stage.snapshot = stage.snapshot.with_content_change(
                    blocks=tuple(updated if b.instance_id == block.instance_id else b
                                 for b in stage.snapshot.blocks))
                stage.dirty = True
            return EditorApplyResult(command.request_id, modified,
                self._result(command.target, program, stage, updated, schema))
