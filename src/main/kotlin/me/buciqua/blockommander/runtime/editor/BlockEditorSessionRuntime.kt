package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** A UUID is not a connection identity. Old disconnects must not close a replacement connection. */
internal class EditorConnections(private val invalidated: (UUID) -> Unit) {
    private data class Connection(val token: Any, val generation: Long)
    private val current = mutableMapOf<UUID, Connection>()
    private val generations = mutableMapOf<UUID, Long>()
    fun join(player: UUID, token: Any) {
        if (current[player]?.token === token) return
        invalidated(player)
        val generation = Math.addExact(generations[player] ?: 0, 1)
        generations[player] = generation
        current[player] = Connection(token, generation)
    }
    fun disconnect(player: UUID, token: Any) {
        if (current[player]?.token !== token) return
        invalidated(player)
        current.remove(player)
    }
    fun generation(player: UUID, token: Any): Long? = current[player]?.takeIf { it.token === token }?.generation
}

/** Server-only facade. No packet, command or player interaction is registered by B3/B. */
internal class BlockEditorSessionRuntime(
    private val server: MinecraftServer, private val registry: HallRegistry, mainThread: Executor,
    transport: BlockEditorTransportAdapter = BlockEditorTransportAdapter(BlockEditorApiClient.fromEnvironment(), mainThread),
    private val programs: ProgramGateway = ProgramApiClient.fromEnvironment(),
    private val graph: GraphApiClient = GraphApiClient.fromEnvironment(),
) {
    var onChanged: (EditorSessionSnapshot) -> Unit = {}
    private val sessions = BlockEditorSessionCoordinator(
        ::recapture, transport::get, transport::apply, ::prepare, mainThread, { onChanged(it) },
    )
    private val connections = EditorConnections { sessions.invalidate(it, "CONNECTION_CHANGED") }

    fun joined(player: ServerPlayer) { checkThread(); connections.join(player.uuid, player.connection) }
    fun disconnected(player: ServerPlayer) { checkThread(); connections.disconnect(player.uuid, player.connection) }
    fun tick() { checkThread(); sessions.poll() }
    fun worldChanged(player: ServerPlayer) { checkThread(); sessions.invalidate(player.uuid, "WORLD_CHANGED") }
    fun shutdown() { checkThread(); sessions.shutdown() }

    fun open(player: ServerPlayer, hallId: UUID, instanceId: UUID): EditorSessionId? {
        checkThread()
        if (!live(player)) return null
        sessions.invalidate(player.uuid, "NEW_OPEN")
        // The Hall and instance are resolved and checked here, never supplied as a client context/CAS.
        val capture = capture(player, hallId, instanceId, null, UUID.randomUUID()) ?: return null
        return sessions.open(capture)
    }
    fun apply(player: ServerPlayer, id: EditorSessionId, changes: EditorChanges): Boolean {
        checkThread()
        return owns(player, id) && sessions.apply(id, changes)
    }
    fun snapshot(player: ServerPlayer, id: EditorSessionId): EditorSessionSnapshot? {
        checkThread()
        return if (owns(player, id)) sessions.snapshot(id) else null
    }
    fun close(player: ServerPlayer, id: EditorSessionId) {
        checkThread()
        if (owns(player, id)) sessions.close(id, "EXPLICIT_CLOSE")
    }
    fun withReconciliation(player: ServerPlayer, id: EditorSessionId, consumer: (PreparedStageSwitch) -> Unit): Boolean {
        checkThread()
        return owns(player, id) && sessions.withReconciliation(id, consumer)
    }
    private fun checkThread() = check(server.isSameThread) { "Editor sessions require the server thread" }
    private fun live(player: ServerPlayer) = server.playerList.getPlayer(player.uuid) === player &&
        connections.generation(player.uuid, player.connection) != null
    private fun owns(player: ServerPlayer, id: EditorSessionId) = live(player) && player.uuid == id.player &&
        connections.generation(player.uuid, player.connection) == id.connection

    private fun capture(player: ServerPlayer, hallId: UUID, instanceId: UUID, opened: EditorGetResult?, request: UUID): EditorCapture? {
        if (!live(player)) return null
        val connection = connections.generation(player.uuid, player.connection) ?: return null
        val hall = registry.hall(hallId.toString()) ?: return null
        // Crossing a Hall boundary closes the session, even if the displayed Stage is unchanged.
        val physicalHall = registry.inWorld(WorldIdentity.uuid(player.level()).toString())
            .singleOrNull { it.contains(player.blockPosition()) } ?: return null
        if (physicalHall.id != hall.id || registry.navigationState(hall.id)?.switchInProgress == true) return null
        val fence = registry.stageUpdateFenceForHall(hall.id) ?: return null
        if (!registry.isStageFenceCurrent(fence)) return null
        val context = try { captureBlockEditorContext(player, hall, instanceId, request, opened) }
            catch (_: Exception) { return null }
        return EditorCapture(context, EditorLifecycle(connection, fence.hallGeneration, fence.projectionGeneration,
            fence.navigationGeneration, fence.workspaceEpoch))
    }
    private fun recapture(original: EditorCapture, opened: EditorGetResult?, request: UUID): EditorCapture? {
        checkThread()
        val context = original.context
        val player = server.playerList.getPlayer(context.playerUuid) ?: return null
        return capture(player, context.transport.hallId, context.instanceId, opened, request)
    }

    private fun prepare(capture: EditorCapture, editor: EditorGetResult): CompletableFuture<PreparedStageSwitch> {
        checkThread()
        val context = capture.context
        val hall = requireNotNull(registry.hall(context.transport.hallId.toString()))
        val player = requireNotNull(server.playerList.getPlayer(context.playerUuid))
        // Pure HTTP clients: unlike GraphTransportAdapter.reconcile, these do not touch projection stores.
        return programs.stage(context.transport, context.stageId).thenCombine(
            graph.getState(context.transport, context.stageId, ServerPlayerIdentity.from(player)),
        ) { stageResult, graphResult ->
            val stage = (stageResult as? ProgramHttpResult.Success)?.value ?: error("STAGE_GET_FAILED")
            val state = (graphResult as? GraphHttpResult.Success)?.response?.state ?: error("GRAPH_GET_FAILED")
            BlockEditorReconciliation.assemble(hall, capture, editor, stage, state)
        }
    }
}
