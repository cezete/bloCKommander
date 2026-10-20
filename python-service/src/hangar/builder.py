"""Deterministic, versioned developer-hall generator for RaspberryJuice/mcpi."""
from __future__ import annotations
from dataclasses import dataclass
import re
from typing import Any, Iterable

GENERATOR_VERSION = 2
MIN_WIDTH = MIN_DEPTH = MIN_HEIGHT = 5
MAX_VOLUME = 250_000
AIR, GLASS, GLOWSTONE, WOODEN_DOOR, WALL_SIGN, STONE_BUTTON = 0, 20, 89, 64, 68, 77

class HallValidationError(ValueError): pass

@dataclass(frozen=True)
class BlockElement:
    kind: str; x: int; y: int; z: int; block_id: int; data: int = 0
    lines: tuple[str, str, str, str] | None = None
    def as_record(self) -> dict[str, Any]:
        return {"kind": self.kind, "x": self.x, "y": self.y, "z": self.z}

def normalize_bounds(p1: dict[str, Any], p2: dict[str, Any]) -> dict[str, int]:
    try:
        x1, x2 = sorted((int(p1["x"]), int(p2["x"])))
        y1, y2 = sorted((int(p1["y"]), int(p2["y"])))
        z1, z2 = sorted((int(p1["z"]), int(p2["z"])))
    except (KeyError, TypeError, ValueError) as exc:
        raise HallValidationError("A kijelölési koordináták hiányosak vagy hibásak.") from exc
    if y1 == y2: y2 = y1 + MIN_HEIGHT - 1
    width, height, depth = x2-x1+1, y2-y1+1, z2-z1+1
    if width < MIN_WIDTH or depth < MIN_DEPTH or height < MIN_HEIGHT:
        raise HallValidationError(f"A csarnok minimális mérete {MIN_WIDTH}x{MIN_DEPTH}x{MIN_HEIGHT} blokk; a kijelölés {width}x{depth}x{height}.")
    volume=width*height*depth
    if volume>MAX_VOLUME:
        raise HallValidationError(f"A csarnok legfeljebb {MAX_VOLUME} blokk térfogatú lehet; a kijelölés {volume} blokk.")
    return {"min_x":x1,"min_y":y1,"min_z":z1,"max_x":x2,"max_y":y2,"max_z":z2}

def _entrance(b: dict[str, int], orientation: str) -> dict[str, Any]:
    x1,y1,z1,x2,z2=b["min_x"],b["min_y"],b["min_z"],b["max_x"],b["max_z"]
    mx,mz=(x1+x2)//2,(z1+z2)//2
    table={"SOUTH":(mx,z1,1,(0,1),(0,-1)),"NORTH":(mx,z2,3,(0,-1),(0,1)),
           "EAST":(x1,mz,0,(1,0),(-1,0)),"WEST":(x2,mz,2,(-1,0),(1,0))}
    try: x,z,data,inside,outside=table[orientation]
    except KeyError as exc: raise HallValidationError("A tájolás csak NORTH, SOUTH, EAST vagy WEST lehet.") from exc
    return {"x":x,"y":y1+1,"z":z,"door_data":data,"inward":inside,"outward":outside}

def control_elements(b: dict[str,int], orientation: str, owner: str, name: str) -> list[BlockElement]:
    door=_entrance(b,orientation); dx,dz=door["inward"]; ox,oz=door["outward"]
    sign_data={"SOUTH":2,"NORTH":3,"EAST":4,"WEST":5}[orientation]
    button_data={"SOUTH":3,"NORTH":4,"EAST":1,"WEST":2}[orientation]
    sx,sz=(door["x"]+1,door["z"]) if orientation in ("NORTH","SOUTH") else (door["x"],door["z"]+1)
    save_x,save_z=(door["x"]-1,door["z"]) if orientation in ("NORTH","SOUTH") else (door["x"],door["z"]-1)
    return [
        BlockElement("OWNER_SIGN",sx+ox,door["y"]+1,sz+oz,WALL_SIGN,sign_data,("Fejlesztoi","csarnok",owner[:15],name[:15])),
        BlockElement("DESTROY_BUTTON",sx+dx,door["y"],sz+dz,STONE_BUTTON,button_data),
        BlockElement("DESTROY_SIGN",sx+dx,door["y"]+1,sz+dz,WALL_SIGN,sign_data,("Csarnok","megszuntetese","Tulajdonos","gombja")),
        BlockElement("SAVE_BUTTON",save_x+dx,door["y"],save_z+dz,STONE_BUTTON,button_data),
        BlockElement("SAVE_SIGN",save_x+dx,door["y"]+1,save_z+dz,WALL_SIGN,sign_data,("STAGE","MENTES","Tulajdonos","MENTVE"))]

