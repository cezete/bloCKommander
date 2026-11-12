package me.buciqua.blockommander.runtime.editor

import com.google.gson.JsonNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlockEditorDtosTest {
    private val f = BlockEditorFixtures
    private fun rejected(change: (com.google.gson.JsonObject) -> Unit) {
        val json = f.json(); change(json)
        assertThrows(BlockEditorProtocolException::class.java) { BlockEditorResponseParser.get(json.toString()) }
    }

    @Test fun `actual B2 START and STOP full schemas parse`() {
        for ((name, type) in listOf("start-get" to EditorBlockType.START, "stop-get" to EditorBlockType.STOP)) {
            val result = BlockEditorResponseParser.get(f.fixture(name))
            assertEquals(type, result.target.blockType)
            assertEquals("ck.block.$type.metadata", result.schemaRef.schemaId)
            assertEquals(1L, result.schemaRef.schemaVersion)
            assertEquals(listOf("label", "description"), result.schema.fields.map { it.key })
            val label = result.schema.fields.first()
            assertEquals(EditorFieldType.STRING, label.type)
            assertEquals(EditorDefaultKind.NONE, label.defaultKind)
            assertEquals(32L, label.constraints.maxLength)
            assertEquals("PYTHON_STRIP_V1", label.normalization.trim)
            assertEquals("Felirat", label.presentation.label)
            assertTrue(label.required && !label.nullable && label.readable && label.editable)
            assertTrue(result.permissions.read && result.permissions.apply)
            assertEquals(12L, result.versionContext.workingRevision)
            assertEquals(f.workingCopy, result.transportContext.context.workingCopyId)
        }
    }

    @Test fun `nullable description requires present property and Unicode is preserved`() {
        val json = f.json()
        json.getAsJsonObject("values").add("description", JsonNull.INSTANCE)
        json.getAsJsonObject("values").addProperty("label", "😀e\u0301")
        val result = BlockEditorResponseParser.get(json.toString())
        assertNull(result.values.description)
        assertEquals("😀e\u0301", result.values.label)
        json.getAsJsonObject("values").remove("description")
        assertThrows(BlockEditorProtocolException::class.java) { BlockEditorResponseParser.get(json.toString()) }
    }

    @Test fun `unknown field type is rejected instead of string fallback`() {
        rejected { it.getAsJsonObject("schema").getAsJsonArray("fields")[0].asJsonObject.addProperty("type", "BOOLEAN") }
    }
    @Test fun `unknown schema versions and schema target mismatches are rejected`() {
        rejected { it.getAsJsonObject("schema_ref").addProperty("schema_version", 2) }
        rejected { it.getAsJsonObject("schema").addProperty("schema_version", 2) }
        rejected { it.getAsJsonObject("schema_ref").addProperty("schema_id", "ck.block.STOP.metadata") }
        rejected { it.getAsJsonObject("schema").addProperty("block_type", "STOP") }
    }
    @Test fun `required result fields cannot be omitted`() {
        for ((key, _) in f.json().entrySet()) rejected { it.remove(key) }
    }
    @Test fun `required nested schema fields cannot be omitted`() {
        val field = f.json().getAsJsonObject("schema").getAsJsonArray("fields")[0].asJsonObject
        for ((key, _) in field.entrySet()) rejected {
            it.getAsJsonObject("schema").getAsJsonArray("fields")[0].asJsonObject.remove(key)
        }
    }
    @Test fun `integer parser rejects booleans strings fractions exponent negative and overflow`() {
        for (raw in listOf("true", "\"12\"", "12.0", "12e0", "-1", "9223372036854775808")) {
            val text = f.fixture("start-get").replace("\"working_revision\": 12", "\"working_revision\": $raw")
            assertThrows(BlockEditorProtocolException::class.java) { BlockEditorResponseParser.get(text) }
        }
    }
    @Test fun `boolean and UUID fields are strict`() {
        rejected { it.getAsJsonObject("permissions").addProperty("apply", "true") }
        rejected { it.getAsJsonObject("version_context").addProperty("stage_dirty", 1) }
        rejected { it.getAsJsonObject("target").addProperty("instance_id", "1-1-1-1-1") }
        rejected { it.getAsJsonObject("transport_context").addProperty("hall_id", "bad") }
    }
    @Test fun `missing bad API version and unknown status are protocol errors`() {
        rejected { it.remove("api_version") }
        rejected { it.addProperty("api_version", "ck-block-editor-v2") }
        rejected { it.addProperty("api_version", 1) }
        rejected { it.addProperty("status", "pending") }
        rejected { it.addProperty("status", "error") }
    }
    @Test fun `structured diagnostics optional field details and request ID parse`() {
        val result = BlockEditorResponseParser.error(422, f.error(422))
        assertEquals(422, result.httpStatus)
        assertEquals(f.requestId, result.requestId)
        assertEquals("label", result.diagnostics.single().field)
        assertEquals(33L, result.diagnostics.single().details["actual_length"])
        val generic = """{"api_version":"ck-block-editor-v1","status":"error","error_code":"INVALID_GRAPH_AUTH","message":"Denied","diagnostics":[{"code":"INVALID_GRAPH_AUTH","message":"Denied"}]}"""
        assertNull(BlockEditorResponseParser.error(401, generic).requestId)
        assertNull(BlockEditorResponseParser.error(401, generic).diagnostics.single().field)
    }
    @Test fun `explicit transport scope and target consistency are mandatory`() {
        rejected { it.getAsJsonObject("transport_context").addProperty("mode", "legacy_program") }
        rejected { it.getAsJsonObject("transport_context").addProperty("content_scope", "PRIVATE") }
        rejected { it.getAsJsonObject("transport_context").addProperty("stage_id", f.start.toString()) }
        rejected { it.getAsJsonObject("transport_context").addProperty("program_id", f.start.toString()) }
    }
    @Test fun `malformed lenient duplicate and oversized JSON fail closed`() {
        for (text in listOf("{", "[]", "null", "{} trailing", "{unquoted: true}", "{\"x\":1,\"x\":2}",
            "{\"x\":NaN}", " ".repeat(262145), "[".repeat(70) + "0" + "]".repeat(70))) {
            assertThrows(BlockEditorProtocolException::class.java) { BlockEditorResponseParser.get(text) }
        }
        val duplicate = f.fixture("start-get").replace("\"label\": \"Indulás\"", "\"label\": \"a\", \"label\": \"b\"")
        assertThrows(BlockEditorProtocolException::class.java) { BlockEditorResponseParser.get(duplicate) }
    }
    @Test fun `APPLY receipt parses exact B2 flattening`() {
        val result = BlockEditorResponseParser.apply(f.fixture("start-apply"))
        assertEquals(f.requestId, result.requestId)
        assertTrue(result.modified)
        assertEquals(13L, result.editor.versionContext.workingRevision)
        assertEquals("HTTP edited", result.editor.values.label)
    }
    @Test fun `parsed collections are immutable`() {
        val view = BlockEditorResponseParser.get(f.fixture("start-get"))
        assertThrows(UnsupportedOperationException::class.java) { (view.schema.fields as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (view.permissions.reasonCodes as MutableList).add("bad") }
    }
    @Test fun `changes preserve missing versus null without client normalization`() {
        val changes = EditorChanges(EditorStringChange.Value(" Raw "), EditorStringChange.Clear).toJson()
        assertEquals(" Raw ", changes["label"].asString)
        assertTrue(changes["description"].isJsonNull)
        assertFalse(EditorChanges(description = EditorStringChange.Value("text")).toJson().has("label"))
        assertThrows(IllegalArgumentException::class.java) { EditorChanges() }
        assertThrows(IllegalArgumentException::class.java) { EditorChanges(label = EditorStringChange.Clear) }
    }
}
