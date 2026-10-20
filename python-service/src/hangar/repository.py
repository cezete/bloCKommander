"""PostgreSQL persistence. Connections are short-lived and transactions explicit."""
from __future__ import annotations
import hashlib, json, os, uuid
from pathlib import Path
from typing import Any
import psycopg2
from psycopg2.extras import RealDictCursor

class HallNotFound(LookupError): pass
class HallConflict(RuntimeError): pass
class HallForbidden(PermissionError): pass
class StageVersionConflict(HallConflict): pass

class HallRepository:
    def __init__(self, connect=None): self._connect = connect or self._connect_from_env

    @staticmethod
    def _connect_from_env():
        password=os.environ.get("BLOCKCOMMANDER_DB_PASSWORD")
        password_file=Path(os.getenv("BLOCKCOMMANDER_DB_PASSWORD_FILE","/home/mcuser/minecraft_server/webhook/.db-password"))
        if not password and password_file.is_file(): password=password_file.read_text(encoding="utf-8").strip()
        if not password: raise RuntimeError("BLOCKCOMMANDER_DB_PASSWORD vagy BLOCKCOMMANDER_DB_PASSWORD_FILE nincs beállítva")
        return psycopg2.connect(host=os.getenv("BLOCKCOMMANDER_DB_HOST","127.0.0.1"),port=int(os.getenv("BLOCKCOMMANDER_DB_PORT","5433")),
            dbname=os.getenv("BLOCKCOMMANDER_DB_NAME","bloCKommander"),user=os.getenv("BLOCKCOMMANDER_DB_USER","admin"),password=password)

    def migrate(self)->None:
        files=sorted((Path(__file__).parent/"migrations").glob("*.sql"))
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute("CREATE SCHEMA IF NOT EXISTS blockcommander; CREATE TABLE IF NOT EXISTS blockcommander.schema_migrations(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())")
            for path in files:
                sql=path.read_text(encoding="utf-8"); checksum=hashlib.sha256(sql.encode()).hexdigest()
                cur.execute("SELECT checksum FROM blockcommander.schema_migrations WHERE version=%s",(path.name,)); row=cur.fetchone()
                if row and row[0]!=checksum: raise RuntimeError(f"Módosított, már futtatott migráció: {path.name}")
                if not row:
                    cur.execute(sql); cur.execute("INSERT INTO blockcommander.schema_migrations(version,checksum) VALUES(%s,%s)",(path.name,checksum))

    @staticmethod
    def _row(row):
        if not row: return None
        result=dict(row)
        if isinstance(result.get("generator_params"),str): result["generator_params"]=json.loads(result["generator_params"])
        return result

    def reserve(self,h:dict[str,Any],request_id:str)->dict[str,Any]:
        with self._connect() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT pg_advisory_xact_lock(hashtext(%s))",(h["world_uuid"],))
            cur.execute("INSERT INTO blockcommander.developers(player_uuid,current_name) VALUES(%s,%s) ON CONFLICT(player_uuid) DO UPDATE SET current_name=excluded.current_name,updated_at=now()",(h["owner_uuid"],h["owner_name"]))
            cur.execute("SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid WHERE create_request_id=%s",(request_id,)); existing=cur.fetchone()
            if existing: return self._load_controls(cur,self._row(existing))
            cur.execute("""SELECT hall_id FROM blockcommander.halls WHERE world_uuid=%s
                AND occupied_min_x<=%s AND occupied_max_x>=%s AND occupied_min_y<=%s AND occupied_max_y>=%s AND occupied_min_z<=%s AND occupied_max_z>=%s LIMIT 1""",
                (h["world_uuid"],h["occupied_max_x"],h["occupied_min_x"],h["occupied_max_y"],h["occupied_min_y"],h["occupied_max_z"],h["occupied_min_z"]))
            if cur.fetchone(): raise HallConflict("A kijelölt terület átfed egy meglévő csarnokot.")
            cur.execute("SELECT 1 FROM blockcommander.halls WHERE owner_uuid=%s AND lower(hall_code)=lower(%s)",(h["owner_uuid"],h["hall_code"]))
            if cur.fetchone(): raise HallConflict("Már van ilyen csarnokkódod. Válassz másik rövid nevet.")
            columns=["hall_id","owner_uuid","hall_name","hall_code","world_uuid","world_name","dimension_key","min_x","min_y","min_z","max_x","max_y","max_z","occupied_min_x","occupied_min_y","occupied_min_z","occupied_max_x","occupied_max_y","occupied_max_z","orientation","material_name","material_id","generator_version"]
            values=[h[c] for c in columns]
            cur.execute(f"INSERT INTO blockcommander.halls(create_request_id,{','.join(columns)},generator_params,lifecycle) VALUES(%s,{','.join(['%s']*len(columns))},%s::jsonb,'BUILDING')",[request_id,*values,json.dumps(h["generator_params"])])
            for c in h["controls"]: cur.execute("INSERT INTO blockcommander.hall_controls(hall_id,world_uuid,kind,x,y,z) VALUES(%s,%s,%s,%s,%s,%s)",(h["hall_id"],h["world_uuid"],c["kind"],c["x"],c["y"],c["z"]))
        return h

    def _load_controls(self,cur,h):
        if not h: return None
        cur.execute("SELECT kind,x,y,z FROM blockcommander.hall_controls WHERE hall_id=%s ORDER BY kind",(h["hall_id"],)); h["controls"]=[dict(r) for r in cur.fetchall()]; return h

    def get(self,hall_id:str)->dict[str,Any]:
        with self._connect() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid WHERE hall_id=%s",(hall_id,)); h=self._load_controls(cur,self._row(cur.fetchone()))
        if not h: raise HallNotFound("Nincs ilyen csarnok.")
        return h

    def by_button(self,world_uuid:str,x:int,y:int,z:int)->dict[str,Any]:
        return self.by_control(world_uuid,"DESTROY_BUTTON",x,y,z)

    def by_control(self,world_uuid:str,kind:str,x:int,y:int,z:int)->dict[str,Any]:
        with self._connect() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid JOIN blockcommander.hall_controls c ON c.hall_id=h.hall_id WHERE h.lifecycle='ACTIVE' AND c.world_uuid=%s AND c.kind=%s AND c.x=%s AND c.y=%s AND c.z=%s",(world_uuid,kind,x,y,z)); h=self._load_controls(cur,self._row(cur.fetchone()))
        if not h: raise HallNotFound("Ez a gomb nincs csarnokhoz rendelve.")
        return h

    def add_controls(self,hall_id:str,world_uuid:str,controls:list[dict[str,Any]])->None:
        with self._connect() as conn,conn.cursor() as cur:
            for c in controls:
                cur.execute("""INSERT INTO blockcommander.hall_controls(hall_id,world_uuid,kind,x,y,z)
                    VALUES(%s,%s,%s,%s,%s,%s) ON CONFLICT(hall_id,kind) DO NOTHING""",
                    (hall_id,world_uuid,c["kind"],c["x"],c["y"],c["z"]))

    def list_world(self,world_uuid:str)->list[dict[str,Any]]:
        with self._connect() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid WHERE h.world_uuid=%s AND h.lifecycle='ACTIVE' ORDER BY h.created_at",(world_uuid,))
            halls=[]
            for row in cur.fetchall(): halls.append(self._load_controls(cur,self._row(row)))
        return halls

    def list_owner(self,owner_uuid:str)->list[dict[str,Any]]:
        with self._connect() as conn, conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT hall_id,hall_name,hall_code,world_uuid,world_name,dimension_key,lifecycle FROM blockcommander.halls WHERE owner_uuid=%s ORDER BY created_at",(owner_uuid,))
            return [dict(row) for row in cur.fetchall()]

    def get_by_code(self,owner_uuid:str,hall_code:str)->dict[str,Any]:
        with self._connect() as conn,conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid WHERE h.owner_uuid=%s AND lower(h.hall_code)=lower(%s)",(owner_uuid,hall_code))
            hall=self._load_controls(cur,self._row(cur.fetchone()))
        if not hall: raise HallNotFound("Nincs ilyen kódú csarnokod.")
        return hall

    def restore_candidates(self,owner_uuid:str,world:dict[str,str])->list[dict[str,Any]]:
        with self._connect() as conn,conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""SELECT h.*,d.current_name owner_name FROM blockcommander.halls h JOIN blockcommander.developers d ON d.player_uuid=h.owner_uuid
                WHERE h.owner_uuid=%s AND h.world_name=%s AND h.dimension_key=%s AND h.world_uuid<>%s ORDER BY h.created_at""",
                (owner_uuid,world["world_name"],world["dimension"],world["world_uuid"]))
            halls=[]
            for row in cur.fetchall(): halls.append(self._load_controls(cur,self._row(row)))
        return halls

    def state(self,hall_id:str,lifecycle:str,error:str|None=None)->None:
        with self._connect() as conn,conn.cursor() as cur: cur.execute("UPDATE blockcommander.halls SET lifecycle=%s,last_error=%s,updated_at=now() WHERE hall_id=%s",(lifecycle,error,hall_id))

    def delete(self,hall_id:str)->None:
        with self._connect() as conn,conn.cursor() as cur: cur.execute("DELETE FROM blockcommander.halls WHERE hall_id=%s",(hall_id,))

    def reassign_world(self,hall_id:str,owner_uuid:str,world:dict[str,str])->dict[str,Any]:
        h=self.get(hall_id)
        if str(h["owner_uuid"])!=owner_uuid: raise HallForbidden("Csak a tulajdonos állíthatja helyre a csarnokot.")
        with self._connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT pg_advisory_xact_lock(hashtext(%s))",(world["world_uuid"],))
            cur.execute("""SELECT 1 FROM blockcommander.halls WHERE hall_id<>%s AND world_uuid=%s
              AND occupied_min_x<=%s AND occupied_max_x>=%s AND occupied_min_y<=%s AND occupied_max_y>=%s AND occupied_min_z<=%s AND occupied_max_z>=%s LIMIT 1""",
              (hall_id,world["world_uuid"],h["occupied_max_x"],h["occupied_min_x"],h["occupied_max_y"],h["occupied_min_y"],h["occupied_max_z"],h["occupied_min_z"]))
            if cur.fetchone(): raise HallConflict("A célvilágban a terület átfed egy meglévő csarnokot.")
            cur.execute("UPDATE blockcommander.halls SET world_uuid=%s,world_name=%s,dimension_key=%s,updated_at=now() WHERE hall_id=%s",(world["world_uuid"],world["world_name"],world["dimension"],hall_id))
            cur.execute("UPDATE blockcommander.hall_controls SET world_uuid=%s WHERE hall_id=%s",(world["world_uuid"],hall_id))
        return self.get(hall_id)

    def ensure_stage(self,hall:dict[str,Any])->dict[str,Any]:
        """Create the hall's one stable stage if it predates the stage migration."""
        with self._connect() as conn,conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT * FROM blockcommander.stages WHERE hall_id=%s",(hall["hall_id"],))
            stage=cur.fetchone()
            if not stage:
                stage_id=str(uuid.uuid4())
                # Keep a one-block inner service aisle so program blocks can never
                # overwrite the door-side SAVE/DESTROY controls.
                size_x=max(1,int(hall["max_x"])-int(hall["min_x"])-3)
                size_y=max(1,int(hall["max_y"])-int(hall["min_y"])-1)
                size_z=max(1,int(hall["max_z"])-int(hall["min_z"])-3)
                cur.execute("""INSERT INTO blockcommander.stages
                    (stage_id,hall_id,stage_name,origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                    VALUES(%s,%s,%s,2,1,2,%s,%s,%s) RETURNING *""",
                    (stage_id,hall["hall_id"],f"{hall['hall_name']} stage",size_x,size_y,size_z))
                stage=cur.fetchone()
            result=dict(stage)
            result.update({
                "owner_uuid":hall["owner_uuid"],"world_uuid":hall["world_uuid"],
                "hall_min_x":hall["min_x"],"hall_min_y":hall["min_y"],"hall_min_z":hall["min_z"],
                "hall_id":hall["hall_id"]})
            return result

    def load_stage(self,stage_id:str)->dict[str,Any]:
        with self._connect() as conn,conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("""SELECT s.*,h.owner_uuid,h.world_uuid,h.min_x hall_min_x,h.min_y hall_min_y,h.min_z hall_min_z
                FROM blockcommander.stages s JOIN blockcommander.halls h ON h.hall_id=s.hall_id
                WHERE s.stage_id=%s""",(stage_id,))
            stage=self._row(cur.fetchone())
            if not stage: raise HallNotFound("Nincs ilyen stage.")
            cur.execute("""SELECT b.instance_id,b.type_key,b.rel_x,b.rel_y,b.rel_z,b.orientation,b.label,b.description,
                    t.display_name,t.material_name,t.display_definition
                FROM blockcommander.stage_blocks b JOIN blockcommander.program_block_types t ON t.type_key=b.type_key
                WHERE b.stage_id=%s ORDER BY b.created_at,b.instance_id""",(stage_id,))
            stage["blocks"]=[self._row(row) for row in cur.fetchall()]
            return stage

    def load_stage_for_hall(self,hall_id:str)->dict[str,Any]:
        with self._connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT stage_id FROM blockcommander.stages WHERE hall_id=%s",(hall_id,)); row=cur.fetchone()
        if not row: raise HallNotFound("A csarnokhoz még nincs stage.")
        return self.load_stage(str(row[0]))

    def list_block_types(self)->list[dict[str,Any]]:
        with self._connect() as conn,conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute("SELECT type_key,display_name,material_name,default_label,default_description,display_definition FROM blockcommander.program_block_types ORDER BY type_key")
            return [self._row(row) for row in cur.fetchall()]

    def save_stage_snapshot(self,stage_id:str,expected_version:int,blocks:list[dict[str,Any]])->int:
        """Atomically replace the canonical block set using optimistic versioning."""
        with self._connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT saved_version FROM blockcommander.stages WHERE stage_id=%s FOR UPDATE",(stage_id,)); row=cur.fetchone()
            if not row: raise HallNotFound("Nincs ilyen stage.")
            if int(row[0])!=int(expected_version):
                raise StageVersionConflict("A stage mentett változata közben megváltozott; építsd vissza mentés előtt.")
            cur.execute("DELETE FROM blockcommander.stage_blocks WHERE stage_id=%s",(stage_id,))
            for b in blocks:
                cur.execute("""INSERT INTO blockcommander.stage_blocks
                    (instance_id,stage_id,type_key,rel_x,rel_y,rel_z,orientation,label,description)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (b["instance_id"],stage_id,b["type_key"],b["rel_x"],b["rel_y"],b["rel_z"],b["orientation"],b["label"],b.get("description")))
            new_version=int(row[0])+1
            cur.execute("UPDATE blockcommander.stages SET saved_version=%s,updated_at=now() WHERE stage_id=%s",(new_version,stage_id))
            return new_version
