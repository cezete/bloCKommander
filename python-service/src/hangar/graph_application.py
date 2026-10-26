"""Versioned application boundary for Fabric graph and wire commands.

This module owns transport-facing validation, process-local idempotency and the
first deliberately narrow topology discovery.  Graph semantics remain in the
existing immutable domain and every mutation still crosses StageService.
"""
from __future__ import annotations

import copy
import hashlib
import hmac
import json
import threading
import uuid
from collections import OrderedDict
from dataclasses import dataclass
from typing import Any, Callable

from .graph import (
    BindEdgesCommand,
    CK_CONTROL_PORT_CATALOG,
    CardinalDirection,
    Diagnostic,
    GraphWorkspaceMappingError,
    GraphWorkspaceState,
    GridPos,
    PortRef,
    RouteCandidate,
    WireCell,
    WireChannel,
    WireChannelState,
    WireStep,
    bind_edges,
    port_connection_position,
    replace_content,
)
from .repository import HallConflict, HallForbidden, HallNotFound
from .stage import GraphOperationRejected, StageGraphContext, StageService


API_VERSION = "ck-graph-v1"
SCHEMA_VERSION = 1
GRAPH_SECRET_ENV = "BLOCKCOMMANDER_GRAPH_SHARED_SECRET"
GRAPH_SECRET_HEADER = "X-BlockCommander-Graph-Secret"


@dataclass(frozen=True)
class ApplicationDiagnostic:
    code: str
    severity: str
    message: str
    node_ids: tuple[str,...]=()
    port_refs: tuple[tuple[str,str],...]=()
    edge_ids: tuple[str,...]=()
    wire_positions: tuple[GridPos,...]=()
    field_name: str|None=None


class GraphRequestError(ValueError):
    def __init__(self,code:str,message:str,*,status_code:int=400,field_name:str|None=None):
        self.status_code=status_code
        self.diagnostics=(ApplicationDiagnostic(
            code,"ERROR",message,field_name=field_name
        ),)
        super().__init__(message)


class GraphApplicationError(RuntimeError):
    def __init__(self,code:str,message:str,*,status_code:int=422,
                 wire_positions:tuple[GridPos,...]=()):
        self.status_code=status_code
        self.diagnostics=(ApplicationDiagnostic(
            code,"ERROR",message,wire_positions=wire_positions
        ),)
        super().__init__(message)


class GraphTransportAuthError(PermissionError):
    def __init__(self,code:str,message:str,status_code:int):
        self.code=code;self.status_code=status_code
        super().__init__(message)


@dataclass(frozen=True)
class GraphApplicationResponse:
    status_code: int
    body: dict[str,Any]

    def copy(self)->"GraphApplicationResponse":
        return GraphApplicationResponse(self.status_code,copy.deepcopy(self.body))


@dataclass(frozen=True)
class WireChange:
    position: GridPos
    cell: WireCell|None

    def canonical(self)->dict[str,Any]:
        return {
            "position":_position_dict(self.position),
            "cell":None if self.cell is None else _wire_cell_dict(self.cell),
        }


@dataclass(frozen=True)
class GraphCommand:
    schema_version: int
    request_id: uuid.UUID
    stage_id: uuid.UUID
    player_uuid: uuid.UUID
    workspace_epoch: uuid.UUID
    expected_working_revision: int
    operation: str
    wire_changes: tuple[WireChange,...]=()

    def canonical(self)->dict[str,Any]:
        payload=(
            {"changes":[item.canonical() for item in self.wire_changes]}
            if self.operation=="WIRE_LAYOUT" else {}
        )
        return {
            "schema_version":self.schema_version,
            "request_id":str(self.request_id),
            "stage_id":str(self.stage_id),
            "player_uuid":str(self.player_uuid),
            "workspace_epoch":str(self.workspace_epoch),
            "expected_working_revision":self.expected_working_revision,
            "operation":self.operation,
            "payload":payload,
        }

    @property
    def fingerprint(self)->str:
        encoded=json.dumps(
            self.canonical(),sort_keys=True,separators=(",",":"),ensure_ascii=False
        ).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()


