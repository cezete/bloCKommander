package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpRequest
import java.util.UUID

class TransportContextTest {
    private val context = WorkingCopyTransportContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val stage = UUID.randomUUID()
    private fun response(value: WorkingCopyTransportContext = context) = JsonObject().apply {
        add("transport_context", JsonObject().apply {
            addProperty("mode", "explicit")
            addProperty("content_scope", "SHARED_PROGRAM")
            addProperty("hall_id", value.hallId.toString())
            addProperty("program_id", value.programId.toString())
            addProperty("working_copy_id", value.workingCopyId.toString())
            addProperty("stage_id", stage.toString())
        })
    }

    @Test fun `headers carry the initiating Hall identity`() {
        val request = context.addHeaders(HttpRequest.newBuilder(URI("http://localhost/"))).GET().build()
        assertEquals(context.hallId.toString(), request.headers().firstValue(HALL_ID_HEADER).get())
        assertEquals(context.programId.toString(), request.headers().firstValue(PROGRAM_ID_HEADER).get())
        assertEquals(context.workingCopyId.toString(), request.headers().firstValue(WORKING_COPY_ID_HEADER).get())
        TransportContextParser.validateResponse(response(), context, stage)
    }

    @Test fun `same Program and Stage in another Hall is rejected`() {
        val other = context.copy(hallId = UUID.randomUUID(), workingCopyId = UUID.randomUUID())
        assertThrows(TransportContextProtocolException::class.java) {
            TransportContextParser.validateResponse(response(other), context, stage)
        }
    }

    @Test fun `Hall registry keeps distinct mount identities for shared Stage`() {
        val registry=HallRegistry()
        val other=context.copy(hallId=UUID.randomUUID(),workingCopyId=UUID.randomUUID())
        registry.install(hall(context))
        registry.install(hall(other))
        assertEquals(context,registry.transportContext(context.hallId.toString()))
        assertEquals(other,registry.transportContext(other.hallId.toString()))
        val invalid=hall(context.copy(hallId=UUID.randomUUID()))
        invalid.remove("working_copy_id")
        assertThrows(TransportContextProtocolException::class.java) { registry.install(invalid) }
        assertNull(registry.hall(invalid["hall_id"].asString))
    }

    private fun hall(value:WorkingCopyTransportContext)=JsonParser.parseString("""
        {"hall_id":"${value.hallId}","program_id":"${value.programId}","working_copy_id":"${value.workingCopyId}",
         "hall_name":"Hall","owner_uuid":"${context.hallId}","owner_name":"Owner","world_uuid":"${context.programId}",
         "material_name":"STONE","min_x":0,"min_y":0,"min_z":0,"max_x":10,"max_y":10,"max_z":10,
         "door":{"x":0,"y":1,"z":1},"orientation":"NORTH","controls":[],
         "stage":{"stage_id":"$stage","origin":{"x":1,"y":1,"z":1},"size":{"x":7,"y":5,"z":7},
          "dirty":true,"workspace_epoch":"${context.workingCopyId}","working_revision":0,"blocks":[]}}
    """).asJsonObject

    @Test fun `foreign Program WorkingCopy and Stage are rejected independently`() {
        for (other in listOf(context.copy(programId=UUID.randomUUID()), context.copy(workingCopyId=UUID.randomUUID()))) {
            assertThrows(TransportContextProtocolException::class.java) {
                TransportContextParser.validateResponse(response(other), context, stage)
            }
        }
        assertThrows(TransportContextProtocolException::class.java) {
            TransportContextParser.validateResponse(response(), context, UUID.randomUUID())
        }
    }

    @Test fun `missing malformed and legacy response context never succeeds`() {
        assertThrows(TransportContextProtocolException::class.java) {
            TransportContextParser.validateResponse(JsonObject(), context, stage)
        }
        for (field in listOf("hall_id", "program_id", "working_copy_id", "stage_id", "mode", "content_scope")) {
            val body=response()
            body.getAsJsonObject("transport_context").addProperty(field, "invalid")
            assertThrows(TransportContextProtocolException::class.java) {
                TransportContextParser.validateResponse(body, context, stage)
            }
        }
    }
}
