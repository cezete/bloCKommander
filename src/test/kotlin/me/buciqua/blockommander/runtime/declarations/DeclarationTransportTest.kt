package me.buciqua.blockommander.runtime.declarations

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.editor.*
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

internal object DeclarationFixtures {
    val f = BlockEditorFixtures
    val context = f.context().let { DeclarationContext(it.playerUuid, it.worldUuid, it.transport, it.stageId) }
    fun text(name: String) = javaClass.getResourceAsStream("/declarations/$name.json")!!.bufferedReader().use { it.readText() }
    fun json(name: String) = JsonParser.parseString(text(name)).asJsonObject
    fun page(name: String = "editor-local") = DeclarationJson.page(text(name), if (name == "editor-child") context.copy(stage = UUID(0, 9100)) else context,
        when (name) { "own" -> "declarations"; "symbols" -> "symbols"; else -> "declaration-editor" }, 0, null)
    fun error(code: String = "STALE_RESOLUTION_BASIS", request: UUID? = null) = JsonObject().apply {
        addProperty("api_version", DECLARATION_API); addProperty("status", "error"); addProperty("error_code", code)
        addProperty("message", "Declaration request rejected."); addProperty("retry_automatically", false)
        request?.let { addProperty("request_id", it.toString()) }
    }.toString()
}

