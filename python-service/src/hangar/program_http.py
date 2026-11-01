"""Versioned HTTP contract for Program aggregates.

This module deliberately contains transport validation and JSON projection only.
Program/Stage hierarchy rules and SAVE semantics remain in ``ProgramService``.
"""
from __future__ import annotations

import uuid
from dataclasses import dataclass
from typing import Any

import psycopg2

from .program import ProgramReloadRequired, ProgramService, ProgramWorkspace
from .repository import (
    HallConflict,
    HallForbidden,
    HallNotFound,
    ProgramSavePlanError,
    ProgramSaveReceipt,
    ProgramVersionConflict,
    SnapshotStorageError,
    StageVersionConflict,
)


PROGRAM_API_VERSION="ck-program-v1"


@dataclass(frozen=True)
class ProgramHttpFailure(Exception):
    status_code: int
    error_code: str
    message: str
    field: str|None=None

    def body(self)->dict[str,Any]:
        diagnostic={"code":self.error_code,"severity":"ERROR","message":self.message}
        if self.field is not None: diagnostic["field"]=self.field
        return {
            "api_version":PROGRAM_API_VERSION,"status":"error",
            "error_code":self.error_code,"message":self.message,
            "diagnostics":[diagnostic],
        }


def parse_uuid(value:Any,field:str)->str:
    try: return str(uuid.UUID(str(value)))
    except (TypeError,ValueError) as exc:
        raise ProgramHttpFailure(400,"INVALID_FIELD",f"A(z) {field} mező UUID kell legyen.",field) from exc


def parse_json_object(raw:Any,*,required:set[str],optional:set[str]=frozenset())->dict[str,Any]:
    if not isinstance(raw,dict):
        raise ProgramHttpFailure(400,"INVALID_BODY","A kérés törzse JSON objektum kell legyen.")
    unknown=set(raw)-required-optional
    if unknown:
        field=sorted(unknown)[0]
        raise ProgramHttpFailure(400,"UNKNOWN_FIELD",f"Ismeretlen mező: {field}.",field)
    missing=required-set(raw)
    if missing:
        field=sorted(missing)[0]
        raise ProgramHttpFailure(400,"MISSING_FIELD",f"Hiányzó mező: {field}.",field)
    return raw


def parse_ensure_request(raw:Any)->dict[str,Any]:
    data=parse_json_object(
        raw,required={"player_uuid","program_block_instance_id"},optional={"stage_name"},
    )
    result={
        "player_uuid":parse_uuid(data["player_uuid"],"player_uuid"),
        "program_block_instance_id":parse_uuid(
            data["program_block_instance_id"],"program_block_instance_id"
        ),
    }
    if "stage_name" in data:
        if not isinstance(data["stage_name"],str) or not data["stage_name"].strip():
            raise ProgramHttpFailure(400,"INVALID_FIELD","A stage_name nem lehet üres.","stage_name")
        if len(data["stage_name"].strip())>128:
            raise ProgramHttpFailure(400,"INVALID_FIELD","A stage_name legfeljebb 128 karakteres.","stage_name")
        result["stage_name"]=data["stage_name"].strip()
    return result


def parse_save_request(raw:Any)->dict[str,Any]:
    data=parse_json_object(raw,required={"player_uuid","expected_saved_version"})
    version=data["expected_saved_version"]
    if not isinstance(version,int) or isinstance(version,bool) or version<0:
        raise ProgramHttpFailure(
            400,"INVALID_FIELD","Az expected_saved_version nemnegatív egész kell legyen.",
            "expected_saved_version",
        )
    return {
        "player_uuid":parse_uuid(data["player_uuid"],"player_uuid"),
        "expected_saved_version":version,
    }


def link_dto(link)->dict[str,Any]:
    return {
        "parent_stage_id":str(link.parent_stage_id),
        "program_block_instance_id":str(link.program_block_instance_id),
        "child_stage_id":str(link.child_stage_id),
        "state":str(link.link_state),
    }


def stage_dto(workspace:ProgramWorkspace,stage_id:uuid.UUID)->dict[str,Any]:
    header=workspace.stage_headers[stage_id]
    loaded=workspace.stage_workspaces.get(stage_id)
    saved_version=(loaded.saved_version if loaded is not None else int(header["saved_version"]))
    return {
        "stage_id":str(stage_id),
        "program_id":str(workspace.program_id),
        "kind":str(header["stage_kind"]),
        "name":str(header["stage_name"]),
        "is_root":stage_id==workspace.root_stage_id,
        "saved_version":saved_version,
        "workspace":None if loaded is None else {
            "epoch":str(loaded.workspace_epoch),
            "working_revision":loaded.revision,
            "dirty":bool(loaded.dirty),
        },
        # These are Stage-domain coordinates, never Hall/world coordinates.
        "domain_layout":{
            "origin":{
                "x":int(header["origin_rel_x"]),
                "y":int(header["origin_rel_y"]),
                "z":int(header["origin_rel_z"]),
            },
            "size":{
                "x":int(header["size_x"]),
                "y":int(header["size_y"]),
                "z":int(header["size_z"]),
            },
        },
        "parents":[link_dto(item) for item in workspace.links if item.child_stage_id==stage_id],
        "children":[link_dto(item) for item in workspace.links if item.parent_stage_id==stage_id],
    }


