import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

from hangar.graph import (
    BindingResult, CardinalDirection, Diagnostic, DiagnosticCode, GridPos,
    Severity,
)
from hangar.graph_application import (
    GraphApplicationService, GraphRequestError, parse_graph_command,
)
from hangar.stage import StageService
from tests.test_stage_graph_service import runtime_types
from tests.test_stage_service import FakeStageRepository


class GraphApplicationTest(unittest.TestCase):
    def setUp(self):
        self.repo=FakeStageRepository(graph_aware=True)
        self.repo.types=runtime_types()
        self.stages=StageService(self.repo)
        self.stage_id=self.repo.stage["stage_id"]
        self.owner=self.repo.stage["owner_uuid"]
        self.stages.place(self.stage_id,self._place("START",11,"EAST"))
        self.stages.place(self.stage_id,self._place("STOP",13,"EAST"))
        self.app=GraphApplicationService(self.stages,max_idempotency_entries=4)

    def _place(self,type_key,x,orientation):
        return {"player_uuid":self.owner,"type_key":type_key,"x":x,"y":65,"z":21,
                "orientation":orientation}

    def _context(self):
        return self.stages.read_graph_context(self.stage_id,self.owner)

    def _raw(self,operation,*,request_id=None,revision=None,epoch=None,player=None,
             position=(1,0,0),connections=("EAST","WEST"),cell=True):
        context=self._context()
        payload={}
        if operation=="WIRE_LAYOUT":
            payload={"changes":[{
                "position":{"x":position[0],"y":position[1],"z":position[2]},
                "cell":None if not cell else {"channels":[{
                    "channel":"SINGLE","connections":list(connections),
                }]},
            }]}
        return {
            "schema_version":1,"request_id":str(request_id or uuid.uuid4()),
            "stage_id":self.stage_id,"player_uuid":player or self.owner,
            "workspace_epoch":epoch or str(context.workspace_epoch),
            "expected_working_revision":(
                context.state.working_revision if revision is None else revision
            ),
            "operation":operation,"payload":payload,
        }

    def _command(self,operation,**kwargs):
        return parse_graph_command(
            self._raw(operation,**kwargs),expected_stage_id=self.stage_id,
            expected_operation=operation,
        )

    def _wire(self,**kwargs):
        return self.app.apply_wire(self._command("WIRE_LAYOUT",**kwargs))

    def _bind(self,**kwargs):
        return self.app.bind_edges(self._command("BIND_EDGES",**kwargs))

    def test_wire_upsert_is_dirty_and_does_not_create_edge_or_save(self):
        result=self._wire()
        self.assertEqual(200,result.status_code)
        self.assertTrue(result.body["modified"])
        self.assertEqual(1,len(result.body["state"]["wire_cells"]))
        self.assertEqual([],result.body["state"]["edges"])
        self.assertTrue(result.body["state"]["dirty"])
        self.assertIsNone(self.repo.full_saved)
        self.assertEqual(0,self.repo.stage["saved_version"])

    def test_wire_replace_and_remove_are_supported(self):
        self._wire()
        replaced=self._wire()
        self.assertFalse(replaced.body["modified"])
        removed=self._wire(cell=False)
        self.assertTrue(removed.body["modified"])
        self.assertEqual([],removed.body["state"]["wire_cells"])

    def test_wire_placement_outside_connector_cell_is_valid_incomplete_draft(self):
        result=self._wire(position=(1,0,1))
        self.assertEqual(200,result.status_code)
        self.assertTrue(result.body["modified"])
        self.assertEqual("INCOMPLETE",result.body["discovery"]["components"][0]["status"])
        self.assertEqual(1,len(self._context().state.wire_cells))

    def test_wire_removal_outside_connector_cell_remains_supported(self):
        result=self._wire(position=(1,0,1),cell=False)
        self.assertEqual(200,result.status_code)
        self.assertFalse(result.body["modified"])

    def test_north_south_wire_geometry_is_accepted_as_incomplete_draft(self):
        result=self._wire(connections=("NORTH","SOUTH"))
        self.assertEqual(200,result.status_code)
        self.assertEqual("INCOMPLETE",result.body["discovery"]["components"][0]["status"])

    def test_wire_removal_preserves_existing_logical_edge_and_marks_graph_draft(self):
        self._wire();bound=self._bind()
        edge_id=bound.body["state"]["edges"][0]["edge_id"]
        removed=self._wire(cell=False)
        self.assertEqual(edge_id,removed.body["state"]["edges"][0]["edge_id"])
        self.assertEqual(1,len(removed.body["state"]["routes"]))
        self.assertEqual("DRAFT",removed.body["state"]["status"]["graph"])
        self.assertEqual("CHECK_REQUIRED",removed.body["state"]["status"]["edge_indicator"])

    def test_wire_noop_does_not_increment_revision(self):
        first=self._wire()
        revision=first.body["working_revision"]
        repeated=self._wire()
        self.assertFalse(repeated.body["modified"])
        self.assertEqual(revision,repeated.body["working_revision"])

    def test_bind_creates_exactly_one_control_edge_and_valid_route(self):
        self._wire()
        result=self._bind()
        self.assertEqual(200,result.status_code)
        state=result.body["state"]
        self.assertEqual(1,len(state["edges"]))
        self.assertEqual("CONTROL",state["edges"][0]["edge_kind"])
        self.assertEqual("out",state["edges"][0]["source_port_key"])
        self.assertEqual("in",state["edges"][0]["target_port_key"])
        self.assertEqual(1,len(state["routes"]))
        self.assertEqual("VALID",state["routes"][0]["status"])
        self.assertTrue(state["dirty"])
        self.assertIsNone(self.repo.full_saved)

    def test_repeated_bind_reuses_edge_and_route_without_revision_change(self):
        self._wire();first=self._bind();revision=first.body["working_revision"]
        second=self._bind()
        self.assertEqual(200,second.status_code)
        self.assertFalse(second.body["modified"])
        self.assertEqual(revision,second.body["working_revision"])
        self.assertEqual(1,len(second.body["state"]["edges"]))
        self.assertEqual(1,len(second.body["state"]["routes"]))
        self.assertEqual(
            first.body["state"]["routes"][0]["route_id"],
            second.body["state"]["routes"][0]["route_id"],
        )

    def test_same_request_and_payload_returns_original_response(self):
        request_id=uuid.uuid4();command=self._command("WIRE_LAYOUT",request_id=request_id)
        first=self.app.apply_wire(command);second=self.app.apply_wire(command)
        self.assertEqual(first,second)
        self.assertEqual(3,self._context().state.working_revision)

    def test_same_request_with_different_payload_conflicts(self):
        request_id=uuid.uuid4();self._wire(request_id=request_id)
        conflict=self._wire(
            request_id=request_id,revision=2,connections=("NORTH","SOUTH")
        )
        self.assertEqual(409,conflict.status_code)
        self.assertEqual("REQUEST_ID_CONFLICT",conflict.body["error_code"])

    def test_new_request_with_stale_revision_is_rejected(self):
        self._wire();result=self._wire(revision=2)
        self.assertEqual(409,result.status_code)
        self.assertEqual("STALE_WORKING_REVISION",result.body["error_code"])
        self.assertEqual(3,self._context().state.working_revision)

    def test_parallel_same_request_modifies_once(self):
        command=self._command("WIRE_LAYOUT")
        with ThreadPoolExecutor(max_workers=2) as executor:
            results=list(executor.map(lambda _:self.app.apply_wire(command),range(2)))
        self.assertEqual(results[0],results[1])
        self.assertEqual(3,self._context().state.working_revision)
        self.assertEqual(1,len(self._context().state.wire_cells))

    def test_owner_is_enforced(self):
        result=self._wire(player=str(uuid.uuid4()))
        self.assertEqual(403,result.status_code)
        self.assertEqual("FORBIDDEN",result.body["error_code"])
        self.assertEqual(2,self._context().state.working_revision)

    def test_stale_epoch_is_rejected_and_old_cache_is_expired(self):
        self._wire();self.assertEqual(1,self.app.idempotency_size)
        result=self._wire(epoch=str(uuid.uuid4()))
        self.assertEqual(409,result.status_code)
        self.assertEqual("STALE_WORKSPACE_EPOCH",result.body["error_code"])

    def test_successful_epoch_change_expires_previous_request_records(self):
        self._wire();self.assertEqual(1,self.app.idempotency_size)
        self.stages.rebuild(
            self.stage_id,{"player_uuid":self.owner,"confirm_discard":True}
        )
        self._bind()
        self.assertEqual(1,self.app.idempotency_size)

    def test_out_of_bounds_and_node_collision_are_rejected(self):
        outside=self._wire(position=(9,0,0))
        self.assertEqual("WIRE_POSITION_OUT_OF_BOUNDS",outside.body["error_code"])
        occupied=self._wire(position=(0,0,0))
        self.assertEqual("WIRE_OCCUPIES_NODE",occupied.body["error_code"])
        self.assertEqual(2,self._context().state.working_revision)

    def test_missing_wire_does_not_create_edge(self):
        result=self._bind()
        self.assertEqual(422,result.status_code)
        self.assertEqual("MISSING_WIRE",result.body["error_code"])
        self.assertEqual((),self._context().state.edges)

    def test_wrong_orientation_does_not_create_edge(self):
        repo=FakeStageRepository(graph_aware=True);repo.types=runtime_types()
        stages=StageService(repo);owner=repo.stage["owner_uuid"];stage_id=repo.stage["stage_id"]
        stages.place(stage_id,{"player_uuid":owner,"type_key":"START","x":11,"y":65,"z":21,"orientation":"EAST"})
        stages.place(stage_id,{"player_uuid":owner,"type_key":"STOP","x":13,"y":65,"z":21,"orientation":"NORTH"})
        app=GraphApplicationService(stages);context=stages.read_graph_context(stage_id,owner)
        wire_raw=self._raw("WIRE_LAYOUT")
        wire_raw.update({"stage_id":stage_id,"player_uuid":owner,
                         "workspace_epoch":str(context.workspace_epoch),
                         "expected_working_revision":context.state.working_revision})
        wire=parse_graph_command(wire_raw,expected_stage_id=stage_id,expected_operation="WIRE_LAYOUT")
        app.apply_wire(wire);context=stages.read_graph_context(stage_id,owner)
        bind_raw={"schema_version":1,"request_id":str(uuid.uuid4()),"stage_id":stage_id,
                  "player_uuid":owner,"workspace_epoch":str(context.workspace_epoch),
                  "expected_working_revision":context.state.working_revision,
                  "operation":"BIND_EDGES","payload":{}}
        result=app.bind_edges(parse_graph_command(
            bind_raw,expected_stage_id=stage_id,expected_operation="BIND_EDGES"
        ))
        self.assertEqual("UNSUPPORTED_TOPOLOGY",result.body["error_code"])
        self.assertEqual((),stages.read_graph_context(stage_id,owner).state.edges)

    def test_non_matching_geometry_has_structured_discovery_diagnostic(self):
        result=self._wire(connections=("NORTH","SOUTH"))
        self.assertEqual(200,result.status_code)
        self.assertEqual("OPEN_WIRE",result.body["discovery"]["diagnostics"][0]["code"])

    def test_domain_rejection_leaves_workspace_unchanged(self):
        self._wire();before=self._context().state
        diagnostic=Diagnostic(
            DiagnosticCode.AMBIGUOUS_CONNECTION,Severity.ERROR,"rejected"
        )
        with patch("hangar.graph_application.bind_edges",side_effect=lambda state,*args,**kwargs:
                   BindingResult(False,state,(diagnostic,))):
            result=self._bind()
        self.assertEqual(422,result.status_code)
        after=self._context().state
        self.assertEqual(before,after)

    def test_graph_state_is_authoritative_and_read_only(self):
        self._wire();before=self._context().state.working_revision
        result=self.app.graph_state(self.stage_id,self.owner)
        self.assertEqual(200,result.status_code)
        self.assertEqual(before,result.body["working_revision"])
        self.assertEqual(before,self._context().state.working_revision)
        self.assertEqual(1,len(result.body["state"]["wire_cells"]))

    def test_lost_response_can_be_reconciled_by_retry_and_get(self):
        command=self._command("WIRE_LAYOUT")
        original=self.app.apply_wire(command)
        retry=self.app.apply_wire(command)
        current=self.app.graph_state(self.stage_id,self.owner)
        self.assertEqual(original,retry)
        self.assertEqual(original.body["working_revision"],current.body["working_revision"])

    def test_idempotency_cache_is_bounded_and_clearable(self):
        for _ in range(8):
            self._wire()
        self.assertEqual(4,self.app.idempotency_size)
        self.app.clear_idempotency()
        self.assertEqual(0,self.app.idempotency_size)

    def test_parser_rejects_unknown_invalid_and_multi_cell_payloads(self):
        raw=self._raw("WIRE_LAYOUT");raw["unexpected"]=True
        with self.assertRaises(GraphRequestError):
            parse_graph_command(raw,expected_stage_id=self.stage_id,
                                expected_operation="WIRE_LAYOUT")
        raw=self._raw("WIRE_LAYOUT");raw["payload"]["changes"][0]["cell"]["channels"][0]["connections"]=["EAST","WEST","NORTH"]
        with self.assertRaises(GraphRequestError):
            parse_graph_command(raw,expected_stage_id=self.stage_id,
                                expected_operation="WIRE_LAYOUT")
        raw=self._raw("WIRE_LAYOUT");raw["payload"]["changes"].append({
            "position":{"x":1,"y":0,"z":1},"cell":None,
        })
        command=parse_graph_command(raw,expected_stage_id=self.stage_id,
                                    expected_operation="WIRE_LAYOUT")
        result=self.app.apply_wire(command)
        self.assertEqual("UNSUPPORTED_BATCH_SIZE",result.body["error_code"])


if __name__=="__main__":unittest.main()
