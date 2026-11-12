package me.buciqua.blockommander.runtime.editor

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import me.buciqua.blockommander.runtime.SHARED_PROGRAM_SCOPE
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import java.io.StringReader
import java.util.Collections
import java.util.UUID

internal const val BLOCK_EDITOR_API_VERSION = "ck-block-editor-v1"
internal enum class EditorBlockType { START, STOP }
internal enum class EditorFieldType { STRING }
internal enum class EditorDefaultKind { NONE }
internal data class EditorTarget(val programId: UUID, val stageId: UUID, val instanceId: UUID, val blockType: EditorBlockType)
internal data class EditorSchemaRef(val schemaId: String, val schemaVersion: Long)
internal data class EditorConstraints(val minLength: Long, val maxLength: Long, val lengthUnit: String)
internal data class EditorNormalization(val trim: String, val unicodeNormalization: String, val emptyToNull: Boolean)
internal data class EditorPresentation(val label: String, val order: Long)
internal data class EditorFieldSchema(
    val key: String, val type: EditorFieldType, val required: Boolean, val nullable: Boolean,
    val defaultKind: EditorDefaultKind, val constraints: EditorConstraints,
    val normalization: EditorNormalization, val presentation: EditorPresentation,
    val readable: Boolean, val editable: Boolean,
)
internal data class EditorSchema(val reference: EditorSchemaRef, val blockType: EditorBlockType, val fields: List<EditorFieldSchema>)
internal data class EditorValues(val label: String, val description: String?)
internal data class EditorPermissions(val read: Boolean, val apply: Boolean, val reasonCodes: List<String>)
internal data class EditorVersionContext(
    val stageWorkspaceEpoch: UUID, val workingRevision: Long, val stageSavedVersion: Long,
    val programSavedVersion: Long, val stageDirty: Boolean, val programDirty: Boolean,
)
internal data class EditorTransportEcho(val context: WorkingCopyTransportContext, val stageId: UUID)
internal data class EditorGetResult(
    val target: EditorTarget, val schema: EditorSchema, val schemaRef: EditorSchemaRef,
    val values: EditorValues, val permissions: EditorPermissions,
    val versionContext: EditorVersionContext, val transportContext: EditorTransportEcho,
)
internal data class EditorApplyResult(val editor: EditorGetResult, val requestId: UUID, val modified: Boolean)
internal data class EditorFieldDiagnostic(val field: String?, val code: String, val message: String, val details: Map<String, Long>)
internal data class EditorApiError(
    val httpStatus: Int, val errorCode: String, val message: String,
    val requestId: UUID?, val diagnostics: List<EditorFieldDiagnostic>,
)

/** Missing is different from explicit JSON null. Values are never normalized here. */
internal sealed interface EditorStringChange {
    data object Missing : EditorStringChange
    data object Clear : EditorStringChange
    data class Value(val text: String) : EditorStringChange
}
internal data class EditorChanges(
    val label: EditorStringChange = EditorStringChange.Missing,
    val description: EditorStringChange = EditorStringChange.Missing,
) {
    init {
        require(label != EditorStringChange.Clear) { "label cannot be null" }
        require(label != EditorStringChange.Missing || description != EditorStringChange.Missing) { "Empty changes" }
    }
    fun toJson(): JsonObject = JsonObject().also { result ->
        fun add(key: String, change: EditorStringChange) {
            when (change) {
                EditorStringChange.Missing -> Unit
                EditorStringChange.Clear -> result.add(key, JsonNull.INSTANCE)
                is EditorStringChange.Value -> result.addProperty(key, change.text)
            }
        }
        add("label", label); add("description", description)
    }
}
internal data class EditorApplyBasis(val schema: EditorSchemaRef, val workspaceEpoch: UUID, val workingRevision: Long) {
    init { require(schema.schemaVersion == 1L && workingRevision >= 0) }
}

