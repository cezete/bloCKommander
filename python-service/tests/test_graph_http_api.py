import unittest
import uuid
from unittest.mock import patch

from ck_webhook import create_app
from hangar.graph_application import GRAPH_SECRET_HEADER
from tests.test_stage_graph_service import runtime_types
from tests.test_stage_service import FakeStageRepository


class GraphHttpApiTest(unittest.TestCase):
    SECRET="isolated-test-secret"

    def setUp(self):
        self.repo=FakeStageRepository(graph_aware=True);self.repo.types=runtime_types()
        self.flask=create_app(
            repository=self.repo,migrate=False,graph_shared_secret=self.SECRET
        )
        self.client=self.flask.test_client()
        self.stages=self.flask.extensions["blockcommander_stage_service"]
        self.stage_id=self.repo.stage["stage_id"];self.owner=self.repo.stage["owner_uuid"]
        self.stages.place(self.stage_id,self._place("START",11))
        self.stages.place(self.stage_id,self._place("STOP",13))

    def _place(self,type_key,x):
        return {"player_uuid":self.owner,"type_key":type_key,"x":x,"y":65,"z":21,
                "orientation":"EAST"}

    def _headers(self,secret=None):
        return {GRAPH_SECRET_HEADER:self.SECRET if secret is None else secret}

    def _command(self,operation):
        context=self.stages.read_graph_context(self.stage_id,self.owner)
        payload={}
        if operation=="WIRE_LAYOUT":
            payload={"changes":[{
                "position":{"x":1,"y":0,"z":0},
                "cell":{"channels":[{"channel":"SINGLE",
                                       "connections":["EAST","WEST"]}]},
            }]}
        return {"schema_version":1,"request_id":str(uuid.uuid4()),
                "stage_id":self.stage_id,"player_uuid":self.owner,
                "workspace_epoch":str(context.workspace_epoch),
                "expected_working_revision":context.state.working_revision,
                "operation":operation,"payload":payload}

    def test_wire_bind_and_get_endpoints(self):
        wire=self.client.post(
            f"/api/v1/stages/{self.stage_id}/graph/wire",
            json=self._command("WIRE_LAYOUT"),headers=self._headers(),
        )
        self.assertEqual(200,wire.status_code)
        self.assertEqual([],wire.get_json()["state"]["edges"])
        bind=self.client.post(
            f"/api/v1/stages/{self.stage_id}/graph/bind",
            json=self._command("BIND_EDGES"),headers=self._headers(),
        )
        self.assertEqual(200,bind.status_code)
        self.assertEqual(1,len(bind.get_json()["state"]["edges"]))
        self.assertEqual(1,len(bind.get_json()["binding"]["candidate_ids"]))
        self.assertEqual(1,len(bind.get_json()["discovery"]["candidates"]))
        before=bind.get_json()["working_revision"]
        state=self.client.get(
            f"/api/v1/stages/{self.stage_id}/graph?player_uuid={self.owner}",
            headers=self._headers(),
        )
        self.assertEqual(200,state.status_code)
        self.assertEqual(before,state.get_json()["working_revision"])
        self.assertEqual(1,len(state.get_json()["state"]["wire_cells"]))
        self.assertEqual(1,state.get_json()["discovery"]["schema_version"])
        self.assertEqual("COMPLETE",state.get_json()["discovery"]["components"][0]["status"])

    def test_missing_and_invalid_transport_auth_are_rejected(self):
        path=f"/api/v1/stages/{self.stage_id}/graph/wire"
        missing=self.client.post(path,json=self._command("WIRE_LAYOUT"))
        invalid=self.client.post(
            path,json=self._command("WIRE_LAYOUT"),headers=self._headers("wrong")
        )
        self.assertEqual(401,missing.status_code)
        self.assertEqual(401,invalid.status_code)
        self.assertEqual("INVALID_GRAPH_AUTH",missing.get_json()["error_code"])
        self.assertEqual(2,self.stages.read_graph_context(
            self.stage_id,self.owner
        ).state.working_revision)

    def test_unconfigured_auth_fails_closed_for_read_and_write(self):
        app=create_app(repository=self.repo,migrate=False,graph_shared_secret="")
        client=app.test_client()
        write=client.post(
            f"/api/v1/stages/{self.stage_id}/graph/wire",
            json=self._command("WIRE_LAYOUT"),headers=self._headers(),
        )
        read=client.get(
            f"/api/v1/stages/{self.stage_id}/graph?player_uuid={self.owner}",
            headers=self._headers(),
        )
        self.assertEqual(503,write.status_code)
        self.assertEqual(503,read.status_code)
        self.assertEqual("GRAPH_AUTH_NOT_CONFIGURED",write.get_json()["error_code"])

    def test_http_errors_are_structured_and_traceback_free(self):
        command=self._command("WIRE_LAYOUT");command["unknown"]=1
        response=self.client.post(
            f"/api/v1/stages/{self.stage_id}/graph/wire",
            json=command,headers=self._headers(),
        )
        body=response.get_json()
        self.assertEqual(400,response.status_code)
        self.assertEqual("UNKNOWN_FIELD",body["error_code"])
        self.assertIsInstance(body["diagnostics"],list)
        self.assertNotIn("Traceback",response.get_data(as_text=True))

    def test_unexpected_graph_error_is_structured_and_traceback_free(self):
        graph_app=self.flask.extensions["blockcommander_graph_application"]
        with patch.object(graph_app,"apply_wire",side_effect=RuntimeError("private")), \
                patch.object(self.flask.logger,"exception"):
            response=self.client.post(
                f"/api/v1/stages/{self.stage_id}/graph/wire",
                json=self._command("WIRE_LAYOUT"),headers=self._headers(),
            )
        body=response.get_json()
        self.assertEqual(500,response.status_code)
        self.assertEqual("INTERNAL_ERROR",body["error_code"])
        self.assertNotIn("private",response.get_data(as_text=True))
        self.assertNotIn("Traceback",response.get_data(as_text=True))

    def test_graph_get_requires_owner(self):
        response=self.client.get(
            f"/api/v1/stages/{self.stage_id}/graph?player_uuid={uuid.uuid4()}",
            headers=self._headers(),
        )
        self.assertEqual(403,response.status_code)
        self.assertEqual("FORBIDDEN",response.get_json()["error_code"])


if __name__=="__main__":unittest.main()
