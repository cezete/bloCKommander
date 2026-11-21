package me.buciqua.blockommander.runtime.value

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.declarations.DeclarationContext
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.program.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

class LetProtocolTest {
    private val context = DeclarationContext(id(1), id(2), WorkingCopyTransportContext(id(3),id(4),id(5)), id(6))
    private val block = id(7)
    private val target = id(8)
    private val child = id(9)
    private val basis = LetBasis(id(10),4,"ck-resolution-basis-v1:" + "a".repeat(64),id(11),3,2,1)

    @Test fun `placement and break use B1 CAS and check the resulting block identity`() = withApi({ request ->
        val body = request.requestBody.readAllBytes().decodeToString()
        val fields = JsonParser.parseString(body).asJsonObject
        assertEquals(context.player.toString(), fields["player_uuid"].asString)
        assertEquals(basis.digest, fields["expected_resolution_basis"].asString)
        assertEquals(basis.stageEpoch.toString(), fields["expected_stage_workspace_epoch"].asString)
        val req = fields["request_id"].asString
        if (request.requestURI.path.endsWith("/delete")) {
            assertTrue(request.requestURI.path.endsWith("$block/delete"))
            200 to receipt(req,5,""" "deleted_instance_id":"$block","link":null """)
        } else {
            assertEquals("/api/v1/stages/${context.stage}/blocks",request.requestURI.path)
            assertEquals("STAGE_RELATIVE",fields["coordinate_space"].asString)
            assertEquals(2,fields["x"].asInt)
            201 to receipt(req,5,""" "let":${let(null)},"stage":{"blocks":[
                {"instance_id":"$block","type_key":"LET","orientation":"NORTH","rel_x":2,"rel_y":0,"rel_z":3}]} """)
        }
    }) {
        val placed = it.place(context,GraphPosition(2,0,3),"NORTH",basis).get(4,TimeUnit.SECONDS)
        assertEquals(block,(placed as ProgramHttpResult.Success).value.block)
        val broken = it.remove(context,block,basis).get(4,TimeUnit.SECONDS)
        assertEquals(5L,(broken as ProgramHttpResult.Success).value.revision)
    }

    @Test fun `configuration binds UUID and ensure requires explicit LET role with an active matching child`() = withApi({ request ->
        val fields = JsonParser.parseString(request.requestBody.readAllBytes().decodeToString()).asJsonObject
        val req = fields["request_id"].asString
        when (request.requestMethod) {
            "PATCH" -> {
                assertEquals(target.toString(),fields["target_id"].asString)
                200 to receipt(req,5,""" "let":${let(target)} """)
            }
            else -> {
                assertEquals("LET_RHS",fields["owner_role"].asString)
                assertEquals(block.toString(),fields["program_block_instance_id"].asString)
                val link = """ {"parent_stage_id":"${context.stage}","program_block_instance_id":"$block",
                    "child_stage_id":"$child","owner_role":"LET_RHS","state":"ACTIVE"} """
                201 to receipt(req,4,""" "created":true,"link":$link,"child":{
                    "stage_id":"$child","program_id":"${context.transport.programId}","kind":"VALUE_BODY",
                    "name":"RHS","is_root":false,"saved_version":0,"value_schema_version":1,
                    "workspace":{"epoch":"${id(12)}","working_revision":0,"dirty":true},
                    "domain_layout":{"origin":{"x":1,"y":1,"z":1},"size":{"x":4,"y":4,"z":4}},
                    "parents":[$link],"children":[]} """,hierarchy=4)
            }
        }
    }) {
        val patched = it.configure(context,block,target,basis).get(4,TimeUnit.SECONDS)
        assertEquals(target,(patched as ProgramHttpResult.Success).value.target)
        val ensured = it.ensureChild(context,block,basis).get(4,TimeUnit.SECONDS)
        assertEquals(child,(ensured as ProgramHttpResult.Success).value.child.child.stageId)
        assertEquals(ProgramOwnerRole.LET_RHS,ensured.value.child.link.ownerRole)
    }

    @Test fun `stale basis and uncertain transport have different recovery meaning`() = withApi({ request ->
        val fields=JsonParser.parseString(request.requestBody.readAllBytes().decodeToString()).asJsonObject
        409 to """{"api_version":"ck-value-v1","status":"error","error_code":"STALE_VALUE_BASIS",
           "message":"Reload","request_id":"${fields["request_id"].asString}"}"""
    }) {
        val actual=it.configure(context,block,target,basis).get(4,TimeUnit.SECONDS)
        assertEquals("STALE_VALUE_BASIS",(actual as ProgramHttpResult.ApiError).error.errorCode)
        assertFalse(isUncertainLetResult(actual))
        assertTrue(isUncertainLetResult(ProgramHttpResult.Unavailable("timeout")))
        assertTrue(isUncertainLetResult(ProgramHttpResult.ProtocolFailure("wrong receipt")))
    }