class DeclarationTransportTest {
    private val f = DeclarationFixtures
    private fun reject(change: (JsonObject) -> Unit) {
        val json = f.json("editor-local"); change(json)
        assertThrows(Exception::class.java) { DeclarationJson.page(json.toString(), f.context, "declaration-editor", 0, null) }
    }
    @Test fun `unchanged Python fixtures parse own symbols and atomic editor`() {
        for (name in listOf("editor-empty", "editor-local", "editor-child", "own", "symbols")) assertNotNull(f.page(name))
        val child = f.page("editor-child")
        assertEquals(listOf(DeclarationGroup.OWN, DeclarationGroup.LOCAL, DeclarationGroup.INHERITED, DeclarationGroup.SHADOWED), child.rows.map { it.group })
        assertEquals(child.rows.first().id, child.rows.last().shadowedBy)
        assertFalse(child.rows.last().editable); assertFalse(child.rows[2].editable)
    }
    @Test fun `version enum UUID malformed and duplicate JSON fail closed`() {
        reject { it.addProperty("api_version", "future") }
        reject { it.getAsJsonObject("target").addProperty("stage_id", "1-1-1-1-1") }
        reject { it.getAsJsonArray("rows")[0].asJsonObject.getAsJsonObject("declaration").addProperty("type_key", "FLOAT") }
        reject { it.getAsJsonArray("rows")[0].asJsonObject.getAsJsonObject("declaration").addProperty("mutability", "CONST") }
        for (text in listOf("{}{}", "{\"x\":1,\"x\":2}", "{\"x\":NaN}", "{\"x\":1.1}", "{\"x\":9223372036854775808}"))
            assertThrows(Exception::class.java) { DeclarationJson.parse(text) }
    }
    @Test fun `context Program Hall WorkingCopy and Stage mismatches rejected`() {
        for (key in listOf("program_id", "hall_id", "working_copy_id", "stage_id")) reject {
            it.getAsJsonObject("transport_context").addProperty(key, UUID.randomUUID().toString())
        }
        reject { it.getAsJsonObject("transport_context").addProperty("content_scope", "LOCAL") }
        reject { it.getAsJsonObject("resolution_basis").addProperty("program_workspace_epoch", UUID.randomUUID().toString()) }
        reject { it.getAsJsonObject("resolution_basis").getAsJsonArray("scopes")[0].asJsonObject.addProperty("working_revision", 99) }
    }
    @Test fun `pages are bounded typed and pinned to token`() {
        reject { it.getAsJsonObject("page").addProperty("index", 1) }
        reject { it.getAsJsonObject("page").addProperty("size", 17) }
        reject { it.getAsJsonObject("page").addProperty("has_next", true) }
        assertThrows(Exception::class.java) { DeclarationJson.page(f.text("editor-local"), f.context, "declaration-editor", 0, "$DECLARATION_BASIS:" + "0".repeat(64)) }
        assertThrows(Exception::class.java) { DeclarationJson.parse(" ".repeat(16385)) }
    }
    @Test fun `unknown scope explicit cannot fabricate an empty success`() {
        val o = f.json("editor-empty")
        o.addProperty("scope_status", "UNAVAILABLE"); o.addProperty("scope_error_code", "SCOPE_UNAVAILABLE")
        o.add("resolution_basis", com.google.gson.JsonNull.INSTANCE); o.add("resolution_basis_digest", com.google.gson.JsonNull.INSTANCE)
        for (key in listOf("resolve", "create", "edit_local")) o.getAsJsonObject("capabilities").addProperty(key, false)
        assertFalse(DeclarationJson.page(o.toString(), f.context, "declaration-editor", 0, null).scopeAvailable)
        o.addProperty("scope_status", "FUTURE")
        assertThrows(Exception::class.java) { DeclarationJson.page(o.toString(), f.context, "declaration-editor", 0, null) }
    }
    @Test fun `create receipt validates UUID request original CAS and workspace-only contract`() {
        val before = f.page("editor-empty").version
        val result = DeclarationJson.receipt(f.text("create"), f.context, f.f.requestId, "CREATE", before, null)
        assertTrue(result.modified); assertEquals(before.revision + 1, result.version.revision)
        for (change in listOf<(JsonObject) -> Unit>({ it.addProperty("request_id", UUID.randomUUID().toString()) },
            { it.addProperty("persisted", true) }, { it.addProperty("retry_automatically", true) },
            { it.getAsJsonObject("version_context").addProperty("working_revision", 10) })) {
            val o = f.json("create"); change(o)
            assertThrows(Exception::class.java) { DeclarationJson.receipt(o.toString(), f.context, f.f.requestId, "CREATE", before, null) }
        }
    }
    @Test fun `SAVE between GET and mutation is not a false protocol failure`() {
        val before = f.page("editor-empty").version
        val o = f.json("create")
        for (key in listOf("previous_version", "version_context")) {
            o.getAsJsonObject(key).addProperty("stage_saved_version", 1)
            o.getAsJsonObject(key).addProperty("program_saved_version", 1)
        }
        assertEquals(1, DeclarationJson.receipt(o.toString(), f.context, f.f.requestId, "CREATE", before, null).version.programSaved)
    }
    @Test fun `typed client supports all six endpoints with trusted headers and original CAS`() {
        val requests = mutableListOf<HttpRequest>()
        var response = EditorHttpResponse(200, f.text("editor-empty"))
        val client = DeclarationApiClient.withExchange("http://localhost:5001", "trusted-test-only", EditorHttpExchange {
            requests += it; CompletableFuture.completedFuture(response)
        })
        for ((endpoint, fixture) in listOf("declarations" to "own", "symbols" to "symbols", "declaration-editor" to "editor-local")) {
            response = EditorHttpResponse(200, f.text(fixture))
            assertTrue(client.read(f.context, endpoint).join() is DeclarationResult.Success)
            assertTrue(requests.last().uri().path.endsWith(endpoint))
        }
        for ((operation, method) in listOf("CREATE" to "POST", "UPDATE" to "PATCH", "DELETE" to "DELETE")) {
            val o = f.json("create"); o.addProperty("operation", operation)
            response = EditorHttpResponse(if (operation == "CREATE") 201 else 200, o.toString())
            val target = if (operation == "CREATE") null else UUID(0, 9000)
            assertTrue(client.mutate(f.context, f.page("editor-empty"), operation, target,
                if (operation == "DELETE") null else DeclarationFields("counter", DeclarationType.INTEGER, DeclarationMutability.MUTABLE), f.f.requestId).join() is DeclarationResult.Success)
            val request = requests.last(); assertEquals(method, request.method())
            assertEquals("trusted-test-only", request.headers().firstValue(GRAPH_SECRET_HEADER).get())
            val body = JsonParser.parseString(BlockEditorFixtures.body(request)).asJsonObject
            assertEquals(f.page("editor-empty").basisDigest, body["expected_resolution_basis"].asString)
            assertFalse(body.has("hall_id")); assertFalse(body.has("program_id")); assertFalse(body.has("world_uuid"))
            assertEquals(f.context.player.toString(), body["player_uuid"].asString)
            assertEquals(10, request.timeout().get().seconds)
        }
        assertFalse(client.toString().contains("trusted-test-only"))
    }
    @Test fun `timeout mutation is UNKNOWN with exactly one dispatch and domain errors stay structured`() {
        var sends = 0
        val client = DeclarationApiClient.withExchange("http://localhost", "test", EditorHttpExchange {
            sends++; CompletableFuture.failedFuture(HttpTimeoutException("secret internal details"))
        })
        val fields = DeclarationFields("x", DeclarationType.STRING, DeclarationMutability.IMMUTABLE)
        val unknown = client.mutate(f.context, f.page(), "CREATE", null, fields, UUID.randomUUID()).join() as DeclarationResult.Failure
        assertTrue(unknown.unknown); assertEquals("TIMEOUT", unknown.code); assertEquals(1, sends)
        assertFalse((client.read(f.context).join() as DeclarationResult.Failure).unknown); assertEquals(2, sends)
        val rejected = DeclarationApiClient.withExchange("http://localhost", "test", EditorHttpExchange {
            CompletableFuture.completedFuture(EditorHttpResponse(409, f.error()))
        }).read(f.context).join() as DeclarationResult.Rejected
        assertEquals("STALE_RESOLUTION_BASIS", rejected.code); assertEquals(409, rejected.status)
    }
    @Test fun `missing secret rejects locally and malformed mutation response remains UNKNOWN`() {
        var sends = 0
        val exchange = EditorHttpExchange { sends++; CompletableFuture.completedFuture(EditorHttpResponse(201, "{}")) }
        val fields = DeclarationFields("x", DeclarationType.INTEGER, DeclarationMutability.MUTABLE)
        assertEquals("NOT_CONFIGURED", (DeclarationApiClient.withExchange("http://localhost", null, exchange).read(f.context).join() as DeclarationResult.Failure).code)
        assertEquals(0, sends)
        assertTrue((DeclarationApiClient.withExchange("http://localhost", "test", exchange).mutate(f.context, f.page(), "CREATE", null, fields, UUID.randomUUID()).join() as DeclarationResult.Failure).unknown)
        assertEquals(1, sends)
    }
    @Test fun `response subscriber cancels oversized stream before buffering it`() {
        var cancelled = false
        val subscriber = DeclarationBodySubscriber(16)
        subscriber.onSubscribe(object : Flow.Subscription { override fun request(n: Long) {} ; override fun cancel() { cancelled = true } })
        subscriber.onNext(listOf(ByteBuffer.wrap(ByteArray(17))))
        assertTrue(cancelled); assertTrue(subscriber.body.toCompletableFuture().isCompletedExceptionally)
    }
}
