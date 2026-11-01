"""CK-06/A4 Program HTTP contract on isolated PostgreSQL."""
from __future__ import annotations

import unittest

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.repository import HallRepository
from postgres_graph_integration import (
    HALL_ID,
    OWNER_ID,
    PROGRAM_ID,
    STAGE_ID,
    assert_database_identity,
    connect,
    create_stage,
    repository,
    reset_schema,
)
from postgres_program_save_integration import stable_id, stage_snapshot


class ProgramHttpPostgreSQLIntegrationTest(unittest.TestCase):
    SECRET="isolated-postgres-program-http"

    def setUp(self):
        reset_schema();repository().migrate();create_stage()
        repository().save_full_stage_snapshot(
            str(STAGE_ID),0,stage_snapshot(STAGE_ID,"root-v1",with_program_block=True),
        )
        self.app=create_app(
            repository=HallRepository(connect=connect),migrate=False,
            graph_shared_secret=self.SECRET,
        )
        self.client=self.app.test_client()
        self.headers={GRAPH_SECRET_HEADER:self.SECRET}

    def test_mounted_program_and_graph_share_the_canonical_stage_workspace(self):
        response=self.client.get(
            f"/api/v1/halls/{HALL_ID}/program",headers=self.headers,
        )
        self.assertEqual(response.status_code,200)
        program=response.get_json()["program"]
        self.assertEqual(program["program_id"],str(PROGRAM_ID))
        self.assertEqual(program["root_stage_id"],str(STAGE_ID))
        self.assertEqual(len(program["stages"]),1)

        graph=self.client.get(
            f"/api/v1/stages/{STAGE_ID}/graph?player_uuid={OWNER_ID}",
            headers=self.headers,
        )
        self.assertEqual(graph.status_code,200)
        stage_service=self.app.extensions["blockcommander_stage_service"]
        program_service=self.app.extensions["blockcommander_program_service"]
        graph_service=self.app.extensions["blockcommander_graph_application"]
        self.assertIs(stage_service,program_service.stages)
        self.assertIs(stage_service,graph_service.stages)

    def test_child_ensure_then_program_save_is_atomic_and_persistent(self):
        block_id=stable_id(STAGE_ID,"program")
        ensure=self.client.post(
            f"/api/v1/programs/{PROGRAM_ID}/stages/{STAGE_ID}/children/ensure",
            json={
                "player_uuid":str(OWNER_ID),
                "program_block_instance_id":str(block_id),
                "stage_name":"HTTP child",
            },headers=self.headers,
        )
        self.assertEqual(ensure.status_code,201)
        child_id=ensure.get_json()["child"]["stage_id"]
        child_graph=self.client.get(
            f"/api/v1/stages/{child_id}/graph?player_uuid={OWNER_ID}",
            headers=self.headers,
        )
        self.assertEqual(child_graph.status_code,200)
        self.assertIsNone(child_graph.get_json()["state"]["hall_id"])
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM blockcommander.stages WHERE program_id=%s",(str(PROGRAM_ID),))
            self.assertEqual(cur.fetchone()[0],1)

        saved=self.client.post(
            f"/api/v1/programs/{PROGRAM_ID}/save",
            json={"player_uuid":str(OWNER_ID),"expected_saved_version":0},
            headers=self.headers,
        )
        self.assertEqual(saved.status_code,200)
        self.assertEqual(saved.get_json()["receipt"]["saved_version"],1)
        with connect() as conn,conn.cursor() as cur:
            cur.execute("SELECT saved_version FROM blockcommander.programs WHERE program_id=%s",(str(PROGRAM_ID),))
            self.assertEqual(cur.fetchone()[0],1)
            cur.execute("SELECT stage_kind,saved_version FROM blockcommander.stages WHERE stage_id=%s",(child_id,))
            self.assertEqual(cur.fetchone(),("PROGRAM_BODY",1))
            cur.execute("""SELECT link_state FROM blockcommander.program_stage_links
                WHERE program_id=%s AND child_stage_id=%s""",(str(PROGRAM_ID),child_id))
            self.assertEqual(cur.fetchone()[0],"ACTIVE")

        reloaded=create_app(
            repository=HallRepository(connect=connect),migrate=False,
            graph_shared_secret=self.SECRET,
        ).test_client()
        persisted_graph=reloaded.get(
            f"/api/v1/stages/{child_id}/graph?player_uuid={OWNER_ID}",
            headers=self.headers,
        )
        self.assertEqual(persisted_graph.status_code,200)
        state=persisted_graph.get_json()["state"]
        self.assertEqual(child_id,state["stage_id"])
        self.assertIsNone(state["hall_id"])
        self.assertEqual({"x":0,"y":0,"z":0},state["origin"])
        placed=reloaded.post(
            f"/api/v1/stages/{child_id}/blocks",
            json={
                "player_uuid":str(OWNER_ID),"type_key":"START",
                "coordinate_space":"STAGE_RELATIVE","x":1,"y":0,"z":1,
                "orientation":"EAST",
            },
        )
        self.assertEqual(placed.status_code,201)
        block=placed.get_json()["stage"]["blocks"][0]
        self.assertEqual((1,0,1),(block["rel_x"],block["rel_y"],block["rel_z"]))


if __name__=="__main__":
    assert_database_identity();unittest.main()