def prepare_hall(payload: dict[str,Any], hall_id: str, hall_name: str) -> dict[str,Any]:
    required=("player_uuid","player_name","world_uuid","world_name","dimension","hall_code","p1","p2")
    missing=[k for k in required if not payload.get(k)]
    if missing: raise HallValidationError("Hiányzó mezők: "+", ".join(missing))
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,23}",str(payload["hall_code"])):
        raise HallValidationError("A csarnokkód 1–24 karakteres lehet: betű, szám, _ vagy -.")
    b=normalize_bounds(payload["p1"],payload["p2"]); orientation=str(payload.get("orientation","SOUTH")).upper()
    controls=control_elements(b,orientation,str(payload["player_name"]),hall_name); occupied=dict(b)
    for e in controls:
        for axis,value in (("x",e.x),("y",e.y),("z",e.z)):
            occupied[f"min_{axis}"]=min(occupied[f"min_{axis}"],value); occupied[f"max_{axis}"]=max(occupied[f"max_{axis}"],value)
    return {"hall_id":hall_id,"hall_name":hall_name,"hall_code":str(payload["hall_code"]),"owner_uuid":str(payload["player_uuid"]),"owner_name":str(payload["player_name"]),
            "world_uuid":str(payload["world_uuid"]),"world_name":str(payload["world_name"]),"dimension_key":str(payload["dimension"]),
            "orientation":orientation,"material_name":str(payload.get("material","STONE")).upper(),"material_id":int(payload.get("material_id",1)),
            "generator_version":GENERATOR_VERSION,"generator_params":{"window_block_id":GLASS,"light_block_id":GLOWSTONE,"door_block_id":WOODEN_DOOR},
            **b,**{f"occupied_{k}":v for k,v in occupied.items()},
            "controls":[e.as_record() for e in controls]}

def _set_element(mc: Any,e: BlockElement)->None:
    if e.lines is not None: mc.setSign(e.x,e.y,e.z,e.block_id,e.data,*e.lines)
    else: mc.setBlock(e.x,e.y,e.z,e.block_id,e.data)

def _windows(b:dict[str,int],orientation:str)->Iterable[tuple[int,int,int]]:
    y=b["min_y"]+2
    for x in range(b["min_x"]+1,b["max_x"],2):
        for z in (b["min_z"],b["max_z"]):
            if not (orientation in ("SOUTH","NORTH") and x==(b["min_x"]+b["max_x"])//2): yield x,y,z
    for z in range(b["min_z"]+1,b["max_z"],2):
        for x in (b["min_x"],b["max_x"]):
            if not (orientation in ("EAST","WEST") and z==(b["min_z"]+b["max_z"])//2): yield x,y,z

def build_hall(mc:Any,hall:dict[str,Any])->None:
    if int(hall["generator_version"])!=GENERATOR_VERSION: raise HallValidationError(f"Nem támogatott generátorverzió: {hall['generator_version']}")
    b={k:int(hall[k]) for k in ("min_x","min_y","min_z","max_x","max_y","max_z")}; m=int(hall["material_id"])
    mc.setBlocks(b["min_x"],b["min_y"],b["min_z"],b["max_x"],b["max_y"],b["max_z"],m)
    mc.setBlocks(b["min_x"]+1,b["min_y"]+1,b["min_z"]+1,b["max_x"]-1,b["max_y"]-1,b["max_z"]-1,AIR)
    for x,y,z in _windows(b,hall["orientation"]): mc.setBlock(x,y,z,GLASS)
    for x in range(b["min_x"]+2,b["max_x"],3):
        for z in range(b["min_z"]+2,b["max_z"],3): mc.setBlock(x,b["max_y"],z,GLOWSTONE)
    # Stateful fixtures are installed by Paper/Bukkit after this call. RaspberryJuice's
    # legacy numeric door/sign metadata is not reliable on modern Minecraft versions.

def hall_client_view(hall:dict[str,Any],stage:dict[str,Any]|None=None)->dict[str,Any]:
    b={k:int(hall[k]) for k in ("min_x","min_y","min_z","max_x","max_y","max_z")}
    door=_entrance(b,hall["orientation"])
    result={"hall_id":str(hall["hall_id"]),"hall_name":hall["hall_name"],"hall_code":hall["hall_code"],"owner_uuid":str(hall["owner_uuid"]),"owner_name":hall["owner_name"],
            "world_uuid":str(hall["world_uuid"]),"orientation":hall["orientation"],"material_name":hall["material_name"],**b,
            "door":{"x":door["x"],"y":door["y"],"z":door["z"]},"controls":hall.get("controls",[])}
    if stage is not None: result["stage"]=stage
    return result

def demolish_hall(mc:Any,hall:dict[str,Any])->None:
    mc.setBlocks(hall["min_x"],hall["min_y"],hall["min_z"],hall["max_x"],hall["max_y"],hall["max_z"],AIR)
    for c in hall.get("controls",[]): mc.setBlock(int(c["x"]),int(c["y"]),int(c["z"]),AIR)

def build_hollow_box(mc:Any,p1:dict[str,Any],p2:dict[str,Any],material_id:int)->None:
    payload={"player_uuid":"00000000-0000-0000-0000-000000000000","player_name":"legacy","world_uuid":"00000000-0000-0000-0000-000000000000","world_name":"world","dimension":"minecraft:overworld","orientation":"SOUTH","material":"LEGACY","material_id":material_id,"hall_code":"legacy","p1":p1,"p2":p2}
    build_hall(mc,prepare_hall(payload,"legacy","Legacy hall"))
