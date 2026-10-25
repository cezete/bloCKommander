package me.buciqua.blockommander.runtime.graph

import java.util.UUID

internal val STAGE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
internal val OTHER_STAGE_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
internal val HALL_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
internal val WORLD_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
internal val EPOCH_A: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
internal val EPOCH_B: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
internal val PLAYER_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
internal val REQUEST_ID: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
internal val NODE_A: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
internal val NODE_B: UUID = UUID.fromString("88888888-8888-8888-8888-888888888888")
internal val EDGE_ID: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")
internal val ROUTE_ID: UUID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111")

internal fun snapshot(
    stageId: UUID = STAGE_ID,
    epoch: UUID = EPOCH_A,
    revision: Long = 3,
    dirty: Boolean = true,
    nodes: List<GraphNode> = emptyList(),
) = GraphStateSnapshot(
    stageId, HALL_ID, WORLD_ID, GraphPosition(10, 20, 30), GraphPosition(7, 5, 7), epoch, revision, 2,
    dirty, 1, 1, true, emptyList(), GraphStatuses("VALID", "CONNECTED", "DIRTY", "PRESENT", "VALID"),
    nodes, emptyList(), emptyList(), emptyList(), GraphValidation(revision, emptyList()),
)

internal fun successJson(
    stageId: UUID = STAGE_ID,
    epoch: UUID = EPOCH_A,
    revision: Long = 3,
    requestId: UUID? = null,
): String = """
{
  "api_version":"ck-graph-v1","status":"ok",${requestId?.let { "\"request_id\":\"$it\"," } ?: ""}
  "modified":false,"stage_id":"$stageId","workspace_epoch":"$epoch","working_revision":$revision,
  "diagnostics":[],"wire_diff":[],
  "state":{
    "stage_id":"$stageId","hall_id":"$HALL_ID","world_uuid":"$WORLD_ID",
    "origin":{"x":10,"y":20,"z":30},"size":{"x":7,"y":5,"z":7},
    "workspace_epoch":"$epoch","working_revision":$revision,"saved_version":2,"dirty":true,
    "graph_snapshot_version":1,"port_catalog_version":1,"compatible_for_graph_mutation":true,
    "compatibility_issues":[],
    "status":{"graph":"VALID","edge_indicator":"CONNECTED","save":"DIRTY","layout":"PRESENT","program_validation":"VALID"},
    "nodes":[
      {"instance_id":"$NODE_A","type_key":"START","position":{"x":0,"y":0,"z":0},"orientation":"EAST"},
      {"instance_id":"$NODE_B","type_key":"STOP","position":{"x":2,"y":0,"z":0},"orientation":"EAST"}
    ],
    "wire_cells":[{"position":{"x":1,"y":0,"z":0},"channels":[{"channel":"SINGLE","connections":["EAST","WEST"]}]}],
    "edges":[{"edge_id":"$EDGE_ID","source_instance_id":"$NODE_A","source_port_key":"out","target_instance_id":"$NODE_B","target_port_key":"in","edge_kind":"CONTROL"}],
    "routes":[{"route_id":"$ROUTE_ID","edge_id":"$EDGE_ID","source_port":{"instance_id":"$NODE_A","port_key":"out"},"target_port":{"instance_id":"$NODE_B","port_key":"in"},"steps":[{"position":{"x":1,"y":0,"z":0},"channel":"SINGLE"}],"status":"VALID"}],
    "validation":{"revision":$revision,"diagnostics":[]}
  }
}
""".trimIndent()

internal fun errorJson(code: String = "INVALID_GRAPH_AUTH") = """
{"api_version":"ck-graph-v1","status":"error","error_code":"$code","message":"hiba","diagnostics":[{"code":"$code","severity":"ERROR","message":"hiba","node_ids":[],"port_refs":[],"edge_ids":[],"wire_positions":[],"field_name":null}]}
""".trimIndent()