@dataclass(frozen=True)
class _RequestRecord:
    fingerprint: str
    response: GraphApplicationResponse


class GraphTransportAuthenticator:
    """Authenticate the Fabric server process, not an individual player."""

    def __init__(self,shared_secret:str|None):
        self._secret=shared_secret

    @property
    def configured(self)->bool:
        return bool(self._secret)

    def verify(self,provided:str|None)->None:
        if not self._secret:
            raise GraphTransportAuthError(
                "GRAPH_AUTH_NOT_CONFIGURED",
                "A graph API transport-hitelesítése nincs konfigurálva.",503,
            )
        if not provided or not hmac.compare_digest(str(provided),self._secret):
            raise GraphTransportAuthError(
                "INVALID_GRAPH_AUTH",
                "Hiányzó vagy hibás graph API hitelesítés.",401,
            )


def parse_graph_command(
    raw:Any,*,expected_stage_id:str,expected_operation:str
)->GraphCommand:
    data=_object(raw,"request")
    _keys(data,{
        "schema_version","request_id","stage_id","player_uuid","workspace_epoch",
        "expected_working_revision","operation","payload",
    },{
        "schema_version","request_id","stage_id","player_uuid","workspace_epoch",
        "expected_working_revision","operation","payload",
    },"request")
    schema=_integer(data["schema_version"],"schema_version")
    if schema!=SCHEMA_VERSION:
        raise GraphRequestError(
            "UNSUPPORTED_SCHEMA_VERSION",
            f"Csak a {SCHEMA_VERSION} graph parancsséma támogatott.",
            field_name="schema_version",
        )
    stage_id=_uuid(data["stage_id"],"stage_id")
    path_stage=_uuid(expected_stage_id,"stage_id")
    if stage_id!=path_stage:
        raise GraphRequestError(
            "STAGE_ID_MISMATCH","A body és az URL Stage azonosítója eltér.",
            field_name="stage_id",
        )
    operation=data["operation"]
    if operation!=expected_operation:
        raise GraphRequestError(
            "INVALID_OPERATION",f"Az endpoint {expected_operation} műveletet vár.",
            field_name="operation",
        )
    revision=_integer(data["expected_working_revision"],"expected_working_revision")
    if revision<0:
        raise GraphRequestError(
            "INVALID_REVISION","A working revision nem lehet negatív.",
            field_name="expected_working_revision",
        )
    payload=_object(data["payload"],"payload")
    changes:tuple[WireChange,...]=()
    if operation=="WIRE_LAYOUT":
        _keys(payload,{"changes"},{"changes"},"payload")
        raw_changes=payload["changes"]
        if not isinstance(raw_changes,list):
            raise GraphRequestError(
                "INVALID_FIELD_TYPE","A payload.changes tömb kell legyen.",
                field_name="payload.changes",
            )
        changes=tuple(_wire_change(item,index) for index,item in enumerate(raw_changes))
        positions=[item.position for item in changes]
        if len(set(positions))!=len(positions):
            raise GraphRequestError(
                "DUPLICATE_WIRE_POSITION","Egy parancs ugyanazt a wire pozíciót csak egyszer adhatja meg.",
                status_code=422,field_name="payload.changes",
            )
    else:
        _keys(payload,set(),set(),"payload")
    return GraphCommand(
        schema,
        _uuid(data["request_id"],"request_id"),
        stage_id,
        _uuid(data["player_uuid"],"player_uuid"),
        _uuid(data["workspace_epoch"],"workspace_epoch"),
        revision,
        operation,
        changes,
    )


def parse_player_uuid(value:Any)->str:
    if value is None:
        raise GraphRequestError(
            "MISSING_FIELD","Hiányzó mező: player_uuid",field_name="player_uuid"
        )
    return str(_uuid(value,"player_uuid"))


