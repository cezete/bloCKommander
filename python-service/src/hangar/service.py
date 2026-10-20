from __future__ import annotations
import uuid
from typing import Any
from .builder import build_hall, demolish_hall, prepare_hall
from .repository import HallForbidden, HallRepository

class HallService:
    def __init__(self,repository:HallRepository,mc:Any=None,apply_world:bool=True):
        self.repository,self.mc,self.apply_world=repository,mc,apply_world
    def create(self,payload:dict[str,Any])->dict[str,Any]:
        request_id=str(payload.get("request_id") or uuid.uuid4()); hall_id=str(uuid.uuid4())
        hall=prepare_hall(payload,hall_id,payload.get("hall_name") or str(payload["hall_code"]))
        hall=self.repository.reserve(hall,request_id)
        if hall.get("lifecycle")=="ACTIVE": return hall
        try:
            if self.apply_world: build_hall(self.mc,hall)
            self.repository.state(str(hall["hall_id"]),"ACTIVE")
        except Exception as exc: self.repository.state(str(hall["hall_id"]),"ERROR",str(exc)); raise
        hall["lifecycle"]="ACTIVE"; return hall
    def destroy(self,payload:dict[str,Any])->dict[str,Any]:
        hall=self.repository.by_button(str(payload["world_uuid"]),int(payload["x"]),int(payload["y"]),int(payload["z"]))
        if str(hall["owner_uuid"])!=str(payload["player_uuid"]): raise HallForbidden("Ezt a csarnokot csak a tulajdonosa bonthatja le.")
        self.repository.state(str(hall["hall_id"]),"DELETING")
        try:
            if self.apply_world: demolish_hall(self.mc,hall)
        except Exception as exc: self.repository.state(str(hall["hall_id"]),"ERROR",str(exc)); raise
        self.repository.delete(str(hall["hall_id"])); return hall
    def rebuild(self,hall_id:str,payload:dict[str,Any],restore:bool=False)->dict[str,Any]:
        hall=self.repository.get(hall_id); owner=str(payload["player_uuid"])
        if str(hall["owner_uuid"])!=owner: raise HallForbidden("Csak a tulajdonos építheti újra a csarnokot.")
        if restore: hall=self.repository.reassign_world(hall_id,owner,payload)
        elif str(hall["world_uuid"])!=str(payload["world_uuid"]):
            raise HallForbidden("A világ UUID-je eltér. Lecserélt világban használd a /bchall restore parancsot.")
        self.repository.state(hall_id,"BUILDING")
        try:
            if self.apply_world: build_hall(self.mc,hall)
            self.repository.state(hall_id,"ACTIVE")
        except Exception as exc: self.repository.state(hall_id,"ERROR",str(exc)); raise
        hall["lifecycle"]="ACTIVE"; return hall

    def rebuild_by_code(self,payload:dict[str,Any])->dict[str,Any]:
        hall=self.repository.get_by_code(str(payload["player_uuid"]),str(payload["hall_code"]))
        return self.rebuild(str(hall["hall_id"]),payload)

    def restore_all(self,payload:dict[str,Any])->list[dict[str,Any]]:
        owner=str(payload["player_uuid"])
        candidates=self.repository.restore_candidates(owner,payload)
        return [self.rebuild(str(hall["hall_id"]),payload,restore=True) for hall in candidates]