def stage_projection_dto(
    workspace:ProgramWorkspace,stage_id:uuid.UUID,stage_workspace
)->dict[str,Any]:
    projected=stage_dto(workspace,stage_id)
    projected["workspace"]={
        "epoch":str(stage_workspace.workspace_epoch),
        "working_revision":stage_workspace.revision,
        "dirty":bool(stage_workspace.dirty),
    }
    projected["graph_snapshot_version"]=stage_workspace.graph_snapshot_version
    projected["port_catalog_version"]=stage_workspace.snapshot.port_catalog_version
    projected["blocks"]=[{
        "instance_id":str(block.instance_id),
        "type_key":block.type_key,
        "position":{
            "x":block.position.x,"y":block.position.y,"z":block.position.z,
        },
        "orientation":block.orientation.value,
        "label":block.label,
        "description":block.description,
    } for block in stage_workspace.snapshot.blocks]
    return projected


def program_dto(service:ProgramService,workspace:ProgramWorkspace)->dict[str,Any]:
    with workspace.lock:
        aggregate_dirty=service.is_dirty(workspace.program_id)
        return {
            "program_id":str(workspace.program_id),
            "owner_uuid":str(workspace.owner_uuid),
            "root_stage_id":str(workspace.root_stage_id),
            "saved_version":workspace.saved_version,
            "aggregate_dirty":aggregate_dirty,
            "hierarchy_dirty":bool(workspace.hierarchy_dirty),
            "hierarchy_revision":workspace.hierarchy_revision,
            "workspace_epoch":str(workspace.workspace_epoch),
            "workspace_state":workspace.state.value,
            "stages":[stage_dto(workspace,item) for item in sorted(workspace.stage_headers,key=str)],
            "links":[link_dto(item) for item in workspace.links],
            "mounts":[{
                "hall_id":str(item["hall_id"]),
                "program_id":str(workspace.program_id),
                "current":True,
            } for item in workspace.mounts],
        }


def receipt_dto(receipt:ProgramSaveReceipt,workspace:ProgramWorkspace)->dict[str,Any]:
    return {
        "program_id":str(receipt.program_id),
        "saved_version":receipt.saved_version,
        "changed":bool(receipt.changed),
        "aggregate_dirty":bool(workspace.dirty),
        "hierarchy_revision":workspace.hierarchy_revision,
        "workspace_epoch":str(workspace.workspace_epoch),
        "workspace_state":workspace.state.value,
        "stages":[{
            "stage_id":str(item.stage_id),
            "saved_version":item.saved_version,
            "changed":bool(item.changed),
        } for item in receipt.stages],
    }


def success(**payload:Any)->dict[str,Any]:
    return {"api_version":PROGRAM_API_VERSION,"status":"ok",**payload}


def translate_error(exc:Exception)->ProgramHttpFailure:
    if isinstance(exc,ProgramHttpFailure): return exc
    if isinstance(exc,ProgramReloadRequired):
        return ProgramHttpFailure(409,"RELOAD_REQUIRED",str(exc))
    if isinstance(exc,(ProgramVersionConflict,StageVersionConflict)):
        return ProgramHttpFailure(409,"STALE_VERSION",str(exc))
    if isinstance(exc,HallForbidden):
        return ProgramHttpFailure(403,"FORBIDDEN",str(exc))
    if isinstance(exc,HallNotFound):
        return ProgramHttpFailure(404,"NOT_FOUND",str(exc))
    if isinstance(exc,(ProgramSavePlanError,SnapshotStorageError,ValueError)):
        return ProgramHttpFailure(422,"VALIDATION_ERROR",str(exc))
    if isinstance(exc,HallConflict):
        return ProgramHttpFailure(409,"DOMAIN_CONFLICT",str(exc))
    if isinstance(exc,psycopg2.Error):
        return ProgramHttpFailure(503,"REPOSITORY_ERROR","A Program repository művelet nem sikerült.")
    return ProgramHttpFailure(500,"INTERNAL_ERROR","A Program művelet belső hiba miatt nem sikerült.")