class GraphApplicationService:
    """Serialize graph commands, apply them through StageService, and cache outcomes."""

    def __init__(self,stages:StageService,*,max_idempotency_entries:int=1024,
                 id_factory:Callable[[],uuid.UUID]=uuid.uuid4):
        if not isinstance(max_idempotency_entries,int) or max_idempotency_entries<1:
            raise ValueError("max_idempotency_entries must be a positive integer")
        self.stages=stages
        self.max_idempotency_entries=max_idempotency_entries
        self._id_factory=id_factory
        self._lock=threading.RLock()
        self._records:OrderedDict[tuple[str,str,str,str],_RequestRecord]=OrderedDict()

    def clear_idempotency(self)->None:
        """Explicitly end every process-local request record lifecycle."""
        with self._lock:
            self._records.clear()

    @property
    def idempotency_size(self)->int:
        with self._lock:
            return len(self._records)

    def graph_state(self,stage_id:str,player_uuid:str)->GraphApplicationResponse:
        try:
            normalized_stage=str(_uuid(stage_id,"stage_id"))
            normalized_player=parse_player_uuid(player_uuid)
            context=self.stages.read_graph_context(normalized_stage,normalized_player)
            return GraphApplicationResponse(200,{
                "api_version":API_VERSION,"status":"ok","modified":False,
                **_context_envelope(context),"diagnostics":[],
                "state":_state_dict(context),
            })
        except GraphRequestError as exc:
            return request_error_response(exc)
        except HallForbidden as exc:
            return _read_error_response(stage_id,"FORBIDDEN",str(exc),403)
        except HallNotFound as exc:
            return _read_error_response(stage_id,"STAGE_NOT_FOUND",str(exc),404)
        except HallConflict as exc:
            return _read_error_response(stage_id,"CONFLICT",str(exc),409)

    def apply_wire(self,command:GraphCommand)->GraphApplicationResponse:
        if command.operation!="WIRE_LAYOUT":
            raise ValueError("apply_wire requires a WIRE_LAYOUT command")
        return self._execute(command,self._apply_wire)

    def bind_edges(self,command:GraphCommand)->GraphApplicationResponse:
        if command.operation!="BIND_EDGES":
            raise ValueError("bind_edges requires a BIND_EDGES command")
        return self._execute(command,self._bind_edges)

    def _execute(self,command:GraphCommand,operation)->GraphApplicationResponse:
        # The application lock is always acquired before the Stage RLock. StageService
        # never calls back into this service, so there is no reverse lock path.
        with self._lock:
            try:
                before=self.stages.read_graph_context(
                    str(command.stage_id),str(command.player_uuid)
                )
            except Exception as exc:
                return _exception_response(command,None,exc)
            self._expire_other_epochs(str(command.stage_id),str(before.workspace_epoch))
            if command.workspace_epoch!=before.workspace_epoch:
                return _problem_response(command,before,GraphApplicationError(
                    "STALE_WORKSPACE_EPOCH",
                    "A workspace epoch elavult; kérd le újra a graph állapotot.",
                    status_code=409,
                ))
            key=(str(command.stage_id),str(command.workspace_epoch),
                 str(command.player_uuid),str(command.request_id))
            existing=self._records.get(key)
            if existing is not None:
                self._records.move_to_end(key)
                if existing.fingerprint!=command.fingerprint:
                    return _problem_response(command,before,GraphApplicationError(
                        "REQUEST_ID_CONFLICT",
                        "Azonos request_id eltérő szemantikai payloadhoz tartozik.",
                        status_code=409,
                    ))
                return existing.response.copy()
            if command.expected_working_revision!=before.state.working_revision:
                result=_problem_response(command,before,GraphApplicationError(
                    "STALE_WORKING_REVISION",
                    "A working revision elavult; kérd le újra a graph állapotot.",
                    status_code=409,
                ))
                self._remember(key,command.fingerprint,result)
                return result.copy()
            try:
                extra,diagnostics,wire_diff=operation(command,before)
                after=self.stages.read_graph_context(
                    str(command.stage_id),str(command.player_uuid)
                )
                result=GraphApplicationResponse(200,{
                    "api_version":API_VERSION,"status":"ok",
                    "request_id":str(command.request_id),
                    "modified":after.state.working_revision!=before.state.working_revision,
                    **_context_envelope(after),
                    "diagnostics":[_diagnostic_dict(item) for item in diagnostics],
                    "wire_diff":wire_diff,"state":_state_dict(after),**extra,
                })
            except Exception as exc:
                result=_exception_response(command,before,exc)
            self._remember(key,command.fingerprint,result)
            return result.copy()

    def _apply_wire(self,command:GraphCommand,context:StageGraphContext):
        if len(command.wire_changes)!=1:
            raise GraphApplicationError(
                "UNSUPPORTED_BATCH_SIZE",
                "A CK-04/B1 egy parancsban pontosan egy wire cella módosítását támogatja.",
            )
        change=command.wire_changes[0]
        _validate_position(change.position,context)
        if any(node.position==change.position for node in context.state.nodes):
            raise GraphApplicationError(
                "WIRE_OCCUPIES_NODE","WireCell nem kerülhet GraphNode pozíciójára.",
                wire_positions=(change.position,),
            )
        if change.cell is not None:
            expected_wire=_single_cell_start_stop_position(context.state)
            if change.position!=expected_wire:
                raise GraphApplicationError(
                    "WIRE_POSITION_NOT_CONNECTOR",
                    "A CK-04/B3 Wire csak a START.out és STOP.in közös csatlakozási cellájára kerülhet.",
                    wire_positions=(change.position,expected_wire),
                )
            expected_channels=(WireChannelState(
                WireChannel.SINGLE,
                frozenset({CardinalDirection.EAST,CardinalDirection.WEST}),
            ),)
            if change.cell.channels!=expected_channels:
                raise GraphApplicationError(
                    "UNSUPPORTED_WIRE_GEOMETRY",
                    "A CK-04/B3 csak E-W SINGLE WireCell geometriát támogat.",
                    wire_positions=(change.position,),
                )

        def mutate(state:GraphWorkspaceState)->GraphWorkspaceState:
            cells={cell.position:cell for cell in state.wire_cells}
            if change.cell is None:
                cells.pop(change.position,None)
            else:
                cells[change.position]=change.cell
            return replace_content(state,wire_cells=tuple(cells.values()))

        self.stages.apply_graph_operation(
            str(command.stage_id),_stage_payload(command),mutate
        )
        return ({},(),(change.canonical(),))

    def _bind_edges(self,command:GraphCommand,_context:StageGraphContext):
        captured={}

        def bind(state:GraphWorkspaceState):
            candidate=_discover_single_cell_start_stop(state)
            result=bind_edges(
                state,
                BindEdgesCommand(
                    state.stage_id,command.player_uuid,state.working_revision,(candidate,)
                ),
                CK_CONTROL_PORT_CATALOG,
                edge_id_factory=self._id_factory,
                route_id_factory=self._id_factory,
            )
            captured["result"]=result
            return result

        self.stages.apply_graph_operation(
            str(command.stage_id),_stage_payload(command),bind
        )
        result=captured["result"]
        return ({
            "binding":{
                "created_edge_ids":[str(item) for item in result.created_edge_ids],
                "reused_edge_ids":[str(item) for item in result.reused_edge_ids],
            }
        },result.diagnostics,())

    def _expire_other_epochs(self,stage_id:str,current_epoch:str)->None:
        stale=[key for key in self._records if key[0]==stage_id and key[1]!=current_epoch]
        for key in stale:
            self._records.pop(key,None)

    def _remember(self,key,fingerprint,response)->None:
        self._records[key]=_RequestRecord(fingerprint,response.copy())
        self._records.move_to_end(key)
        while len(self._records)>self.max_idempotency_entries:
            self._records.popitem(last=False)


