"""Program aggregate workspaces coordinating the existing Stage domain."""
from __future__ import annotations

import threading
import uuid
from contextlib import ExitStack
from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any, Callable

from .graph.port_catalog import PORT_CATALOG_VERSION
from .graph.snapshot import GRAPH_SNAPSHOT_VERSION, ProgramValidationStatus, StageSnapshot
from .repository import (
    HallConflict,
    HallForbidden,
    HallNotFound,
    HallRepository,
    LoadedProgram,
    NewStageHeader,
    ProgramSavePlan,
    ProgramSaveReceipt,
    ProgramStageLink,
    ProgramStageSavePlan,
    ProgramVersionConflict,
)
from .stage import StageService, StageWorkspace


class ProgramWorkspaceState(str,Enum):
    ACTIVE="ACTIVE"
    RELOAD_REQUIRED="RELOAD_REQUIRED"


class ProgramReloadRequired(HallConflict):
    pass


@dataclass(frozen=True)
class ChildEnsureResult:
    workspace: StageWorkspace
    link: ProgramStageLink
    created: bool


@dataclass
class ProgramWorkspace:
    program_id: uuid.UUID
    root_stage_id: uuid.UUID
    owner_uuid: str
    saved_version: int
    stage_headers: dict[uuid.UUID,dict[str,Any]]
    links: tuple[ProgramStageLink,...]
    mounts: tuple[dict[str,Any],...]=()
    stage_workspaces: dict[uuid.UUID,StageWorkspace]=field(default_factory=dict)
    new_stage_ids: set[uuid.UUID]=field(default_factory=set)
    hierarchy_dirty: bool=False
    hierarchy_revision: int=0
    state: ProgramWorkspaceState=ProgramWorkspaceState.ACTIVE
    workspace_epoch: uuid.UUID=field(default_factory=uuid.uuid4)
    lock: threading.RLock=field(default_factory=threading.RLock)

    @property
    def dirty(self)->bool:
        return (
            self.state is ProgramWorkspaceState.RELOAD_REQUIRED
            or self.hierarchy_dirty
            or bool(self.new_stage_ids)
            or any(workspace.dirty for workspace in self.stage_workspaces.values())
        )


