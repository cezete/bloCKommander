"""CK-07/A1.2 HTTP preflight against real, disposable PostgreSQL mount state."""
import unittest
from uuid import uuid4
from unittest.mock import patch

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.transport_context import CONTEXT_HEADERS
from postgres_graph_integration import (
    HALL_ID,OWNER_ID,PROGRAM_ID,STAGE_ID,WORLD_ID,assert_database_identity,
    connect,create_stage,repository,reset_schema,
)
from postgres_program_model_integration import storage_fingerprint
from postgres_program_save_integration import stable_id,stage_snapshot


class TransportContextPostgreSQLIntegrationTest(unittest.TestCase):
    def setUp(self):
        reset_schema();repository().migrate();create_stage()
        repository().save_full_stage_snapshot(str(STAGE_ID),0,
            stage_snapshot(STAGE_ID,"root",with_program_block=True))
        self.app=create_app(repository=repository(),migrate=False,graph_shared_secret="ctx-pg")
        self.client=self.app.test_client()
        self.programs=self.app.extensions["blockcommander_program_service"]
        self.stages=self.app.extensions["blockcommander_stage_service"]
        self.auth={GRAPH_SECRET_HEADER:"ctx-pg"}

    def headers(self,hall=HALL_ID,**overrides):
        data=repository().resolve_working_copy_context(hall).as_dict();data.update(overrides)
        return {**self.auth,**{header:str(data[key]) for key,header in CONTEXT_HEADERS.items()}}

    def second_mount(self):
        hall,copy_id=str(uuid4()),str(uuid4())
        with connect() as conn,conn.cursor() as cur:
            cur.execute("""INSERT INTO blockcommander.halls(
                hall_id,create_request_id,owner_uuid,hall_name,world_uuid,
                world_name,dimension_key,min_x,min_y,min_z,max_x,max_y,max_z,
                occupied_min_x,occupied_min_y,occupied_min_z,
                occupied_max_x,occupied_max_y,occupied_max_z,orientation,
                material_name,material_id,generator_version,generator_params,lifecycle,hall_code)
                SELECT %s,%s,owner_uuid,'Context B',world_uuid,
                world_name,dimension_key,30,0,0,50,20,20,30,0,0,50,20,20,orientation,
                material_name,material_id,generator_version,generator_params,lifecycle,'context-b'
                FROM blockcommander.halls WHERE hall_id=%s""",(hall,str(uuid4()),str(HALL_ID)))
            cur.execute("INSERT INTO blockcommander.working_copies(working_copy_id,program_id) VALUES(%s,%s)",
                (copy_id,str(PROGRAM_ID)))
            cur.execute("""INSERT INTO blockcommander.hall_program_mounts(hall_id,program_id,working_copy_id)
                VALUES(%s,%s,%s)""",(hall,str(PROGRAM_ID),copy_id))
        return hall,copy_id

    def place(self,headers):
        return self.client.post(f"/api/v1/stages/{STAGE_ID}/blocks",headers=headers,json={
            "player_uuid":str(OWNER_ID),"type_key":"START","coordinate_space":"STAGE_RELATIVE",
            "x":5,"y":0,"z":0,"orientation":"NORTH"})

    def test_real_foreign_and_removed_mounts_fail_before_workspace_or_database_mutation(self):
        hall,copy_id=self.second_mount()
        headers=self.headers()
        before=storage_fingerprint()
        invalid=self.place(self.headers(working_copy_id=copy_id))
        self.assertEqual(409,invalid.status_code)
        self.assertEqual("FOREIGN_WORKING_COPY",invalid.get_json()["error_code"])
        self.assertEqual(before,storage_fingerprint())
        self.assertEqual({},self.programs._programs)
        self.assertEqual({},self.stages._workspaces)
        with connect() as conn,conn.cursor() as cur:
            cur.execute("DELETE FROM blockcommander.hall_program_mounts WHERE hall_id=%s",(str(HALL_ID),))
        before=storage_fingerprint()
        removed=self.place(headers)
        self.assertEqual(404,removed.status_code)
        self.assertEqual("MOUNT_NOT_FOUND",removed.get_json()["error_code"])
        self.assertEqual(before,storage_fingerprint())
        self.assertEqual({},self.stages._workspaces)

    def test_multi_mount_legacy_is_closed_and_explicit_child_graph_save_remain_shared(self):
        hall,_=self.second_mount()
        self.assertEqual(409,self.place({}).status_code)
        placed=self.place(self.headers())
        self.assertEqual(201,placed.status_code,placed.get_json())
        child=self.client.post(f"/api/v1/programs/{PROGRAM_ID}/stages/{STAGE_ID}/children/ensure",
            headers=self.headers(),json={"player_uuid":str(OWNER_ID),
                "program_block_instance_id":str(stable_id(STAGE_ID,"program"))})
        self.assertEqual(201,child.status_code,child.get_json())
        child_id=child.get_json()["child"]["stage_id"]
        graph=self.client.get(f"/api/v1/stages/{child_id}/graph?player_uuid={OWNER_ID}",headers=self.headers(hall))
        self.assertEqual(200,graph.status_code,graph.get_json())
        self.assertEqual(str(hall),graph.get_json()["transport_context"]["hall_id"])
        root=self.programs.root(PROGRAM_ID)
        command={"schema_version":1,"request_id":str(uuid4()),"stage_id":str(STAGE_ID),
            "player_uuid":str(OWNER_ID),"workspace_epoch":str(root.workspace_epoch),
            "expected_working_revision":root.revision,"operation":"WIRE_LAYOUT",
            "payload":{"changes":[{"position":{"x":8,"y":0,"z":0},"cell":None}]}}
        graph_app=self.app.extensions["blockcommander_graph_application"]
        with patch.object(graph_app,"apply_wire",wraps=graph_app.apply_wire) as apply:
            bad=self.client.post(f"/api/v1/stages/{STAGE_ID}/graph/wire",json=command,
                headers=self.headers(working_copy_id=uuid4()))
            self.assertEqual(409,bad.status_code);apply.assert_not_called()
            self.assertEqual(0,graph_app.idempotency_size)
            good=self.client.post(f"/api/v1/stages/{STAGE_ID}/graph/wire",json=command,headers=self.headers())
            self.assertEqual(200,good.status_code,good.get_json());apply.assert_called_once()
        self.assertIs(self.programs.for_hall(HALL_ID),self.programs.for_hall(hall))
        before=storage_fingerprint()
        bad_save=self.client.post(f"/api/v1/programs/{PROGRAM_ID}/save",headers=self.headers(working_copy_id=uuid4()),
            json={"player_uuid":str(OWNER_ID),"expected_saved_version":0})
        self.assertEqual(409,bad_save.status_code)
        self.assertEqual(before,storage_fingerprint())
        self.assertTrue(self.programs.load(PROGRAM_ID).dirty)
        saved=self.client.post(f"/api/v1/programs/{PROGRAM_ID}/save",headers=self.headers(hall),
            json={"player_uuid":str(OWNER_ID),"expected_saved_version":0})
        self.assertEqual(200,saved.status_code,saved.get_json())
        self.assertFalse(saved.get_json()["receipt"]["aggregate_dirty"])
        self.assertEqual(2,len(repository().load_program(str(PROGRAM_ID)).stages))

    def test_bulk_reconnect_preflight_rejects_explicit_context_before_restore_writes(self):
        with patch.object(self.app.logger,"exception"),patch.object(
                repository().__class__,"restore_candidates",side_effect=AssertionError("must not enter restore")):
            response=self.client.post("/api/v1/halls/restore",headers=self.headers(),
                json={"player_uuid":str(OWNER_ID),"world_uuid":str(WORLD_ID)})
        self.assertEqual(409,response.status_code,response.get_json())
        self.assertEqual("CONTEXT_ROUTE_UNSUPPORTED",response.get_json()["error_code"])

    def test_legacy_restore_keeps_working_copy_and_dirty_workspace(self):
        context=repository().resolve_working_copy_context(HALL_ID)
        root=self.programs.root(PROGRAM_ID);root.dirty=True
        snapshot=root.snapshot
        new_world=str(uuid4())
        payload={"player_uuid":str(OWNER_ID),"world_uuid":new_world,
            "world_name":"B2T world","dimension":"minecraft:overworld"}
        response=self.client.post("/api/v1/halls/restore",json=payload)
        self.assertEqual(200,response.status_code,response.get_json())
        hall=response.get_json()["halls"][0]
        self.assertEqual(str(context.working_copy_id),hall["working_copy_id"])
        self.assertTrue(hall["stage"]["program_dirty"])
        self.assertIs(snapshot,self.programs.root(PROGRAM_ID).snapshot)
        self.assertTrue(root.dirty)
        followup=self.client.get(f"/api/v1/worlds/{new_world}/halls")
        self.assertEqual(200,followup.status_code,followup.get_json())
        self.assertEqual(str(context.working_copy_id),followup.get_json()["halls"][0]["working_copy_id"])

    def test_restore_missing_mount_is_rejected_before_hall_world_write(self):
        with connect() as conn,conn.cursor() as cur:
            cur.execute("DELETE FROM blockcommander.hall_program_mounts WHERE hall_id=%s",(str(HALL_ID),))
        response=self.client.post("/api/v1/halls/restore",json={
            "player_uuid":str(OWNER_ID),"world_uuid":str(uuid4()),
            "world_name":"B2T world","dimension":"minecraft:overworld"})
        self.assertEqual(404,response.status_code,response.get_json())
        self.assertEqual("MOUNT_NOT_FOUND",response.get_json()["error_code"])
        self.assertEqual(str(WORLD_ID),str(repository().get(str(HALL_ID))["world_uuid"]))
        self.assertEqual({},self.stages._workspaces)


if __name__=="__main__":
    assert_database_identity()
    unittest.main()
