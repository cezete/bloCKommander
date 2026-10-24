from __future__ import annotations
import os
from flask import Flask, jsonify, request
from hangar.builder import HallValidationError, control_elements, hall_client_view
from hangar.repository import HallConflict, HallForbidden, HallNotFound, HallRepository
from hangar.service import HallService
from hangar.stage import StageService
from hangar.graph_application import (
    API_VERSION, GRAPH_SECRET_ENV, GRAPH_SECRET_HEADER, GraphApplicationService, GraphCommand,
    GraphRequestError, GraphTransportAuthError, GraphTransportAuthenticator,
    parse_graph_command, parse_player_uuid, request_error_response,
    transport_error_response,
)

BLOCK_IDS={"AIR":0,"STONE":1,"GRASS_BLOCK":2,"DIRT":3,"COBBLESTONE":4,"OAK_PLANKS":5,"SAND":12,
           "GRAVEL":13,"GOLD_BLOCK":41,"IRON_BLOCK":42,"BRICK_BLOCK":45,"OBSIDIAN":49,"DIAMOND_BLOCK":57}

def create_app(repository=None,mc=None,migrate=True,graph_shared_secret=None):
    app=Flask(__name__); repo=repository or HallRepository()
    if migrate: repo.migrate()
    world_driver=os.getenv("BLOCKCOMMANDER_WORLD_DRIVER","fabric").lower()
    minecraft=mc
    if world_driver=="mcpi" and minecraft is None:
        from mcpi.minecraft import Minecraft
        minecraft=Minecraft.create(os.getenv("BLOCKCOMMANDER_MC_HOST","127.0.0.1"),int(os.getenv("BLOCKCOMMANDER_MC_PORT","4711")))
    if world_driver not in ("fabric","mcpi"):
        raise RuntimeError("BLOCKCOMMANDER_WORLD_DRIVER must be fabric or mcpi")
    service=HallService(repo,minecraft,apply_world=world_driver=="mcpi"); stages=StageService(repo)
    graph_app=GraphApplicationService(stages)
    configured_secret=(
        graph_shared_secret if graph_shared_secret is not None
        else os.getenv(GRAPH_SECRET_ENV)
    )
    graph_auth=GraphTransportAuthenticator(configured_secret)
    app.extensions["blockcommander_stage_service"]=stages
    app.extensions["blockcommander_graph_application"]=graph_app

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
            return parse_graph_command(
                request.get_json(silent=True),
                expected_stage_id=stage_id,
                expected_operation=operation,
            )
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

    def enrich(hall):
        bounds={k:int(hall[k]) for k in ("min_x","min_y","min_z","max_x","max_y","max_z")}
        expected=[e.as_record() for e in control_elements(bounds,str(hall["orientation"]),str(hall["owner_name"]),str(hall["hall_name"]))]
        present={c["kind"] for c in hall.get("controls",[])};missing=[c for c in expected if c["kind"] not in present]
        if missing:
            repo.add_controls(str(hall["hall_id"]),str(hall["world_uuid"]),missing);hall.setdefault("controls",[]).extend(missing)
        return hall_client_view(hall,stages.ensure(hall))

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

    @app.post("/api/v1/stages/<stage_id>/blocks")
    def place_stage_block(stage_id):
        stage=stages.place(stage_id,request.get_json(silent=True) or {})
        return jsonify(status="ok",message="Programelem lerakva; a stage még nincs mentve.",stage=stage),201

    @app.post("/api/v1/stages/<stage_id>/blocks/<instance_id>")
    def update_stage_block(stage_id,instance_id):
        stage=stages.update(stage_id,instance_id,request.get_json(silent=True) or {})
        return jsonify(status="ok",message="Programelem módosítva; a stage még nincs mentve.",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/blocks/<instance_id>/delete")
    def delete_stage_block(stage_id,instance_id):
        stage=stages.delete(stage_id,instance_id,request.get_json(silent=True) or {})
        return jsonify(status="ok",message="Programelem törölve; a stage még nincs mentve.",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/save")
    def save_stage(stage_id):
        stage=stages.save(stage_id,request.get_json(silent=True) or {})
        return jsonify(status="ok",message=f"Stage mentve (v{stage['saved_version']}).",stage=stage)

    @app.post("/api/v1/stages/<stage_id>/rebuild")
    def rebuild_stage(stage_id):
        stage=stages.rebuild(stage_id,request.get_json(silent=True) or {})
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

    @app.post("/api/v1/halls/save-button")
    def save_button():
        data=request.get_json(silent=True) or {}
        hall=repo.by_control(str(data["world_uuid"]),"SAVE_BUTTON",int(data["x"]),int(data["y"]),int(data["z"]))
        ensured=stages.ensure(hall);stage=stages.save(str(ensured["stage_id"]),data)
        return jsonify(status="ok",message=f"Stage mentve (v{stage['saved_version']}).",stage=stage)

    @app.get("/api/v1/players/<player_uuid>/halls")
    def player_halls(player_uuid):
        halls=repo.list_owner(player_uuid)
        return jsonify(status="ok",halls=[{**h,"hall_id":str(h["hall_id"]),"world_uuid":str(h["world_uuid"])} for h in halls])

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