def transport_error_response(exc:GraphTransportAuthError)->GraphApplicationResponse:
    diagnostic=ApplicationDiagnostic(exc.code,"ERROR",str(exc))
    return GraphApplicationResponse(exc.status_code,{
        "api_version":API_VERSION,"status":"error","error_code":exc.code,
        "message":str(exc),"diagnostics":[_diagnostic_dict(diagnostic)],
    })


def request_error_response(exc:GraphRequestError)->GraphApplicationResponse:
    return GraphApplicationResponse(exc.status_code,{
        "api_version":API_VERSION,"status":"error",
        "error_code":exc.diagnostics[0].code,"message":str(exc),
        "diagnostics":[_diagnostic_dict(item) for item in exc.diagnostics],
    })


def _read_error_response(stage_id:str,code:str,message:str,status_code:int)->GraphApplicationResponse:
    diagnostic=ApplicationDiagnostic(code,"ERROR",message)
    return GraphApplicationResponse(status_code,{
        "api_version":API_VERSION,"status":"error","stage_id":str(stage_id),
        "error_code":code,"message":message,"modified":False,
        "diagnostics":[_diagnostic_dict(diagnostic)],
    })


def _discover_single_cell_start_stop(state:GraphWorkspaceState)->RouteCandidate:
    starts=tuple(node for node in state.nodes if node.type_key=="START")
    stops=tuple(node for node in state.nodes if node.type_key=="STOP")
    if len(starts)!=1 or len(stops)!=1:
        raise GraphApplicationError(
            "UNSUPPORTED_TOPOLOGY",
            "A B1 Élbekötés pontosan egy START és egy STOP node-ot támogat.",
        )
    start,stop=starts[0],stops[0]
    expected_wire=_single_cell_start_stop_position(state)
    cell=next((item for item in state.wire_cells if item.position==expected_wire),None)
    if cell is None:
        raise GraphApplicationError(
            "MISSING_WIRE","Hiányzik a START és STOP közötti WireCell.",
            wire_positions=(expected_wire,),
        )
    expected_channels=(WireChannelState(
        WireChannel.SINGLE,
        frozenset({CardinalDirection.EAST,CardinalDirection.WEST}),
    ),)
    if cell.channels!=expected_channels or len(state.wire_cells)!=1:
        raise GraphApplicationError(
            "UNSUPPORTED_TOPOLOGY",
            "A B1 Élbekötés pontosan egy E-W SINGLE WireCell geometriát támogat.",
            wire_positions=tuple(item.position for item in state.wire_cells),
        )
    return RouteCandidate(
        PortRef(start.instance_id,"out"),PortRef(stop.instance_id,"in"),
        (WireStep(expected_wire,WireChannel.SINGLE),),
    )


