"""B1 selection tests, only through the identity-guarded disposable PG runner."""
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from uuid import uuid4

import psycopg2

from ck_webhook import create_app
from hangar.program_http import program_dto
from hangar.selection import SelectionFailure
from postgres_graph_integration import (
    HALL_ID,OWNER_ID,PROGRAM_ID,STAGE_ID,WORLD_ID,apply_migrations_through,
    assert_database_identity,connect,create_stage,repository,reset_schema,
)
from postgres_program_model_integration import storage_fingerprint
from postgres_program_save_integration import stable_id
from postgres_transport_context_integration import TransportContextPostgreSQLIntegrationTest as Fixture


class HallSelectionPostgreSQLIntegrationTest(unittest.TestCase):
    setUp=Fixture.setUp
    headers=Fixture.headers
    second_mount=Fixture.second_mount
    place=Fixture.place

    def get(self,hall=HALL_ID,owner=OWNER_ID,client=None):
        return (client or self.client).get(f"/api/v1/halls/{hall}/selection?player_uuid={owner}",headers=self.auth)

    def put(self,hall=HALL_ID,program=PROGRAM_ID,stage=STAGE_ID,revision=0,owner=OWNER_ID,headers=None):
        return self.client.put(f"/api/v1/halls/{hall}/selection",headers=headers or self.auth,json={
            "player_uuid":str(owner),"program_id":str(program),"stage_id":str(stage),
            "expected_selection_revision":revision})

    def child(self,save=True):
        result=self.programs.ensure_child_result(PROGRAM_ID,STAGE_ID,
            stable_id(STAGE_ID,"program"),str(OWNER_ID))
        if save:self.programs.save(PROGRAM_ID,str(OWNER_ID))
        return str(result.workspace.snapshot.stage_id)

    def other_program(self,owner=OWNER_ID):
        program,root=str(uuid4()),str(uuid4())
        with connect() as conn,conn.cursor() as cur:
            if str(owner)!=str(OWNER_ID):
                cur.execute("INSERT INTO blockcommander.developers(player_uuid,current_name) VALUES(%s,'Other')",(str(owner),))
            cur.execute("""INSERT INTO blockcommander.programs(program_id,owner_uuid,program_name,root_stage_id)
                VALUES(%s,%s,'Other',%s)""",(program,str(owner),root))
            cur.execute("""INSERT INTO blockcommander.stages(stage_id,program_id,stage_kind,stage_name,
                origin_rel_x,origin_rel_y,origin_rel_z,size_x,size_y,size_z)
                VALUES(%s,%s,'ROOT','Other',2,1,2,10,10,10)""",(root,program))
        return program,root

    def identity_state(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT row_to_json(m)::text FROM blockcommander.hall_program_mounts m ORDER BY hall_id")
            mounts=cur.fetchall()
            cur.execute("SELECT row_to_json(w)::text FROM blockcommander.working_copies w ORDER BY working_copy_id")
            return mounts,cur.fetchall(),storage_fingerprint()

    def test_shared_mount_child_restart_and_coordinates(self):
        hall,_=self.second_mount();child=self.child()
        original=self.get().get_json()["selection"]
        self.assertEqual(200,self.put(stage=child).status_code)
        self.assertEqual(200,self.put(hall=hall,stage=child).status_code)
        selected=self.get().get_json()["selection"]
        self.assertEqual(original["working_copy_id"],selected["working_copy_id"])
        second=self.get(hall).get_json()["selection"]
        self.assertNotEqual(selected["working_copy_id"],second["working_copy_id"])
        # Deliberately different dimension/world and all three offsets.
        world=str(uuid4())
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""UPDATE blockcommander.halls SET world_uuid=%s,min_y=40,max_y=60,
                min_z=-80,max_z=-60 WHERE hall_id=%s""",(world,hall))
        fresh=create_app(repository=repository(),migrate=False,graph_shared_secret="ctx-pg").test_client()
        self.assertEqual(selected,self.get(client=fresh).get_json()["selection"])
        read=fresh.get(f"/api/v1/worlds/{world}/halls")
        self.assertEqual(200,read.status_code,read.get_json())
        view=read.get_json()["halls"][0]
        self.assertEqual(child,view["stage"]["stage_id"])
        self.assertEqual(world,view["stage"]["world_uuid"])
        self.assertEqual(30,view["stage"]["origin"]["x"])
        self.assertEqual(40,view["stage"]["origin"]["y"])
        self.assertEqual(-80,view["stage"]["origin"]["z"])
        projected=self.client.get(f"/api/v1/programs/{PROGRAM_ID}/stages/{child}",headers=self.headers(hall))
        self.assertEqual(200,projected.status_code,projected.get_json())
        self.assertEqual(view["stage"]["origin"],projected.get_json()["stage"]["origin"])
        self.assertEqual({"x":0,"y":0,"z":0},projected.get_json()["stage"]["domain_layout"]["origin"])
        detail=self.client.get(f"/api/v1/halls/{hall}",headers=self.headers(hall))
        self.assertEqual(200,detail.status_code,detail.get_json())
        self.assertEqual(child,detail.get_json()["hall"]["stage"]["stage_id"])
        mounted=self.client.get(f"/api/v1/halls/{hall}/program",headers=self.headers(hall))
        self.assertEqual(child,mounted.get_json()["mount"]["active_stage_id"])
        listed=self.client.get(f"/api/v1/players/{OWNER_ID}/halls").get_json()["halls"]
        self.assertEqual(child,next(item for item in listed if item["hall_id"]==hall)["active_stage_id"])
        graphs=[]
        for target in (HALL_ID,hall):
            response=self.client.get(f"/api/v1/stages/{STAGE_ID}/graph?player_uuid={OWNER_ID}",headers=self.headers(target))
            self.assertEqual(200,response.status_code,response.get_json());graphs.append(response.get_json()["state"])
        self.assertEqual(graphs[0]["wire_cells"],graphs[1]["wire_cells"])
        self.assertEqual(graphs[0]["routes"],graphs[1]["routes"])
        self.assertEqual(graphs[0]["nodes"],graphs[1]["nodes"])
        self.assertEqual({"x":30,"y":40,"z":-80},graphs[1]["origin"])
        self.assertEqual(world,graphs[1]["world_uuid"])
        self.assertEqual(0,self.programs.root(PROGRAM_ID).stage["hall_min_x"])
        restored_world=str(uuid4())
        restored=self.client.post("/api/v1/halls/restore",json={
            "player_uuid":str(OWNER_ID),"world_uuid":restored_world,
            "world_name":"B2T world","dimension":"minecraft:overworld"})
        self.assertEqual(200,restored.status_code,restored.get_json())
        self.assertEqual(2,len(restored.get_json()["halls"]))
        for item in restored.get_json()["halls"]:
            self.assertEqual(child,item["stage"]["stage_id"])
            self.assertEqual(1,item["selection_revision"])
            self.assertEqual(restored_world,item["stage"]["world_uuid"])

    def test_program_aba_new_copy_stage_switch_same_copy_and_strict_idempotence(self):
        child=self.child();program,root=self.other_program()
        initial=self.get().get_json()["selection"]
        noop=self.put();self.assertFalse(noop.get_json()["changed"])
        self.assertEqual(initial,noop.get_json()["selection"])
        stage=self.put(stage=child).get_json()["selection"]
        self.assertEqual(initial["working_copy_id"],stage["working_copy_id"])
        handoff=self.put(program=program,stage=root,revision=1,headers=self.headers())
        self.assertEqual(200,handoff.status_code,handoff.get_json())
        other=handoff.get_json()["selection"]
        back=self.put(revision=2).get_json()["selection"]
        self.assertEqual(3,back["selection_revision"])
        self.assertEqual(3,len({initial["working_copy_id"],other["working_copy_id"],back["working_copy_id"]}))
        self.assertEqual(409,self.put().status_code)
        self.assertFalse(self.put(revision=3).get_json()["changed"])
        stale=self.client.get(f"/api/v1/programs/{PROGRAM_ID}",headers={
            **self.auth,"X-BlockCommander-Hall-Id":str(HALL_ID),
            "X-BlockCommander-Program-Id":str(PROGRAM_ID),
            "X-BlockCommander-Working-Copy-Id":initial["working_copy_id"]})
        self.assertEqual(409,stale.status_code)

    def test_invalid_requests_are_atomic_and_do_not_touch_dirty_workspace(self):
        child=self.child(save=False);root=self.programs.root(PROGRAM_ID);root.dirty=True
        snapshot=root.snapshot;before=self.identity_state()
        foreign,foreign_root=self.other_program(uuid4())
        before=self.identity_state()
        cases=[dict(owner=uuid4()),dict(stage=child),dict(stage=uuid4()),dict(program=uuid4()),
            dict(program=foreign,stage=foreign_root),dict(program=PROGRAM_ID,stage=foreign_root),
            dict(revision=7),dict(hall=uuid4())]
        for case in cases:
            with self.subTest(case=case):
                response=self.put(**case)
                self.assertIn(response.status_code,(403,404,409,422),response.get_json())
                self.assertIn("error_code",response.get_json())
                self.assertEqual(before,self.identity_state())
                self.assertIs(snapshot,root.snapshot);self.assertTrue(root.dirty)
        self.assertEqual(403,self.get(owner=uuid4()).status_code)
        with connect() as conn,conn.cursor() as cur:
            cur.execute("DELETE FROM blockcommander.hall_program_mounts WHERE hall_id=%s",(str(HALL_ID),))
        before=self.identity_state()
        self.assertEqual(404,self.put().status_code)
        self.assertEqual(before,self.identity_state())

    def test_allocation_failure_rolls_back_and_mount_cache_failure_is_retryable(self):
        workspace=self.programs.load(PROGRAM_ID);root=self.programs.root(PROGRAM_ID);root.dirty=True
        snapshot=root.snapshot;program,other_root=self.other_program()
        # Force a real PK allocation failure, after all ownership/CAS checks.
        copy_id=repository().resolve_working_copy_context(HALL_ID).working_copy_id
        before=self.identity_state()
        with patch("hangar.repository.uuid.uuid4",return_value=copy_id),patch.object(self.app.logger,"exception"):
            failed=self.put(program=program,stage=other_root)
        self.assertEqual(503,failed.status_code,failed.get_json())
        self.assertEqual(before,self.identity_state())
        self.assertIs(snapshot,root.snapshot);self.assertTrue(root.dirty)
        mounts=workspace.mounts
        with patch.object(self.programs.repository,"load_program",side_effect=psycopg2.OperationalError("cache read")):
            # A successful selection does not depend on mountcache refresh.
            self.assertEqual(200,self.put(program=program,stage=other_root).status_code)
            with self.assertRaises(psycopg2.Error):program_dto(self.programs,workspace)
        self.assertIs(mounts,workspace.mounts)
        self.assertEqual([],program_dto(self.programs,workspace)["mounts"])
        self.assertIs(root,self.programs.root(PROGRAM_ID));self.assertTrue(root.dirty)
        self.assertIs(snapshot,root.snapshot)
        self.assertEqual(200,self.put(revision=1).status_code)
        dto=program_dto(self.programs,workspace)
        self.assertEqual(self.get().get_json()["selection"]["working_copy_id"],dto["mounts"][0]["working_copy_id"])

    def test_concurrent_program_and_stage_switch_have_one_cas_winner(self):
        child=self.child();program,root=self.other_program();barrier=threading.Barrier(2)
        def select(target):
            barrier.wait()
            try:
                return repository().select(str(HALL_ID),str(OWNER_ID),*target,0)[1]
            except SelectionFailure as exc:return exc.code
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(select,[(str(PROGRAM_ID),child),(program,root)]))
        self.assertCountEqual([True,"SELECTION_CONFLICT"],results)
        self.assertEqual(1,self.get().get_json()["selection"]["selection_revision"])

    def test_two_concurrent_identical_puts_have_one_winner(self):
        child=self.child();barrier=threading.Barrier(2)
        def put(_):
            with self.app.test_client() as client:
                barrier.wait()
                response=client.put(f"/api/v1/halls/{HALL_ID}/selection",headers=self.auth,json={
                    "player_uuid":str(OWNER_ID),"program_id":str(PROGRAM_ID),"stage_id":child,
                    "expected_selection_revision":0})
                return response.status_code
        with ThreadPoolExecutor(max_workers=2) as pool:
            self.assertCountEqual([200,409],list(pool.map(put,range(2))))

    def test_invalid_persisted_selection_never_falls_back_and_deleted_hall_is_missing(self):
        workspace=self.programs.load(PROGRAM_ID);root=self.programs.root(PROGRAM_ID);root.dirty=True
        with connect() as conn,conn.cursor() as cur:
            # Simulate corrupted imported state; normal writes cannot bypass the FK.
            cur.execute("ALTER TABLE blockcommander.hall_program_mounts DROP CONSTRAINT hall_selection_stage_fkey")
            cur.execute("UPDATE blockcommander.hall_program_mounts SET active_stage_id=%s",(str(uuid4()),))
        before=self.identity_state()
        self.assertEqual("INVALID_SELECTION",self.get().get_json()["error_code"])
        response=self.client.get(f"/api/v1/worlds/{WORLD_ID}/halls")
        self.assertEqual(409,response.status_code,response.get_json())
        self.assertEqual(before,self.identity_state())
        self.assertIs(workspace,self.programs.load(PROGRAM_ID));self.assertTrue(root.dirty)
        repository().delete(str(HALL_ID))
        self.assertEqual(404,self.put().status_code)
        self.assertTrue(root.dirty)

    def test_upgrade_backfills_existing_mounts_and_database_rejects_invalid_selection(self):
        reset_schema();apply_migrations_through("009_working_copy_identity.sql");create_stage()
        before=storage_fingerprint();repository().migrate()
        selected=repository().get_selection(HALL_ID,OWNER_ID)
        self.assertEqual(str(STAGE_ID),str(selected["active_stage_id"]))
        self.assertEqual(0,selected["selection_revision"])
        self.assertEqual(before,storage_fingerprint())
        for column,value in (("active_stage_id",str(uuid4())),("active_stage_id",None),("selection_revision",-1)):
            with self.subTest(column=column,value=value),self.assertRaises(psycopg2.Error):
                with connect() as conn,conn.cursor() as cur:
                    cur.execute(f"UPDATE blockcommander.hall_program_mounts SET {column}=%s WHERE hall_id=%s",(value,str(HALL_ID)))
        self.assertEqual(str(STAGE_ID),str(repository().load_stage_for_hall(str(HALL_ID))["stage_id"]))
        second,_=self.second_mount()
        with self.assertRaises(psycopg2.Error):
            with connect() as conn,conn.cursor() as cur:
                cur.execute("UPDATE blockcommander.hall_program_mounts SET working_copy_id=%s WHERE hall_id=%s",
                    (str(selected["working_copy_id"]),second))

    def test_auth_and_handoff_do_not_reuse_g1_context_echo(self):
        self.assertEqual(401,self.client.put(f"/api/v1/halls/{HALL_ID}/selection",json={}).status_code)
        response=self.put(headers=self.headers())
        self.assertEqual(200,response.status_code,response.get_json())
        self.assertNotIn("transport_context",response.get_json())
        self.assertEqual("DESIRED_ONLY",response.get_json()["selection"]["projection_state"])
        self.assertEqual("ck-hall-selection-v1",response.get_json()["selection_api_version"])


del Fixture  # unittest must not discover the imported fixture's own suite twice.

if __name__=="__main__":
    assert_database_identity()
    unittest.main()