/** Server-only snapshot, not a network payload or an authorization/session token. */
internal data class BlockEditorRequestContext(
    val playerUuid: UUID, val worldUuid: UUID, val transport: WorkingCopyTransportContext,
    val stageId: UUID, val instanceId: UUID, val blockType: EditorBlockType?, val requestId: UUID,
    val applyBasis: EditorApplyBasis? = null,
)
internal data class EditorApplyRequest(val context: BlockEditorRequestContext, val changes: EditorChanges) {
    fun toJson(): JsonObject {
        val basis = requireNotNull(context.applyBasis) { "APPLY requires captured versions" }
        require(context.blockType != null && basis.schema.schemaId == "ck.block.${context.blockType}.metadata")
        return JsonObject().apply {
            addProperty("api_version", BLOCK_EDITOR_API_VERSION)
            addProperty("request_id", context.requestId.toString())
            addProperty("player_uuid", context.playerUuid.toString())
            addProperty("schema_id", basis.schema.schemaId)
            addProperty("schema_version", basis.schema.schemaVersion)
            addProperty("expected_stage_workspace_epoch", basis.workspaceEpoch.toString())
            addProperty("expected_working_revision", basis.workingRevision)
            add("changes", changes.toJson())
        }
    }
}

internal class BlockEditorProtocolException(message: String) : RuntimeException(message)

internal object BlockEditorResponseParser {
    fun get(text: String): EditorGetResult = view(success(text))
    fun apply(text: String): EditorApplyResult = success(text).let {
        EditorApplyResult(view(it), it.uuid("request_id"), it.bool("modified"))
    }
    fun error(status: Int, text: String): EditorApiError {
        checkProtocol(status in 400..599, "Unexpected HTTP error status")
        val root = envelope(text, "error")
        val requestId = if (root.has("request_id")) root.uuid("request_id") else null
        val diagnostics = root.array("diagnostics").map { element ->
            val d = element.objectValue("diagnostic")
            val field = if (d.has("field")) d.nullableString("field") else null
            val details = if (d.has("details")) d.obj("details").entrySet().associate { (key, value) ->
                key to value.nonnegativeLong("details.$key")
            } else emptyMap()
            EditorFieldDiagnostic(field, d.string("code"), d.string("message"), Collections.unmodifiableMap(details))
        }
        return EditorApiError(status, root.string("error_code"), root.string("message"), requestId, immutable(diagnostics))
    }

    private fun success(text: String) = envelope(text, "ok")
    private fun envelope(text: String, status: String): JsonObject {
        val root = strictJson(text)
        checkProtocol(root.string("api_version") == BLOCK_EDITOR_API_VERSION, "Unsupported editor API version")
        checkProtocol(root.string("status") == status, "HTTP/body status mismatch")
        return root
    }
    private fun view(root: JsonObject): EditorGetResult {
        val t = root.obj("target")
        val type = blockType(t.string("block_type"))
        val target = EditorTarget(t.uuid("program_id"), t.uuid("stage_id"), t.uuid("instance_id"), type)
        val s = root.obj("schema")
        val ref = schemaRef(root.obj("schema_ref"))
        checkProtocol(schemaRef(s) == ref && blockType(s.string("block_type")) == type, "Schema reference/type mismatch")
        checkProtocol(ref.schemaId == "ck.block.$type.metadata", "Schema ID does not match target")
        val fields = s.array("fields").map { field(it.objectValue("field")) }
        checkProtocol(fields.map { it.key }.toSet() == setOf("label", "description") && fields.size == 2, "Unsupported schema fields")
        val v = root.obj("values")
        val values = EditorValues(v.string("label"), v.nullableString("description"))
        val p = root.obj("permissions")
        val permissions = EditorPermissions(p.bool("read"), p.bool("apply"), immutable(p.array("reason_codes").map { it.stringValue("reason code") }))
        val c = root.obj("version_context")
        val version = EditorVersionContext(c.uuid("stage_workspace_epoch"), c.long("working_revision"),
            c.long("stage_saved_version"), c.long("program_saved_version"), c.bool("stage_dirty"), c.bool("program_dirty"))
        val echo = root.obj("transport_context")
        checkProtocol(echo.string("mode") == "explicit" && echo.string("content_scope") == SHARED_PROGRAM_SCOPE, "Unsupported transport scope")
        val transport = EditorTransportEcho(WorkingCopyTransportContext(echo.uuid("hall_id"), echo.uuid("program_id"),
            echo.uuid("working_copy_id")), echo.uuid("stage_id"))
        checkProtocol(transport.context.programId == target.programId && transport.stageId == target.stageId, "Target/echo mismatch")
        return EditorGetResult(target, EditorSchema(ref, type, immutable(fields)), ref, values, permissions, version, transport)
    }
    private fun field(f: JsonObject): EditorFieldSchema {
        checkProtocol(f.string("type") == "STRING", "Unsupported field type")
        checkProtocol(f.obj("default").string("kind") == "NONE", "Unsupported field default")
        val c = f.obj("constraints")
        val constraints = EditorConstraints(c.long("min_length"), c.long("max_length"), c.string("length_unit"))
        checkProtocol(constraints.minLength <= constraints.maxLength && constraints.lengthUnit == "UNICODE_CODE_POINT", "Unsupported length constraints")
        val n = f.obj("normalization")
        val normalization = EditorNormalization(n.string("trim"), n.string("unicode_normalization"), n.bool("empty_to_null"))
        checkProtocol(normalization.trim == "PYTHON_STRIP_V1" && normalization.unicodeNormalization == "NONE", "Unsupported normalization")
        val p = f.obj("presentation")
        return EditorFieldSchema(f.string("key"), EditorFieldType.STRING, f.bool("required"), f.bool("nullable"),
            EditorDefaultKind.NONE, constraints, normalization, EditorPresentation(p.string("label"), p.long("order")),
            f.bool("readable"), f.bool("editable"))
    }
    private fun schemaRef(o: JsonObject): EditorSchemaRef {
        val version = o.long("schema_version")
        checkProtocol(version == 1L, "Unsupported schema version")
        return EditorSchemaRef(o.string("schema_id"), version)
    }
    private fun blockType(text: String): EditorBlockType = when (text) {
        "START" -> EditorBlockType.START
        "STOP" -> EditorBlockType.STOP
        else -> fail("Unsupported block type")
    }

