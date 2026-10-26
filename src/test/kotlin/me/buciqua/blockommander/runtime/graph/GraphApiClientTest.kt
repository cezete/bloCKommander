package me.buciqua.blockommander.runtime.graph

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GraphApiClientTest {
    private var server: HttpServer? = null

    @AfterEach fun stopServer() { server?.stop(0) }

    @Test fun `missing secret sends no request and secret never appears in diagnostics`() {
        val hits = AtomicInteger()
        val base = startServer { _, _ -> hits.incrementAndGet(); 200 to successJson() }
        val client = GraphApiClient.configured(base, "   ")
        val result = client.getState(STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID)).get(2, TimeUnit.SECONDS)
        assertTrue(result is GraphHttpResult.Unavailable)
        assertEquals(0, hits.get())
        assertFalse(client.toString().contains("top-secret"))
        assertFalse(result.toString().contains("top-secret"))
    }

    @Test fun `authenticated GET parses snapshot and sends secret header`() {
        val header = AtomicReference<String>()
        val base = startServer { requestHeader, _ -> header.set(requestHeader); 200 to successJson() }
        val result = GraphApiClient.configured(base, "top-secret")
            .getState(STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID)).get(3, TimeUnit.SECONDS)
        assertTrue(result is GraphHttpResult.Success)
        assertEquals("top-secret", header.get())
        assertFalse(result.toString().contains("top-secret"))
    }

    @Test fun `all B1 HTTP errors remain structured`() {
        for (status in listOf(400, 401, 403, 404, 409, 422, 503, 500)) {
            server?.stop(0)
            val base = startServer { _, _ -> status to errorJson("E$status") }
            val result = GraphApiClient.configured(base, "secret")
                .getState(STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID)).get(3, TimeUnit.SECONDS)
            assertEquals(status, (result as GraphHttpResult.ApiError).response.httpStatus)
            assertEquals("E$status", result.response.errorCode)
        }
    }

    @Test fun `POST preserves command request id in body and response`() {
        val body = AtomicReference<String>()
        val base = startServer { _, requestBody -> body.set(requestBody); 200 to successJson(requestId = REQUEST_ID) }
        val command = BindEdgesCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
        )
        val result = GraphApiClient.configured(base, "secret").send(command).get(3, TimeUnit.SECONDS)
        assertTrue(result is GraphHttpResult.Success)
        assertTrue(body.get().contains(REQUEST_ID.toString()))
        assertEquals(REQUEST_ID, (result as GraphHttpResult.Success).response.requestId)
    }

    @Test fun `wire removal POST preserves explicit null cell in HTTP body`() {
        val body = AtomicReference<String>()
        val base = startServer { _, requestBody -> body.set(requestBody); 200 to successJson(requestId = REQUEST_ID) }
        val command = WireLayoutCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
            listOf(GraphWireChangeInput(GraphPosition(1, 0, 0), null)),
        )
        val result = GraphApiClient.configured(base, "secret").send(command).get(3, TimeUnit.SECONDS)
        assertTrue(result is GraphHttpResult.Success)
        val change = JsonParser.parseString(body.get()).asJsonObject
            .getAsJsonObject("payload").getAsJsonArray("changes")[0].asJsonObject
        assertTrue(change.has("cell"))
        assertTrue(change["cell"].isJsonNull)
    }

    private fun startServer(response: (String?, String) -> Pair<Int, String>): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            val requestBody = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
            val (status, body) = response(exchange.requestHeaders.getFirst(GRAPH_SECRET_HEADER), requestBody)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        created.start()
        server = created
        return "http://127.0.0.1:${created.address.port}"
    }
}
