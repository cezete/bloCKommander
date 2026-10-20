"""In-memory editable stage workspaces backed by atomic PostgreSQL snapshots."""
from __future__ import annotations

import copy
import threading
import uuid
from dataclasses import dataclass, field
from typing import Any

from .builder import HallValidationError
from .repository import HallConflict, HallForbidden, HallNotFound, HallRepository

ORIENTATIONS={"NORTH","SOUTH","EAST","WEST"}

@dataclass
class StageWorkspace:
    stage: dict[str,Any]
    blocks: list[dict[str,Any]]
    saved_version: int
    dirty: bool=False
    revision: int=0
    lock: threading.RLock=field(default_factory=threading.RLock)

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
        return self.view(self._workspace(str(loaded["stage_id"]),loaded))

    def _workspace(self,stage_id:str,loaded:dict[str,Any]|None=None)->StageWorkspace:
        with self._guard:
            if stage_id not in self._workspaces:
                data=loaded or self.repository.load_stage(stage_id)
                blocks=copy.deepcopy(data.pop("blocks",[]))
                self._workspaces[stage_id]=StageWorkspace(data,blocks,int(data["saved_version"]))
            return self._workspaces[stage_id]

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
            if any((int(b["rel_x"]),int(b["rel_y"]),int(b["rel_z"]))==rel for b in ws.blocks):
                raise HallConflict("Ezen a stage-pozíción már van programelem.")
            orientation=str(payload.get("orientation","SOUTH")).upper()
            if orientation not in ORIENTATIONS: raise HallValidationError("Hibás blokkirány.")
            block={"instance_id":str(uuid.uuid4()),"stage_id":stage_id,"type_key":type_key,
                   "rel_x":rel[0],"rel_y":rel[1],"rel_z":rel[2],"orientation":orientation,
                   "label":str(types[type_key]["default_label"]),"description":types[type_key].get("default_description"),
                   "display_name":types[type_key]["display_name"],"material_name":types[type_key]["material_name"],
                   "display_definition":types[type_key].get("display_definition") or {}}
            ws.blocks.append(block);ws.dirty=True;ws.revision+=1
            return self.view(ws)

    def update(self,stage_id:str,instance_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]));block=self._find(ws,instance_id)
            if "label" in payload:
                label=str(payload["label"]).strip()
                if not label or len(label)>32: raise HallValidationError("A rövid felirat 1–32 karakteres lehet.")
                block["label"]=label
            if "description" in payload:
                description=str(payload["description"]).strip()
                if len(description)>256: raise HallValidationError("A leírás legfeljebb 256 karakteres lehet.")
                block["description"]=description or None
            ws.dirty=True;ws.revision+=1
            return self.view(ws)

    def delete(self,stage_id:str,instance_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]));block=self._find(ws,instance_id)
            ws.blocks.remove(block);ws.dirty=True;ws.revision+=1
            return self.view(ws)

    def save(self,stage_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            snapshot=copy.deepcopy(ws.blocks)
            # The repository transaction either replaces every saved block and bumps
            # the version, or rolls back. On failure the workspace remains untouched.
            new_version=self.repository.save_stage_snapshot(stage_id,ws.saved_version,snapshot)
            ws.saved_version=new_version;ws.stage["saved_version"]=new_version;ws.dirty=False;ws.revision+=1
            return self.view(ws)

    def rebuild(self,stage_id:str,payload:dict[str,Any])->dict[str,Any]:
        ws=self._workspace(stage_id)
        with ws.lock:
            self._authorize(ws,str(payload["player_uuid"]))
            if ws.dirty and not bool(payload.get("confirm_discard")):
                raise HallConflict("Nem mentett módosítások vannak. Ismételd meg kifejezett eldobási megerősítéssel.")
            loaded=self.repository.load_stage(stage_id);blocks=copy.deepcopy(loaded.pop("blocks",[]))
            ws.stage=loaded;ws.blocks=blocks;ws.saved_version=int(loaded["saved_version"]);ws.dirty=False;ws.revision+=1
            return self.view(ws)

    @staticmethod
    def _find(ws:StageWorkspace,instance_id:str)->dict[str,Any]:
        try: return next(b for b in ws.blocks if str(b["instance_id"])==str(instance_id))
        except StopIteration as exc: raise HallNotFound("Nincs ilyen programelem a munkapéldányban.") from exc

    @classmethod
    def view(cls,ws:StageWorkspace)->dict[str,Any]:
        with ws.lock:
            s=ws.stage;ox,oy,oz=cls._origin(s)
            blocks=[]
            for raw in ws.blocks:
                b=copy.deepcopy(raw)
                b.update({"instance_id":str(b["instance_id"]),"x":ox+int(b["rel_x"]),
                          "y":oy+int(b["rel_y"]),"z":oz+int(b["rel_z"])})
                blocks.append(b)
            return {"stage_id":str(s["stage_id"]),"hall_id":str(s["hall_id"]),"stage_name":s["stage_name"],
                    "world_uuid":str(s["world_uuid"]),"origin":{"x":ox,"y":oy,"z":oz},
                    "size":{"x":int(s["size_x"]),"y":int(s["size_y"]),"z":int(s["size_z"])},
                    "saved_version":ws.saved_version,"working_revision":ws.revision,"dirty":ws.dirty,"blocks":blocks}
