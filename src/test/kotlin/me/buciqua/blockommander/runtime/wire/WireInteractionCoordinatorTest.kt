package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class WireInteractionCoordinatorTest {
    private val direct = Executor(Runnable::run)
    private val stage = WireStageContext(STAGE_ID, GraphPosition(10, 20, 30), GraphPosition(7, 5, 7), EPOCH_A)
    private val target = GraphPosition(11, 20, 30)
    private val player = ServerPlayerIdentity.trustedForTest(PLAYER_ID)

    @Test fun `stale projection reconciles before placement and uses stable server identity request`() {
        val store = syncedStore(0)
        store.legacyStageUpdated(STAGE_ID, EPOCH_A, 1)
        val gateway = FakeGraphGateway(store).apply {
            reconcileSnapshot = wireSnapshot(1, withWire = false)
            sendSnapshot = wireSnapshot(2)
        }
        val fixedRequest = UUID.fromString("12121212-1212-1212-1212-121212121212")
        val world = FakeWireWorld()
        val result = coordinator(store, gateway, fixedRequest).place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(1, gateway.reconcileCalls)
        assertEquals(fixedRequest, gateway.commands.single().requestId)
        assertEquals(PLAYER_ID, gateway.commands.single().player.uuid)
        assertEquals(GraphPosition(1, 0, 0), (gateway.commands.single() as WireLayoutCommand).changes.single().position)
        assertEquals(WireActionStatus.SUCCESS, result.status)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(target))
    }

    @Test fun `unsynchronized Stage never sends modifying command when reconciliation fails`() {
        val store = GraphProjectionStore().also { it.authoritativeStageReload(STAGE_ID, EPOCH_A, 0) }
        val gateway = FakeGraphGateway(store)
        val world = FakeWireWorld()
        val result = coordinator(store, gateway).place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.NOT_SYNCED, result.status)
        assertEquals(1, gateway.reconcileCalls)
        assertTrue(gateway.commands.isEmpty())
        assertEquals(0, world.setCalls)
        assertEquals(1, world.correctCalls)
    }

    @Test fun `HTTP reject never renders persistent wire`() {
        val store = syncedStore(0)
        val gateway = FakeGraphGateway(store).apply { rejectStatus = 422 }
        val world = FakeWireWorld()
        val result = coordinator(store, gateway).place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.REJECTED, result.status)
        assertEquals(0, world.setCalls)
        assertEquals(1, world.correctCalls)
    }

    @Test fun `HTTP reject detail includes diagnostic field path`() {
        val store = syncedStore(1, withWire = true)
        val gateway = FakeGraphGateway(store).apply {
            rejectStatus = 400
            rejectErrorCode = "MISSING_FIELD"
            rejectDiagnostics = listOf(GraphDiagnostic(
                "MISSING_FIELD", "ERROR", "missing", emptyList(), emptyList(), emptyList(), emptyList(),
                null, "payload.changes[0].cell",
            ))
        }
        val result = coordinator(store, gateway).remove(
            stage, player, target, FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND)),
        ).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.REJECTED, result.status)
        assertEquals("MISSING_FIELD: payload.changes[0].cell", result.detail)
    }

    @Test fun `world remains unchanged until Python command future completes`() {
        val store = syncedStore(0)
        val pending = CompletableFuture<CommandTransportOutcome>()
        lateinit var captured: GraphCommand
        val gateway = object : GraphGateway {
            override fun reconcile(stageId: UUID, player: ServerPlayerIdentity) =
                CompletableFuture.completedFuture(ReconciliationOutcome(GraphHttpResult.Unavailable("unused"), null))
            override fun send(command: GraphCommand): CompletableFuture<CommandTransportOutcome> {
                captured = command
                return pending
            }
        }
        val world = FakeWireWorld()
        val future = WireInteractionCoordinator(store, gateway, WireWorldProjector(), direct) { REQUEST_ID }
            .place(stage, player, target, world)
        assertEquals(0, world.setCalls)
        assertFalse(future.isDone)
        val authoritative = wireSnapshot(1)
        val applied = store.apply(store.begin(STAGE_ID, GraphRequestKind.COMMAND), authoritative)
        pending.complete(CommandTransportOutcome(
            GraphHttpResult.Success(GraphSuccessResponse(
                GRAPH_API_VERSION, "ok", captured.requestId, STAGE_ID, EPOCH_A, 1, true,
                emptyList(), emptyList(), null, authoritative,
            )), applied, null,
        ))
        assertEquals(WireActionStatus.SUCCESS, future.get(3, TimeUnit.SECONDS).status)
        assertEquals(1, world.setCalls)
    }

    @Test fun `world writes run through configured main-thread executor`() {
        val store = syncedStore(0)
        val gateway = FakeGraphGateway(store).apply { sendSnapshot = wireSnapshot(1) }
        var onMain = false
        var wroteOnMain = false
        val executor = Executor { task ->
            onMain = true
            try { task.run() } finally { onMain = false }
        }
        val world = object : WireWorldAccess {
            private var wire = false
            override fun ckWirePositions(stage: WireStageContext) = if (wire) setOf(target) else emptySet()
            override fun cell(position: GraphPosition) = if (wire) WireWorldCell.CK_WIRE_UNBOUND else WireWorldCell.REPLACEABLE
            override fun setEastWestWire(position: GraphPosition, visualState: WireVisualState) { wroteOnMain = onMain; wire = true }
            override fun removeWire(position: GraphPosition) { wroteOnMain = onMain; wire = false }
            override fun correct(position: GraphPosition) = Unit
        }
        val result = WireInteractionCoordinator(store, gateway, WireWorldProjector(), executor) { REQUEST_ID }
            .place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.SUCCESS, result.status)
        assertTrue(wroteOnMain)
    }

    @Test fun `late response after epoch change cannot write world`() {
        val store = syncedStore(0)
        val gateway = FakeGraphGateway(store).apply {
            sendSnapshot = wireSnapshot(1)
            afterSend = { store.authoritativeStageReload(STAGE_ID, EPOCH_B, 0) }
        }
        val world = FakeWireWorld()
        val result = coordinator(store, gateway).place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.STALE, result.status)
        assertEquals(0, world.setCalls)
    }

    @Test fun `break reject preserves wire and success removes it without bind command`() {
        val rejectStore = syncedStore(1, withWire = true)
        val rejecting = FakeGraphGateway(rejectStore).apply { rejectStatus = 422 }
        val rejectWorld = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val rejected = coordinator(rejectStore, rejecting).remove(stage, player, target, rejectWorld).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.REJECTED, rejected.status)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, rejectWorld.cell(target))

        val successStore = syncedStore(1, withWire = true)
        val retainedEdge = GraphEdge(EDGE_ID, NODE_A, "out", NODE_B, "in", "CONTROL")
        val success = FakeGraphGateway(successStore).apply {
            sendSnapshot = wireSnapshot(2, withWire = false).copy(edges = listOf(retainedEdge))
        }
        val successWorld = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val removed = coordinator(successStore, success).remove(stage, player, target, successWorld).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.SUCCESS, removed.status)
        assertEquals(WireWorldCell.REPLACEABLE, successWorld.cell(target))
        assertTrue(success.commands.single() is WireLayoutCommand)
        assertNull((success.commands.single() as WireLayoutCommand).changes.single().cell)
        assertEquals(listOf(retainedEdge), successStore.view(STAGE_ID)!!.snapshot!!.edges)
    }

    @Test fun `render failure keeps Python authoritative and triggers bounded reconciliation`() {
        val store = syncedStore(0)
        val gateway = FakeGraphGateway(store).apply {
            sendSnapshot = wireSnapshot(1)
            reconcileSnapshot = wireSnapshot(1)
        }
        val world = FakeWireWorld().apply { failWrites = true }
        val coordinator = coordinator(store, gateway)
        val result = coordinator.place(stage, player, target, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.NEEDS_REPAIR, result.status)
        assertEquals(1, gateway.reconcileCalls)
        assertEquals(1, store.view(STAGE_ID)!!.snapshot!!.wireCells.size)
        assertEquals(WireWorldStatus.NEEDS_REPAIR, coordinator.worldStatus(STAGE_ID))
    }

    @Test fun `bind uses player identity renders green and repeated bind remains one edge`() {
        val store = syncedStore(1, withWire = true)
        val bound = wireSnapshot(2, edgeStatus = "VALID")
        val gateway = FakeGraphGateway(store).apply {
            sendSnapshots.add(bound)
            sendSnapshots.add(bound)
        }
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val coordinator = coordinator(store, gateway)
        assertEquals(WireActionStatus.SUCCESS, coordinator.bind(stage, player, world).get(3, TimeUnit.SECONDS).status)
        assertEquals(WireWorldCell.CK_WIRE_BOUND_VALID, world.cell(target))
        assertEquals(WireActionStatus.SUCCESS, coordinator.bind(stage, player, world).get(3, TimeUnit.SECONDS).status)
        assertEquals(2, gateway.commands.size)
        assertTrue(gateway.commands.all { it is BindEdgesCommand && it.player.uuid == PLAYER_ID })
        assertEquals(1, store.view(STAGE_ID)!!.snapshot!!.edges.size)
        assertEquals(1, world.setCalls)
    }

    @Test fun `bind rejection never produces false green`() {
        val store = syncedStore(1, withWire = true)
        val gateway = FakeGraphGateway(store).apply { rejectStatus = 422 }
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val result = coordinator(store, gateway).bind(stage, player, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.REJECTED, result.status)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(target))
    }

    @Test fun `bind conflict reconciles and remains non-green`() {
        val store = syncedStore(1, withWire = true)
        val gateway = FakeGraphGateway(store).apply {
            rejectStatus = 409
            reconcileSnapshot = wireSnapshot(2)
        }
        val world = FakeWireWorld(mutableMapOf(target to WireWorldCell.CK_WIRE_UNBOUND))
        val result = coordinator(store, gateway).bind(stage, player, world).get(3, TimeUnit.SECONDS)
        assertEquals(WireActionStatus.REJECTED, result.status)
        assertEquals(1, gateway.reconcileCalls)
        assertEquals(WireWorldCell.CK_WIRE_UNBOUND, world.cell(target))
    }

    private fun coordinator(
        store: GraphProjectionStore,
        gateway: FakeGraphGateway,
        requestId: UUID = REQUEST_ID,
    ) = WireInteractionCoordinator(store, gateway, WireWorldProjector(), direct) { requestId }

    private fun syncedStore(revision: Long, withWire: Boolean = false) = GraphProjectionStore().also { store ->
        store.authoritativeStageReload(STAGE_ID, EPOCH_A, 0)
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), wireSnapshot(revision, withWire = withWire))
    }
}

