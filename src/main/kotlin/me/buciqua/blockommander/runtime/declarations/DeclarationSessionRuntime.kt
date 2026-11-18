package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.editor.EditorConnections
import me.buciqua.blockommander.runtime.editor.MinecraftMetadataProjectionWorld
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import me.buciqua.blockommander.runtime.wire.WireInteractionCoordinator
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal fun captureDeclarationHall(registry: HallRegistry, player: UUID, world: UUID, position: net.minecraft.core.BlockPos): Hall? =
    registry.inWorld(world.toString()).singleOrNull { it.contains(position) }?.takeIf {
        it.ownerUuid == player.toString() && it.stage != null && registry.editingAllowed(it.id) &&
            registry.navigationState(it.id)?.switchInProgress == false && it.transportContext.hallId.toString() == it.id
    }

internal class DeclarationSessionRuntime(private val server: MinecraftServer, private val registry: HallRegistry,
    private val main: Executor, wires: WireInteractionCoordinator,
    api: DeclarationGateway = DeclarationApiClient.fromEnvironment(),
    private val programs: ProgramGateway = ProgramApiClient.fromEnvironment(),
    private val graph: GraphApiClient = GraphApiClient.fromEnvironment()) {
    var onChanged: (DeclarationSessionSnapshot) -> Unit = {}
    var onOpening: (UUID) -> Unit = {}
    private val reconciler = DeclarationReconciliation(registry, wires)
    private val sessions = DeclarationSessionCoordinator(api, ::current, ::stamp, ::reconcile, main,
        { snapshot ->
            if (snapshot.state == DeclarationState.PENDING) markDirty(snapshot.capture.context.transport.programId)
            onChanged(snapshot)
        }, { context, except -> sessionsChanged(context, except) })
    private val connections = EditorConnections { sessions.invalidate(it, "CONNECTION_CHANGED") }
    private fun sessionsChanged(context: DeclarationContext, except: EditorSessionKey) { sessions.programChanged(context, except) }
    private fun checkThread() = check(server.isSameThread)
    fun joined(player: ServerPlayer) { checkThread(); connections.join(player.uuid, player.connection) }
    fun disconnected(player: ServerPlayer) { checkThread(); connections.disconnect(player.uuid, player.connection) }
    fun worldChanged(player: ServerPlayer) { checkThread(); sessions.invalidate(player.uuid, "WORLD_CHANGED") }
    fun tick() { checkThread(); sessions.poll() }
    fun shutdown() { checkThread(); sessions.shutdown() }
    fun invalidate(player: UUID, reason: String) { checkThread(); sessions.invalidate(player, reason) }
    fun open(player: ServerPlayer): Boolean {
        checkThread()
        val generation = connections.generation(player.uuid, player.connection) ?: return false
        if (server.playerList.getPlayer(player.uuid) !== player) return false
        val world = WorldIdentity.uuid(player.level())
        val hall = captureDeclarationHall(registry, player.uuid, world, player.blockPosition()) ?: return false
        val fence = registry.stageUpdateFenceForHall(hall.id) ?: return false
        if (!registry.isStageFenceCurrent(fence)) return false
        val context = DeclarationContext(player.uuid, world, hall.transportContext, UUID.fromString(hall.stage!!.id))
        if (WorldIdentity.dimension(player.level()).length > 128) return false
        onOpening(player.uuid)
        return sessions.open(DeclarationCapture(context, generation, fence, WorldIdentity.dimension(player.level()))) != null
    }
    fun snapshot(player: ServerPlayer, key: EditorSessionKey): DeclarationSessionSnapshot? {
        checkThread()
        return if (owns(player, key)) sessions.snapshot(key) else null
    }
    fun intent(player: ServerPlayer, intent: DeclarationIntent): Boolean {
        checkThread()
        return owns(player, intent.session) && sessions.intent(player.uuid, intent.session, intent.operation, intent.action.name, intent.target, intent.fields)
    }
    private fun owns(player: ServerPlayer, key: EditorSessionKey) = key.player == player.uuid && server.playerList.getPlayer(player.uuid) === player &&
        connections.generation(player.uuid, player.connection) == key.connection
    private fun current(capture: DeclarationCapture): Boolean {
        checkThread()
        val c = capture.context; val player = server.playerList.getPlayer(c.player) ?: return false
        if (connections.generation(player.uuid, player.connection) != capture.connection || WorldIdentity.uuid(player.level()) != c.world ||
            WorldIdentity.dimension(player.level()) != capture.dimension) return false
        val hall = captureDeclarationHall(registry, c.player, c.world, player.blockPosition()) ?: return false
        return hall.id == c.transport.hallId.toString() && hall.transportContext == c.transport && hall.stage?.id == c.stage.toString() &&
            hall.stage.workspaceEpoch == capture.fence.workspaceEpoch && registry.isStageFenceCurrent(capture.fence)
    }
    private fun stamp(capture: DeclarationCapture): Any = server.allLevels.flatMap { registry.inWorld(WorldIdentity.uuid(it).toString()) }
        .filter { it.transportContext.programId == capture.context.transport.programId }.sortedBy { it.id }.map {
            listOf(it.id, it.stage?.id, it.stage?.workspaceEpoch, it.stage?.workingRevision, it.programDirty,
                registry.graphProjections.view(it.projectionKey())?.stageRevision,
                registry.graphProjections.view(it.projectionKey())?.status,
                registry.navigationState(it.id)?.programWorkspaceEpoch, registry.navigationState(it.id)?.hierarchyRevision)
        }
    private fun markDirty(program: UUID) {
        server.allLevels.forEach { level ->
            registry.inWorld(WorldIdentity.uuid(level).toString()).filter { it.transportContext.programId == program }.forEach { hall ->
                registry.beginProgramStatus(hall.id) // invalidates old clean-status replies, pessimistically dirty
                registry.hall(hall.id)?.let { WorldRenderer.renderSaveState(level, it, true) }
            }
        }
    }
    private fun reconcile(capture: DeclarationCapture, view: DeclarationPage, stillCurrent: () -> Boolean): CompletableFuture<DeclarationProjection> {
        checkThread()
        val c = capture.context
        val hall = registry.hall(c.transport.hallId.toString()) ?: return CompletableFuture.completedFuture(DeclarationProjection(false, null))
        val observed = registry.graphProjections.observe(c.transport.projectionKey(c.stage)) ?: return CompletableFuture.completedFuture(DeclarationProjection(false, null))
        val player = server.playerList.getPlayer(c.player) ?: return CompletableFuture.completedFuture(DeclarationProjection(false, null))
        return programs.stage(c.transport, c.stage).thenCombine(graph.getState(c.transport, c.stage, ServerPlayerIdentity.from(player))) { a, b ->
            val response = (a as? ProgramHttpResult.Success)?.value ?: error("STAGE_GET_FAILED")
            val state = (b as? GraphHttpResult.Success)?.response?.state ?: error("GRAPH_GET_FAILED")
            StageProjectionAssembler.assemble(hall, response, state, capture.fence.projectionGeneration, physicalSelection = true)
        }.thenApplyAsync({ frame ->
            val committed = stillCurrent() && current(capture) && reconciler.commit(capture, view, hall, observed, frame, MinecraftMetadataProjectionWorld(player.level()), stillCurrent)
            DeclarationProjection(committed, stamp(capture))
        }, main)
    }
}
