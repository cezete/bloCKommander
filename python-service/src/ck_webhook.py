from __future__ import annotations
import os
import uuid
from flask import Flask, g, jsonify, request
from hangar.builder import HallValidationError, control_elements, hall_client_view
from hangar.repository import HallConflict, HallForbidden, HallNotFound, HallRepository
from hangar.service import HallService
from hangar.program import ProgramService
from hangar.block_editor import BlockEditorService
from hangar.block_editor_http import BlockEditorHttpAdapter, EDITOR_ENDPOINTS
from hangar.program_http import (
    PROGRAM_API_VERSION, ProgramHttpFailure, link_dto, parse_ensure_request,
    parse_save_request, parse_uuid, program_dto, receipt_dto, stage_dto,
    stage_projection_dto, success as program_success,
    translate_error as translate_program_error,
)
from hangar.stage import StageService
from hangar.selection import SelectionFailure, parse_selection, selection_dto
from hangar.hall_projection import project_stage
from hangar.diagnostics import emit as diagnostic, context as diagnostic_context
from hangar.transport_context import TransportContextError, TransportContextResolver
from hangar.graph_application import (
    API_VERSION, GRAPH_SECRET_ENV, GRAPH_SECRET_HEADER, GraphApplicationService, GraphCommand,
    GraphRequestError, GraphTransportAuthError, GraphTransportAuthenticator,
    parse_graph_command, parse_player_uuid, request_error_response,
    transport_error_response,
)

BLOCK_IDS={"AIR":0,"STONE":1,"GRASS_BLOCK":2,"DIRT":3,"COBBLESTONE":4,"OAK_PLANKS":5,"SAND":12,
           "GRAVEL":13,"GOLD_BLOCK":41,"IRON_BLOCK":42,"BRICK_BLOCK":45,"OBSIDIAN":49,"DIAMOND_BLOCK":57}