private class FakeGraphGateway(private val store: GraphProjectionStore) : GraphGateway {
    var reconcileCalls = 0
    val commands = mutableListOf<GraphCommand>()
    var reconcileSnapshot: GraphStateSnapshot? = null
    var sendSnapshot: GraphStateSnapshot? = null
    val sendSnapshots = ArrayDeque<GraphStateSnapshot>()
    var rejectStatus: Int? = null
    var rejectErrorCode = "REJECTED"
    var rejectDiagnostics: List<GraphDiagnostic> = emptyList()
    var afterSend: () -> Unit = {}

    override fun reconcile(stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<ReconciliationOutcome> {
        reconcileCalls++
        val snapshot = reconcileSnapshot ?: store.view(stageId)?.snapshot
            ?: return CompletableFuture.completedFuture(ReconciliationOutcome(GraphHttpResult.Unavailable("missing"), null))
        val applied = store.apply(store.begin(stageId, GraphRequestKind.RECONCILIATION), snapshot)
        return CompletableFuture.completedFuture(ReconciliationOutcome(GraphHttpResult.Success(success(snapshot, null)), applied))
    }

    override fun send(command: GraphCommand): CompletableFuture<CommandTransportOutcome> {
        commands += command
        rejectStatus?.let { status ->
            val error = GraphErrorResponse(
                GRAPH_API_VERSION, "error", status, command.requestId, command.stageId,
                command.workspaceEpoch, command.expectedWorkingRevision, false,
                rejectErrorCode, "rejected", rejectDiagnostics,
            )
            val transport = GraphHttpResult.ApiError(error)
            return if (status == 409 || status >= 500) reconcile(command.stageId, command.player).thenApply {
                CommandTransportOutcome(transport, null, it)
            } else CompletableFuture.completedFuture(CommandTransportOutcome(transport, null, null))
        }
        val snapshot = if (sendSnapshots.isEmpty()) requireNotNull(sendSnapshot) else sendSnapshots.removeFirst()
        val applied = store.apply(store.begin(command.stageId, GraphRequestKind.COMMAND), snapshot)
        afterSend()
        return CompletableFuture.completedFuture(CommandTransportOutcome(
            GraphHttpResult.Success(success(snapshot, command.requestId)), applied, null,
        ))
    }

    private fun success(snapshot: GraphStateSnapshot, requestId: UUID?) = GraphSuccessResponse(
        GRAPH_API_VERSION, "ok", requestId, snapshot.stageId, snapshot.workspaceEpoch,
        snapshot.workingRevision, true, emptyList(), emptyList(), null, snapshot,
    )
}
