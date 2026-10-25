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

    @Test fun `bind command has empty payload and retry retains request id`() {
        val identity = ServerPlayerIdentity.trustedForTest(PLAYER_ID)
        val command = BindEdgesCommand(REQUEST_ID, STAGE_ID, identity, EPOCH_A, 3)
        assertEquals("BIND_EDGES", command.toJson()["operation"].asString)
        assertEquals(0, command.toJson().getAsJsonObject("payload").size())
        val retriedJson = command.toJson()
        assertEquals(REQUEST_ID.toString(), retriedJson["request_id"].asString)
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
