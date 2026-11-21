import json
import threading
import unittest
from uuid import UUID, uuid4

from ck_webhook import create_app
from hangar.declaration_service import DeclarationTarget
from hangar.declarations import Mutability, VariableType
from hangar.graph_application import GRAPH_SECRET_HEADER
from hangar.value_http import API_VERSION
from test_program_service import FakeProgramRepository, HALL, OTHER_PROGRAM, OTHER_ROOT, OWNER, PROGRAM, ROOT


class ValueHttpTest(unittest.TestCase):
    def setUp(self):
        self.repo = FakeProgramRepository()
        self.repo.types = [row for row in self.repo.types if row["type_key"] not in {"CLASS", "INTERFACE"}]
        self.repo.types.append(dict(self.repo.types[0], type_key="LET"))
        self.app = create_app(repository=self.repo, migrate=False, graph_shared_secret="isolated-value")
        self.client = self.app.test_client()
        self.programs = self.app.extensions["blockcommander_program_service"]
        self.declarations = self.app.extensions["blockcommander_declarations"]
        context = self.repo.resolve_working_copy_context(HALL)
        self.headers = {GRAPH_SECRET_HEADER: "isolated-value",
                        "X-BlockCommander-Hall-Id": str(HALL),
                        "X-BlockCommander-Program-Id": str(PROGRAM),
                        "X-BlockCommander-Working-Copy-Id": str(context.working_copy_id)}

    def base(self, stage):
        return f"/api/v1/programs/{PROGRAM}/stages/{stage}"

    def request(self, method, url, status=200, *, client=None, headers=None, **kwargs):
        result = (client or self.client).open(url, method=method,
            headers=self.headers if headers is None else headers, **kwargs)
        self.assertEqual(status, result.status_code, result.get_data(as_text=True))
        return result.get_json()

    def read(self, path, status=200, **kwargs):
        return self.request("GET", path, status, query_string={"player_uuid": str(OWNER)}, **kwargs)

    def command(self, stage, **fields):
        page = self.read(self.base(stage) + "/symbols")
        return dict(api_version=API_VERSION, player_uuid=str(OWNER), request_id=str(uuid4()),
                    expected_stage_workspace_epoch=page["version_context"]["stage_workspace_epoch"],
                    expected_working_revision=page["version_context"]["working_revision"],
                    expected_resolution_basis=page["resolution_basis_digest"], **fields)

    def place_payload(self, stage=ROOT, x=4):
        return self.command(stage, type_key="LET", coordinate_space="STAGE_RELATIVE",
                            x=x, y=0, z=5, orientation="EAST")

    def place(self, stage=ROOT, **kwargs):
        return self.request("POST", f"/api/v1/stages/{stage}/blocks", 201,
                            json=self.place_payload(stage, **kwargs))["let"]["instance_id"]

    def block_resource(self, stage, block):
        return self.base(stage) + f"/blocks/{block}/value"

    def declare(self, stage_id, name, mutability):
        stage_id = UUID(str(stage_id))
        stage = self.programs.stage(PROGRAM, stage_id)
        return self.declarations.create(DeclarationTarget(PROGRAM, stage_id), OWNER,
            stage.workspace_epoch, stage.revision, name=name, variable_type=VariableType.INTEGER,
            mutability=mutability).declaration.declaration_id

    def program_body(self, role=None):
        root = self.programs.root(PROGRAM)
        body = {"player_uuid": str(OWNER), "program_block_instance_id": str(root.snapshot.blocks[0].instance_id)}
        if role is not None:
            body["owner_role"] = role
        return self.request("POST", self.base(ROOT) + "/children/ensure", 201, json=body)

    def ensure(self, stage, block, status=201):
        return self.request("POST", self.base(stage) + "/children/ensure", status,
            json=self.command(stage, owner_role="LET_RHS", program_block_instance_id=block))

    def graph(self, stage):
        return self.read(f"/api/v1/stages/{stage}/graph")

    def graph_command(self, stage, route, operation, payload, status=200):
        state = self.graph(stage)["state"]
        return self.request("POST", f"/api/v1/stages/{stage}/graph{route}", status,
            json=dict(schema_version=1, request_id=str(uuid4()), stage_id=str(stage),
                      player_uuid=str(OWNER), workspace_epoch=state["workspace_epoch"],
                      expected_working_revision=state["working_revision"], operation=operation, payload=payload))

    def test_shared_workspaces_and_let_lifecycle(self):
        values = self.app.extensions["blockcommander_value_service"]
        self.assertIs(self.programs, values.programs)
        self.assertIs(self.declarations, values.declarations)
        legacy = self.program_body()
        self.assertEqual("PROGRAM_BODY", legacy["link"]["owner_role"])
        parent = legacy["child"]["stage_id"]
        var = self.declare(parent, "var", Mutability.MUTABLE)
        val = self.declare(parent, "val", Mutability.IMMUTABLE)
        let = self.place(parent)
        url = self.block_resource(parent, let)
        opened = self.read(url)
        self.assertEqual({}, opened["let"]["semantic_config"])
        self.assertIsNone(opened["let"]["rhs_link"])
        self.assertEqual(opened, self.read(url))

        for target in (var, val):
            selected = self.request("PATCH", url, json=self.command(parent, target_id=str(target)))
            self.assertEqual({"version": 1, "target_id": str(target)}, selected["let"]["semantic_config"])
        no_op = self.request("PATCH", url, json=self.command(parent, target_id=str(val)))
        self.assertEqual(selected["working_revision"], no_op["working_revision"])

        child_receipt = self.ensure(parent, let)
        child = child_receipt["child"]["stage_id"]
        self.assertEqual("LET_RHS", child_receipt["link"]["owner_role"])
        self.assertEqual("VALUE_BODY", child_receipt["child"]["kind"])
        self.assertFalse(self.ensure(parent, let, 200)["created"])
        self.assertEqual(child, self.read(url)["let"]["rhs_link"]["child_stage_id"])
        self.assertTrue(self.programs.retain_child(PROGRAM, parent, let, str(OWNER)))
        self.assertIsNone(self.read(url)["let"]["rhs_link"])
        reactivated = self.ensure(parent, let, 200)
        self.assertEqual(("ACTIVE", child), (reactivated["link"]["state"], reactivated["link"]["child_stage_id"]))
        stage = self.request("GET", self.base(child))
        graph = self.graph(child)
        self.assertEqual(stage["program_workspace_epoch"], graph["program_workspace_epoch"])
        self.assertEqual(stage["hierarchy_revision"], graph["hierarchy_revision"])
        self.assertEqual(stage["stage"]["workspace"]["epoch"], graph["state"]["workspace_epoch"])
        self.assertEqual(stage["stage"]["workspace"]["working_revision"], graph["state"]["working_revision"])
        self.assertEqual([], stage["stage"]["blocks"])
        for field in ("nodes", "wire_cells", "routes", "edges"):
            self.assertEqual([], graph["state"][field])
        self.assertTrue(graph["aggregate_dirty"])
        self.assertFalse(graph["state"]["control_mutation_allowed"])
        self.assertEqual(0, self.repo.program_save_calls)
        receipt = self.request("POST", f"/api/v1/programs/{PROGRAM}/save",
            json={"player_uuid": str(OWNER), "expected_saved_version": 0})["receipt"]
        after_save = self.graph(child)
        self.assertFalse(after_save["aggregate_dirty"])
        self.assertEqual(receipt["saved_version"], after_save["program_saved_version"])
        self.assertEqual(graph["state"]["working_revision"], after_save["state"]["working_revision"])

        for path, operation, payload in (
            ("/wire/place", "WIRE_PLACE_INTENT", {"position": {"x": 1, "y": 0, "z": 1}, "intent": "AUTO"}),
            ("/wire", "WIRE_LAYOUT", {"changes": [{"position": {"x": 1, "y": 0, "z": 1}, "cell": None}]}),
            ("/bind", "BIND_EDGES", {}),
        ):
            self.assertEqual("CONTROL_STAGE_REQUIRED", self.graph_command(child, path, operation, payload, 409)["error_code"])
        self.assertEqual(after_save, self.graph(child))

    def test_scope_staleness_and_stable_binding(self):
        parent = self.program_body()["child"]["stage_id"]
        inherited = self.declare(ROOT, "same", Mutability.MUTABLE)
        self.declare(parent, "same", Mutability.IMMUTABLE)
        let = self.place(parent)
        url = self.block_resource(parent, let)
        page = self.read(self.base(parent) + "/symbols")
        shadowed = next(row for row in page["rows"] if row["group"] == "SHADOWED")
        self.assertEqual(str(inherited), shadowed["declaration"]["declaration_id"])
        self.assertEqual(str(ROOT), shadowed["origin_scope"]["owner_id"])
        self.request("PATCH", url, json=self.command(parent, target_id=str(inherited)))
        stale = self.command(parent, target_id=str(inherited))

        root = self.programs.root(PROGRAM)
        root_target = DeclarationTarget(PROGRAM, ROOT)
        self.declarations.update(root_target, OWNER, root.workspace_epoch, root.revision, inherited, {"name": "renamed"})
        current = self.read(url)
        self.assertEqual(stale["expected_working_revision"], current["working_revision"])
        self.assertEqual(str(inherited), current["let"]["target_id"])
        self.assertEqual("STALE_VALUE_BASIS", self.request("PATCH", url, 409, json=stale)["error_code"])

        self.declarations.delete(root_target, OWNER, root.workspace_epoch, root.revision, inherited)
        replacement = self.declare(ROOT, "renamed", Mutability.MUTABLE)
        self.assertNotEqual(inherited, replacement)
        self.assertEqual(str(inherited), self.read(url)["let"]["target_id"])
        unreachable = self.request("PATCH", url, 422, json=self.command(parent, target_id=str(inherited)))
        self.assertEqual("DECLARATION_NOT_REACHABLE", unreachable["error_code"])

    def test_context_access_and_legacy_bypass(self):
        path = f"/api/v1/stages/{ROOT}/blocks"
        payload = self.place_payload()
        missing = {GRAPH_SECRET_HEADER: self.headers[GRAPH_SECRET_HEADER]}
        self.assertEqual("INVALID_GRAPH_AUTH", self.request("POST", path, 401, headers={}, json=payload)["error_code"])
        self.assertEqual("CONTEXT_REQUIRED", self.request("POST", path, 409, headers=missing, json=payload)["error_code"])
        incomplete = {**missing, "X-BlockCommander-Hall-Id": str(HALL)}
        self.assertEqual("INCOMPLETE_CONTEXT", self.request("POST", path, 400, headers=incomplete, json=payload)["error_code"])
        wrong_copy = {**self.headers, "X-BlockCommander-Working-Copy-Id": str(uuid4())}
        self.assertEqual("FOREIGN_WORKING_COPY", self.request("POST", path, 409, headers=wrong_copy, json=payload)["error_code"])
        self.repo.add_program(OTHER_PROGRAM, OTHER_ROOT)
        self.assertEqual("STAGE_PROGRAM_MISMATCH", self.request(
            "POST", f"/api/v1/stages/{OTHER_ROOT}/blocks", 409, json=payload)["error_code"])
        self.assertEqual("FORBIDDEN", self.request("POST", path, 403,
            json={**payload, "player_uuid": str(uuid4())})["error_code"])
        self.assertEqual("INVALID_FIELDS", self.request("POST", path, 400,
            json=dict(player_uuid=str(OWNER), type_key="LET"))["error_code"])
        for kind in ("REF", "RESULT", "OPERATOR", "LITERAL"):
            self.assertEqual("VALUE_HTTP_REQUIRED", self.request("POST", path, 409,
                json=dict(player_uuid=str(OWNER), type_key=kind))["error_code"])
        let = self.place()
        legacy_block = path + "/" + let
        for suffix in ("", "/delete"):
            self.assertEqual("VALUE_HTTP_REQUIRED", self.request("POST", legacy_block + suffix, 409,
                json=dict(player_uuid=str(OWNER), label="legacy"))["error_code"])
        self.assertEqual("BLOCK_NOT_FOUND", self.read(self.block_resource(ROOT, uuid4()), 404)["error_code"])
        old_owner = self.request("POST", self.base(ROOT) + "/children/ensure", 409,
            json=dict(player_uuid=str(OWNER), program_block_instance_id=let))
        self.assertEqual("error", old_owner["status"])

    def test_place_conflicts_and_concurrent_replay(self):
        path = f"/api/v1/stages/{ROOT}/blocks"
        self.graph_command(ROOT, "/wire/place", "WIRE_PLACE_INTENT",
                           {"position": {"x": 3, "y": 0, "z": 5}, "intent": "AUTO"})
        blocked = self.request("POST", path, 409, json=self.place_payload(x=3))
        self.assertEqual("OCCUPIED_POSITION", blocked["error_code"])
        payload = self.place_payload()
        gate = threading.Barrier(2)
        results = []

        def place():
            gate.wait(timeout=5)
            results.append(self.app.test_client().post(path, headers=self.headers, json=payload))

        threads = [threading.Thread(target=place) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(5)
        self.assertEqual(2, len(results))
        self.assertEqual([201, 201], [result.status_code for result in results])
        self.assertEqual(results[0].get_json(), results[1].get_json())
        self.assertEqual(1, sum(block.type_key == "LET" for block in self.programs.root(PROGRAM).snapshot.blocks))
        self.assertEqual("REQUEST_ID_CONFLICT", self.request("POST", path, 409,
            json={**payload, "x": 6})["error_code"])
        self.assertEqual("OCCUPIED_POSITION", self.request("POST", path, 409,
            json=self.place_payload())["error_code"])

    def test_strict_fields_receipt_failure_and_old_epoch(self):
        path = f"/api/v1/stages/{ROOT}/blocks"
        command = self.place_payload()
        invalid = {**command, "coordinate_space": "WORLD"}
        self.assertEqual("INVALID_PLACEMENT", self.request("POST", path, 400, json=invalid)["error_code"])
        self.assertEqual("REQUEST_ID_CONFLICT", self.request("POST", path, 409, json=command)["error_code"])
        duplicate = json.dumps(command)[:-1] + ', "orientation": "WEST"}'
        self.assertEqual("DUPLICATE_JSON_KEY", self.request(
            "POST", path, 400, data=duplicate, content_type="application/json")["error_code"])
        self.assertEqual("BODY_TOO_LARGE", self.request(
            "POST", path, 413, data=" " * 65537, content_type="application/json")["error_code"])
        command = self.place_payload()
        place = self.request("POST", path, 201, json=command)
        self.request("PATCH", self.block_resource(ROOT, place["let"]["instance_id"]), 400,
                     json=self.command(ROOT, target_id="bad"))
        self.request("POST", f"/api/v1/programs/{PROGRAM}/save",
                     json={"player_uuid": str(OWNER), "expected_saved_version": 0})
        self.request("POST", f"/api/v1/stages/{ROOT}/rebuild", json={"player_uuid": str(OWNER)})
        self.assertEqual("STALE_VALUE_BASIS", self.request("POST", path, 409, json=command)["error_code"])

    def test_control_wire_and_let_removal_keep_old_graph_contract(self):
        path = f"/api/v1/stages/{ROOT}/blocks"
        for kind, x in (("START", 2), ("STOP", 6)):
            self.request("POST", path, 201, json=dict(player_uuid=str(OWNER), type_key=kind,
                x=x, y=0, z=5, coordinate_space="STAGE_RELATIVE", orientation="EAST"))
        let = self.place()
        child = self.ensure(ROOT, let)["child"]["stage_id"]
        for x in (3, 5):
            wire = self.graph_command(ROOT, "/wire/place", "WIRE_PLACE_INTENT",
                                      {"position": {"x": x, "y": 0, "z": 5}, "intent": "AUTO"})
            self.assertEqual([], wire["state"]["edges"])
        self.assertEqual(2, len(self.graph(ROOT)["discovery"]["candidates"]))
        bound = self.graph_command(ROOT, "/bind", "BIND_EDGES", {})["state"]
        edges = bound["edges"]
        self.assertEqual(2, len(edges))
        self.assertEqual(2, len(bound["routes"]))
        self.assertEqual({"CONTROL"}, {edge["edge_kind"] for edge in edges})
        self.assertEqual({"in"}, {edge["target_port_key"] for edge in edges if edge["target_instance_id"] == let})
        self.assertEqual({"out"}, {edge["source_port_key"] for edge in edges if edge["source_instance_id"] == let})

        delete = self.command(ROOT)
        url = path + f"/{let}/delete"
        removed = self.request("POST", url, json=delete)
        self.assertEqual("RETAINED", removed["link"]["state"])
        self.assertEqual(child, removed["link"]["child_stage_id"])
        self.assertEqual(let, removed["deleted_instance_id"])
        self.assertEqual(removed, self.request("POST", url, json=delete))
        self.assertEqual([], self.graph(ROOT)["state"]["edges"])
        self.assertEqual([], self.graph(ROOT)["state"]["routes"])
        for x in (3, 5):
            self.graph_command(ROOT, "/wire", "WIRE_LAYOUT",
                               {"changes": [{"position": {"x": x, "y": 0, "z": 5}, "cell": None}]})
        replacement = self.place()
        self.assertEqual(removed, self.request("POST", url, json=delete))
        self.assertNotEqual(replacement, let)
        self.assertEqual(replacement, self.read(self.block_resource(ROOT, replacement))["let"]["instance_id"])

    def test_let_does_not_relax_unspecified_ports(self):
        let = self.place()
        self.assertEqual([], self.graph(ROOT)["discovery"]["candidates"])  # PROGRAM has no ports.
        catalog = self.programs.stages.read_graph_context(str(ROOT), str(OWNER)).catalog
        self.assertEqual((), catalog["PROGRAM"])
        for kind, x in (("FOR", 8), ("FUN", 9)):
            self.assertEqual("UNSPECIFIED", catalog.status(kind).value)
            self.request("POST", f"/api/v1/stages/{ROOT}/blocks", 201, json=dict(player_uuid=str(OWNER),
                type_key=kind, x=x, y=0, z=0, coordinate_space="STAGE_RELATIVE"))
        rejected = self.graph_command(ROOT, "/wire/place", "WIRE_PLACE_INTENT",
            {"position": {"x": 3, "y": 0, "z": 5}, "intent": "AUTO"}, 422)
        self.assertEqual("GRAPH_MAPPING_ERROR", rejected["error_code"])
        self.assertEqual(2, sum(row["code"] == "UNSPECIFIED_BLOCK_TYPE" for row in rejected["diagnostics"]))
        self.assertEqual(let, self.read(self.block_resource(ROOT, let))["let"]["instance_id"])

    def test_projection_requires_reload_when_saved_state_has_changed(self):
        before = self.graph(ROOT)
        self.assertEqual(0, before["program_saved_version"])
        self.repo.programs[PROGRAM]["saved_version"] = 1
        for path in (f"/api/v1/stages/{ROOT}/graph", self.base(ROOT)):
            rejected = self.read(path, 409)
            self.assertEqual("RELOAD_REQUIRED", rejected["error_code"])
