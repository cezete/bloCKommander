package me.buciqua.blockommander.runtime.editor

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import me.buciqua.blockommander.runtime.HALL_ID_HEADER
import me.buciqua.blockommander.runtime.PROGRAM_ID_HEADER
import me.buciqua.blockommander.runtime.WORKING_COPY_ID_HEADER
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class BlockEditorTransportTest {
    private val f = BlockEditorFixtures
    private val direct = Executor { it.run() }
    private class FakeExchange : EditorHttpExchange {
        val calls = mutableListOf<HttpRequest>()
        var response = EditorHttpResponse(200, BlockEditorFixtures.fixture("start-get"))
        var failure: Throwable? = null
        var pending: CompletableFuture<EditorHttpResponse>? = null
        override fun send(request: HttpRequest): CompletableFuture<EditorHttpResponse> {
            calls.add(request)
            return pending ?: failure?.let { CompletableFuture.failedFuture(it) }
                ?: CompletableFuture.completedFuture(response)
        }
    }
    private fun adapter(fake: FakeExchange, executor: Executor = direct, secret: String? = "test-secret") =
        BlockEditorTransportAdapter(BlockEditorApiClient.withExchange("http://backend:5001/", secret, fake), executor)
    private val changes get() = EditorChanges(EditorStringChange.Value(" Raw 😀 "), EditorStringChange.Clear)

    @Test fun `GET uses exact path server identity authentication and context headers`() {
        val fake = FakeExchange()
        val context = f.context()
        val outcome = adapter(fake).get(context).get(1, TimeUnit.SECONDS)
        assertInstanceOf(BlockEditorResult.Success::class.java, outcome.result)
        assertSame(context, outcome.context)
        val request = fake.calls.single()
        assertEquals("GET", request.method())
        assertEquals("/api/v1/programs/${f.program}/stages/${f.stage}/blocks/${f.start}/editor", request.uri().path)
        assertEquals("player_uuid=${f.player}", request.uri().rawQuery)
        assertEquals("test-secret", request.headers().firstValue(GRAPH_SECRET_HEADER).orElseThrow())
        assertEquals(f.hall.toString(), request.headers().firstValue(HALL_ID_HEADER).orElseThrow())
        assertEquals(f.program.toString(), request.headers().firstValue(PROGRAM_ID_HEADER).orElseThrow())
        assertEquals(f.workingCopy.toString(), request.headers().firstValue(WORKING_COPY_ID_HEADER).orElseThrow())
        assertEquals(Duration.ofSeconds(10), request.timeout().orElseThrow())
        assertTrue(request.bodyPublisher().isEmpty)
    }

    @Test fun `APPLY body is exact versioned request with explicit null and original CAS`() {
        val fake = FakeExchange().apply { response = EditorHttpResponse(200, f.fixture("start-apply")) }
        val context = f.context(apply = true)
        val result = adapter(fake).apply(context, changes).get(1, TimeUnit.SECONDS)
        assertInstanceOf(BlockEditorResult.Success::class.java, result.result)
        assertSame(context, result.context)
        val request = fake.calls.single()
        assertEquals("POST", request.method())
        assertEquals("/api/v1/programs/${f.program}/stages/${f.stage}/blocks/${f.start}/editor/apply", request.uri().path)
        assertNull(request.uri().query)
        val body = JsonParser.parseString(f.body(request)).asJsonObject
        assertEquals(setOf("api_version", "request_id", "player_uuid", "schema_id", "schema_version",
            "expected_stage_workspace_epoch", "expected_working_revision", "changes"), body.keySet())
        assertEquals(BLOCK_EDITOR_API_VERSION, body["api_version"].asString)
        assertEquals(f.requestId.toString(), body["request_id"].asString)
        assertEquals(f.player.toString(), body["player_uuid"].asString)
        assertEquals(f.epoch.toString(), body["expected_stage_workspace_epoch"].asString)
        assertEquals(12, body["expected_working_revision"].asInt)
        assertEquals(" Raw 😀 ", body.getAsJsonObject("changes")["label"].asString)
        assertTrue(body.getAsJsonObject("changes")["description"].isJsonNull)
        assertFalse(body.has("hall_id"))
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow())
    }

    @Test fun `STOP GET keeps its own instance and schema`() {
        val fake = FakeExchange().apply { response = EditorHttpResponse(200, f.fixture("stop-get")) }
        val result = adapter(fake).get(f.context(type = EditorBlockType.STOP)).join()
        val success = result.result as BlockEditorResult.Success
        assertEquals(EditorBlockType.STOP, success.value.target.blockType)
        assertEquals(f.stop, success.value.target.instanceId)
    }

    @Test fun `each identity mismatch becomes protocol failure not success`() {
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("transport_context").addProperty("hall_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("transport_context").addProperty("program_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("transport_context").addProperty("working_copy_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("transport_context").addProperty("stage_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("target").addProperty("instance_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("target").addProperty("program_id", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("target").addProperty("stage_id", UUID.randomUUID().toString()) },
        )
        for (mutate in mutations) {
            val raw = f.json(); mutate(raw)
            val fake = FakeExchange().apply { response = EditorHttpResponse(200, raw.toString()) }
            assertInstanceOf(BlockEditorResult.ProtocolFailure::class.java, adapter(fake).get(f.context()).join().result)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun `internally consistent response for a different request is rejected`() {
        val context = f.context().copy(transport = f.context().transport.copy(programId = UUID.randomUUID()))
        assertInstanceOf(BlockEditorResult.ProtocolFailure::class.java, adapter(FakeExchange()).get(context).join().result)
    }

    @Test fun `APPLY request ID mismatch is rejected without retry`() {
        val raw = f.json("start-apply").apply { addProperty("request_id", UUID.randomUUID().toString()) }
        val fake = FakeExchange().apply { response = EditorHttpResponse(200, raw.toString()) }
        val outcome = adapter(fake).apply(f.context(true), changes).join()
        assertTrue((outcome.result as BlockEditorResult.ProtocolFailure).applyOutcomeUnknown)
        assertEquals(1, fake.calls.size)
    }

    @Test fun `APPLY schema epoch revision and modified flag have consistent CAS`() {
        val mutations: List<(com.google.gson.JsonObject) -> Unit> = listOf(
            { it.getAsJsonObject("version_context").addProperty("stage_workspace_epoch", UUID.randomUUID().toString()) },
            { it.getAsJsonObject("version_context").addProperty("working_revision", 99) },
            { it.addProperty("modified", false) },
        )
        for (mutate in mutations) {
            val raw = f.json("start-apply"); mutate(raw)
            val fake = FakeExchange().apply { response = EditorHttpResponse(200, raw.toString()) }
            assertInstanceOf(BlockEditorResult.ProtocolFailure::class.java, adapter(fake).apply(f.context(true), changes).join().result)
        }
    }

    @Test fun `no-op APPLY must retain the captured revision`() {
        val raw = f.json("start-get").apply { addProperty("modified", false); addProperty("request_id", f.requestId.toString()) }
        val fake = FakeExchange().apply { response = EditorHttpResponse(200, raw.toString()) }
        val result = adapter(fake).apply(f.context(true), changes).join().result as BlockEditorResult.Success
        assertFalse(result.value.modified)
    }

    @Test fun `structured HTTP errors remain distinct and carry field diagnostics`() {
        for (status in listOf(400, 401, 403, 404, 409, 413, 422, 500, 503)) {
            val fake = FakeExchange().apply { response = EditorHttpResponse(status, f.error(status)) }
            val result = adapter(fake).apply(f.context(true), changes).join().result as BlockEditorResult.ApiError
            assertEquals(status, result.error.httpStatus)
            assertEquals("label", result.error.diagnostics.single().field)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun `errors can lack request ID but a mismatched present ID fails`() {
        val fake = FakeExchange().apply { response = EditorHttpResponse(403, f.error(403, null)) }
        assertInstanceOf(BlockEditorResult.ApiError::class.java, adapter(fake).apply(f.context(true), changes).join().result)
        fake.response = EditorHttpResponse(409, f.error(409, UUID.randomUUID()))
        assertInstanceOf(BlockEditorResult.ProtocolFailure::class.java, adapter(fake).apply(f.context(true), changes).join().result)
    }

    @Test fun `timeout and network failure are uncertain for APPLY with exactly one send`() {
        for (failure in listOf(HttpTimeoutException("timeout"), java.io.IOException("offline"))) {
            val fake = FakeExchange().apply { this.failure = failure }
            val context = f.context(true)
            val result = adapter(fake).apply(context, changes).join()
            val error = result.result as BlockEditorResult.TransportFailure
            assertTrue(error.applyOutcomeUnknown)
            assertSame(context, result.context)
            assertEquals(if (failure is HttpTimeoutException) EditorTransportFailureKind.TIMEOUT else EditorTransportFailureKind.UNAVAILABLE, error.kind)
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun `GET timeout does not imply a domain mutation`() {
        val fake = FakeExchange().apply { failure = HttpTimeoutException("timeout") }
        val result = adapter(fake).get(f.context()).join().result as BlockEditorResult.TransportFailure
        assertFalse(result.applyOutcomeUnknown)
    }

    @Test fun `unconfigured transport does not send or expose the secret`() {
        val fake = FakeExchange()
        val client = BlockEditorApiClient.withExchange("http://backend:5001", null, fake)
        val result = BlockEditorTransportAdapter(client, direct).apply(f.context(true), changes).join().result as BlockEditorResult.TransportFailure
        assertEquals(EditorTransportFailureKind.NOT_CONFIGURED, result.kind)
        assertFalse(result.applyOutcomeUnknown)
        assertTrue(fake.calls.isEmpty())
        assertFalse(BlockEditorApiClient.withExchange("http://backend:5001", "unique-private-secret", fake).toString().contains("unique-private-secret"))
    }

    @Test fun `missing APPLY basis and oversized body are rejected locally without sending`() {
        val fake = FakeExchange()
        val adapter = adapter(fake)
        val missing = adapter.apply(f.context(), changes).join().result as BlockEditorResult.TransportFailure
        assertEquals(EditorTransportFailureKind.LOCAL_REJECTED, missing.kind)
        val big = adapter.apply(f.context(true), EditorChanges(EditorStringChange.Value("x".repeat(65537)))).join().result
        assertEquals(EditorTransportFailureKind.LOCAL_REJECTED, (big as BlockEditorResult.TransportFailure).kind)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `malformed JSON wrong API version HTTP status or body status cannot succeed`() {
        for ((status, text) in listOf(200 to "{", 200 to f.fixture("start-get").replace(BLOCK_EDITOR_API_VERSION, "v2"),
            200 to f.error(400), 409 to f.fixture("start-get"), 201 to f.fixture("start-get"), 503 to "<html>offline</html>")) {
            val fake = FakeExchange().apply { response = EditorHttpResponse(status, text) }
            assertInstanceOf(BlockEditorResult.ProtocolFailure::class.java, adapter(fake).get(f.context()).join().result)
        }
    }

    @Test fun `network is async and completion waits for supplied main executor`() {
        val network = CompletableFuture<EditorHttpResponse>()
        val fake = FakeExchange().apply { pending = network }
        val main = ArrayDeque<Runnable>()
        val context = f.context()
        val future = adapter(fake, Executor { main.add(it) }).get(context)
        assertFalse(future.isDone)
        network.complete(EditorHttpResponse(200, f.fixture("start-get")))
        assertFalse(future.isDone)
        assertEquals(1, main.size)
        main.removeFirst().run()
        assertSame(context, future.join().context)
        assertEquals(1, fake.calls.size) // No reconciliation, retry or other side-effect request.
    }

    @Test fun `real JDK sendAsync reaches local test server without Minecraft runtime`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = f.fixture("start-get").toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = BlockEditorApiClient.configured("http://127.0.0.1:${server.address.port}", "test")
            val result = BlockEditorTransportAdapter(client, direct).get(f.context()).get(3, TimeUnit.SECONDS)
            assertInstanceOf(BlockEditorResult.Success::class.java, result.result)
        } finally { server.stop(0) }
    }
}
