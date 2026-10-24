"""In-memory editable stage workspaces backed by atomic PostgreSQL snapshots."""
from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field, replace
from typing import Any, Callable

from .builder import HallValidationError
from .graph.adapter import (
    AdapterIssue,
    AdapterIssueCode,
    GraphWorkspaceMappingError,
    prepare_graph_workspace_update,
    to_graph_workspace,
    to_stage_snapshot,
)
from .graph.model import CardinalDirection, GridPos
from .graph.port_catalog import CK_CONTROL_PORT_CATALOG
from .graph.snapshot import (
    GRAPH_SNAPSHOT_VERSION,
    ProgramValidationStatus,
    StageBlockSnapshot,
    StageSnapshot,
)
from .graph.state import GraphWorkspaceState
from .graph.validation import validate_graph
from .repository import (
    HallConflict,
    HallForbidden,
    HallNotFound,
    HallRepository,
    LoadedStageSnapshot,
    SnapshotCompatibilityError,
)

ORIENTATIONS={"NORTH","SOUTH","EAST","WEST"}

@dataclass(init=False)
class StageWorkspace:
    stage: dict[str,Any]
    snapshot: StageSnapshot
    saved_version: int
    dirty: bool=False
    workspace_epoch: uuid.UUID=field(default_factory=uuid.uuid4)
    lock: threading.RLock=field(default_factory=threading.RLock)
    graph_snapshot_version: int=GRAPH_SNAPSHOT_VERSION
    compatible_for_graph_mutation: bool=True
    compatibility_issues: tuple[str,...]=()

    def __init__(
        self,stage:dict[str,Any],snapshot:StageSnapshot|None=None,saved_version:int=0,
        dirty:bool=False,workspace_epoch:uuid.UUID|None=None,
        lock:threading.RLock|None=None,*,blocks:list[dict[str,Any]]|None=None,
        revision:int=0,graph_snapshot_version:int=GRAPH_SNAPSHOT_VERSION,
        compatible_for_graph_mutation:bool=True,
        compatibility_issues:tuple[str,...]=(),
    ):
        self.stage=stage;self.saved_version=saved_version;self.dirty=dirty
        self.workspace_epoch=workspace_epoch or uuid.uuid4();self.lock=lock or threading.RLock()
        self.graph_snapshot_version=int(graph_snapshot_version)
        self.compatible_for_graph_mutation=bool(compatible_for_graph_mutation)
        self.compatibility_issues=tuple(compatibility_issues)
        if snapshot is not None and blocks is not None:
            raise TypeError("pass snapshot or legacy blocks, not both")
        if snapshot is None:
            converted=[];seen=set()
            for index,raw in enumerate(blocks or []):
                required=("instance_id","type_key","rel_x","rel_y","rel_z","orientation","label")
                missing=[name for name in required if name not in raw]
                if missing:
                    raise GraphWorkspaceMappingError(tuple(
                        AdapterIssue(AdapterIssueCode.MISSING_BLOCK_FIELD,f"missing {name}",index,name)
                        for name in missing
                    ))
                try: instance_id=uuid.UUID(str(raw["instance_id"]))
                except (TypeError,ValueError) as exc:
                    raise GraphWorkspaceMappingError((AdapterIssue(
                        AdapterIssueCode.INVALID_INSTANCE_ID,"instance_id must be a UUID",index,"instance_id"
                    ),)) from exc
                if instance_id in seen:
                    raise GraphWorkspaceMappingError((AdapterIssue(
                        AdapterIssueCode.DUPLICATE_INSTANCE_ID,"duplicate instance_id",index,"instance_id"
                    ),))
                seen.add(instance_id)
                coords=(raw["rel_x"],raw["rel_y"],raw["rel_z"])
                if any(not isinstance(value,int) or isinstance(value,bool) for value in coords):
                    raise GraphWorkspaceMappingError((AdapterIssue(
                        AdapterIssueCode.INVALID_POSITION,"relative coordinates must be integers",index,None
                    ),))
                try: orientation=CardinalDirection(raw["orientation"])
                except (TypeError,ValueError) as exc:
                    raise GraphWorkspaceMappingError((AdapterIssue(
                        AdapterIssueCode.UNKNOWN_ORIENTATION,"invalid orientation",index,"orientation"
                    ),)) from exc
                converted.append(StageBlockSnapshot(
                    instance_id,str(raw["type_key"]),GridPos(*coords),orientation,
                    str(raw["label"]),raw.get("description"),
                    str(raw.get("display_name",raw["type_key"])),str(raw.get("material_name","")),
                    raw.get("display_definition") or {},
                ))
            try: stage_id=uuid.UUID(str(stage["stage_id"]))
            except (KeyError,TypeError,ValueError): stage_id=uuid.UUID(int=0)
            snapshot=StageSnapshot(
                stage_id,tuple(converted),(),(),(),revision,None,None,
                ProgramValidationStatus.NOT_CHECKED,CK_CONTROL_PORT_CATALOG.version,
            )
        self.snapshot=snapshot

    @property
    def revision(self)->int:
        return self.snapshot.working_revision

    @property
    def blocks(self)->list[dict[str,Any]]:
        """Legacy read projection; mutating it cannot alter the workspace."""
        return [item.as_legacy_dict(self.snapshot.stage_id) for item in self.snapshot.blocks]