def _single_cell_start_stop_position(state:GraphWorkspaceState)->GridPos:
    starts=tuple(node for node in state.nodes if node.type_key=="START")
    stops=tuple(node for node in state.nodes if node.type_key=="STOP")
    if len(starts)!=1 or len(stops)!=1:
        raise GraphApplicationError(
            "UNSUPPORTED_TOPOLOGY",
            "A B3 Wire placement pontosan egy START és egy STOP node-ot támogat.",
        )
    start,stop=starts[0],stops[0]
    if start.orientation is not CardinalDirection.EAST or stop.orientation is not CardinalDirection.EAST:
        raise GraphApplicationError(
            "UNSUPPORTED_TOPOLOGY","A B1 START és STOP orientációja EAST kell legyen.",
        )
    start_port=next(item for item in CK_CONTROL_PORT_CATALOG["START"] if item.port_key=="out")
    stop_port=next(item for item in CK_CONTROL_PORT_CATALOG["STOP"] if item.port_key=="in")
    start_position=port_connection_position(start,start_port)
    stop_position=port_connection_position(stop,stop_port)
    if start_position!=stop_position:
        raise GraphApplicationError(
            "UNSUPPORTED_TOPOLOGY","A portok nem ugyanarra az egycellás wire pozícióra mutatnak.",
        )
    return start_position


