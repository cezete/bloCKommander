package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import java.net.http.HttpRequest
import java.util.UUID

const val HALL_ID_HEADER = "X-BlockCommander-Hall-Id"
const val PROGRAM_ID_HEADER = "X-BlockCommander-Program-Id"
const val WORKING_COPY_ID_HEADER = "X-BlockCommander-Working-Copy-Id"
const val SHARED_PROGRAM_SCOPE = "SHARED_PROGRAM"

data class WorkingCopyTransportContext(
    val hallId: UUID,
    val programId: UUID,
    val workingCopyId: UUID,
) {
    fun addHeaders(builder: HttpRequest.Builder): HttpRequest.Builder = builder
        .header(HALL_ID_HEADER, hallId.toString())
        .header(PROGRAM_ID_HEADER, programId.toString())
        .header(WORKING_COPY_ID_HEADER, workingCopyId.toString())
}

class TransportContextProtocolException(message: String) : RuntimeException(message)

object TransportContextParser {
    fun fromHall(json: JsonObject): WorkingCopyTransportContext {
        val hallId = uuid(json, "hall_id")
        return WorkingCopyTransportContext(
            hallId,
            uuid(json, "program_id"),
            uuid(json, "working_copy_id"),
        )
    }

    fun validateResponse(
        root: JsonObject,
        expected: WorkingCopyTransportContext,
        expectedStageId: UUID? = null,
    ) {
        val value = obj(root, "transport_context")
        val actual = WorkingCopyTransportContext(
            uuid(value, "hall_id"),
            uuid(value, "program_id"),
            uuid(value, "working_copy_id"),
        )
        if (string(value, "mode") != "explicit") fail("A válasz nem explicit transport-contextet igazol.")
        if (string(value, "content_scope") != SHARED_PROGRAM_SCOPE) fail("Ismeretlen transport content scope.")
        if (actual != expected) fail("A válasz Hall–Program–WorkingCopy identityje eltér a kérésétől.")
        if (expectedStageId != null && uuid(value, "stage_id") != expectedStageId) {
            fail("A válasz Stage identityje eltér a kérésétől.")
        }
    }

    private fun obj(value: JsonObject, name: String): JsonObject {
        val element=value.get(name) ?: fail("Hiányzó transport-context mező: $name")
        if (!element.isJsonObject) fail("Hibás transport-context objektummező: $name")
        return element.asJsonObject
    }

    private fun string(value: JsonObject, name: String): String = try {
        val element = value.get(name) ?: fail("Hiányzó transport-context mező: $name")
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) fail("Hibás transport-context szövegmező: $name")
        element.asString
    } catch (_: UnsupportedOperationException) {
        fail("Hibás transport-context szövegmező: $name")
    }

    private fun uuid(value: JsonObject, name: String): UUID = try {
        val text=string(value,name)
        val parsed=UUID.fromString(text)
        if (!parsed.toString().equals(text,ignoreCase=true)) fail("Hibás transport-context UUID mező: $name")
        parsed
    } catch (_: IllegalArgumentException) {
        fail("Hibás transport-context UUID mező: $name")
    }

    private fun fail(message: String): Nothing = throw TransportContextProtocolException(message)
}