class GraphOperationRejected(HallConflict):
    def __init__(self, diagnostics=(), issues=()):
        self.diagnostics=tuple(diagnostics);self.issues=tuple(issues)
        super().__init__("A gráfműveletet a domain vagy az alkalmazási szerződés elutasította.")


@dataclass(frozen=True)
class StageGraphContext:
    """Immutable, authorized read model for graph application adapters."""

    stage_id: uuid.UUID
    hall_id: str
    world_uuid: str
    origin: GridPos
    size: GridPos
    workspace_epoch: uuid.UUID
    graph_snapshot_version: int
    compatible_for_graph_mutation: bool
    compatibility_issues: tuple[str,...]
    port_catalog_version: str|None
    program_validation_status: ProgramValidationStatus
    state: GraphWorkspaceState

class StageService:
    def __init__(self,repository:HallRepository):
        self.repository=repository
        self._workspaces:dict[str,StageWorkspace]={}
        self._guard=threading.RLock()

    def ensure(self,hall:dict[str,Any])->dict[str,Any]:
        stage=self.repository.ensure_stage(hall)
        return self.view(self._workspace(str(stage["stage_id"])))

    def for_hall(self,hall_id:str)->dict[str,Any]:
        loaded=self.repository.load_stage_for_hall(hall_id)
        return self.view(self._workspace(str(loaded["stage_id"])))

    def _workspace(self,stage_id:str)->StageWorkspace:
        with self._guard:
            if stage_id not in self._workspaces:
                loaded=self.repository.load_full_stage_snapshot(stage_id)
                self._workspaces[stage_id]=self._prepare_loaded_workspace(
                    loaded,working_revision=0
                )
            return self._workspaces[stage_id]

    def _prepare_loaded_workspace(
        self,loaded:LoadedStageSnapshot,*,working_revision:int
    )->StageWorkspace:
        stage=dict(loaded.stage)
        snapshot=replace(
            loaded.snapshot,
            working_revision=working_revision,
            validated_revision=None,
            last_graph_validation=None,
            program_validation_status=ProgramValidationStatus.NOT_CHECKED,
        )
        candidate=StageWorkspace(
            stage,snapshot,loaded.saved_version,False,
            graph_snapshot_version=loaded.graph_snapshot_version,
            compatible_for_graph_mutation=loaded.compatible_for_graph_mutation,
            compatibility_issues=loaded.compatibility_issues,
        )
        if loaded.compatible_for_graph_mutation:
            runtime_types={str(item["type_key"]) for item in self.repository.list_block_types()}
            try:
                mapping=to_graph_workspace(
                    candidate,CK_CONTROL_PORT_CATALOG,known_block_types=runtime_types
                )
            except GraphWorkspaceMappingError as exc:
                candidate.compatible_for_graph_mutation=False
                candidate.compatibility_issues=tuple(
                    f"{item.code.value}: {item.message}" for item in exc.issues
                )
                return candidate
            if not mapping.permits_graph_mutation:
                candidate.compatible_for_graph_mutation=False
                candidate.compatibility_issues=tuple(
                    f"{item.code.value}: {item.message}" for item in mapping.issues
                )
                return candidate
            report=validate_graph(mapping.state,CK_CONTROL_PORT_CATALOG)
            candidate.snapshot=replace(
                snapshot,
                validated_revision=(None if report.has_blocking_errors else report.revision),
                last_graph_validation=report,
                program_validation_status=ProgramValidationStatus.NOT_CHECKED,
            )
        return candidate

    @staticmethod
    def _block_from_legacy(raw:dict[str,Any])->StageBlockSnapshot:
        return StageBlockSnapshot(
            instance_id=uuid.UUID(str(raw["instance_id"])),
            type_key=str(raw["type_key"]),
            position=GridPos(int(raw["rel_x"]),int(raw["rel_y"]),int(raw["rel_z"])),
            orientation=CardinalDirection(str(raw["orientation"])),
            label=str(raw["label"]),
            description=(None if raw.get("description") is None else str(raw["description"])),
            display_name=str(raw.get("display_name",raw["type_key"])),
            material_name=str(raw.get("material_name","")),
            display_definition=raw.get("display_definition") or {},
        )

    @classmethod
    def _snapshot_from_legacy(
        cls,stage:dict[str,Any],raw_blocks:list[dict[str,Any]],*,working_revision:int
    )->StageSnapshot:
        return StageSnapshot(
            stage_id=uuid.UUID(str(stage["stage_id"])),
            blocks=tuple(cls._block_from_legacy(item) for item in raw_blocks),
            edges=(),routes=(),wire_cells=(),working_revision=working_revision,
            validated_revision=None,last_graph_validation=None,
            program_validation_status=ProgramValidationStatus.NOT_CHECKED,
            port_catalog_version=CK_CONTROL_PORT_CATALOG.version,
        )

    @staticmethod
    def _authorize(ws:StageWorkspace,player_uuid:str)->None:
        if str(ws.stage["owner_uuid"])!=str(player_uuid):
            raise HallForbidden("Ezt a stage-et csak a csarnok tulajdonosa szerkesztheti vagy mentheti.")

    @staticmethod
    def _origin(stage:dict[str,Any])->tuple[int,int,int]:
        return (int(stage["hall_min_x"])+int(stage["origin_rel_x"]),
                int(stage["hall_min_y"])+int(stage["origin_rel_y"]),
                int(stage["hall_min_z"])+int(stage["origin_rel_z"]))

    @classmethod
    def _relative(cls,stage:dict[str,Any],x:int,y:int,z:int)->tuple[int,int,int]:
        ox,oy,oz=cls._origin(stage); rel=(int(x)-ox,int(y)-oy,int(z)-oz)
        if not (0<=rel[0]<int(stage["size_x"]) and 0<=rel[1]<int(stage["size_y"]) and 0<=rel[2]<int(stage["size_z"])):
            raise HallValidationError("A programelem csak a stage kijelölt belső területére helyezhető.")
        return rel

    def place(self,stage_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            type_key=str(payload.get("type_key","")).upper()
            types={str(t["type_key"]):t for t in self.repository.list_block_types()}
            if type_key not in types: raise HallValidationError("Ismeretlen CK-elemtípus.")
            rel=self._relative(ws.stage,int(payload["x"]),int(payload["y"]),int(payload["z"]))
            if any((b.position.x,b.position.y,b.position.z)==rel for b in ws.snapshot.blocks):
                raise HallConflict("Ezen a stage-pozíción már van programelem.")
            orientation=str(payload.get("orientation","SOUTH")).upper()
            if orientation not in ORIENTATIONS: raise HallValidationError("Hibás blokkirány.")
            block=StageBlockSnapshot(
                uuid.uuid4(),type_key,GridPos(*rel),CardinalDirection(orientation),
                str(types[type_key]["default_label"]),types[type_key].get("default_description"),
                str(types[type_key]["display_name"]),str(types[type_key]["material_name"]),
                types[type_key].get("display_definition") or {},
            )
            ws.snapshot=ws.snapshot.with_content_change(blocks=(*ws.snapshot.blocks,block))
            ws.dirty=True
            return self.view(ws)

    def update(self,stage_id:str,instance_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]));block=self._find(ws,instance_id)
            updated=block
            if "label" in payload:
                label=str(payload["label"]).strip()
                if not label or len(label)>32: raise HallValidationError("A rövid felirat 1–32 karakteres lehet.")
                updated=replace(updated,label=label)
            if "description" in payload:
                description=str(payload["description"]).strip()
                if len(description)>256: raise HallValidationError("A leírás legfeljebb 256 karakteres lehet.")
                updated=replace(updated,description=description or None)
            if updated != block:
                ws.snapshot=ws.snapshot.with_content_change(
                    blocks=tuple(updated if item.instance_id==block.instance_id else item for item in ws.snapshot.blocks)
                )
                ws.dirty=True
            return self.view(ws)

    def delete(self,stage_id:str,instance_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]));block=self._find(ws,instance_id)
            removed_edges=tuple(
                edge for edge in ws.snapshot.edges
                if edge.source_instance_id!=block.instance_id and edge.target_instance_id!=block.instance_id
            )
            kept_edge_ids={edge.edge_id for edge in removed_edges}
            removed_routes=tuple(
                route for route in ws.snapshot.routes
                if route.edge_id in kept_edge_ids or (
                    route.edge_id is None
                    and (route.source_port is None or route.source_port.instance_id!=block.instance_id)
                    and (route.target_port is None or route.target_port.instance_id!=block.instance_id)
                )
            )
            ws.snapshot=ws.snapshot.with_content_change(
                blocks=tuple(item for item in ws.snapshot.blocks if item.instance_id!=block.instance_id),
                edges=removed_edges,routes=removed_routes,
            )
            ws.dirty=True
            return self.view(ws)

    def save(self,stage_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            self._check_client_version(ws,payload)
            if ws.graph_snapshot_version==0:
                if ws.snapshot.has_unpersisted_graph_content:
                    raise SnapshotCompatibilityError(
                        "Legacy Stage nem tartalmazhat menthető graph-tartalmat explicit upgrade nélkül."
                    )
                blocks=[item.as_legacy_dict(ws.snapshot.stage_id) for item in ws.snapshot.blocks]
                new_version=self.repository.save_stage_snapshot(
                    stage_id,ws.saved_version,blocks
                )
            else:
                if not ws.compatible_for_graph_mutation:
                    raise SnapshotCompatibilityError(
                        "Az inkompatibilis graph snapshot csak olvasható."
                    )
                new_version=self.repository.save_full_stage_snapshot(
                    stage_id,ws.saved_version,ws.snapshot
                )
            ws.saved_version=new_version;ws.stage["saved_version"]=new_version;ws.dirty=False
            return self.view(ws)

    def rebuild(self,stage_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            self._check_client_version(ws,payload)
            if ws.dirty and not bool(payload.get("confirm_discard")):
                raise HallConflict("Nem mentett módosítások vannak. Ismételd meg kifejezett eldobási megerősítéssel.")
            loaded=self.repository.load_full_stage_snapshot(stage_id)
            prepared=self._prepare_loaded_workspace(
                loaded,working_revision=ws.snapshot.working_revision+1
            )
            next_epoch=uuid.uuid4()
            (
                ws.stage,ws.snapshot,ws.saved_version,ws.dirty,ws.workspace_epoch,
                ws.graph_snapshot_version,ws.compatible_for_graph_mutation,
                ws.compatibility_issues,
            )=(
                prepared.stage,prepared.snapshot,prepared.saved_version,False,next_epoch,
                prepared.graph_snapshot_version,prepared.compatible_for_graph_mutation,
                prepared.compatibility_issues,
            )
            return self.view(ws)

    def apply_graph_operation(
        self,
        stage_id:str,
        payload:dict[str,Any],
        operation:Callable[[GraphWorkspaceState],Any],
        *,
        new_block_metadata:tuple[StageBlockSnapshot,...]=(),
    )->dict[str,Any]:
        """Execute one domain operation and atomically swap the resulting snapshot."""
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            if not ws.compatible_for_graph_mutation:
                raise SnapshotCompatibilityError(
                    "A Stage graph formátuma vagy portkatalógusa nem kompatibilis módosításhoz."
                )
            try: expected_epoch=uuid.UUID(str(payload["workspace_epoch"]))
            except (KeyError,TypeError,ValueError) as exc:
                raise HallConflict("Hiányzó vagy hibás workspace epoch.") from exc
            if expected_epoch!=ws.workspace_epoch:
                raise HallConflict("A workspace epoch elavult; töltsd újra a Stage-et.")
            expected_revision=payload.get("expected_working_revision")
            if expected_revision!=ws.snapshot.working_revision:
                raise HallConflict("A working revision elavult; töltsd újra a Stage-et.")

            runtime_types={str(item["type_key"]) for item in self.repository.list_block_types()}
            mapping=to_graph_workspace(
                ws,CK_CONTROL_PORT_CATALOG,known_block_types=runtime_types
            )
            if not mapping.permits_graph_mutation:
                raise GraphWorkspaceMappingError(mapping.issues)
            domain_result=operation(mapping.state)
            if isinstance(domain_result,GraphWorkspaceState):
                accepted=True;target=domain_result;diagnostics=()
            else:
                target=getattr(domain_result,"state",None)
                accepted=bool(
                    getattr(domain_result,"accepted",getattr(domain_result,"applied",False))
                )
                diagnostics=getattr(domain_result,"diagnostics",())
            if not isinstance(target,GraphWorkspaceState):
                raise TypeError("graph operation must return a GraphWorkspaceState result")
            plan=prepare_graph_workspace_update(
                mapping.state,target,operation_accepted=accepted
            )
            if not plan.accepted:
                raise GraphOperationRejected(diagnostics,plan.issues)
            next_snapshot=to_stage_snapshot(
                ws.snapshot,plan,new_block_metadata=new_block_metadata
            )
            if plan.requires_apply:
                ws.snapshot=next_snapshot
                ws.dirty=target.dirty
            return self.view(ws)

    def read_graph_context(self,stage_id:str,player_uuid:str)->StageGraphContext:
        """Return an immutable graph snapshot without changing workspace content."""
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(player_uuid))
            snapshot=ws.snapshot
            ox,oy,oz=self._origin(ws.stage)
            state=GraphWorkspaceState(
                stage_id=snapshot.stage_id,
                nodes=snapshot.graph_nodes(),
                edges=snapshot.edges,
                wire_cells=snapshot.wire_cells,
                routes=snapshot.routes,
                working_revision=snapshot.working_revision,
                validated_revision=snapshot.validated_revision,
                saved_version=ws.saved_version,
                dirty=ws.dirty,
                last_validation=snapshot.last_graph_validation,
            )
            return StageGraphContext(
                stage_id=snapshot.stage_id,
                hall_id=str(ws.stage["hall_id"]),
                world_uuid=str(ws.stage["world_uuid"]),
                origin=GridPos(ox,oy,oz),
                size=GridPos(
                    int(ws.stage["size_x"]),
                    int(ws.stage["size_y"]),
                    int(ws.stage["size_z"]),
                ),
                workspace_epoch=ws.workspace_epoch,
                graph_snapshot_version=ws.graph_snapshot_version,
                compatible_for_graph_mutation=ws.compatible_for_graph_mutation,
                compatibility_issues=ws.compatibility_issues,
                port_catalog_version=snapshot.port_catalog_version,
                program_validation_status=snapshot.program_validation_status,
                state=state,
            )

    @staticmethod
    def _check_client_version(ws:StageWorkspace,payload:dict[str,Any])->None:
        carries_epoch="workspace_epoch" in payload
        carries_revision="expected_working_revision" in payload
        if not carries_epoch and not carries_revision:
            return
        if not carries_epoch or not carries_revision:
            raise HallConflict("Az epoch és a working revision csak együtt adható meg.")
        try: expected_epoch=uuid.UUID(str(payload["workspace_epoch"]))
        except (TypeError,ValueError) as exc:
            raise HallConflict("Hiányzó vagy hibás workspace epoch.") from exc
        if expected_epoch!=ws.workspace_epoch:
            raise HallConflict("A workspace epoch elavult; töltsd újra a Stage-et.")
        if payload["expected_working_revision"]!=ws.snapshot.working_revision:
            raise HallConflict("A working revision elavult; töltsd újra a Stage-et.")

    @staticmethod
    def _find(ws:StageWorkspace,instance_id:str)->StageBlockSnapshot:
        try: return next(b for b in ws.snapshot.blocks if str(b.instance_id)==str(instance_id))
        except StopIteration as exc: raise HallNotFound("Nincs ilyen programelem a munkapéldányban.") from exc

    @classmethod
    def view(cls,ws:StageWorkspace)->dict[str,Any]:
        with ws.lock:
            s=ws.stage;ox,oy,oz=cls._origin(s)
            blocks=[]
            for raw in ws.snapshot.blocks:
                b=raw.as_legacy_dict(ws.snapshot.stage_id)
                b.update({"x":ox+raw.position.x,"y":oy+raw.position.y,"z":oz+raw.position.z})
                blocks.append(b)
            if ws.graph_snapshot_version==0:
                persistence="LEGACY_BLOCKS_ONLY"
            elif ws.compatible_for_graph_mutation:
                persistence="FULL"
            else:
                persistence="READ_ONLY_INCOMPATIBLE"
            return {"stage_id":str(s["stage_id"]),"hall_id":str(s["hall_id"]),"stage_name":s["stage_name"],
                    "world_uuid":str(s["world_uuid"]),"origin":{"x":ox,"y":oy,"z":oz},
                    "size":{"x":int(s["size_x"]),"y":int(s["size_y"]),"z":int(s["size_z"])},
                    "saved_version":ws.saved_version,"working_revision":ws.revision,"dirty":ws.dirty,
                    "workspace_epoch":str(ws.workspace_epoch),"port_catalog_version":ws.snapshot.port_catalog_version,
                    "graph_snapshot_version":ws.graph_snapshot_version,
                    "graph_persistence":persistence,
                    "graph_compatibility_issues":list(ws.compatibility_issues),
                    "blocks":blocks}