def _stage_payload(command:GraphCommand)->dict[str,Any]:
    return {
        "player_uuid":str(command.player_uuid),
        "workspace_epoch":str(command.workspace_epoch),
        "expected_working_revision":command.expected_working_revision,
    }


def _validate_position(position:GridPos,context:StageGraphContext)->None:
    if not (0<=position.x<context.size.x and 0<=position.y<context.size.y and
            0<=position.z<context.size.z):
        raise GraphApplicationError(
            "WIRE_POSITION_OUT_OF_BOUNDS","A WireCell a Stage területén kívülre esik.",
            wire_positions=(position,),
        )


def _context_envelope(context:StageGraphContext)->dict[str,Any]:
    return {
        "stage_id":str(context.stage_id),
        "workspace_epoch":str(context.workspace_epoch),
        "working_revision":context.state.working_revision,
    }


def _state_dict(context:StageGraphContext)->dict[str,Any]:
    state=context.state
    report=state.last_validation
    return {
        "stage_id":str(state.stage_id),"hall_id":context.hall_id,
        "world_uuid":context.world_uuid,
        "origin":_position_dict(context.origin),"size":_position_dict(context.size),
        "workspace_epoch":str(context.workspace_epoch),
        "working_revision":state.working_revision,"saved_version":state.saved_version,
        "dirty":state.dirty,"graph_snapshot_version":context.graph_snapshot_version,
        "port_catalog_version":context.port_catalog_version,
        "compatible_for_graph_mutation":context.compatible_for_graph_mutation,
        "compatibility_issues":list(context.compatibility_issues),
        "status":{
            "graph":state.graph_status.value,
            "edge_indicator":state.indicator_status.value,
            "save":state.save_status.value,
            "layout":"EMPTY" if not state.wire_cells else "PRESENT",
            "program_validation":context.program_validation_status.value,
        },
        "nodes":[{
            "instance_id":str(node.instance_id),"type_key":node.type_key,
            "position":_position_dict(node.position),"orientation":node.orientation.value,
        } for node in state.nodes],
        "wire_cells":[_wire_cell_dict(cell) for cell in state.wire_cells],
        "edges":[{
            "edge_id":str(edge.edge_id),"source_instance_id":str(edge.source_instance_id),
            "source_port_key":edge.source_port_key,
            "target_instance_id":str(edge.target_instance_id),
            "target_port_key":edge.target_port_key,"edge_kind":edge.edge_kind.value,
        } for edge in state.edges],
        "routes":[{
            "route_id":str(route.route_id),
            "edge_id":None if route.edge_id is None else str(route.edge_id),
            "source_port":_port_ref_dict(route.source_port),
            "target_port":_port_ref_dict(route.target_port),
            "steps":[{"position":_position_dict(step.position),"channel":step.channel.value}
                     for step in route.steps],
            "status":route.status.value,
        } for route in state.routes],
        "validation":{
            "revision":None if report is None else report.revision,
            "diagnostics":[] if report is None else [
                _diagnostic_dict(item) for item in report.diagnostics
            ],
        },
    }


def _problem_response(command:GraphCommand,context:StageGraphContext,
                      exc:GraphApplicationError)->GraphApplicationResponse:
    return GraphApplicationResponse(exc.status_code,{
        "api_version":API_VERSION,"status":"error",
        "request_id":str(command.request_id),"error_code":exc.diagnostics[0].code,
        "message":str(exc),"modified":False,**_context_envelope(context),
        "diagnostics":[_diagnostic_dict(item) for item in exc.diagnostics],
    })


