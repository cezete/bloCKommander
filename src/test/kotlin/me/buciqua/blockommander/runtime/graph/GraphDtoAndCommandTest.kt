package me.buciqua.blockommander.runtime.graph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphDtoAndCommandTest {
    @Test fun `GET snapshot preserves envelope and complete projection`() {
        val response = GraphResponseParser.parse(200, successJson()) as GraphSuccessResponse
        assertEquals(EPOCH_A, response.workspaceEpoch)
        assertEquals(3, response.workingRevision)
        assertTrue(response.state.dirty)
        assertEquals(2, response.state.nodes.size)
        assertEquals(listOf("EAST", "WEST"), response.state.wireCells.single().channels.single().connections)
        assertEquals(EDGE_ID, response.state.edges.single().edgeId)
        assertEquals(ROUTE_ID, response.state.routes.single().routeId)
        assertEquals("VALID", response.state.statuses.programValidation)
    }

    @Test fun `unknown API version is rejected`() {
        val body = successJson().replace("ck-graph-v1", "ck-graph-v2")
        assertThrows(GraphProtocolException::class.java) { GraphResponseParser.parse(200, body) }
    }

    @Test fun `missing discovery remains safe for CK-04 responses`() {
        val response = GraphResponseParser.parse(200, successJson()) as GraphSuccessResponse
        assertNull(response.discovery)
    }

    @Test fun `versioned discovery preserves components candidates steps and diagnostics`() {
        val componentId = "10101010-1010-1010-1010-101010101010"
        val candidateId = "20202020-2020-2020-2020-202020202020"
        val discovery = """
          "discovery":{
            "schema_version":1,"future_additive_field":{"ignored":true},
            "components":[{"component_id":"$componentId","status":"INCOMPLETE",
              "vertices":[{"position":{"x":1,"y":0,"z":0},"channel":"SINGLE","future":"ignored"}],
              "ports":[{"instance_id":"$NODE_A","port_key":"out"}],
              "open_ends":[{"position":{"x":1,"y":0,"z":0},"channel":"SINGLE","direction":"EAST"}]}],
            "candidates":[{"candidate_id":"$candidateId","component_id":"$componentId",
              "source_port":{"instance_id":"$NODE_A","port_key":"out"},
              "target_port":{"instance_id":"$NODE_B","port_key":"in"},
              "steps":[{"position":{"x":1,"y":0,"z":0},"channel":"SINGLE"}]}],
            "diagnostics":[{"code":"OPEN_WIRE","severity":"ERROR","message":"open",
              "component_id":"$componentId","port_refs":[],"wire_positions":[{"x":1,"y":0,"z":0}]}]
          },
        """.trimIndent()
        val body = successJson().replace("  \"state\":{", "$discovery\n  \"state\":{")
        val parsed = (GraphResponseParser.parse(200, body) as GraphSuccessResponse).discovery!!
        assertEquals(1, parsed.schemaVersion)
        assertEquals("INCOMPLETE", parsed.components.single().status)
        assertEquals("EAST", parsed.components.single().openEnds.single().direction)
        assertEquals(java.util.UUID.fromString(candidateId), parsed.candidates.single().candidateId)
        assertEquals(GraphPosition(1, 0, 0), parsed.candidates.single().steps.single().position)
        assertEquals("OPEN_WIRE", parsed.diagnostics.single().code)
    }

    @Test fun `unknown discovery schema is rejected before projection`() {
        val body = successJson().replace(
            "  \"state\":{",
            "  \"discovery\":{\"schema_version\":2,\"components\":[],\"candidates\":[],\"diagnostics\":[]},\n  \"state\":{",
        )
        assertThrows(GraphProtocolException::class.java) { GraphResponseParser.parse(200, body) }
    }

    @Test fun `placement metadata is optional and parsed without replacing authoritative state`() {
        val placement = """
          "placement":{"intent":"AUTO","affected_cells":[
            {"position":{"x":1,"y":0,"z":0},"channels":[{"channel":"SINGLE","connections":["EAST"]}]}
          ]},
        """.trimIndent()
        val body = successJson().replace("  \"state\":{", "$placement\n  \"state\":{")
        val response = GraphResponseParser.parse(200, body) as GraphSuccessResponse
        val parsedPlacement = requireNotNull(response.placement)
        assertEquals(GraphPlacementIntent.AUTO, parsedPlacement.intent)
        assertEquals(listOf("EAST"), parsedPlacement.affectedCells.single().channels.single().connections)
        assertEquals(listOf("EAST", "WEST"), response.state.wireCells.single().channels.single().connections)
    }

    @Test fun `unknown placement intent in response fails closed`() {
        val body = successJson().replace(
            "  \"state\":{",
            "  \"placement\":{\"intent\":\"JUNCTION\",\"affected_cells\":[]},\n  \"state\":{",
        )
        assertThrows(GraphProtocolException::class.java) { GraphResponseParser.parse(200, body) }
    }

    @Test fun `command metadata wire diff binding and diagnostics are preserved`() {
        val body = successJson(requestId = REQUEST_ID)
            .replace("\"diagnostics\":[],\"wire_diff\":[]", """"diagnostics":[{"code":"NOTICE","severity":"WARNING","message":"m","node_ids":["$NODE_A"],"port_refs":[],"edge_ids":["$EDGE_ID"],"wire_positions":[{"x":1,"y":0,"z":0}],"suggestion":"s"}],"wire_diff":[{"position":{"x":1,"y":0,"z":0},"cell":{"position":{"x":1,"y":0,"z":0},"channels":[{"channel":"SINGLE","connections":["EAST","WEST"]}]}}],"binding":{"created_edge_ids":["$EDGE_ID"],"reused_edge_ids":[]}""")
        val response = GraphResponseParser.parse(200, body) as GraphSuccessResponse
        assertEquals(REQUEST_ID, response.requestId)
        assertEquals("SINGLE", response.wireDiff.single().cell!!.channels.single().channel)
        assertEquals(listOf(EDGE_ID), response.binding!!.createdEdgeIds)
        assertEquals("s", response.diagnostics.single().suggestion)
    }

    @Test fun `missing required field is rejected`() {
        val body = successJson().replace("\"working_revision\":3,", "")
        assertThrows(GraphProtocolException::class.java) { GraphResponseParser.parse(200, body) }
    }

    @Test fun `legacy graph snapshot accepts null port catalog version`() {
        val body = successJson()
            .replace("\"port_catalog_version\":\"ck-control-v2\"", "\"port_catalog_version\":null")
            .replace("\"compatible_for_graph_mutation\":true", "\"compatible_for_graph_mutation\":false")
            .replace("\"compatibility_issues\":[]", "\"compatibility_issues\":[\"legacy\"]")
        val response = GraphResponseParser.parse(200, body) as GraphSuccessResponse
        assertNull(response.state.portCatalogVersion)
        assertFalse(response.state.compatibleForGraphMutation)
        assertEquals(listOf("legacy"), response.state.compatibilityIssues)
    }

    @Test fun `wire command matches B1 contract`() {
        val identity = ServerPlayerIdentity.trustedForTest(PLAYER_ID)
        val command = WireLayoutCommand(
            REQUEST_ID, STAGE_ID, identity, EPOCH_A, 3,
            listOf(GraphWireChangeInput(GraphPosition(1, 0, 0), GraphWireCellInput(
                listOf(GraphWireChannel("SINGLE", listOf("EAST", "WEST"))),
            ))),
        )
        val json = command.toJson()
        assertEquals(1, json["schema_version"].asInt)
        assertEquals(REQUEST_ID.toString(), json["request_id"].asString)
        assertEquals(PLAYER_ID.toString(), json["player_uuid"].asString)
        assertEquals("WIRE_LAYOUT", json["operation"].asString)
        val cell = json.getAsJsonObject("payload").getAsJsonArray("changes")[0].asJsonObject.getAsJsonObject("cell")
        assertFalse(cell.has("position"))
        assertEquals("SINGLE", cell.getAsJsonArray("channels")[0].asJsonObject["channel"].asString)
    }

    @Test fun `wire removal command keeps required cell field as explicit null`() {
        val command = WireLayoutCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
            listOf(GraphWireChangeInput(GraphPosition(1, 0, 0), null)),
        )
        val change = command.toJson().getAsJsonObject("payload")
            .getAsJsonArray("changes")[0].asJsonObject
        assertTrue(change.has("cell"))
        assertTrue(change["cell"].isJsonNull)
    }

    @Test fun `bind command has empty payload and retry retains request id`() {
        val identity = ServerPlayerIdentity.trustedForTest(PLAYER_ID)
        val command = BindEdgesCommand(REQUEST_ID, STAGE_ID, identity, EPOCH_A, 3)
        assertEquals("BIND_EDGES", command.toJson()["operation"].asString)
        assertEquals(0, command.toJson().getAsJsonObject("payload").size())
        val retriedJson = command.toJson()
        assertEquals(REQUEST_ID.toString(), retriedJson["request_id"].asString)
    }

    @Test fun `wire placement intent commands serialize strict AUTO and CROSS payloads`() {
        GraphPlacementIntent.entries.forEach { intent ->
            val command = WirePlaceIntentCommand(
                REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
                GraphPosition(2, 0, 3), intent,
            )
            val json = command.toJson()
            assertEquals("WIRE_PLACE_INTENT", json["operation"].asString)
            assertEquals(REQUEST_ID.toString(), json["request_id"].asString)
            assertEquals(EPOCH_A.toString(), json["workspace_epoch"].asString)
            assertEquals(3, json["expected_working_revision"].asLong)
            val payload = json.getAsJsonObject("payload")
            assertEquals(intent.name, payload["intent"].asString)
            assertEquals(2, payload.getAsJsonObject("position")["x"].asInt)
            assertEquals(3, payload.getAsJsonObject("position")["z"].asInt)
            assertEquals(2, payload.size())
        }
    }

    @Test fun `server identity cannot be constructed from arbitrary UUID by production callers`() {
        assertTrue(ServerPlayerIdentity::class.java.constructors.none { constructor ->
            constructor.parameterTypes.contentEquals(arrayOf(java.util.UUID::class.java))
        })
        val publicFactories = ServerPlayerIdentity.Companion::class.java.methods
            .filter { it.declaringClass == ServerPlayerIdentity.Companion::class.java }
        assertTrue(publicFactories.any { method ->
            method.name == "from" && method.parameterTypes.contentEquals(arrayOf(net.minecraft.server.level.ServerPlayer::class.java))
        })
    }
}
