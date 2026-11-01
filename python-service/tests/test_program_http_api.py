import unittest
import uuid
from unittest.mock import patch

import psycopg2

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.program import ProgramService, ProgramWorkspaceState
from tests.test_program_service import (
    FakeProgramRepository, HALL, OTHER_PROGRAM, OTHER_ROOT, OWNER, PROGRAM, ROOT,
)


class ProgramHttpApiTest(unittest.TestCase):
    SECRET="program-http-test-secret"

    def setUp(self):
        self.repo=FakeProgramRepository()
        self.app=create_app(
            repository=self.repo,migrate=False,graph_shared_secret=self.SECRET,
        )
        self.client=self.app.test_client()
        self.headers={GRAPH_SECRET_HEADER:self.SECRET}
        self.programs=self.app.extensions["blockcommander_program_service"]
        self.stages=self.app.extensions["blockcommander_stage_service"]
        self.program_block=uuid.uuid5(ROOT,"program")

    def ensure(self,program=PROGRAM,parent=ROOT,owner=OWNER,block_id=None):
        return self.client.post(
            f"/api/v1/programs/{program}/stages/{parent}/children/ensure",
            json={
                "player_uuid":str(owner),
                "program_block_instance_id":str(block_id or self.program_block),
                "stage_name":"child",
            },headers=self.headers,
        )

    def save(self,version=0,owner=OWNER):
        return self.client.post(
            f"/api/v1/programs/{PROGRAM}/save",
            json={"player_uuid":str(owner),"expected_saved_version":version},
            headers=self.headers,
        )

    def test_one_canonical_service_pair_is_shared_with_graph(self):
        graph=self.app.extensions["blockcommander_graph_application"]
        self.assertIs(self.stages,graph.stages)
        self.assertIs(self.stages,self.programs.stages)
        self.assertIs(self.repo,self.programs.repository)
        self.assertIs(self.programs.load(PROGRAM),self.programs.for_hall(HALL))
        with self.assertRaisesRegex(RuntimeError,"already attached"):
            ProgramService(self.repo,self.stages)

    def test_hall_program_and_hierarchy_dto_round_trip(self):
        ensured=self.ensure()
        self.assertEqual(201,ensured.status_code)
        child=ensured.get_json()["child"]
        response=self.client.get(f"/api/v1/halls/{HALL}/program",headers=self.headers)
        body=response.get_json();program=body["program"]
        self.assertEqual(200,response.status_code)
        self.assertEqual("ck-program-v1",body["api_version"])
        self.assertEqual(str(PROGRAM),program["program_id"])
        self.assertEqual(str(ROOT),program["root_stage_id"])
        self.assertEqual(2,len(program["stages"]))
        self.assertEqual("PROGRAM_BODY",child["kind"])
        self.assertEqual("ACTIVE",program["links"][0]["state"])
        self.assertEqual(1,program["hierarchy_revision"])
        root=next(item for item in program["stages"] if item["is_root"])
        self.assertEqual({"x":0,"y":0,"z":0},root["domain_layout"]["origin"])
        serialized=str(program)
        self.assertNotIn("world_uuid",serialized)
        self.assertNotIn("hall_min_",serialized)

        children=self.client.get(
            f"/api/v1/programs/{PROGRAM}/stages/{ROOT}/children",headers=self.headers,
        ).get_json()
        self.assertEqual(1,len(children["children"]))
        self.assertEqual(child["stage_id"],children["children"][0]["stage_id"])

    def test_child_ensure_is_idempotent_and_does_not_write_database(self):
        first=self.ensure();second=self.ensure()
        self.assertEqual(201,first.status_code)
        self.assertEqual(200,second.status_code)
        self.assertTrue(first.get_json()["created"])
        self.assertFalse(second.get_json()["created"])
        self.assertEqual(
            first.get_json()["child"]["stage_id"],second.get_json()["child"]["stage_id"],
        )
        self.assertEqual(0,self.repo.program_save_calls)

    def test_retained_link_is_exposed_without_being_an_active_child(self):
        first=self.ensure();child_id=first.get_json()["child"]["stage_id"]
        self.programs.retain_child(PROGRAM,ROOT,self.program_block,str(OWNER))
        response=self.client.get(
            f"/api/v1/programs/{PROGRAM}/stages/{ROOT}/children",headers=self.headers,
        ).get_json()
        self.assertEqual("RETAINED",response["links"][0]["state"])
        self.assertEqual(child_id,response["children"][0]["stage_id"])

    def test_legacy_stage_http_save_delegates_to_program_save(self):
        placed=self.client.post(
            f"/api/v1/stages/{ROOT}/blocks",
            json={
                "player_uuid":str(OWNER),"type_key":"START",
                "x":2,"y":0,"z":0,"orientation":"EAST",
            },
        )
        self.assertEqual(201,placed.status_code)
        saved=self.client.post(
            f"/api/v1/stages/{ROOT}/save",json={"player_uuid":str(OWNER)},
        )
        self.assertEqual(200,saved.status_code)
        self.assertEqual(1,self.repo.program_save_calls)
        self.assertEqual(1,self.repo.programs[PROGRAM]["saved_version"])

    def test_program_placement_ensure_and_delete_retains_child(self):
        placed=self.client.post(
            f"/api/v1/stages/{ROOT}/blocks",
            json={
                "player_uuid":str(OWNER),"type_key":"PROGRAM",
                "x":4,"y":0,"z":0,"orientation":"NORTH",
            },
        )
        self.assertEqual(201,placed.status_code)
        instance_id=next(item["instance_id"] for item in placed.get_json()["stage"]["blocks"]
            if item["type_key"]=="PROGRAM" and item["rel_x"]==4)
        ensured=self.ensure(block_id=uuid.UUID(instance_id))
        self.assertEqual(201,ensured.status_code)
        child_id=ensured.get_json()["child"]["stage_id"]

        deleted=self.client.post(
            f"/api/v1/stages/{ROOT}/blocks/{instance_id}/delete",
            json={"player_uuid":str(OWNER)},
        )
        self.assertEqual(200,deleted.status_code)
        workspace=self.programs.load(PROGRAM)
        link=next(item for item in workspace.links
            if str(item.program_block_instance_id)==instance_id)
        self.assertEqual("RETAINED",link.link_state)
        self.assertIn(uuid.UUID(child_id),workspace.stage_headers)

    def test_authoritative_stage_projection_uses_relative_coordinates_only(self):
        response=self.client.get(
            f"/api/v1/programs/{PROGRAM}/stages/{ROOT}",headers=self.headers,
        )
        self.assertEqual(200,response.status_code)
        body=response.get_json();stage=body["stage"]
        self.assertEqual(str(PROGRAM),body["program_id"])
        self.assertEqual(str(ROOT),stage["stage_id"])
        self.assertIsNotNone(stage["workspace"]["epoch"])
        self.assertEqual({"x":0,"y":0,"z":0},stage["blocks"][0]["position"])
        serialized=str(stage)
        self.assertNotIn("world_uuid",serialized)
        self.assertNotIn("hall_min_",serialized)

    def test_program_body_graph_get_uses_logical_coordinates_without_hall_identity(self):
        child_id=self.ensure().get_json()["child"]["stage_id"]
        response=self.client.get(
            f"/api/v1/stages/{child_id}/graph?player_uuid={OWNER}",headers=self.headers,
        )
        self.assertEqual(200,response.status_code)
        state=response.get_json()["state"]
        self.assertEqual(child_id,state["stage_id"])
        self.assertIsNone(state["hall_id"])
        self.assertEqual({"x":0,"y":0,"z":0},state["origin"])
        self.assertEqual(0,state["working_revision"])

    def test_program_body_accepts_explicit_stage_relative_block_placement(self):
        child_id=self.ensure().get_json()["child"]["stage_id"]
        child=self.programs.stage(PROGRAM,child_id)
        child.stage["hall_min_x"]=None
        child.stage["hall_min_y"]=None
        child.stage["hall_min_z"]=None
        response=self.client.post(
            f"/api/v1/stages/{child_id}/blocks",
            json={
                "player_uuid":str(OWNER),"type_key":"START",
                "coordinate_space":"STAGE_RELATIVE","x":2,"y":0,"z":1,
                "orientation":"EAST",
            },
        )
        self.assertEqual(201,response.status_code)
        stage=response.get_json()["stage"]
        self.assertIsNone(stage["hall_id"])
        placed=stage["blocks"][0]
        self.assertEqual((2,0,1),(placed["rel_x"],placed["rel_y"],placed["rel_z"]))
        self.assertTrue(stage["dirty"])

        invalid=self.client.post(
            f"/api/v1/stages/{child_id}/blocks",
            json={
                "player_uuid":str(OWNER),"type_key":"STOP",
                "coordinate_space":"WORLD_GUESS","x":3,"y":0,"z":1,
                "orientation":"EAST",
            },
        )
        self.assertEqual(422,invalid.status_code)

    def test_owner_only_save_and_cross_program_stage_are_rejected(self):
        self.ensure()
        denied=self.save(owner=uuid.uuid4())
        self.assertEqual(403,denied.status_code)
        self.assertEqual("FORBIDDEN",denied.get_json()["error_code"])

        self.repo.add_program(OTHER_PROGRAM,OTHER_ROOT)
        cross=self.ensure(program=PROGRAM,parent=OTHER_ROOT)
        self.assertEqual(404,cross.status_code)
        self.assertEqual("NOT_FOUND",cross.get_json()["error_code"])

    def test_program_save_receipt_status_and_stale_version_contract(self):
        self.ensure()
        response=self.save(0);body=response.get_json()["receipt"]
        self.assertEqual(200,response.status_code)
        self.assertTrue(body["changed"])
        self.assertEqual(1,body["saved_version"])
        self.assertFalse(body["aggregate_dirty"])
        self.assertEqual("ACTIVE",body["workspace_state"])

        status=self.client.get(
            f"/api/v1/programs/{PROGRAM}/status",headers=self.headers,
        ).get_json()["program_status"]
        self.assertFalse(status["aggregate_dirty"])
        self.assertEqual(2,len(status["stages"]))
        self.assertTrue(all(item["workspace"] is not None for item in status["stages"]))

        stale=self.save(0)
        self.assertEqual(409,stale.status_code)
        self.assertEqual("STALE_VERSION",stale.get_json()["error_code"])

    def test_reload_required_blocks_mutation_and_has_explicit_contract(self):
        workspace=self.programs.load(PROGRAM)
        workspace.state=ProgramWorkspaceState.RELOAD_REQUIRED
        response=self.ensure()
        self.assertEqual(409,response.status_code)
        self.assertEqual("RELOAD_REQUIRED",response.get_json()["error_code"])

    def test_repository_failure_keeps_dirty_workspace_and_hides_sql_details(self):
        self.ensure();workspace=self.programs.load(PROGRAM)
        with patch.object(
            self.repo,"save_program_snapshot",
            side_effect=psycopg2.OperationalError("private SQL and password"),
        ),patch.object(self.app.logger,"exception"):
            response=self.save(0)
        self.assertEqual(503,response.status_code)
        self.assertEqual("REPOSITORY_ERROR",response.get_json()["error_code"])
        self.assertTrue(workspace.dirty)
        text=response.get_data(as_text=True)
        self.assertNotIn("private SQL",text)
        self.assertNotIn("Traceback",text)

    def test_transport_and_strict_request_validation(self):
        unauthenticated=self.client.get(f"/api/v1/programs/{PROGRAM}")
        self.assertEqual(401,unauthenticated.status_code)
        self.assertEqual("ck-program-v1",unauthenticated.get_json()["api_version"])
        invalid=self.client.post(
            f"/api/v1/programs/{PROGRAM}/save",
            json={"player_uuid":str(OWNER),"expected_saved_version":0,"secret":"leak"},
            headers=self.headers,
        )
        self.assertEqual(400,invalid.status_code)
        self.assertEqual("UNKNOWN_FIELD",invalid.get_json()["error_code"])


if __name__=="__main__": unittest.main()