def _exception_response(command:GraphCommand,context:StageGraphContext|None,
                        exc:Exception)->GraphApplicationResponse:
    if isinstance(exc,GraphApplicationError):
        if context is None:
            return GraphApplicationResponse(exc.status_code,{
                "api_version":API_VERSION,"status":"error",
                "request_id":str(command.request_id),
                "stage_id":str(command.stage_id),"error_code":exc.diagnostics[0].code,
                "message":str(exc),"modified":False,
                "diagnostics":[_diagnostic_dict(item) for item in exc.diagnostics],
            })
        return _problem_response(command,context,exc)
    if isinstance(exc,GraphOperationRejected):
        diagnostics=tuple(exc.diagnostics) or tuple(exc.issues)
        code="GRAPH_OPERATION_REJECTED";status=422
    elif isinstance(exc,GraphWorkspaceMappingError):
        diagnostics=tuple(exc.issues);code="GRAPH_MAPPING_ERROR";status=422
    elif isinstance(exc,HallForbidden):
        diagnostics=(ApplicationDiagnostic("FORBIDDEN","ERROR",str(exc)),)
        code="FORBIDDEN";status=403
    elif isinstance(exc,HallNotFound):
        diagnostics=(ApplicationDiagnostic("STAGE_NOT_FOUND","ERROR",str(exc)),)
        code="STAGE_NOT_FOUND";status=404
    elif isinstance(exc,HallConflict):
        diagnostics=(ApplicationDiagnostic("CONFLICT","ERROR",str(exc)),)
        code="CONFLICT";status=409
    else:
        raise exc
    body={
        "api_version":API_VERSION,"status":"error",
        "request_id":str(command.request_id),"stage_id":str(command.stage_id),
        "error_code":code,"message":str(exc),"modified":False,
        "diagnostics":[_diagnostic_dict(item) for item in diagnostics],
    }
    if context is not None:
        body.update(_context_envelope(context))
    return GraphApplicationResponse(status,body)


def _diagnostic_dict(item)->dict[str,Any]:
    if isinstance(item,ApplicationDiagnostic):
        return {
            "code":item.code,"severity":item.severity,"message":item.message,
            "node_ids":list(item.node_ids),
            "port_refs":[{"instance_id":node,"port_key":key} for node,key in item.port_refs],
            "edge_ids":list(item.edge_ids),
            "wire_positions":[_position_dict(pos) for pos in item.wire_positions],
            "field_name":item.field_name,
        }
    if isinstance(item,Diagnostic):
        return {
            "code":item.code.value,"severity":item.severity.name,"message":item.message,
            "node_ids":[str(value) for value in item.node_ids],
            "port_refs":[_port_ref_dict(value) for value in item.port_refs],
            "edge_ids":[str(value) for value in item.edge_ids],
            "wire_positions":[_position_dict(value) for value in item.wire_positions],
            "suggestion":item.suggestion,
        }
    return {
        "code":getattr(getattr(item,"code",None),"value",str(getattr(item,"code","UNKNOWN"))),
        "severity":"ERROR","message":str(getattr(item,"message",item)),
        "field_name":getattr(item,"field_name",None),
    }


def _position_dict(position:GridPos)->dict[str,int]:
    return {"x":position.x,"y":position.y,"z":position.z}


def _wire_cell_dict(cell:WireCell)->dict[str,Any]:
    return {
        "position":_position_dict(cell.position),
        "channels":[{
            "channel":channel.channel.value,
            "connections":sorted(item.value for item in channel.connections),
        } for channel in cell.channels],
    }


def _port_ref_dict(port)->dict[str,str]|None:
    if port is None:return None
    return {"instance_id":str(port.instance_id),"port_key":port.port_key}


