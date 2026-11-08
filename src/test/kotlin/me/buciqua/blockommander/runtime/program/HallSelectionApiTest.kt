package me.buciqua.blockommander.runtime.program

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import me.buciqua.blockommander.runtime.graph.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun selectionJson(context:me.buciqua.blockommander.runtime.WorkingCopyTransportContext=TRANSPORT_CONTEXT,
    stage:UUID=STAGE_ID,revision:Long=0,changed:Boolean=false)=JsonObject().apply {
    addProperty("api_version",PROGRAM_API_VERSION);addProperty("selection_api_version","ck-hall-selection-v1")
    addProperty("status","ok");addProperty("changed",changed)
    add("selection",JsonObject().apply {
        addProperty("hall_id",context.hallId.toString());addProperty("world_uuid",WORLD_ID.toString())
        addProperty("program_id",context.programId.toString());addProperty("working_copy_id",context.workingCopyId.toString())
        addProperty("active_stage_id",stage.toString());addProperty("selection_revision",revision)
        addProperty("content_scope","SHARED_PROGRAM");addProperty("projection_state","DESIRED_ONLY")
    })
}

class HallSelectionApiTest {
    @Test fun `strict selection DTO rejects every missing field and invalid identity or version`() {
        val valid=selectionJson()
        assertEquals(STAGE_ID,HallSelectionParser.parse(valid.toString(),HALL_ID,WORLD_ID,true).selection.stageId)
        for(key in listOf("api_version","selection_api_version","status","selection","changed")) {
            val broken=valid.deepCopy().apply { remove(key) }
            assertThrows(Exception::class.java) { HallSelectionParser.parse(broken.toString(),HALL_ID,WORLD_ID,true) }
        }
        for(key in valid.getAsJsonObject("selection").keySet()) {
            val broken=valid.deepCopy();broken.getAsJsonObject("selection").remove(key)
            assertThrows(Exception::class.java) { HallSelectionParser.parse(broken.toString(),HALL_ID,WORLD_ID) }
        }
        for((key,value) in listOf("hall_id" to UUID.randomUUID().toString(),"world_uuid" to UUID.randomUUID().toString(),
            "program_id" to "1-1-1-1-1","working_copy_id" to "bad","active_stage_id" to "bad",
            "content_scope" to "ISOLATED","projection_state" to "DISPLAYED")) {
            val broken=valid.deepCopy();broken.getAsJsonObject("selection").addProperty(key,value)
            assertThrows(Exception::class.java) { HallSelectionParser.parse(broken.toString(),HALL_ID,WORLD_ID) }
        }
        for(value in listOf("-1","1.5","true","\"0\"","9223372036854775808")) {
            val broken=valid.deepCopy();broken.getAsJsonObject("selection").add("selection_revision",JsonParser.parseString(value))
            assertThrows(Exception::class.java) { HallSelectionParser.parse(broken.toString(),HALL_ID,WORLD_ID) }
        }
        for(key in listOf("api_version","selection_api_version")) {
            val broken=valid.deepCopy().apply { addProperty(key,"unknown") }
            assertThrows(Exception::class.java) { HallSelectionParser.parse(broken.toString(),HALL_ID,WORLD_ID) }
        }
    }

    @Test fun `actual HTTP GET PUT uses secret CAS and separate identity handoff without weakening G1`() {
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val seen=mutableListOf<String>();var error:String?=null
        val next=TRANSPORT_CONTEXT.copy(programId=UUID.randomUUID(),workingCopyId=UUID.randomUUID())
        server.createContext("/") { exchange ->
            assertEquals("test-secret",exchange.requestHeaders.getFirst(GRAPH_SECRET_HEADER))
            assertNull(exchange.requestHeaders.getFirst("X-BlockCommander-Hall-Id"))
            seen+=exchange.requestMethod+" "+exchange.requestURI.toString()
            val payload=exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val body=if(error!=null) """{"api_version":"ck-program-v1","status":"error","error_code":"$error","message":"Denied","diagnostics":[]}"""
                else if(exchange.requestMethod=="PUT") {
                    val obj=JsonParser.parseString(payload).asJsonObject
                    assertEquals(0,obj["expected_selection_revision"].asLong)
                    assertEquals(PLAYER_ID.toString(),obj["player_uuid"].asString)
                    assertEquals(next.programId.toString(),obj["program_id"].asString)
                    selectionJson(next,OTHER_STAGE_ID,1,true).toString()
                } else selectionJson().toString()
            val bytes=body.toByteArray();exchange.sendResponseHeaders(if(error==null) 200 else if(error=="FORBIDDEN") 403 else 409,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client=HallSelectionApiClient("http://127.0.0.1:${server.address.port}","test-secret")
            val base=(client.get(HALL_ID,WORLD_ID,PLAYER_ID).get(3,TimeUnit.SECONDS) as ProgramHttpResult.Success).value.selection
            val put=(client.put(base,next.programId,OTHER_STAGE_ID,PLAYER_ID).get(3,TimeUnit.SECONDS) as ProgramHttpResult.Success).value
            assertEquals(next,put.selection.context);assertTrue(put.changed!!)
            assertTrue(seen[0].contains("player_uuid=$PLAYER_ID"));assertTrue(seen[1].startsWith("PUT "))
            for(code in listOf("FORBIDDEN","SELECTION_CONFLICT")) {
                error=code
                assertEquals(code,(client.put(base,next.programId,OTHER_STAGE_ID,PLAYER_ID).get() as ProgramHttpResult.ApiError).error.errorCode)
            }
            assertThrows(Exception::class.java) {
                me.buciqua.blockommander.runtime.TransportContextParser.validateResponse(selectionJson(next),TRANSPORT_CONTEXT,null)
            }
        } finally { server.stop(0) }
    }

    @Test fun `unconfigured client does not issue HTTP`() {
        assertInstanceOf(ProgramHttpResult.Unavailable::class.java,
            HallSelectionApiClient("http://127.0.0.1:1",null).get(HALL_ID,WORLD_ID,PLAYER_ID).get())
    }
}
