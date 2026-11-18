package me.buciqua.blockommander.runtime.declarations

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import java.io.StringReader
import java.util.UUID

internal const val DECLARATION_API = "ck-declarations-v1"
internal const val DECLARATION_BASIS = "ck-resolution-basis-v1"
internal data class DeclarationContext(val player: UUID, val world: UUID, val transport: WorkingCopyTransportContext, val stage: UUID)
enum class DeclarationType { BOOLEAN, INTEGER, STRING }
enum class DeclarationMutability { IMMUTABLE, MUTABLE }
enum class DeclarationGroup { OWN, LOCAL, INHERITED, SHADOWED }
data class DeclarationFields(val name: String, val type: DeclarationType, val mutability: DeclarationMutability)
data class DeclarationRow(val id: UUID, val ownerStage: UUID, val fields: DeclarationFields,
    val group: DeclarationGroup, val editable: Boolean, val shadowedBy: UUID? = null)
internal data class DeclarationVersion(val epoch: UUID, val revision: Long, val stageSaved: Long,
    val programSaved: Long, val stageDirty: Boolean, val programDirty: Boolean)
internal data class DeclarationPage(val version: DeclarationVersion, val programEpoch: UUID, val hierarchy: Long,
    val basisDigest: String?, val scopeAvailable: Boolean, val canCreate: Boolean, val canEdit: Boolean,
    val index: Int, val total: Int, val token: String, val hasNext: Boolean, val rows: List<DeclarationRow>)
internal data class DeclarationReceipt(val request: UUID, val operation: String, val row: DeclarationRow,
    val modified: Boolean, val previous: DeclarationVersion, val version: DeclarationVersion)
internal sealed interface DeclarationResult<out T> {
    data class Success<T>(val value: T) : DeclarationResult<T>
    data class Rejected(val status: Int, val code: String) : DeclarationResult<Nothing>
    data class Failure(val code: String, val unknown: Boolean = false) : DeclarationResult<Nothing>
}