def _wire_change(raw:Any,index:int)->WireChange:
    data=_object(raw,f"payload.changes[{index}]")
    _keys(data,{"position","cell"},{"position","cell"},f"payload.changes[{index}]")
    position=_position(data["position"],f"payload.changes[{index}].position")
    if data["cell"] is None:
        return WireChange(position,None)
    cell_data=_object(data["cell"],f"payload.changes[{index}].cell")
    _keys(cell_data,{"channels"},{"channels"},f"payload.changes[{index}].cell")
    raw_channels=cell_data["channels"]
    if not isinstance(raw_channels,list):
        raise GraphRequestError(
            "INVALID_FIELD_TYPE","A cell.channels tömb kell legyen.",
            field_name=f"payload.changes[{index}].cell.channels",
        )
    channels=[]
    for channel_index,raw_channel in enumerate(raw_channels):
        path=f"payload.changes[{index}].cell.channels[{channel_index}]"
        channel_data=_object(raw_channel,path)
        _keys(channel_data,{"channel","connections"},{"channel","connections"},path)
        try:channel=WireChannel(channel_data["channel"])
        except (TypeError,ValueError) as exc:
            raise GraphRequestError(
                "INVALID_WIRE_CHANNEL","Ismeretlen wire csatorna.",status_code=422,
                field_name=f"{path}.channel",
            ) from exc
        connections_data=channel_data["connections"]
        if not isinstance(connections_data,list):
            raise GraphRequestError(
                "INVALID_FIELD_TYPE","A connections tömb kell legyen.",
                field_name=f"{path}.connections",
            )
        try:
            connections=frozenset(CardinalDirection(value) for value in connections_data)
            channels.append(WireChannelState(channel,connections))
        except (TypeError,ValueError) as exc:
            raise GraphRequestError(
                "INVALID_WIRE_CELL",str(exc),status_code=422,field_name=path,
            ) from exc
    channel_order={WireChannel.SINGLE:0,WireChannel.NS:1,WireChannel.EW:2}
    channels.sort(key=lambda item:channel_order[item.channel])
    try:return WireChange(position,WireCell(position,tuple(channels)))
    except (TypeError,ValueError) as exc:
        raise GraphRequestError(
            "INVALID_WIRE_CELL",str(exc),status_code=422,
            field_name=f"payload.changes[{index}].cell",
        ) from exc


def _position(raw:Any,path:str)->GridPos:
    data=_object(raw,path);_keys(data,{"x","y","z"},{"x","y","z"},path)
    return GridPos(*(_integer(data[key],f"{path}.{key}") for key in ("x","y","z")))


def _object(raw:Any,path:str)->dict[str,Any]:
    if not isinstance(raw,dict):
        raise GraphRequestError(
            "INVALID_FIELD_TYPE",f"A {path} JSON objektum kell legyen.",field_name=path
        )
    return raw


def _keys(data:dict[str,Any],allowed:set[str],required:set[str],path:str)->None:
    unknown=sorted(set(data)-allowed)
    if unknown:
        raise GraphRequestError(
            "UNKNOWN_FIELD",f"Ismeretlen mező: {path}.{unknown[0]}",
            field_name=f"{path}.{unknown[0]}",
        )
    missing=sorted(required-set(data))
    if missing:
        raise GraphRequestError(
            "MISSING_FIELD",f"Hiányzó mező: {path}.{missing[0]}",
            field_name=f"{path}.{missing[0]}",
        )


def _uuid(value:Any,path:str)->uuid.UUID:
    try:return uuid.UUID(str(value))
    except (TypeError,ValueError,AttributeError) as exc:
        raise GraphRequestError(
            "INVALID_UUID",f"A {path} érvényes UUID kell legyen.",field_name=path
        ) from exc


def _integer(value:Any,path:str)->int:
    if not isinstance(value,int) or isinstance(value,bool):
        raise GraphRequestError(
            "INVALID_FIELD_TYPE",f"A {path} egész szám kell legyen.",field_name=path
        )
    return value