def create_app(repository=None,mc=None,migrate=True,graph_shared_secret=None,allow_legacy_context=None):
    app=Flask(__name__); repo=repository or HallRepository()
    if migrate: repo.migrate()
    world_driver=os.getenv("BLOCKCOMMANDER_WORLD_DRIVER","fabric").lower()
    minecraft=mc
    if world_driver=="mcpi" and minecraft is None:
        from mcpi.minecraft import Minecraft
        minecraft=Minecraft.create(os.getenv("BLOCKCOMMANDER_MC_HOST","127.0.0.1"),int(os.getenv("BLOCKCOMMANDER_MC_PORT","4711")))
    if world_driver not in ("fabric","mcpi"):
        raise RuntimeError("BLOCKCOMMANDER_WORLD_DRIVER must be fabric or mcpi")
    diagnostic("PY_DIAGNOSTICS_READY",schema="ck-diag2-v1",world_driver=world_driver)
    service=HallService(repo,minecraft,apply_world=world_driver=="mcpi"); stages=StageService(repo)
    programs=ProgramService(repo,stages)
    graph_app=GraphApplicationService(stages)
    configured_secret=(
        graph_shared_secret if graph_shared_secret is not None
        else os.getenv(GRAPH_SECRET_ENV)
    )
    graph_auth=GraphTransportAuthenticator(configured_secret)
    app.extensions["blockcommander_stage_service"]=stages
    app.extensions["blockcommander_program_service"]=programs
    app.extensions["blockcommander_graph_application"]=graph_app
    if allow_legacy_context is None:
        legacy_setting=os.getenv("BLOCKCOMMANDER_ALLOW_LEGACY_CONTEXT","1")
        if legacy_setting not in ("0","1"):
            raise RuntimeError("BLOCKCOMMANDER_ALLOW_LEGACY_CONTEXT must be 0 or 1")
        allow_legacy_context=legacy_setting=="1"
    contexts=TransportContextResolver(repo,programs,allow_legacy=allow_legacy_context)
    editor=BlockEditorService(programs)
    editor_http=BlockEditorHttpAdapter(editor,contexts,graph_auth)
    editor_http.register(app)
    app.extensions["blockcommander_block_editor"]=editor

    @app.before_request
    def diagnostic_request_start():
        # Observe metadata only. Do not resolve identity, load workspace or parse a body here.
        if request.endpoint not in {"graph_state","graph_wire","graph_bind","graph_wire_place",
                "save_button","save_program","save_stage","mounted_program","program_status",
                "world_halls","restore_all","get_program_stage"}:return
        try:
            def safe_uuid(value):
                try:return str(uuid.UUID(str(value))) if value else None
                except (ValueError,TypeError):return "INVALID_UUID"
            supplied=safe_uuid(request.headers.get("X-BlockCommander-Diagnostic-Id"))
            fields={"diagnostic_id":supplied if supplied not in (None,"INVALID_UUID") else str(uuid.uuid4()),
                "hall_id":safe_uuid(request.headers.get("X-BlockCommander-Hall-Id")),
                "program_id":safe_uuid(request.headers.get("X-BlockCommander-Program-Id")),
                "working_copy_id":safe_uuid(request.headers.get("X-BlockCommander-Working-Copy-Id")),
                "context_verified":False,
                "stage_id":safe_uuid((request.view_args or {}).get("stage_id")),
                "method":request.method,"endpoint":request.endpoint}
            g.diagnostic_token=diagnostic_context.set(fields)
            diagnostic("PY_HTTP_RECEIVE")
        except Exception:pass

    @app.after_request
    def diagnostic_request_end(response):
        if not hasattr(g,"diagnostic_token"):return response
        try:
            body=response.get_json(silent=True) if response.is_json else {}
            body=body if isinstance(body,dict) else {}
            stage=body.get("state") or body.get("stage") or {}
            stage=stage if isinstance(stage,dict) else {}
            diagnostic("PY_HTTP_RESPONSE",http_status=response.status_code,error_code=body.get("error_code"),
                response_stage_id=stage.get("stage_id") or body.get("stage_id"),
                response_epoch=stage.get("workspace_epoch") or body.get("workspace_epoch"),
                response_revision=stage.get("working_revision",body.get("working_revision")),
                program_dirty=stage.get("program_dirty"))
        except Exception:pass
        return response

    @app.teardown_request
    def diagnostic_request_cleanup(_failure):
        try:
            token=g.pop("diagnostic_token",None)
            if token is not None:diagnostic_context.reset(token)
        except Exception:pass

    @app.errorhandler(TransportContextError)
    def context_failure(exc):
        if "/graph" in request.path:
            result=request_error_response(GraphRequestError(exc.code,exc.message,status_code=exc.status_code))
            return jsonify(result.body),result.status_code
        return jsonify(api_version=PROGRAM_API_VERSION,
            status="error",error_code=exc.code,message=exc.message,
            diagnostics=[{"code":exc.code,"severity":"ERROR","message":exc.message}]),exc.status_code

    @app.before_request
    def validate_transport_context():
        endpoint=request.endpoint
        args=request.view_args or {}
        if endpoint in EDITOR_ENDPOINTS:
            # Editor has bounded, duplicate-safe parsing and mandatory explicit context.
            # Do not feed its body through the legacy request.get_json path.
            return editor_http.preflight()
        program_routes={"get_program","get_program_children","get_program_stage",
            "ensure_program_child","program_status","save_program"}
        stage_routes={"place_stage_block","update_stage_block","delete_stage_block",
            "save_stage","rebuild_stage","graph_state","graph_wire","graph_bind","graph_wire_place"}
        if endpoint in {"get_hall_selection","put_hall_selection"}:
            denied=program_authenticated()
            if denied is not None:return denied
            # Dedicated identity handoff: target IDs in the body, optional old
            # G1 headers checked together with the CAS in the repository transaction.
            g.selection_expected_context=contexts.explicit(request.headers)
            return None
        # Keep authentication ahead of identity disclosure on the existing protected routes.
        if endpoint in program_routes or endpoint in {"mounted_program","hall_detail"}:
            denied=program_authenticated()
            if denied is not None:return denied
        if endpoint in {"graph_state","graph_wire","graph_bind","graph_wire_place"}:
            denied=graph_authenticated()
            if denied is not None:return denied
        explicit=contexts.explicit(request.headers)
        if endpoint in program_routes or endpoint in stage_routes or endpoint in {"mounted_program","save_button"}:
            body=request.get_json(silent=True)
            keys={"hall_id","program_id","working_copy_id","transport_context"}
            if (isinstance(body,dict) and keys.intersection(body)) or keys.intersection(request.args):
                raise TransportContextError(400,"CONTEXT_LOCATION_INVALID",
                    "A context kizárólag a három X-BlockCommander identity-headerben adható meg.")
        if endpoint in program_routes or endpoint in stage_routes or endpoint in {"mounted_program","hall_detail"}:
            g.transport_context=contexts.resolve(request.headers,**{
                key:args[key] for key in ("hall_id","program_id","stage_id") if key in args})
        elif endpoint=="save_button":
            data=request.get_json(silent=True) or {}
            hall=repo.by_control(str(data["world_uuid"]),"SAVE_BUTTON",int(data["x"]),int(data["y"]),int(data["z"]))
            g.transport_context=contexts.resolve(request.headers,hall_id=hall["hall_id"],
                stage_id=data.get("active_stage_id"))
        elif endpoint=="rebuild":
            g.transport_context=contexts.resolve(request.headers,hall_id=args["hall_id"])
        elif endpoint=="rebuild_by_code":
            data=request.get_json(silent=True) or {}
            hall=repo.get_by_code(str(data["player_uuid"]),str(data["hall_code"]))
            g.transport_context=contexts.resolve(request.headers,hall_id=hall["hall_id"])
        else:
            # One explicit context cannot authorize a bulk restore or a newly created Hall.
            if explicit is not None:
                raise TransportContextError(409,"CONTEXT_ROUTE_UNSUPPORTED",
                    "Ez az útvonal nem fogad egyetlen WorkingCopy-contextet.")
            if endpoint in {"restore_all","world_halls"}:
                if endpoint=="world_halls":halls=repo.list_world(args["world_uuid"])
                else:
                    data=request.get_json(silent=True) or {}
                    halls=repo.restore_candidates(str(data["player_uuid"]),data)
                # Known Hall identities, never a Stage-ID-based choice. Validate all first.
                for hall in halls:contexts.resolve({},hall_id=hall["hall_id"])

    @app.before_request
    def diagnostic_resolved_context():
        try:
            context=getattr(g,"transport_context",None)
            if context is not None and hasattr(g,"diagnostic_token"):
                identity={"program_id":str(context.program_id)}
                identity["context_verified"]=True
                if context.mount is not None:identity.update(context.mount.as_dict())
                if context.stage_id is not None:identity["stage_id"]=str(context.stage_id)
                diagnostic_context.set({**(diagnostic_context.get() or {}),**identity})
        except Exception:pass

    @app.after_request
    def include_transport_context(response):
        context=getattr(g,"transport_context",None)
        if context is not None and response.is_json and 200<=response.status_code<300:
            body=response.get_json()
            body["transport_context"]=context.as_dict()
            if context.mount is not None and any(isinstance(body.get(key),dict)
                    and "stage_id" in body[key] for key in ("stage","state")):
                hall=repo.get(str(context.mount.hall_id))
                for key in ("stage","state"):
                    view=body.get(key)
                    if isinstance(view,dict) and "stage_id" in view:
                        ws=programs.stage(context.program_id,view["stage_id"])
                        body[key]=project_stage(view,ws.stage,hall)
            response.set_data(app.json.dumps(body))
        return response

    def graph_authenticated():
        try:
            graph_auth.verify(request.headers.get(GRAPH_SECRET_HEADER))
            return None
        except GraphTransportAuthError as exc:
            result=transport_error_response(exc)
            return jsonify(result.body),result.status_code

    def graph_command(stage_id,operation):
        denied=graph_authenticated()
        if denied is not None:return denied
        try:
            command=parse_graph_command(
                request.get_json(silent=True),
                expected_stage_id=stage_id,
                expected_operation=operation,
            )
            diagnostic("PY_GRAPH_COMMAND_RECEIVE",request_id=command.request_id,stage_id=command.stage_id,
                operation=command.operation,request_epoch=command.workspace_epoch,
                request_revision=command.expected_working_revision)
            return command
        except GraphRequestError as exc:
            result=request_error_response(exc)
            return jsonify(result.body),result.status_code

    def graph_internal_error(exc):
        app.logger.exception("Graph API művelet sikertelen",exc_info=exc)
        return jsonify({
            "api_version":API_VERSION,"status":"error",
            "error_code":"INTERNAL_ERROR",
            "message":"A graph művelet belső hiba miatt nem sikerült.",
            "diagnostics":[{
                "code":"INTERNAL_ERROR","severity":"ERROR",
                "message":"A graph művelet belső hiba miatt nem sikerült.",
            }],
        }),500

    def program_authenticated():
        try:
            graph_auth.verify(request.headers.get(GRAPH_SECRET_HEADER))
            return None
        except GraphTransportAuthError as exc:
            result=transport_error_response(exc);body=dict(result.body)
            body["api_version"]=PROGRAM_API_VERSION
            return jsonify(body),result.status_code

    def program_failure(exc):
        failure=translate_program_error(exc)
        if failure.status_code>=500:
            app.logger.exception("Program API művelet sikertelen",exc_info=exc)
        return jsonify(failure.body()),failure.status_code

    def with_program_dirty(stage):
        workspace=programs.for_stage(stage["stage_id"])
        with workspace.lock:
            return {**stage,"program_dirty":programs.is_dirty(workspace.program_id)}

    def enrich(hall):
        bounds={k:int(hall[k]) for k in ("min_x","min_y","min_z","max_x","max_y","max_z")}
        expected=[e.as_record() for e in control_elements(bounds,str(hall["orientation"]),str(hall["owner_name"]),str(hall["hall_name"]))]
        present={c["kind"] for c in hall.get("controls",[])};missing=[c for c in expected if c["kind"] not in present]
        if missing:
            repo.add_controls(str(hall["hall_id"]),str(hall["world_uuid"]),missing);hall.setdefault("controls",[]).extend(missing)
        repo.ensure_stage(hall)
        # Capture one selection. Concurrent PUTs may supersede this revision,
        # but must never mix its identity with another revision's Stage.
        context=getattr(g,"transport_context",None)
        expected=None if context is None or context.mount is None else context.mount.as_dict()
        selection=repo.get_selection(hall["hall_id"],hall["owner_uuid"],expected)
        ws=programs.stage(selection["program_id"],selection["active_stage_id"])
        diagnostic("PY_HALL_READ",hall_id=hall["hall_id"],program_id=selection["program_id"],
            working_copy_id=selection["working_copy_id"],response_stage_id=selection["active_stage_id"],
            response_stage_kind=ws.stage.get("stage_kind"),
            epoch=ws.workspace_epoch,revision=ws.revision)
        view=hall_client_view(hall,with_program_dirty(project_stage(stages.view(ws),ws.stage,hall)))
        view.update(selection_dto(selection))
        return view

    @app.errorhandler(SelectionFailure)
    def selection_failure(exc):
        return jsonify(ProgramHttpFailure(exc.status,exc.code,exc.message).body()),exc.status

    @app.get("/api/v1/halls/<hall_id>/selection")
    def get_hall_selection(hall_id):
        try:
            row=repo.get_selection(parse_uuid(hall_id,"hall_id"),
                parse_uuid(request.args.get("player_uuid"),"player_uuid"),
                g.selection_expected_context)
            return jsonify(program_success(selection_api_version="ck-hall-selection-v1",
                selection=selection_dto(row)))
        except SelectionFailure:raise
        except Exception as exc:return program_failure(exc)

    @app.put("/api/v1/halls/<hall_id>/selection")
    def put_hall_selection(hall_id):
        try:
            data=parse_selection(request.get_json(silent=True))
            row,changed=repo.select(parse_uuid(hall_id,"hall_id"),**data,
                expected_context=g.selection_expected_context)
            return jsonify(program_success(selection_api_version="ck-hall-selection-v1",
                changed=changed,selection=selection_dto(row)))
        except SelectionFailure:raise
        except Exception as exc:return program_failure(exc)

    @app.errorhandler(HallValidationError)
    def validation(exc): return jsonify(status="error",message=str(exc)),422
    @app.errorhandler(HallConflict)
    def conflict(exc): return jsonify(status="error",message=str(exc)),409
    @app.errorhandler(HallForbidden)
    def forbidden(exc): return jsonify(status="error",message=str(exc)),403
    @app.errorhandler(HallNotFound)
    def missing(exc): return jsonify(status="error",message=str(exc)),404
    @app.errorhandler(KeyError)
    def bad_request(exc): return jsonify(status="error",message=f"Hiányzó mező: {exc.args[0]}"),400
    @app.errorhandler(Exception)
    def internal_error(exc):
        app.logger.exception("Csarnokművelet sikertelen")
        return jsonify(status="error",message="A csarnokművelet belső hiba miatt nem sikerült."),500

    @app.get("/health")
    def health(): return jsonify(status="ok",database="configured",world_driver=world_driver)

    @app.post("/api/v1/halls")
    def create_hall():
        data=request.get_json(silent=True) or {}; data["material_id"]=BLOCK_IDS.get(str(data.get("material","STONE")).upper(),1)
        hall=service.create(data); return jsonify(status="ok",message=f"{hall['hall_name']} elkészült. Kód: {hall['hall_code']}",hall=enrich(hall)),201

    @app.post("/api/v1/halls/button")
    def press_button():
        try: hall=service.destroy(request.get_json(silent=True) or {})
        except HallNotFound: return jsonify(status="ignored")
        return jsonify(status="ok",message=f"{hall['hall_name']} lebontva.",hall_id=str(hall["hall_id"]))

    @app.post("/api/v1/halls/<hall_id>/rebuild")
    def rebuild(hall_id):
        data=request.get_json(silent=True) or {}; hall=service.rebuild(hall_id,data,bool(data.get("restore")))
        return jsonify(status="ok",message=f"{hall['hall_name']} újraépítve.",hall=enrich(hall))

    @app.post("/api/v1/halls/rebuild")
    def rebuild_by_code():
        hall=service.rebuild_by_code(request.get_json(silent=True) or {})
        return jsonify(status="ok",message=f"{hall['hall_name']} újraépítve.",hall=enrich(hall))

    @app.post("/api/v1/halls/restore")
    def restore_all():
        halls=service.restore_all(request.get_json(silent=True) or {})
        message="Nem történt világ-inicializálás; nincs helyreállítandó csarnok." if not halls else f"{len(halls)} csarnok helyreállítva."
        return jsonify(status="ok",message=message,halls=[enrich(h) for h in halls])

    @app.get("/api/v1/worlds/<world_uuid>/halls")
    def world_halls(world_uuid):
        return jsonify(status="ok",halls=[enrich(h) for h in repo.list_world(world_uuid)])

    @app.get("/api/v1/halls/<hall_id>")
    def hall_detail(hall_id):
        return jsonify(program_success(hall=enrich(repo.get(parse_uuid(hall_id,"hall_id")))))

    @app.get("/api/v1/halls/<hall_id>/program")
    def mounted_program(hall_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized=parse_uuid(hall_id,"hall_id")
            expected=g.transport_context.mount
            selection=selection_dto(repo.get_selection(normalized,repo.get(normalized)["owner_uuid"],
                None if expected is None else expected.as_dict()))
            workspace=programs.load(selection["program_id"])
            return jsonify(program_success(
                mount={**selection,"current":True},
                program=program_dto(programs,workspace),
            ))
        except SelectionFailure:raise
        except Exception as exc:return program_failure(exc)

    @app.get("/api/v1/programs/<program_id>")
    def get_program(program_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            workspace=programs.load(parse_uuid(program_id,"program_id"))
            return jsonify(program_success(program=program_dto(programs,workspace)))
        except Exception as exc:return program_failure(exc)

    @app.get("/api/v1/programs/<program_id>/stages/<stage_id>/children")
    def get_program_children(program_id,stage_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized_program=parse_uuid(program_id,"program_id")
            normalized_stage=parse_uuid(stage_id,"stage_id")
            workspace=programs.load(normalized_program)
            with workspace.lock:
                # The service call performs Program membership and lifecycle checks.
                programs.children(normalized_program,normalized_stage)
                parent_id=uuid.UUID(normalized_stage)
                links=[item for item in workspace.links if item.parent_stage_id==parent_id]
                children=[stage_dto(workspace,item.child_stage_id) for item in links]
                saved_version=workspace.saved_version
                workspace_epoch=str(workspace.workspace_epoch)
                revision=workspace.hierarchy_revision
            return jsonify(program_success(
                program_id=normalized_program,parent_stage_id=normalized_stage,
                program_saved_version=saved_version,
                program_workspace_epoch=workspace_epoch,
                hierarchy_revision=revision,
                links=[link_dto(item) for item in links],children=children,
            ))
        except Exception as exc:return program_failure(exc)

    @app.get("/api/v1/programs/<program_id>/stages/<stage_id>")
    def get_program_stage(program_id,stage_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized_program=parse_uuid(program_id,"program_id")
            normalized_stage=parse_uuid(stage_id,"stage_id")
            workspace=programs.load(normalized_program)
            with workspace.lock:
                stage_workspace=programs.stage(normalized_program,normalized_stage)
                projected=stage_projection_dto(
                    workspace,uuid.UUID(normalized_stage),stage_workspace,
                )
                program_epoch=str(workspace.workspace_epoch)
                program_version=workspace.saved_version
                hierarchy_revision=workspace.hierarchy_revision
                aggregate_dirty=programs.is_dirty(workspace.program_id)
            return jsonify(program_success(
                program_id=normalized_program,
                program_saved_version=program_version,
                program_workspace_epoch=program_epoch,
                hierarchy_revision=hierarchy_revision,
                aggregate_dirty=aggregate_dirty,
                stage=projected,
            ))
        except Exception as exc:return program_failure(exc)

    @app.post("/api/v1/programs/<program_id>/stages/<stage_id>/children/ensure")
    def ensure_program_child(program_id,stage_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized_program=parse_uuid(program_id,"program_id")
            normalized_stage=parse_uuid(stage_id,"stage_id")
            data=parse_ensure_request(request.get_json(silent=True))
            workspace=programs.load(normalized_program)
            with workspace.lock:
                result=programs.ensure_child_result(
                    normalized_program,normalized_stage,data["program_block_instance_id"],
                    data["player_uuid"],stage_name=data.get("stage_name"),
                )
                child=stage_dto(workspace,result.link.child_stage_id)
                revision=workspace.hierarchy_revision
                saved_version=workspace.saved_version
                workspace_epoch=str(workspace.workspace_epoch)
            return jsonify(program_success(
                program_id=normalized_program,program_saved_version=saved_version,
                program_workspace_epoch=workspace_epoch,
                hierarchy_revision=revision,created=result.created,
                link=link_dto(result.link),child=child,
            )),201 if result.created else 200
        except Exception as exc:return program_failure(exc)

    @app.get("/api/v1/programs/<program_id>/status")
    def program_status(program_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized=parse_uuid(program_id,"program_id")
            workspace=programs.load(normalized)
            dirty=programs.is_dirty(normalized)
            with workspace.lock:
                status={
                    "program_id":normalized,"saved_version":workspace.saved_version,
                    "aggregate_dirty":dirty,"hierarchy_dirty":workspace.hierarchy_dirty,
                    "hierarchy_revision":workspace.hierarchy_revision,
                    "workspace_epoch":str(workspace.workspace_epoch),
                    "workspace_state":workspace.state.value,
                    "stages":[{
                        "stage_id":str(stage_id),
                        "saved_version":(
                            workspace.stage_workspaces[stage_id].saved_version
                            if stage_id in workspace.stage_workspaces
                            else int(header["saved_version"])
                        ),
                        "workspace":None if stage_id not in workspace.stage_workspaces else {
                            "epoch":str(workspace.stage_workspaces[stage_id].workspace_epoch),
                            "working_revision":workspace.stage_workspaces[stage_id].revision,
                            "dirty":workspace.stage_workspaces[stage_id].dirty,
                        },
                    } for stage_id,header in sorted(workspace.stage_headers.items(),key=lambda item:str(item[0]))],
                }
            return jsonify(program_success(program_status=status))
        except Exception as exc:return program_failure(exc)

    @app.post("/api/v1/programs/<program_id>/save")
    def save_program(program_id):
        denied=program_authenticated()
        if denied is not None:return denied
        try:
            normalized=parse_uuid(program_id,"program_id")
            data=parse_save_request(request.get_json(silent=True))
            workspace=programs.load(normalized)
            with workspace.lock:
                receipt=programs.save(
                    normalized,data["player_uuid"],
                    expected_saved_version=data["expected_saved_version"],
                )
                projected=receipt_dto(receipt,workspace)
            return jsonify(program_success(receipt=projected))
        except Exception as exc:return program_failure(exc)

    @app.post("/api/v1/stages/<stage_id>/blocks")
    def place_stage_block(stage_id):
        stage=with_program_dirty(stages.place(stage_id,request.get_json(silent=True) or {}))
        return jsonify(status="ok",message="Programelem lerakva; a stage még nincs mentve.",stage=stage),201

    @app.post("/api/v1/stages/<stage_id>/blocks/<instance_id>")
    def update_stage_block(stage_id,instance_id):
        stage=with_program_dirty(stages.update(stage_id,instance_id,request.get_json(silent=True) or {}))
        return jsonify(status="ok",message="Programelem módosítva; a stage még nincs mentve.",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/blocks/<instance_id>/delete")
    def delete_stage_block(stage_id,instance_id):
        stage=with_program_dirty(stages.delete(stage_id,instance_id,request.get_json(silent=True) or {}))
        return jsonify(status="ok",message="Programelem törölve; a stage még nincs mentve.",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/save")
    def save_stage(stage_id):
        stage=with_program_dirty(stages.save(stage_id,request.get_json(silent=True) or {}))
        return jsonify(status="ok",message=f"Stage mentve (v{stage['saved_version']}).",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/rebuild")
    def rebuild_stage(stage_id):
        stage=with_program_dirty(stages.rebuild(stage_id,request.get_json(silent=True) or {}))
        return jsonify(status="ok",message="A stage a mentett DB-állapotból visszaépült.",stage=stage)

    @app.get("/api/v1/stages/<stage_id>/graph")
    def graph_state(stage_id):
        denied=graph_authenticated()
        if denied is not None:return denied
        try:
            player_uuid=parse_player_uuid(request.args.get("player_uuid"))
        except GraphRequestError as exc:
            result=request_error_response(exc)
            return jsonify(result.body),result.status_code
        try:result=graph_app.graph_state(stage_id,player_uuid)
        except Exception as exc:return graph_internal_error(exc)
        return jsonify(result.body),result.status_code

    @app.post("/api/v1/stages/<stage_id>/graph/wire")
    def graph_wire(stage_id):
        command=graph_command(stage_id,"WIRE_LAYOUT")
        if not isinstance(command,GraphCommand):return command
        try:result=graph_app.apply_wire(command)
        except Exception as exc:return graph_internal_error(exc)
        return jsonify(result.body),result.status_code

    @app.post("/api/v1/stages/<stage_id>/graph/bind")
    def graph_bind(stage_id):
        command=graph_command(stage_id,"BIND_EDGES")
        if not isinstance(command,GraphCommand):return command
        try:result=graph_app.bind_edges(command)
        except Exception as exc:return graph_internal_error(exc)
        return jsonify(result.body),result.status_code

    @app.post("/api/v1/stages/<stage_id>/graph/wire/place")
    def graph_wire_place(stage_id):
        command=graph_command(stage_id,"WIRE_PLACE_INTENT")
        if not isinstance(command,GraphCommand):return command
        try:result=graph_app.place_wire(command)
        except Exception as exc:return graph_internal_error(exc)
        return jsonify(result.body),result.status_code

    @app.post("/api/v1/halls/save-button")
    def save_button():
        data=request.get_json(silent=True) or {}
        hall=repo.by_control(str(data["world_uuid"]),"SAVE_BUTTON",int(data["x"]),int(data["y"]),int(data["z"]))
        diagnostic("PY_SAVE_BUTTON_RECEIVE",hall_id=hall["hall_id"],requested_stage_id=data.get("active_stage_id"),
            request_epoch=data.get("workspace_epoch"),request_revision=data.get("expected_working_revision"))
        if "active_stage_id" in data:
            try: stage_id=uuid.UUID(str(data["active_stage_id"]))
            except (ValueError,TypeError) as exc:
                raise HallConflict("Hibás aktív Stage azonosító.") from exc
            if "workspace_epoch" not in data or "expected_working_revision" not in data:
                raise HallConflict("Az aktív Stage mentéséhez epoch és revision szükséges.")
            workspace=programs.for_hall(hall["hall_id"])
            with workspace.lock:
                programs.save(
                    workspace.program_id,str(data["player_uuid"]),
                    target_stage_id=stage_id,client_payload=data,
                )
                stage=with_program_dirty(stages.view(programs.stage(workspace.program_id,stage_id)))
        else:
            # Compatibility for older clients: still save the entire Program.
            ensured=stages.ensure(hall);stage=with_program_dirty(stages.save(str(ensured["stage_id"]),data))
        return jsonify(status="ok",message=f"Stage mentve (v{stage['saved_version']}).",stage=stage)

    @app.get("/api/v1/players/<player_uuid>/halls")
    def player_halls(player_uuid):
        halls=repo.list_owner(player_uuid)
        return jsonify(status="ok",halls=[{**h,
            **selection_dto(repo.get_selection(h["hall_id"],player_uuid))} for h in halls])

    @app.post("/event")
    def legacy_event():
        data=request.get_json(silent=True) or {}
        if not data: return jsonify(status="error",message="No data received"),400
        if data.get("action")!="HANGAR_BUILD": return jsonify(status="ok",message="Nincs végrehajtandó művelet.")
        data["material_id"]=BLOCK_IDS.get(str(data.get("material","STONE")).upper(),1)
        hall=service.create(data); return jsonify(status="ok",message=f"{hall['hall_name']} elkészült.",hall=enrich(hall)),201
    return app

if __name__=="__main__":
    create_app().run(host=os.getenv("BLOCKCOMMANDER_BIND","127.0.0.1"),port=int(os.getenv("BLOCKCOMMANDER_PORT","5001")),debug=False)