/** Transport validation only. Scope resolution and declaration validation stay in Python. */
internal object DeclarationJson {
    fun parse(text: String, limit: Int = 16384): JsonObject {
        require(text.toByteArray(Charsets.UTF_8).size <= limit) { "RESPONSE_TOO_LARGE" }
        JsonReader(StringReader(text)).use { r ->
            r.strictness = Strictness.STRICT
            fun read(depth: Int): JsonElement {
                require(depth <= 64)
                return when (r.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().also { o ->
                        r.beginObject()
                        while (r.hasNext()) { val key = r.nextName(); require(!o.has(key)); o.add(key, read(depth + 1)) }
                        r.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().also { a -> r.beginArray(); while (r.hasNext()) a.add(read(depth + 1)); r.endArray() }
                    JsonToken.STRING -> JsonPrimitive(r.nextString())
                    JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
                    JsonToken.NUMBER -> { val n = r.nextString(); require(Regex("-?(0|[1-9][0-9]*)").matches(n)); JsonPrimitive(n.toLong()) }
                    JsonToken.NULL -> { r.nextNull(); JsonNull.INSTANCE }
                    else -> error("INVALID_JSON")
                }
            }
            val root = read(0).asJsonObject; require(r.peek() == JsonToken.END_DOCUMENT); return root
        }
    }
    fun JsonObject.keys(vararg keys: String) { require(keySet() == keys.toSet()) { "INVALID_FIELDS" } }
    fun JsonObject.obj(key: String) = get(key).also { require(it != null && it.isJsonObject) }.asJsonObject
    fun JsonObject.text(key: String, max: Int = 128): String = get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString.also { s -> require(s.length <= max && Charsets.UTF_8.newEncoder().canEncode(s)) }
    }
    fun JsonObject.flag(key: String): Boolean = get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean
    }
    fun JsonObject.number(key: String): Long = get(key).let {
        require(it != null && it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        require(Regex("0|[1-9][0-9]*").matches(it.asString)); it.asString.toLong()
    }
    fun JsonObject.uuid(key: String): UUID = uuidText(text(key, 36))
    fun uuidText(text: String): UUID = UUID.fromString(text).also { require(it.toString().equals(text, true)) }
    private fun JsonObject.nullText(key: String, max: Int = 128): String? {
        require(has(key)); return if (get(key).isJsonNull) null else text(key, max)
    }
    private fun JsonObject.scope(key: String): UUID = obj(key).let { it.keys("kind", "owner_id"); require(it.text("kind") == "STAGE"); it.uuid("owner_id") }
    fun version(o: JsonObject): DeclarationVersion = with(o) {
        keys("stage_workspace_epoch", "working_revision", "stage_saved_version", "program_saved_version", "stage_dirty", "program_dirty")
        DeclarationVersion(uuid("stage_workspace_epoch"), number("working_revision"), number("stage_saved_version"),
            number("program_saved_version"), flag("stage_dirty"), flag("program_dirty")).also { require(!it.stageDirty || it.programDirty) }
    }
    private fun envelope(root: JsonObject, status: String) {
        require(root.text("api_version") == DECLARATION_API && root.text("status") == status)
    }
    private fun identity(root: JsonObject, c: DeclarationContext) {
        root.obj("target").also { it.keys("program_id", "stage_id"); require(it.uuid("program_id") == c.transport.programId && it.uuid("stage_id") == c.stage) }
        root.obj("transport_context").also {
            it.keys("mode", "program_id", "content_scope", "hall_id", "working_copy_id", "stage_id")
            require(it.text("mode") == "explicit" && it.text("content_scope") == "SHARED_PROGRAM")
            require(it.uuid("hall_id") == c.transport.hallId && it.uuid("program_id") == c.transport.programId &&
                it.uuid("working_copy_id") == c.transport.workingCopyId && it.uuid("stage_id") == c.stage)
        }
    }
    private fun declaration(o: JsonObject, group: DeclarationGroup, stage: UUID, editable: Boolean, shadowed: UUID? = null): DeclarationRow {
        o.keys("declaration_id", "scope", "name", "type_key", "mutability")
        val owner = o.scope("scope")
        require((group in setOf(DeclarationGroup.OWN, DeclarationGroup.LOCAL)) == (owner == stage))
        return DeclarationRow(o.uuid("declaration_id"), owner,
            DeclarationFields(o.text("name", 64), DeclarationType.valueOf(o.text("type_key")), DeclarationMutability.valueOf(o.text("mutability"))),
            group, editable, shadowed)
    }
    fun page(text: String, c: DeclarationContext, endpoint: String, expectedPage: Int, token: String?): DeclarationPage {
        val root = parse(text); envelope(root, "ok"); identity(root, c)
        root.keys("api_version", "status", "target", "transport_context", "version_context", "resolution_basis", "resolution_basis_digest",
            "scope_status", "scope_error_code", "program_workspace_epoch", "hierarchy_revision", "capabilities", "page", "rows")
        val v = version(root.obj("version_context")); val epoch = root.uuid("program_workspace_epoch"); val hierarchy = root.number("hierarchy_revision")
        val available = when (root.text("scope_status")) { "AVAILABLE" -> true; "UNAVAILABLE" -> false; else -> error("INVALID_SCOPE_STATUS") }
        val basis = root.nullText("resolution_basis_digest")
        if (available) {
            require(root.get("scope_error_code").isJsonNull && basis != null && validDigest(basis))
            val b = root.obj("resolution_basis")
            b.keys("basis_version", "program_id", "query_scope", "policy_version", "program_workspace_epoch", "hierarchy_revision", "scopes")
            require(b.text("basis_version") == DECLARATION_BASIS && b.uuid("program_id") == c.transport.programId &&
                b.scope("query_scope") == c.stage && b.number("policy_version") == 1L && b.uuid("program_workspace_epoch") == epoch && b.number("hierarchy_revision") == hierarchy)
            val scopes = b.getAsJsonArray("scopes"); require(scopes.size() > 0)
            val ids = scopes.mapIndexed { index, s ->
                val o = s.asJsonObject; o.keys("scope", "workspace_epoch", "working_revision")
                val id = o.scope("scope"); val e = o.uuid("workspace_epoch"); val rev = o.number("working_revision")
                if (index == 0) require(id == c.stage && e == v.epoch && rev == v.revision)
                id
            }
            require(ids.toSet().size == ids.size)
        } else require(basis == null && root.get("resolution_basis").isJsonNull && root.text("scope_error_code") == "SCOPE_UNAVAILABLE")
        val caps = root.obj("capabilities"); caps.keys("read_own", "resolve", "create", "edit_local")
        require(caps.flag("read_own") && caps.flag("resolve") == available)
        val create = caps.flag("create"); val edit = caps.flag("edit_local"); require(available || (!create && !edit))
        val p = root.obj("page"); p.keys("index", "size", "total_rows", "token", "has_next")
        require(p.number("index") == expectedPage.toLong() && p.number("size") == 16L)
        val total = p.number("total_rows"); require(total <= Int.MAX_VALUE)
        val pageToken = p.text("token"); require(validDigest(pageToken) && (token == null || pageToken == token))
        val hasNext = p.flag("has_next"); require(hasNext == ((expectedPage.toLong() + 1) * 16 < total))
        val data = root.getAsJsonArray("rows"); require(data.size() <= 16)
        require(data.size().toLong() == (total - expectedPage.toLong() * 16).coerceIn(0, 16))
        val rows = data.map { element ->
            val o = element.asJsonObject; val group = DeclarationGroup.valueOf(o.text("group"))
            if (group == DeclarationGroup.OWN) {
                require(endpoint != "symbols"); o.keys("group", "declaration")
                declaration(o.obj("declaration"), group, c.stage, edit)
            } else {
                require(endpoint != "declarations" && available)
                o.keys("group", "declaration", "origin_scope", "distance", "source", "can_edit_declaration", "shadowed_by")
                require(o.text("source") == group.name)
                val local = group == DeclarationGroup.LOCAL
                require((o.number("distance") == 0L) == local && o.flag("can_edit_declaration") == local)
                val shadow = o.nullText("shadowed_by", 36)?.let(::uuidText)
                require((shadow != null) == (group == DeclarationGroup.SHADOWED))
                declaration(o.obj("declaration"), group, c.stage, local && edit, shadow).also { require(it.ownerStage == o.scope("origin_scope")) }
            }
        }
        require(rows.map { it.group to it.id }.toSet().size == rows.size)
        return DeclarationPage(v, epoch, hierarchy, basis, available, create, edit, expectedPage, total.toInt(), pageToken, hasNext, rows.toList())
    }
    fun receipt(text: String, c: DeclarationContext, request: UUID, operation: String, before: DeclarationVersion, target: UUID?): DeclarationReceipt {
        val o = parse(text); envelope(o, "ok"); identity(o, c)
        o.keys("api_version", "status", "target", "transport_context", "version_context", "request_id", "operation", "declaration_id", "declaration",
            "modified", "previous_version", "mutation_scope", "persisted", "request_id_semantics", "retry_automatically")
        require(o.uuid("request_id") == request && o.text("operation") == operation &&
            o.text("mutation_scope") == "PYTHON_WORKING_COPY_ONLY" && !o.flag("persisted") &&
            o.text("request_id_semantics") == "CORRELATION_ONLY" && !o.flag("retry_automatically"))
        val previous = version(o.obj("previous_version"))
        // Program SAVE may occur after GET without changing the CAS/basis.
        require(previous.epoch == before.epoch && previous.revision == before.revision &&
            previous.stageSaved >= before.stageSaved && previous.programSaved >= before.programSaved)
        val v = version(o.obj("version_context")); val modified = o.flag("modified")
        require(v.epoch == before.epoch && v.revision == Math.addExact(before.revision, if (modified) 1 else 0) &&
            v.stageSaved == previous.stageSaved && v.programSaved == previous.programSaved &&
            (if (modified) v.stageDirty && v.programDirty else v == previous))
        val row = declaration(o.obj("declaration"), DeclarationGroup.OWN, c.stage, true)
        require(row.id == o.uuid("declaration_id") && (target == null || row.id == target))
        return DeclarationReceipt(request, operation, row, modified, previous, v)
    }
    fun error(text: String, status: Int, request: UUID?): DeclarationResult.Rejected {
        val o = parse(text); envelope(o, "error"); require(status in 400..599)
        require(o.keySet() in listOf(setOf("api_version", "status", "error_code", "message", "retry_automatically"),
            setOf("api_version", "status", "error_code", "message", "retry_automatically", "request_id")))
        require(!o.flag("retry_automatically")); o.text("message", 256)
        if (o.has("request_id")) require(o.uuid("request_id") == request)
        val code = o.text("error_code", 96); require(Regex("[A-Z][A-Z0-9_]{0,95}").matches(code))
        return DeclarationResult.Rejected(status, code)
    }
    private fun validDigest(value: String) = Regex("$DECLARATION_BASIS:[0-9a-f]{64}").matches(value)
}