class ProgramService:
    """Coordinate StageWorkspace objects; StageSnapshot remains the graph authority.

    Managed Stage mutations acquire Program then Stage locks. A legacy Stage SAVE
    delegates here once its Program is loaded. Unsupported out-of-process direct
    Stage writers cannot overwrite this workspace: Stage CAS rejects SAVE, while
    ``is_dirty()`` detects their persisted version drift and fences the Program.
    """

    def __init__(
        self,repository:HallRepository,stages:StageService,*,
        id_factory:Callable[[],uuid.UUID]=uuid.uuid4,
    ):
        self.repository=repository
        self.stages=stages
        self._id_factory=id_factory
        self._programs:dict[uuid.UUID,ProgramWorkspace]={}
        self._stage_to_program:dict[uuid.UUID,uuid.UUID]={}
        self._guard=threading.RLock()
        stages.attach_program_coordinator(
            lock_resolver=self._lock_for_stage,
            save_delegate=self._legacy_stage_save,
            block_deleted=self._stage_block_deleted,
            block_policy=self._assert_block_allowed_for_stage,
            stage_guard=self._assert_stage_active,
        )

    def load(self,program_id:str|uuid.UUID)->ProgramWorkspace:
        normalized=uuid.UUID(str(program_id))
        with self._guard:
            existing=self._programs.get(normalized)
            if existing is not None: return existing
            return self._install(self.repository.load_program(str(normalized)))

    def for_hall(self,hall_id:str|uuid.UUID)->ProgramWorkspace:
        loaded=self.repository.load_program_for_hall(str(hall_id))
        program_id=uuid.UUID(str(loaded.program["program_id"]))
        with self._guard:
            return self._programs.get(program_id) or self._install(loaded)

    def for_stage(self,stage_id:str|uuid.UUID)->ProgramWorkspace:
        normalized=uuid.UUID(str(stage_id))
        with self._guard:
            program_id=self._stage_to_program.get(normalized)
            if program_id is not None: return self._programs[program_id]
            loaded=self.repository.load_program_for_stage(str(normalized))
            loaded_program_id=uuid.UUID(str(loaded.program["program_id"]))
            return self._programs.get(loaded_program_id) or self._install(loaded)

    def transient_stage_program_id(self,stage_id:uuid.UUID)->uuid.UUID|None:
        """Read identity of an existing unsaved Stage without installing cache entries."""
        with self._guard:
            program_id=self._stage_to_program.get(stage_id)
            workspace=self._programs.get(program_id)
        if workspace is None:return None
        with workspace.lock:
            return workspace.program_id if stage_id in workspace.new_stage_ids else None

    def _install(self,loaded:LoadedProgram)->ProgramWorkspace:
        program_id=uuid.UUID(str(loaded.program["program_id"]))
        headers={
            uuid.UUID(str(row["stage_id"])):dict(row) for row in loaded.stages
        }
        root_stage_id=uuid.UUID(str(loaded.program["root_stage_id"]))
        if root_stage_id not in headers:
            raise HallConflict("A Program root Stage-e hiányzik.")
        workspace=ProgramWorkspace(
            program_id,root_stage_id,str(loaded.program["owner_uuid"]),
            int(loaded.program["saved_version"]),headers,tuple(loaded.links),
            tuple(dict(row) for row in loaded.mounts),
        )
        for stage_id in headers:
            owner=self._stage_to_program.get(stage_id)
            if owner is not None and owner!=program_id:
                raise HallConflict("Egy Stage több Programhoz lenne rendelve.")
        self._programs[program_id]=workspace
        for stage_id in headers: self._stage_to_program[stage_id]=program_id
        self._validate_hierarchy(workspace,workspace.links)
        return workspace

    def _lock_for_stage(self,stage_id:str)->threading.RLock|None:
        try: normalized=uuid.UUID(str(stage_id))
        except (TypeError,ValueError): return None
        with self._guard:
            program_id=self._stage_to_program.get(normalized)
            workspace=None if program_id is None else self._programs.get(program_id)
        if workspace is None:
            try: workspace=self.for_stage(normalized)
            except HallNotFound: return None
        with workspace.lock:
            self._active(workspace)
            self._load_stage_locked(workspace,normalized)
        return workspace.lock

    @staticmethod
    def _active(workspace:ProgramWorkspace)->None:
        if workspace.state is ProgramWorkspaceState.RELOAD_REQUIRED:
            raise ProgramReloadRequired(
                "A Program mentése már commitolt, de a memóriabeli állapot nem "
                "alkalmazható biztonságosan; teljes újratöltés szükséges."
            )

    @staticmethod
    def _authorize(workspace:ProgramWorkspace,player_uuid:str)->None:
        if str(workspace.owner_uuid)!=str(player_uuid):
            raise HallForbidden("Ezt a programot csak a tulajdonosa szerkesztheti vagy mentheti.")

    def _workspace_for_stage_id(self,stage_id:uuid.UUID)->ProgramWorkspace:
        with self._guard:
            program_id=self._stage_to_program.get(stage_id)
            workspace=None if program_id is None else self._programs.get(program_id)
        if workspace is None: raise HallNotFound("A Stage nincs betöltött Programhoz rendelve.")
        return workspace

    def _load_stage_locked(
        self,workspace:ProgramWorkspace,stage_id:uuid.UUID
    )->StageWorkspace:
        result=workspace.stage_workspaces.get(stage_id)
        if result is not None: return result
        if stage_id not in workspace.stage_headers:
            raise HallNotFound("Nincs ilyen Stage a Programban.")
        result=self.stages._workspace(str(stage_id))
        if uuid.UUID(str(result.stage.get("program_id")))!=workspace.program_id:
            raise HallConflict("A Stage másik Programhoz tartozik.")
        workspace.stage_workspaces[stage_id]=result
        return result

    def root(self,program_id:str|uuid.UUID)->StageWorkspace:
        workspace=self.load(program_id)
        with workspace.lock:
            self._active(workspace)
            return self._load_stage_locked(workspace,workspace.root_stage_id)

    def stage(
        self,program_id:str|uuid.UUID,stage_id:str|uuid.UUID
    )->StageWorkspace:
        workspace=self.load(program_id);normalized=uuid.UUID(str(stage_id))
        with workspace.lock:
            self._active(workspace)
            return self._load_stage_locked(workspace,normalized)

    def children(
        self,program_id:str|uuid.UUID,parent_stage_id:str|uuid.UUID
    )->tuple[StageWorkspace,...]:
        workspace=self.load(program_id);parent=uuid.UUID(str(parent_stage_id))
        with workspace.lock:
            self._active(workspace)
            if parent not in workspace.stage_headers: raise HallNotFound("Nincs ilyen parent Stage.")
            child_ids=sorted((
                link.child_stage_id for link in workspace.links
                if link.parent_stage_id==parent and link.link_state=="ACTIVE"
            ),key=str)
            return tuple(self._load_stage_locked(workspace,item) for item in child_ids)

    @staticmethod
    def assert_block_allowed(stage_kind:str,type_key:str)->None:
        normalized_kind=str(stage_kind).upper();normalized_type=str(type_key).upper()
        if normalized_kind not in ("ROOT","PROGRAM_BODY"):
            raise HallConflict("Ismeretlen Stage-kind.")
        if normalized_kind=="PROGRAM_BODY" and normalized_type in ("CLASS","INTERFACE"):
            raise HallConflict(f"{normalized_type} blokk nem helyezhető PROGRAM_BODY Stage-be.")

    def _assert_block_allowed_for_stage(self,stage_id:str,type_key:str)->None:
        try: workspace=self._workspace_for_stage_id(uuid.UUID(str(stage_id)))
        except HallNotFound: return
        with workspace.lock:
            self._active(workspace)
            header=workspace.stage_headers[uuid.UUID(str(stage_id))]
            self.assert_block_allowed(str(header["stage_kind"]),type_key)

    def _assert_stage_active(self,stage_id:str)->None:
        try: workspace=self._workspace_for_stage_id(uuid.UUID(str(stage_id)))
        except HallNotFound: return
        self._active(workspace)

    @staticmethod
    def _find_program_block(parent:StageWorkspace,block_id:uuid.UUID)->None:
        block=next((item for item in parent.snapshot.blocks if item.instance_id==block_id),None)
        if block is None or block.type_key!="PROGRAM":
            raise HallConflict("A child Stage csak létező PROGRAM blokkhoz kapcsolható.")

    @staticmethod
    def _validate_hierarchy(
        workspace:ProgramWorkspace,links:tuple[ProgramStageLink,...]
    )->None:
        stage_ids=set(workspace.stage_headers)
        active_blocks=set();active_children=set()
        adjacency={stage_id:[] for stage_id in stage_ids}
        for link in links:
            if link.parent_stage_id not in stage_ids or link.child_stage_id not in stage_ids:
                raise HallConflict("A hierarchy-link másik Programra vagy hiányzó Stage-re mutat.")
            if link.child_stage_id==workspace.root_stage_id:
                raise HallConflict("A root Stage nem lehet child.")
            if link.parent_stage_id==link.child_stage_id:
                raise HallConflict("A Stage nem lehet saját maga gyermeke.")
            if link.link_state not in ("ACTIVE","RETAINED"):
                raise HallConflict("Ismeretlen hierarchy-link állapot.")
            if link.link_state=="ACTIVE":
                block_key=(link.parent_stage_id,link.program_block_instance_id)
                if block_key in active_blocks: raise HallConflict("Egy PROGRAM blokknak több aktív childja van.")
                if link.child_stage_id in active_children: raise HallConflict("Egy childnak több aktív parentje van.")
                active_blocks.add(block_key);active_children.add(link.child_stage_id)
                adjacency[link.parent_stage_id].append(link.child_stage_id)
        visiting=set();visited=set()
        def visit(stage_id:uuid.UUID)->None:
            if stage_id in visiting: raise HallConflict("A Stage-hierarchia ciklust tartalmaz.")
            if stage_id in visited: return
            visiting.add(stage_id)
            for child_id in adjacency[stage_id]: visit(child_id)
            visiting.remove(stage_id);visited.add(stage_id)
        for stage_id in sorted(stage_ids,key=str): visit(stage_id)

    def ensure_child(
        self,program_id:str|uuid.UUID,parent_stage_id:str|uuid.UUID,
        program_block_instance_id:str|uuid.UUID,player_uuid:str,*,
        stage_name:str|None=None,
    )->StageWorkspace:
        return self.ensure_child_result(
            program_id,parent_stage_id,program_block_instance_id,player_uuid,
            stage_name=stage_name,
        ).workspace

    def ensure_child_result(
        self,program_id:str|uuid.UUID,parent_stage_id:str|uuid.UUID,
        program_block_instance_id:str|uuid.UUID,player_uuid:str,*,
        stage_name:str|None=None,
    )->ChildEnsureResult:
        workspace=self.load(program_id);parent_id=uuid.UUID(str(parent_stage_id))
        block_id=uuid.UUID(str(program_block_instance_id))
        with workspace.lock:
            self._active(workspace);self._authorize(workspace,player_uuid)
            parent=self._load_stage_locked(workspace,parent_id)
            with parent.lock:
                self._find_program_block(parent,block_id)
                existing=next((link for link in workspace.links
                    if link.parent_stage_id==parent_id
                    and link.program_block_instance_id==block_id),None)
                if existing is not None:
                    if existing.link_state=="RETAINED":
                        replacement=replace(existing,link_state="ACTIVE")
                        candidate=tuple(replacement if item==existing else item for item in workspace.links)
                        self._validate_hierarchy(workspace,candidate)
                        workspace.links=candidate;workspace.hierarchy_dirty=True
                        workspace.hierarchy_revision+=1
                        existing=replacement
                    return ChildEnsureResult(
                        self._load_stage_locked(workspace,existing.child_stage_id),
                        existing,False,
                    )

                child_id=self._id_factory()
                while child_id in workspace.stage_headers: child_id=self._id_factory()
                parent_stage=parent.stage
                stage={
                    "stage_id":str(child_id),"program_id":str(workspace.program_id),
                    "stage_kind":"PROGRAM_BODY","hall_id":None,
                    "stage_name":stage_name or "Program body",
                    "origin_rel_x":int(parent_stage.get("origin_rel_x",0)),
                    "origin_rel_y":int(parent_stage.get("origin_rel_y",0)),
                    "origin_rel_z":int(parent_stage.get("origin_rel_z",0)),
                    "size_x":int(parent_stage["size_x"]),
                    "size_y":int(parent_stage["size_y"]),
                    "size_z":int(parent_stage["size_z"]),
                    "saved_version":0,"owner_uuid":workspace.owner_uuid,
                    "world_uuid":parent_stage.get("world_uuid"),
                    "hall_min_x":None,
                    "hall_min_y":None,
                    "hall_min_z":None,
                    "graph_snapshot_version":GRAPH_SNAPSHOT_VERSION,
                    "port_catalog_version":PORT_CATALOG_VERSION,
                }
                snapshot=StageSnapshot(
                    child_id,(),(),(),(),0,None,None,
                    ProgramValidationStatus.NOT_CHECKED,PORT_CATALOG_VERSION,
                )
                child=StageWorkspace(
                    stage,snapshot,0,True,
                    graph_snapshot_version=GRAPH_SNAPSHOT_VERSION,
                    compatible_for_graph_mutation=True,
                )
                workspace.stage_headers[child_id]=dict(stage)
                workspace.stage_workspaces[child_id]=child
                workspace.new_stage_ids.add(child_id)
                with self._guard: self._stage_to_program[child_id]=workspace.program_id
                self.stages._register_workspace(child)
                link=ProgramStageLink(parent_id,block_id,child_id,"ACTIVE")
                candidate=(*workspace.links,link)
                self._validate_hierarchy(workspace,candidate)
                workspace.links=candidate;workspace.hierarchy_dirty=True
                workspace.hierarchy_revision+=1
                return ChildEnsureResult(child,link,True)

    def link_child(
        self,program_id:str|uuid.UUID,parent_stage_id:str|uuid.UUID,
        program_block_instance_id:str|uuid.UUID,child_stage_id:str|uuid.UUID,
        player_uuid:str,*,link_state:str="ACTIVE",
    )->None:
        workspace=self.load(program_id);parent_id=uuid.UUID(str(parent_stage_id))
        block_id=uuid.UUID(str(program_block_instance_id));child_id=uuid.UUID(str(child_stage_id))
        with workspace.lock:
            self._active(workspace);self._authorize(workspace,player_uuid)
            if parent_id not in workspace.stage_headers or child_id not in workspace.stage_headers:
                raise HallConflict("Cross-program vagy hiányzó Stage-link nem engedélyezett.")
            parent=self._load_stage_locked(workspace,parent_id)
            child=self._load_stage_locked(workspace,child_id)
            with ExitStack() as stack:
                for item in sorted((parent,child),key=lambda ws:str(ws.snapshot.stage_id)):
                    stack.enter_context(item.lock)
                self._find_program_block(parent,block_id)
                link=ProgramStageLink(parent_id,block_id,child_id,link_state)
                same=next((item for item in workspace.links
                    if item.parent_stage_id==parent_id
                    and item.program_block_instance_id==block_id),None)
                if same==link: return
                if same is not None and same.link_state=="ACTIVE":
                    raise HallConflict("A PROGRAM blokknak már van aktív childja.")
                candidate=tuple(item for item in workspace.links if item!=same)+(link,)
                self._validate_hierarchy(workspace,candidate)
                workspace.links=candidate;workspace.hierarchy_dirty=True
                workspace.hierarchy_revision+=1

    def _stage_block_deleted(self,stage_id:str,block_id:uuid.UUID,type_key:str)->None:
        if type_key!="PROGRAM": return
        try: workspace=self._workspace_for_stage_id(uuid.UUID(str(stage_id)))
        except HallNotFound: return
        with workspace.lock:
            self._active(workspace)
            self._retain_link_locked(workspace,uuid.UUID(str(stage_id)),block_id)

    @staticmethod
    def _retain_link_locked(
        workspace:ProgramWorkspace,parent_stage_id:uuid.UUID,block_id:uuid.UUID
    )->bool:
        changed=False;links=[]
        for link in workspace.links:
            if (link.parent_stage_id==parent_stage_id
                    and link.program_block_instance_id==block_id
                    and link.link_state=="ACTIVE"):
                links.append(replace(link,link_state="RETAINED"));changed=True
            else: links.append(link)
        if changed:
            workspace.links=tuple(links);workspace.hierarchy_dirty=True
            workspace.hierarchy_revision+=1
        return changed

    def retain_child(
        self,program_id:str|uuid.UUID,parent_stage_id:str|uuid.UUID,
        program_block_instance_id:str|uuid.UUID,player_uuid:str,
    )->bool:
        workspace=self.load(program_id);parent_id=uuid.UUID(str(parent_stage_id))
        block_id=uuid.UUID(str(program_block_instance_id))
        with workspace.lock:
            self._active(workspace);self._authorize(workspace,player_uuid)
            if parent_id not in workspace.stage_headers:
                raise HallNotFound("Nincs ilyen parent Stage a Programban.")
            return self._retain_link_locked(workspace,parent_id,block_id)

    def is_dirty(
        self,program_id:str|uuid.UUID,*,verify_persistence:bool=True
    )->bool:
        workspace=self.load(program_id)
        with workspace.lock:
            if workspace.dirty: return True
            if not verify_persistence: return False
            loaded=self.repository.load_program(str(workspace.program_id))
            persisted_versions={
                uuid.UUID(str(row["stage_id"])):int(row["saved_version"])
                for row in loaded.stages
            }
            local_versions={
                stage_id:(
                    workspace.stage_workspaces[stage_id].saved_version
                    if stage_id in workspace.stage_workspaces
                    else int(header["saved_version"])
                )
                for stage_id,header in workspace.stage_headers.items()
                if stage_id not in workspace.new_stage_ids
            }
            if (int(loaded.program["saved_version"])!=workspace.saved_version
                    or persisted_versions!=local_versions):
                workspace.state=ProgramWorkspaceState.RELOAD_REQUIRED
                return True
            return False

    def save(
        self,program_id:str|uuid.UUID,player_uuid:str,*,
        expected_saved_version:int|None=None,
        target_stage_id:str|uuid.UUID|None=None,
        client_payload:dict[str,Any]|None=None,
    )->ProgramSaveReceipt:
        workspace=self.load(program_id)
        with workspace.lock:
            self._active(workspace);self._authorize(workspace,player_uuid)
            if (expected_saved_version is not None
                    and int(expected_saved_version)!=workspace.saved_version):
                raise ProgramVersionConflict(
                    "A Program mentett verziója időközben megváltozott."
                )
            stage_workspaces={
                stage_id:self._load_stage_locked(workspace,stage_id)
                for stage_id in sorted(workspace.stage_headers,key=str)
            }
            with ExitStack() as stack:
                for stage_id in sorted(stage_workspaces,key=str):
                    stack.enter_context(stage_workspaces[stage_id].lock)
                if target_stage_id is not None:
                    target=stage_workspaces.get(uuid.UUID(str(target_stage_id)))
                    if target is None: raise HallNotFound("A cél-Stage nem része a Programnak.")
                    StageService._check_client_version(target,client_payload or {})
                plan=self._save_plan(workspace,stage_workspaces)
                receipt=self.repository.save_program_snapshot(plan)
                try: self._apply_receipt(workspace,stage_workspaces,receipt)
                except Exception as exc:
                    workspace.state=ProgramWorkspaceState.RELOAD_REQUIRED
                    raise ProgramReloadRequired(
                        "A Program DB-commitja sikerült, de a memóriabeli receipt "
                        "alkalmazása meghiúsult; újratöltés szükséges."
                    ) from exc
                return receipt

    @staticmethod
    def _save_plan(
        workspace:ProgramWorkspace,stage_workspaces:dict[uuid.UUID,StageWorkspace]
    )->ProgramSavePlan:
        stages=[]
        for stage_id in sorted(stage_workspaces,key=str):
            stage_workspace=stage_workspaces[stage_id]
            is_new=stage_id in workspace.new_stage_ids
            header=None
            if is_new:
                raw=stage_workspace.stage
                header=NewStageHeader(
                    str(raw["stage_name"]),str(raw["stage_kind"]),
                    int(raw["origin_rel_x"]),int(raw["origin_rel_y"]),int(raw["origin_rel_z"]),
                    int(raw["size_x"]),int(raw["size_y"]),int(raw["size_z"]),None,
                )
            stages.append(ProgramStageSavePlan(
                stage_id,None if is_new else stage_workspace.saved_version,
                stage_workspace.snapshot if (is_new or stage_workspace.dirty) else None,
                header,
            ))
        return ProgramSavePlan(
            workspace.program_id,workspace.saved_version,workspace.root_stage_id,
            tuple(stages),tuple(workspace.links),
        )

    def _apply_receipt(
        self,workspace:ProgramWorkspace,
        stage_workspaces:dict[uuid.UUID,StageWorkspace],
        receipt:ProgramSaveReceipt,
    )->None:
        if receipt.program_id!=workspace.program_id:
            raise HallConflict("A SAVE receipt másik Programhoz tartozik.")
        versions={item.stage_id:item for item in receipt.stages}
        if set(versions)!=set(stage_workspaces):
            raise HallConflict("A SAVE receipt Stage-készlete hiányos.")
        workspace.saved_version=receipt.saved_version
        for stage_id,stage_workspace in stage_workspaces.items():
            item=versions[stage_id]
            stage_workspace.saved_version=item.saved_version
            stage_workspace.stage["saved_version"]=item.saved_version
            stage_workspace.dirty=False
            workspace.stage_headers[stage_id]["saved_version"]=item.saved_version
        workspace.new_stage_ids.clear()
        workspace.hierarchy_dirty=False

    def _legacy_stage_save(
        self,stage_id:str,payload:dict[str,Any]
    )->dict[str,Any]|None:
        try: normalized=uuid.UUID(str(stage_id))
        except (TypeError,ValueError): return None
        try: workspace=self.for_stage(normalized)
        except HallNotFound: return None
        program_id=workspace.program_id
        self.save(
            program_id,str(payload["player_uuid"]),target_stage_id=normalized,
            client_payload=payload,
        )
        workspace=self._workspace_for_stage_id(normalized)
        with workspace.lock:
            return StageService.view(self._load_stage_locked(workspace,normalized))