    // Gson's ordinary tree parser accepts duplicate members. Strictly read the
    // tree ourselves so ambiguous or lenient JSON never becomes a successful DTO.
    private fun strictJson(text: String): JsonObject = try {
        checkProtocol(text.length <= 262144, "Editor response too large")
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            fun read(depth: Int): JsonElement {
                checkProtocol(depth <= 64, "Editor JSON nesting too deep")
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            checkProtocol(!obj.has(key), "Duplicate JSON member")
                            obj.add(key, read(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
                        reader.beginArray()
                        while (reader.hasNext()) array.add(read(depth + 1))
                        reader.endArray()
                    }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NUMBER -> JsonParser.parseString(reader.nextString())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    else -> fail("Invalid JSON value")
                }
            }
            val result = read(0).objectValue("response")
            checkProtocol(reader.peek() == JsonToken.END_DOCUMENT, "Trailing JSON content")
            result
        }
    } catch (error: BlockEditorProtocolException) { throw error }
      catch (_: Exception) { fail("Malformed editor JSON") }

    private fun JsonObject.required(name: String) = get(name) ?: fail("Missing $name")
    private fun JsonObject.obj(name: String) = required(name).objectValue(name)
    private fun JsonElement.objectValue(name: String): JsonObject {
        checkProtocol(isJsonObject, "Expected object: $name"); return asJsonObject
    }
    private fun JsonObject.array(name: String): JsonArray {
        val value = required(name); checkProtocol(value.isJsonArray, "Expected array: $name"); return value.asJsonArray
    }
    private fun JsonElement.stringValue(name: String): String {
        checkProtocol(isJsonPrimitive && asJsonPrimitive.isString, "Expected string: $name"); return asString
    }
    private fun JsonObject.string(name: String) = required(name).stringValue(name)
    private fun JsonObject.nullableString(name: String): String? = required(name).let { if (it.isJsonNull) null else it.stringValue(name) }
    private fun JsonObject.bool(name: String): Boolean = required(name).let {
        checkProtocol(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean, "Expected boolean: $name"); it.asBoolean
    }
    private fun JsonElement.nonnegativeLong(name: String): Long {
        checkProtocol(isJsonPrimitive && asJsonPrimitive.isNumber && Regex("0|[1-9][0-9]*").matches(asString), "Expected integer: $name")
        return asString.toLongOrNull() ?: fail("Integer overflow: $name")
    }
    private fun JsonObject.long(name: String) = required(name).nonnegativeLong(name)
    private fun JsonObject.uuid(name: String): UUID {
        val text = string(name)
        val parsed = try { UUID.fromString(text) } catch (_: IllegalArgumentException) { fail("Invalid UUID: $name") }
        checkProtocol(parsed.toString().equals(text, ignoreCase = true), "Invalid UUID: $name")
        return parsed
    }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    private fun checkProtocol(condition: Boolean, message: String) { if (!condition) fail(message) }
    private fun fail(message: String): Nothing = throw BlockEditorProtocolException(message)
}
