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
        val command = WirePlaceIntentCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
            GraphPosition(1, 0, 0), GraphPlacementIntent.AUTO,
        )
        val outcome = adapter.send(TRANSPORT_CONTEXT, command).get(5, TimeUnit.SECONDS)
        assertTrue(outcome.transport is GraphHttpResult.ProtocolFailure)
        assertNotNull(outcome.reconciliation)
        assertEquals(1, postHits.get())
        assertEquals(1, getHits.get())
        assertEquals(4, store.view(testKey(STAGE_ID))!!.snapshot!!.workingRevision)
        assertEquals(GraphSyncStatus.SYNCED, store.view(testKey(STAGE_ID))!!.status)
        assertTrue(mainExecutions.get() >= 2)
    }

    @Test fun `placement conflict reconciles once and never retries semantic command`() {
        val postHits = AtomicInteger()
        val getHits = AtomicInteger()
        val base = startServer { method ->
            if (method == "POST") {
                postHits.incrementAndGet(); 409 to errorJson("STALE_WORKING_REVISION")
            } else {
                getHits.incrementAndGet(); 200 to successJson(revision = 4)
            }
        }
        val store = seededStore()
        val adapter = GraphTransportAdapter(
            GraphApiClient.configured(base, "secret"), store, Executor(Runnable::run),
        )
        val command = WirePlaceIntentCommand(
            REQUEST_ID, STAGE_ID, ServerPlayerIdentity.trustedForTest(PLAYER_ID), EPOCH_A, 3,
            GraphPosition(1, 0, 0), GraphPlacementIntent.AUTO,
        )

        val outcome = adapter.send(TRANSPORT_CONTEXT, command).get(5, TimeUnit.SECONDS)

        assertTrue(outcome.transport is GraphHttpResult.ApiError)
        assertNotNull(outcome.reconciliation)
        assertEquals(1, postHits.get())
        assertEquals(1, getHits.get())
        assertEquals(4, store.view(testKey(STAGE_ID))!!.snapshot!!.workingRevision)
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
        val outcome = adapter.send(TRANSPORT_CONTEXT, command).get(5, TimeUnit.SECONDS)
        assertTrue(outcome.transport is GraphHttpResult.ProtocolFailure)
        assertNotNull(outcome.reconciliation)
        assertEquals(1, getHits.get())
        assertEquals(4, store.view(testKey(STAGE_ID))!!.stageRevision)
    }

    @Test fun `late uncertain result cannot reconcile a reinstalled slot`() {
        val store=seededStore()
        val getHits=AtomicInteger()
        val base=startServer { method ->
            if (method=="POST") {
                store.remove(testKey(STAGE_ID))
                store.authoritativeStageReload(testKey(STAGE_ID),EPOCH_A,0)
                200 to "not-json"
            } else {
                getHits.incrementAndGet()
                200 to successJson(revision=4)
            }
        }
        val adapter=GraphTransportAdapter(GraphApiClient.configured(base,"secret"),store,Executor(Runnable::run))
        val command=BindEdgesCommand(
            REQUEST_ID,STAGE_ID,ServerPlayerIdentity.trustedForTest(PLAYER_ID),EPOCH_A,3,
        )
        val outcome=adapter.send(TRANSPORT_CONTEXT,command).get(5,TimeUnit.SECONDS)
        assertInstanceOf(GraphHttpResult.ProtocolFailure::class.java,outcome.transport)
        assertEquals(0,getHits.get())
        assertNull(store.view(testKey(STAGE_ID))!!.snapshot)
        assertEquals(GraphSyncStatus.NOT_REQUESTED,store.view(testKey(STAGE_ID))!!.status)
    }

    @Test fun `recovery reserves once atomically and cannot apply or fail into a replacement incarnation`() {
        for (before in listOf(true,false)) for (getStatus in listOf(200,503)) {
            server?.stop(0)
            val store=seededStore(); val gets=AtomicInteger(); val executions=AtomicInteger()
            val base=startServer { method ->
                if (method=="POST") 200 to "not-json"
                else { gets.incrementAndGet();getStatus to if(getStatus==200) successJson(revision=4) else errorJson("UNAVAILABLE") }
            }
            fun reinstall() {
                store.remove(testKey(STAGE_ID))
                store.authoritativeStageReload(testKey(STAGE_ID),EPOCH_A,0)
            }
            val main=Executor { task ->
                val reservation=executions.incrementAndGet()==2
                if (reservation && before) reinstall()
                task.run()
                if (reservation && !before) reinstall()
            }
            val adapter=GraphTransportAdapter(GraphApiClient.configured(base,"secret"),store,main)
            val command=BindEdgesCommand(REQUEST_ID,STAGE_ID,ServerPlayerIdentity.trustedForTest(PLAYER_ID),EPOCH_A,3)
            adapter.send(TRANSPORT_CONTEXT,command).get(5,TimeUnit.SECONDS)
            assertEquals(if(before)0 else 1,gets.get())
            assertNull(store.view(testKey(STAGE_ID))!!.snapshot)
            assertEquals(GraphSyncStatus.NOT_REQUESTED,store.view(testKey(STAGE_ID))!!.status)
        }
    }

    private fun seededStore() = GraphProjectionStore().also { store ->
        store.authoritativeStageReload(testKey(STAGE_ID), EPOCH_A, 0)
        store.apply(store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION), snapshot(revision = 3))
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
