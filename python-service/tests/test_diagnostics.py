import json
import unittest
import uuid
from unittest.mock import patch

from ck_webhook import create_app
from hangar import diagnostics
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.transport_context import CONTEXT_HEADERS
from tests.test_program_service import FakeProgramRepository,HALL,OWNER,PROGRAM,ROOT


class DiagnosticTest(unittest.TestCase):
    def setUp(self):
        self.repo=FakeProgramRepository()
        self.app=create_app(repository=self.repo,migrate=False,graph_shared_secret="DO_NOT_LOG_SECRET")
        self.client=self.app.test_client()
        self.programs=self.app.extensions["blockcommander_program_service"]
        self.root=self.programs.root(PROGRAM)
        self.events=[]
        self.logging=patch.object(diagnostics.logger,"info",side_effect=lambda message:self.events.append(json.loads(message)))
        self.logging.start();self.addCleanup(self.logging.stop)
        self.trace=str(uuid.uuid4())
        context=self.repo.resolve_working_copy_context(HALL).as_dict()
        self.headers={GRAPH_SECRET_HEADER:"DO_NOT_LOG_SECRET","X-BlockCommander-Diagnostic-Id":self.trace,
            **{header:context[key] for key,header in CONTEXT_HEADERS.items()}}

    def test_graph_epoch_rejection_has_correlated_receive_versions_and_http_result(self):
        snapshot=self.root.snapshot
        request_id=str(uuid.uuid4())
        response=self.client.post(f"/api/v1/stages/{ROOT}/graph/wire",headers=self.headers,json={
            "schema_version":1,"request_id":request_id,"stage_id":str(ROOT),"player_uuid":str(OWNER),
            "workspace_epoch":str(uuid.uuid4()),"expected_working_revision":self.root.revision,
            "operation":"WIRE_LAYOUT","payload":{"changes":[{"position":{"x":1,"y":0,"z":0},"cell":None}]}})
        self.assertEqual(409,response.status_code)
        self.assertEqual("STALE_WORKSPACE_EPOCH",response.get_json()["error_code"])
        self.assertIs(snapshot,self.root.snapshot)
        for event in ("PY_HTTP_RECEIVE","PY_GRAPH_COMMAND_RECEIVE","PY_GRAPH_VERSION_CHECK","PY_GRAPH_REJECT","PY_HTTP_RESPONSE"):
            record=next(row for row in self.events if row["event"]==event)
            self.assertEqual(self.trace,record["diagnostic_id"])
            self.assertEqual(str(HALL),record["hall_id"])
            self.assertEqual(str(PROGRAM),record["program_id"])
            self.assertEqual(str(ROOT),record["stage_id"])
        check=next(row for row in self.events if row["event"]=="PY_GRAPH_VERSION_CHECK")
        self.assertEqual(str(self.root.workspace_epoch),check["current_epoch"])
        self.assertEqual(request_id,check["request_id"])
        self.assertFalse("DO_NOT_LOG_SECRET" in json.dumps(self.events))
        self.assertIsNone(diagnostics.context.get())

    def test_graph_get_observes_current_epoch_without_mutating_workspace(self):
        snapshot=self.root.snapshot
        response=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",headers=self.headers)
        self.assertEqual(200,response.status_code)
        event=next(row for row in self.events if row["event"]=="PY_GRAPH_GET")
        self.assertEqual(str(self.root.workspace_epoch),event["epoch"])
        self.assertEqual(self.root.revision,event["revision"])
        self.assertIs(snapshot,self.root.snapshot)

    def test_program_save_receipt_and_dirty_before_after_are_observed(self):
        child=self.programs.ensure_child(PROGRAM,ROOT,uuid.uuid5(ROOT,"program"),str(OWNER))
        self.root.dirty=True
        response=self.client.post(f"/api/v1/programs/{PROGRAM}/save",headers=self.headers,
            json={"player_uuid":str(OWNER),"expected_saved_version":0})
        self.assertEqual(200,response.status_code,response.get_json())
        begin=next(row for row in self.events if row["event"]=="PY_PROGRAM_SAVE_BEGIN")
        complete=next(row for row in self.events if row["event"]=="PY_PROGRAM_SAVE_COMPLETE")
        receipt=next(row for row in self.events if row["event"]=="PY_PROGRAM_SAVE_RECEIPT")
        self.assertTrue(begin["dirty_before"]);self.assertFalse(complete["dirty_after"])
        self.assertEqual({str(ROOT),str(child.snapshot.stage_id)},{row["stage_id"] for row in receipt["stages"]})
        self.assertEqual(1,self.repo.program_save_calls)

    def test_save_failure_keeps_existing_error_and_workspace_and_logs_phase(self):
        self.root.dirty=True;self.repo.fail_save=True
        with patch.object(self.app.logger,"exception"):
            response=self.client.post(f"/api/v1/programs/{PROGRAM}/save",headers=self.headers,
                json={"player_uuid":str(OWNER),"expected_saved_version":0})
        self.assertEqual(500,response.status_code)
        self.assertTrue(self.root.dirty)
        failure=next(row for row in self.events if row["event"]=="PY_PROGRAM_SAVE_FAILURE")
        self.assertEqual("REPOSITORY",failure["phase"])
        self.assertEqual("RuntimeError",failure["exception_type"])
        self.assertFalse(any(row["event"]=="PY_PROGRAM_SAVE_COMPLETE" for row in self.events))

    def test_broken_logger_does_not_change_success_or_failed_graph_http_response(self):
        with patch.object(diagnostics.logger,"info",side_effect=RuntimeError("logger failed")):
            response=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",headers=self.headers)
            self.assertEqual(200,response.status_code)
            bad=dict(self.headers);bad[CONTEXT_HEADERS["working_copy_id"]]=str(uuid.uuid4())
            response=self.client.get(f"/api/v1/stages/{ROOT}/graph?player_uuid={OWNER}",headers=bad)
            self.assertEqual(409,response.status_code)
            self.assertEqual("FOREIGN_WORKING_COPY",response.get_json()["error_code"])
        self.assertIsNone(diagnostics.context.get())


if __name__=="__main__":unittest.main()
