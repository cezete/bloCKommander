package me.buciqua.blockommander.runtime.graph

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GraphTransportAdapterTest {
    private var server: HttpServer? = null

    @AfterEach fun stopServer() { server?.stop(0) }

    @Test fun `uncertain command response performs exactly one reconciliation`() {
        val postHits = AtomicInteger()
        val getHits = AtomicInteger()
        val base = startServer { method ->
            if (method == "POST") {
                postHits.incrementAndGet(); 200 to "not-json"
            } else {
                getHits.incrementAndGet(); 200 to successJson(revision = 4)
            }
        }
        val store = seededStore()
        val mainExecutions = AtomicInteger()
        val adapter = GraphTransportAdapter(
            GraphApiClient.configured(base, "secret"), store,
            Executor { task -> mainExecutions.incrementAndGet(); task.run() },
        )
        val command = BindEdgesCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
        )
        val outcome = adapter.send(command).get(5, TimeUnit.SECONDS)
        assertTrue(outcome.transport is GraphHttpResult.ProtocolFailure)
        assertNotNull(outcome.reconciliation)
        assertEquals(1, postHits.get())
        assertEquals(1, getHits.get())
        assertEquals(4, store.view(STAGE_ID)!!.snapshot!!.workingRevision)
        assertEquals(GraphSyncStatus.SYNCED, store.view(STAGE_ID)!!.status)
        assertTrue(mainExecutions.get() >= 2)
    }

    @Test fun `graph response only updates projection and has no world mutation dependency`() {
        val forbiddenTypes = GraphTransportAdapter::class.java.declaredMethods
            .flatMap { it.parameterTypes.asList() + it.returnType }
            .map(Class<*>::getName)
        assertTrue(forbiddenTypes.none { it.contains("ServerLevel") || it.contains("WorldRenderer") })
    }

    @Test fun `mismatched command request id performs reconciliation`() {
        val getHits = AtomicInteger()
        val base = startServer { method ->
            if (method == "POST") 200 to successJson(revision = 4, requestId = java.util.UUID.randomUUID())
            else { getHits.incrementAndGet(); 200 to successJson(revision = 4) }
        }
        val store = seededStore()
        val adapter = GraphTransportAdapter(GraphApiClient.configured(base, "secret"), store, Executor(Runnable::run))
        val command = BindEdgesCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
        )
        val outcome = adapter.send(command).get(5, TimeUnit.SECONDS)
        assertTrue(outcome.transport is GraphHttpResult.ProtocolFailure)
        assertNotNull(outcome.reconciliation)
        assertEquals(1, getHits.get())
        assertEquals(4, store.view(STAGE_ID)!!.stageRevision)
    }

    private fun seededStore() = GraphProjectionStore().also { store ->
        store.authoritativeStageReload(STAGE_ID, EPOCH_A, 0)
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(revision = 3))
    }

    private fun startServer(response: (String) -> Pair<Int, String>): String {
        val created = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        created.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val (status, body) = response(exchange.requestMethod)
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        created.start(); server = created
        return "http://127.0.0.1:${created.address.port}"
    }
}