    @Test fun `session accepts VAR VAL and shadowed UUIDs and rejects stale or closed intent`() {
        val key=EditorSessionKey(context.player,1,2,id(30))
        val rows=listOf(LetRow(id(31),"counter","INTEGER","VAR",context.stage,"LOCAL"),
            LetRow(id(32),"counter","INTEGER","VAL",id(33),"SHADOWED"))
        val state=LetScreenState(key,20,"minecraft:overworld",block,null,0,true,rows,"",true,false)
        for (row in rows) assertTrue(acceptsLetIntent(state,LetIntentPayload(key,20,LetAction.APPLY,row.id)))
        assertFalse(acceptsLetIntent(state,LetIntentPayload(key,19,LetAction.APPLY,rows.last().id)))
        assertFalse(acceptsLetIntent(state,LetIntentPayload(key.copy(connection=2),20,LetAction.APPLY,rows.first().id)))
        assertFalse(acceptsLetIntent(state,LetIntentPayload(key,20,LetAction.APPLY,id(99))))
        assertFalse(acceptsLetIntent(state,LetIntentPayload(key,20,LetAction.ENTER)))
        assertFalse(acceptsLetIntent(state.copy(closed=true),LetIntentPayload(key,20,LetAction.APPLY,rows.first().id)))
        val surface=EditorSurfaceFence(context.player)
        assertTrue(surface.receive("LET",key,20,false))
        surface.dismiss("LET",key)
        assertFalse(surface.receive("LET",key,21,false))
        assertTrue(surface.receive("LET",key.copy(generation=3,nonce=id(35)),22,false))
    }

    private fun let(uuid: UUID?) = """{"instance_id":"$block","type_key":"LET",
        "target_id":${uuid?.let { "\"$it\"" } ?: "null"},
        "semantic_config":${uuid?.let { """{"version":1,"target_id":"$it"}""" } ?: "{}"}}"""

    private fun receipt(req:String,revision:Long,fields:String,hierarchy:Long=3):String {
        val c=context.transport
        return """{"api_version":"ck-value-v1","status":"ok","request_id":"$req",
            "transport_context":{"mode":"explicit","content_scope":"SHARED_PROGRAM","hall_id":"${c.hallId}",
                "program_id":"${c.programId}","working_copy_id":"${c.workingCopyId}","stage_id":"${context.stage}"},
            "program_id":"${c.programId}","stage_id":"${context.stage}","stage_kind":"PROGRAM_BODY",
            "stage_workspace_epoch":"${basis.stageEpoch}","working_revision":$revision,
            "program_workspace_epoch":"${basis.programEpoch}","hierarchy_revision":$hierarchy,
            "program_saved_version":2,"stage_saved_version":1,"aggregate_dirty":true,"stage_dirty":true,
            "resolution_basis_digest":"${basis.digest}","resolution_basis":{
                "basis_version":"ck-resolution-basis-v1","program_id":"${c.programId}",
                "program_workspace_epoch":"${basis.programEpoch}","hierarchy_revision":$hierarchy,
                "query_scope":{"owner_id":"${context.stage}"},"scopes":[{"scope":{"owner_id":"${context.stage}"},
                    "workspace_epoch":"${basis.stageEpoch}","working_revision":$revision}]},$fields}"""
    }

    private fun withApi(reply:(HttpExchange)->Pair<Int,String>, run:(LetApi)->Unit) {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/") { exchange ->
            val c=context.transport
            assertEquals("secret",exchange.requestHeaders.getFirst(GRAPH_SECRET_HEADER))
            assertEquals(c.hallId.toString(),exchange.requestHeaders.getFirst(HALL_ID_HEADER))
            assertEquals(c.programId.toString(),exchange.requestHeaders.getFirst(PROGRAM_ID_HEADER))
            assertEquals(c.workingCopyId.toString(),exchange.requestHeaders.getFirst(WORKING_COPY_ID_HEADER))
            val (status,body)=reply(exchange)
            val bytes=body.encodeToByteArray()
            exchange.sendResponseHeaders(status,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { run(LetApi(ProgramApiClient.configured("http://127.0.0.1:${server.address.port}","secret"))) }
        finally { server.stop(0) }
    }

    private fun id(n:Long)=UUID(0,n)
}
