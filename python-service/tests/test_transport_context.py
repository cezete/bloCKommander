import copy
import unittest
import uuid
from unittest.mock import patch
import psycopg2

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER, GraphApplicationResponse
from hangar.transport_context import CONTEXT_HEADERS
from tests.test_program_service import FakeProgramRepository, HALL, OWNER, PROGRAM, ROOT, OTHER_PROGRAM, OTHER_ROOT


class TransportContextHttpTest(unittest.TestCase):
    def setUp(self):
        self.repo=FakeProgramRepository()
        self.repo.add_program(OTHER_PROGRAM,OTHER_ROOT)
        self.app=create_app(repository=self.repo,migrate=False,graph_shared_secret="context-test")
        self.client=self.app.test_client()
        self.programs=self.app.extensions["blockcommander_program_service"]
        self.stages=self.app.extensions["blockcommander_stage_service"]
        self.graph=self.app.extensions["blockcommander_graph_application"]
        self.auth={GRAPH_SECRET_HEADER:"context-test"}

    def headers(self,hall=HALL,**overrides):
        context=self.repo.resolve_working_copy_context(hall).as_dict()
        context.update(overrides)
        return {**self.auth,**{header:str(context[key]) for key,header in CONTEXT_HEADERS.items()}}

    def second_hall(self):
        hall=uuid.uuid4();self.repo.mounts[hall]=PROGRAM
        return hall

    def place(self,headers,stage=ROOT):
        return self.client.post(f"/api/v1/stages/{stage}/blocks",headers=headers,json={
            "player_uuid":str(OWNER),"type_key":"START","coordinate_space":"STAGE_RELATIVE",
            "x":3,"y":0,"z":0,"orientation":"NORTH"})

    def assert_error(self,response,code,status=409):
        self.assertEqual(status,response.status_code,response.get_json())
        self.assertEqual(code,response.get_json()["error_code"])
        self.assertTrue(response.get_json()["diagnostics"])

    def test_explicit_context_allows_multi_mount_without_claiming_isolation(self):
        second=self.second_hall()
        response=self.place(self.headers())
        self.assertEqual(201,response.status_code,response.get_json())
        context=response.get_json()["transport_context"]
        self.assertEqual("explicit",context["mode"])
        self.assertEqual("SHARED_PROGRAM",context["content_scope"])
        self.assertEqual(str(HALL),context["hall_id"])
        self.assertIs(self.programs.for_hall(HALL),self.programs.for_hall(second))
        read=self.client.get(f"/api/v1/programs/{PROGRAM}/stages/{ROOT}",headers=self.headers(second))
        self.assertEqual(200,read.status_code)
        self.assertEqual(str(second),read.get_json()["transport_context"]["hall_id"])
        self.assertTrue(read.get_json()["aggregate_dirty"])

    def test_invalid_identity_never_reaches_domain_or_installs_workspace(self):
        for headers,stage,code in (
            (self.headers(working_copy_id=uuid.uuid4()),ROOT,"FOREIGN_WORKING_COPY"),
            (self.headers(),OTHER_ROOT,"STAGE_PROGRAM_MISMATCH"),
            (self.headers(program_id=OTHER_PROGRAM),ROOT,"HALL_PROGRAM_MISMATCH"),
        ):
            with patch.object(self.stages,"place",wraps=self.stages.place) as mutate:
                self.assert_error(self.place(headers,stage),code)
                mutate.assert_not_called()
            self.assertEqual({},self.programs._programs)
            self.assertEqual({},self.stages._workspaces)
            self.assertEqual(0,self.repo.program_save_calls)

    def test_working_copy_program_mismatch_has_its_own_contract(self):
        other_hall=uuid.uuid4();self.repo.mounts[other_hall]=OTHER_PROGRAM
        other_copy=uuid.uuid5(other_hall,"working-copy")
        response=self.place(self.headers(working_copy_id=other_copy))
        self.assert_error(response,"WORKING_COPY_PROGRAM_MISMATCH")
        self.assertEqual({},self.programs._programs)
        self.assertEqual({},self.stages._workspaces)
        self.assertEqual(0,self.repo.program_save_calls)

    def test_partial_malformed_and_misplaced_context_never_fall_back(self):
        partial={**self.auth,CONTEXT_HEADERS["hall_id"]:str(HALL)}
        self.assert_error(self.place(partial),"INCOMPLETE_CONTEXT",400)
        self.assert_error(self.place(self.headers(working_copy_id="invalid")),"INVALID_CONTEXT_ID",400)
        response=self.client.post(f"/api/v1/stages/{ROOT}/blocks",json={"working_copy_id":str(uuid.uuid4())})
        self.assert_error(response,"CONTEXT_LOCATION_INVALID",400)
        response=self.client.get(f"/api/v1/programs/{PROGRAM}/status?hall_id={HALL}",headers=self.auth)
        self.assert_error(response,"CONTEXT_LOCATION_INVALID",400)

    def test_legacy_single_mount_is_program_scoped_and_multi_mount_is_rejected(self):
        response=self.place({})
        self.assertEqual(201,response.status_code)
        context=response.get_json()["transport_context"]
        self.assertEqual("legacy_program",context["mode"])
        self.assertNotIn("hall_id",context)
        self.assertNotIn("working_copy_id",context)
        self.second_hall()
        before=self.programs.root(PROGRAM).snapshot
        for method,path,body in (
            ("post",f"/api/v1/stages/{ROOT}/blocks",{}),
            ("get",f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",None),
            ("get",f"/api/v1/programs/{PROGRAM}/status",None),
            ("get",f"/api/v1/programs/{PROGRAM}/stages/{ROOT}",None),
            ("post",f"/api/v1/programs/{PROGRAM}/save",{"player_uuid":str(OWNER),"expected_saved_version":0}),
        ):
            response=getattr(self.client,method)(path,json=body,headers=self.auth)
            self.assert_error(response,"CONTEXT_REQUIRED")
        self.assertIs(before,self.programs.root(PROGRAM).snapshot)
        self.assertEqual(0,self.repo.program_save_calls)

    def command(self,operation):
        workspace=self.programs.root(PROGRAM)
        return {"schema_version":1,"request_id":str(uuid.uuid4()),"stage_id":str(ROOT),
            "player_uuid":str(OWNER),"workspace_epoch":str(workspace.workspace_epoch),
            "expected_working_revision":workspace.revision,"operation":operation,
            "payload":({"changes":[]} if operation=="WIRE_LAYOUT" else
                {"position":{"x":3,"y":0,"z":3},"intent":"AUTO"} if operation=="WIRE_PLACE_INTENT" else {})}

    def test_each_graph_command_is_validated_before_application_and_idempotency(self):
        self.second_hall()
        for operation,suffix,method in (("WIRE_LAYOUT","wire","apply_wire"),
                ("WIRE_PLACE_INTENT","wire/place","place_wire"),("BIND_EDGES","bind","bind_edges")):
            command=self.command(operation)
            path=f"/api/v1/stages/{ROOT}/graph/{suffix}"
            before=self.graph.idempotency_size
            with patch.object(self.graph,method,return_value=GraphApplicationResponse(200,{"status":"ok"})) as apply:
                self.assert_error(self.client.post(path,json=command,
                    headers=self.headers(working_copy_id=uuid.uuid4())),"FOREIGN_WORKING_COPY")
                apply.assert_not_called()
                valid=self.client.post(path,json=command,headers=self.headers())
                self.assertEqual(200,valid.status_code)
                apply.assert_called_once()
            self.assertEqual(before,self.graph.idempotency_size)
        response=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",headers=self.headers())
        self.assertEqual(200,response.status_code)
        self.assertEqual("explicit",response.get_json()["transport_context"]["mode"])

    def test_child_and_save_context_checks_preserve_dirty_and_hierarchy(self):
        self.second_hall()
        path=f"/api/v1/programs/{PROGRAM}/stages/{ROOT}/children/ensure"
        body={"player_uuid":str(OWNER),"program_block_instance_id":str(uuid.uuid5(ROOT,"program"))}
        wrong=self.headers(working_copy_id=uuid.uuid4())
        self.assert_error(self.client.post(path,json=body,headers=wrong),"FOREIGN_WORKING_COPY")
        self.assertEqual({},self.programs._programs)
        response=self.client.post(path,json=body,headers=self.headers())
        self.assertEqual(201,response.status_code)
        child=response.get_json()["child"]["stage_id"]
        self.assertEqual(200,self.client.get(f"/api/v1/programs/{PROGRAM}/stages/{child}",headers=self.headers()).status_code)
        workspace=self.programs.load(PROGRAM)
        before=(copy.deepcopy(self.repo.snapshots),workspace.links,workspace.dirty)
        save=f"/api/v1/programs/{PROGRAM}/save"
        self.assert_error(self.client.post(save,json={"player_uuid":str(OWNER),"expected_saved_version":0},headers=wrong),"FOREIGN_WORKING_COPY")
        self.assertEqual(before,(self.repo.snapshots,workspace.links,workspace.dirty))
        valid=self.client.post(save,json={"player_uuid":str(OWNER),"expected_saved_version":0},headers=self.headers())
        self.assertEqual(200,valid.status_code)
        self.assertFalse(valid.get_json()["receipt"]["aggregate_dirty"])

    def test_save_button_is_bound_to_control_hall_and_keeps_legacy_compatibility(self):
        second=self.second_hall()
        workspace=self.programs.root(PROGRAM);workspace.dirty=True
        body={"player_uuid":str(OWNER),"world_uuid":str(uuid.UUID(int=77)),"x":0,"y":0,"z":0,
            "active_stage_id":str(ROOT),"workspace_epoch":str(workspace.workspace_epoch),
            "expected_working_revision":workspace.revision}
        with patch.object(self.repo,"by_control",return_value={"hall_id":str(HALL)},create=True):
            self.assert_error(self.client.post("/api/v1/halls/save-button",json=body,
                headers=self.headers(second)),"CONTEXT_MISMATCH")
            self.assertTrue(workspace.dirty)
            valid=self.client.post("/api/v1/halls/save-button",json=body)
        self.assertEqual(200,valid.status_code)
        self.assertFalse(valid.get_json()["stage"]["program_dirty"])
        self.assertEqual("legacy_hall",valid.get_json()["transport_context"]["mode"])

    def test_owner_checks_and_bulk_explicit_context_stay_closed(self):
        denied=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={uuid.uuid4()}",headers=self.headers())
        self.assertEqual(403,denied.status_code)
        denied=self.client.post(f"/api/v1/programs/{PROGRAM}/save",json={
            "player_uuid":str(uuid.uuid4()),"expected_saved_version":0},headers=self.headers())
        self.assertEqual(403,denied.status_code)
        with patch.object(self.repo,"list_world",create=True) as list_world:
            self.assert_error(self.client.get(f"/api/v1/worlds/{uuid.uuid4()}/halls",
                headers=self.headers()),"CONTEXT_ROUTE_UNSUPPORTED")
            list_world.assert_not_called()

    def test_missing_or_deleted_mount_is_rejected_without_cache_or_domain_changes(self):
        headers=self.headers()
        del self.repo.mounts[HALL]
        self.assert_error(self.place(headers),"MOUNT_NOT_FOUND",404)
        self.assertEqual({},self.programs._programs)
        self.assertEqual({},self.stages._workspaces)
        self.assertEqual(0,self.repo.program_save_calls)

    def test_legacy_adapter_can_be_disabled_without_another_fallback(self):
        app=create_app(repository=self.repo,migrate=False,graph_shared_secret="context-test",allow_legacy_context=False)
        client=app.test_client()
        self.assert_error(client.get(f"/api/v1/programs/{PROGRAM}/status",headers=self.auth),"CONTEXT_REQUIRED")
        self.assertEqual(200,client.get(f"/api/v1/programs/{PROGRAM}/status",headers=self.headers()).status_code)

    def test_update_delete_rebuild_reads_child_and_save_all_use_common_preflight(self):
        wrong=self.headers(working_copy_id=uuid.uuid4())
        block=uuid.uuid5(ROOT,"program")
        for method,path in (
            ("post",f"/api/v1/stages/{ROOT}/blocks/{block}"),
            ("post",f"/api/v1/stages/{ROOT}/blocks/{block}/delete"),
            ("post",f"/api/v1/stages/{ROOT}/rebuild"),
            ("post",f"/api/v1/stages/{ROOT}/save"),
            ("get",f"/api/v1/programs/{PROGRAM}"),
            ("get",f"/api/v1/programs/{PROGRAM}/stages/{ROOT}/children"),
            ("post",f"/api/v1/programs/{PROGRAM}/stages/{ROOT}/children/ensure"),
            ("get",f"/api/v1/halls/{HALL}/program"),
        ):
            self.assert_error(getattr(self.client,method)(path,json={},headers=wrong),"FOREIGN_WORKING_COPY")
        self.assertEqual({},self.stages._workspaces)
        self.assertEqual({},self.programs._programs)
        self.assertEqual(0,self.repo.program_save_calls)

    def test_repository_failure_is_structured_and_does_not_enter_domain(self):
        with patch.object(self.repo,"load_program_for_stage",side_effect=psycopg2.OperationalError("private DSN")):
            response=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",headers=self.auth)
        self.assert_error(response,"CONTEXT_REPOSITORY_UNAVAILABLE",503)
        self.assertEqual("ck-graph-v1",response.get_json()["api_version"])
        self.assertNotIn("private DSN",response.get_data(as_text=True))
        self.assertEqual({},self.programs._programs)
