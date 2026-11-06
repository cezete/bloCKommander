package me.buciqua.blockommander.runtime.program

import com.sun.net.httpserver.HttpServer
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProgramApiClientTest {
    private var server:HttpServer?=null

    @AfterEach fun stopServer() { server?.stop(0) }

    @Test fun `shared graph secret authenticates mounted Program GET and strict DTO is parsed`() {
        val header=AtomicReference<String>()
        val path=AtomicReference<String>()
        val base=startServer { requestPath,requestHeader,_ ->
            path.set(requestPath);header.set(requestHeader);200 to mountedJson()
        }
        val result=ProgramApiClient.configured(base,"shared-secret")
            .mountedProgram(HALL).get(3,TimeUnit.SECONDS)

        val value=(result as ProgramHttpResult.Success).value
        assertEquals("shared-secret",header.get())
        assertEquals("/api/v1/halls/$HALL/program",path.get())
        assertEquals(PROGRAM,value.program.programId)
        assertEquals(ROOT,value.program.rootStageId)
        assertEquals(ProgramWorkspaceState.ACTIVE,value.program.workspaceState)
        assertEquals(ProgramLinkState.ACTIVE,value.program.links.single().state)
        assertFalse(result.toString().contains("shared-secret"))
    }

    @Test fun `missing secret performs no request`() {
        val hits=AtomicInteger()
        val base=startServer { _,_,_ -> hits.incrementAndGet();200 to mountedJson() }
        val result=ProgramApiClient.configured(base,"  ").mountedProgram(HALL).get(2,TimeUnit.SECONDS)
        assertInstanceOf(ProgramHttpResult.Unavailable::class.java,result)
        assertEquals(0,hits.get())
    }

    @Test fun `child ensure carries player and block identity and parses retained link`() {
        val body=AtomicReference<String>()
        val base=startServer { _,_,requestBody -> body.set(requestBody);200 to childJson("RETAINED") }
        val result=ProgramApiClient.configured(base,"secret")
            .ensureChild(PROGRAM,ROOT,BLOCK,OWNER).get(3,TimeUnit.SECONDS)
        val value=(result as ProgramHttpResult.Success).value
        assertTrue(body.get().contains(OWNER.toString()))
        assertTrue(body.get().contains(BLOCK.toString()))
        assertEquals(ProgramLinkState.RETAINED,value.link.state)
        assertEquals(CHILD,value.child.stageId)
    }

    @Test fun `structured domain errors and malformed success remain distinct`() {
        var response=422 to errorJson("VALIDATION_ERROR")
        val base=startServer { _,_,_ -> response }
        val client=ProgramApiClient.configured(base,"secret")
        val error=client.program(PROGRAM).get(3,TimeUnit.SECONDS) as ProgramHttpResult.ApiError
        assertEquals(422,error.error.httpStatus)
        assertEquals("VALIDATION_ERROR",error.error.errorCode)

        response=200 to mountedJson().replace("ck-program-v1","ck-program-v0")
        assertInstanceOf(ProgramHttpResult.ProtocolFailure::class.java,
            client.mountedProgram(HALL).get(3,TimeUnit.SECONDS))
    }

    @Test fun `authoritative Stage DTO keeps domain coordinates separate from Hall coordinates`() {
        val base=startServer { _,_,_ -> 200 to stageJson() }
        val value=(ProgramApiClient.configured(base,"secret").stage(PROGRAM,CHILD)
            .get(3,TimeUnit.SECONDS) as ProgramHttpResult.Success).value
        assertEquals(1,value.stage.summary.domainLayout.origin.x)
        assertEquals(2,value.stage.blocks.single().position.x)
        assertEquals(EPOCH,value.stage.summary.workspace!!.epoch)
        assertEquals(4,value.stage.summary.workspace.workingRevision)
    }

    private fun startServer(response:(String,String?,String)->Pair<Int,String>):String {
        val created=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        created.createContext("/") { exchange ->
            val requestBody=exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val (status,body)=response(
                exchange.requestURI.path,exchange.requestHeaders.getFirst(GRAPH_SECRET_HEADER),requestBody,
            )
            val bytes=body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(status,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        created.start();server=created
        return "http://127.0.0.1:${created.address.port}"
    }

    private fun mountedJson()="""
      {"api_version":"ck-program-v1","status":"ok","mount":{"hall_id":"$HALL","program_id":"$PROGRAM","current":true},
       "program":${programJson()}}
    """.trimIndent()
    private fun programJson()="""
      {"program_id":"$PROGRAM","owner_uuid":"$OWNER","root_stage_id":"$ROOT","saved_version":1,
       "aggregate_dirty":true,"hierarchy_dirty":true,"hierarchy_revision":2,"workspace_epoch":"$PROGRAM_EPOCH",
       "workspace_state":"ACTIVE","stages":[${summaryJson(ROOT,"ROOT",true)},${summaryJson(CHILD,"PROGRAM_BODY",false)}],
       "links":[${linkJson("ACTIVE")}],"mounts":[{"hall_id":"$HALL","program_id":"$PROGRAM","current":true}]}
    """.trimIndent()
    private fun childJson(state:String)="""
      {"api_version":"ck-program-v1","status":"ok","program_id":"$PROGRAM","program_saved_version":1,
       "program_workspace_epoch":"$PROGRAM_EPOCH","hierarchy_revision":2,"created":false,
       "link":${linkJson(state)},"child":${summaryJson(CHILD,"PROGRAM_BODY",false)}}
    """.trimIndent()
    private fun stageJson()="""
      {"api_version":"ck-program-v1","status":"ok","program_id":"$PROGRAM","program_saved_version":1,
       "program_workspace_epoch":"$PROGRAM_EPOCH","hierarchy_revision":2,"aggregate_dirty":true,
       "stage":${summaryJson(CHILD,"PROGRAM_BODY",false).dropLast(1)},
         "graph_snapshot_version":1,"port_catalog_version":"ck-control-v3",
         "blocks":[{"instance_id":"$BLOCK","type_key":"PROGRAM","position":{"x":2,"y":0,"z":1},
           "orientation":"EAST","label":"PROGRAM","description":null}]}}
    """.trimIndent()
    private fun summaryJson(id:UUID,kind:String,root:Boolean)="""
      {"stage_id":"$id","program_id":"$PROGRAM","kind":"$kind","name":"Stage","is_root":$root,"saved_version":0,
       "workspace":{"epoch":"$EPOCH","working_revision":4,"dirty":true},
       "domain_layout":{"origin":{"x":1,"y":1,"z":1},"size":{"x":7,"y":5,"z":7}},
       "parents":[],"children":[]}
    """.trimIndent()
    private fun linkJson(state:String)="""
      {"parent_stage_id":"$ROOT","program_block_instance_id":"$BLOCK","child_stage_id":"$CHILD","state":"$state"}
    """.trimIndent()
    private fun errorJson(code:String)="""
      {"api_version":"ck-program-v1","status":"error","error_code":"$code","message":"hiba",
       "diagnostics":[{"code":"$code","severity":"ERROR","message":"hiba","field":null}]}
    """.trimIndent()

    companion object {
        private val HALL=UUID.fromString("10000000-0000-0000-0000-000000000001")
        private val PROGRAM=UUID.fromString("10000000-0000-0000-0000-000000000002")
        private val ROOT=UUID.fromString("10000000-0000-0000-0000-000000000003")
        private val CHILD=UUID.fromString("10000000-0000-0000-0000-000000000004")
        private val BLOCK=UUID.fromString("10000000-0000-0000-0000-000000000005")
        private val OWNER=UUID.fromString("10000000-0000-0000-0000-000000000006")
        private val EPOCH=UUID.fromString("10000000-0000-0000-0000-000000000007")
        private val PROGRAM_EPOCH=UUID.fromString("10000000-0000-0000-0000-000000000008")
    }
}
