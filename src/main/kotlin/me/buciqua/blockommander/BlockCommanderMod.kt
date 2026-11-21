package me.buciqua.blockommander

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import me.buciqua.blockommander.runtime.ApiClient
import me.buciqua.blockommander.runtime.HallRegistry
import me.buciqua.blockommander.runtime.Interactions
import me.buciqua.blockommander.runtime.graph.GraphApiClient
import me.buciqua.blockommander.runtime.graph.GraphTransportAdapter
import me.buciqua.blockommander.runtime.program.ProgramApiClient
import me.buciqua.blockommander.runtime.program.StageNavigationCoordinator
import me.buciqua.blockommander.runtime.program.HallSelectionCoordinator
import me.buciqua.blockommander.runtime.program.HallSelectionApiClient
import me.buciqua.blockommander.runtime.wire.WireInteractionCoordinator
import me.buciqua.blockommander.runtime.wire.WireWorldProjector
import me.buciqua.blockommander.network.Networking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import me.buciqua.blockommander.runtime.editor.BlockEditorSessionRuntime
import me.buciqua.blockommander.runtime.editor.EditorStateMapper
import me.buciqua.blockommander.runtime.declarations.DeclarationSessionRuntime
import me.buciqua.blockommander.runtime.declarations.DeclarationApiClient
import me.buciqua.blockommander.runtime.value.LetApi
import me.buciqua.blockommander.runtime.value.LetRuntime
import me.buciqua.blockommander.network.DeclarationStatePayload
import me.buciqua.blockommander.network.ui
import me.buciqua.blockommander.runtime.WorldIdentity
import me.buciqua.blockommander.network.EditorStatePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class BlockCommanderMod : ModInitializer {
    companion object {
        const val MOD_ID = "blockommander"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
        @Volatile var graphTransport: GraphTransportAdapter? = null
            private set
        @Volatile internal var editorSessions: BlockEditorSessionRuntime? = null
            private set
        @Volatile internal var declarationSessions: DeclarationSessionRuntime? = null
            private set
        @Volatile internal var letRuntime: LetRuntime? = null
            private set
        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
    }

    override fun onInitialize() {
        ModContent.initialize()
        val registry = HallRegistry()
        val wireCoordinator = AtomicReference<WireInteractionCoordinator?>()
        val stageNavigation = AtomicReference<StageNavigationCoordinator?>()
        val hallSelection = AtomicReference<HallSelectionCoordinator?>()
        val interactions = Interactions(ApiClient(), registry, wireCoordinator::get, stageNavigation::get,
            hallSelection=hallSelection::get)
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val mainThread = Executor(server::execute)
            val transport = GraphTransportAdapter(
                GraphApiClient.fromEnvironment(), registry.graphProjections, Executor(server::execute),
            )
            graphTransport = transport
            wireCoordinator.set(WireInteractionCoordinator(
                registry.graphProjections, transport, WireWorldProjector(), mainThread,
                projectionFence = registry::permitsWireProjection,
            ))
            editorSessions = BlockEditorSessionRuntime(server, registry, mainThread, wires = wireCoordinator.get())
            declarationSessions = DeclarationSessionRuntime(server, registry, mainThread, wireCoordinator.get()!!)
            val editorSequences = mutableMapOf<java.util.UUID, Long>()
            fun nextSequence(player:java.util.UUID):Long =
                Math.addExact(editorSequences[player] ?: 0L,1L).also { editorSequences[player]=it }
            editorSessions!!.onChanged = { snapshot ->
                val player = server.playerList.getPlayer(snapshot.id.player)
                if (player != null && editorSessions?.snapshot(player, snapshot.id) != null &&
                    ServerPlayNetworking.canSend(player, EditorStatePayload.TYPE)) {
                    val sequence = nextSequence(player.uuid)
                    val dimension = server.allLevels.firstOrNull { WorldIdentity.uuid(it) == snapshot.target.worldUuid }
                        ?.let(WorldIdentity::dimension) ?: ""
                    ServerPlayNetworking.send(player, EditorStatePayload(EditorStateMapper.map(snapshot, sequence).copy(dimension = dimension)))
                }
            }
            declarationSessions!!.onChanged = { snapshot ->
                val player = server.playerList.getPlayer(snapshot.key.player)
                if (player != null && declarationSessions?.snapshot(player, snapshot.key) != null &&
                    ServerPlayNetworking.canSend(player, DeclarationStatePayload.TYPE)) {
                    val sequence = nextSequence(player.uuid)
                    ServerPlayNetworking.send(player, DeclarationStatePayload(snapshot.ui(sequence)))
                }
            }
            server.playerList.players.forEach { editorSessions?.joined(it); declarationSessions?.joined(it) }
            val programApi=ProgramApiClient.fromEnvironment()
            val navigation=StageNavigationCoordinator(registry,programApi,transport,mainThread)
            stageNavigation.set(navigation)
            letRuntime=LetRuntime(registry,navigation,LetApi(programApi),DeclarationApiClient.fromEnvironment(),mainThread,::nextSequence)
            server.playerList.players.forEach { letRuntime?.joined(it) }
            letRuntime!!.onOpening = { editorSessions?.invalidate(it,"LET_OPENED"); declarationSessions?.invalidate(it,"LET_OPENED") }
            editorSessions!!.onOpening = {
                declarationSessions?.invalidate(it,"BLOCK_EDITOR_OPENED")
                server.playerList.getPlayer(it)?.let { player -> letRuntime?.invalidate(player) }
            }
            declarationSessions!!.onOpening = {
                editorSessions?.invalidate(it,"DECLARATION_EDITOR_OPENED")
                server.playerList.getPlayer(it)?.let { player -> letRuntime?.invalidate(player) }
            }
            hallSelection.set(HallSelectionCoordinator(registry,HallSelectionApiClient.fromEnvironment(),
                stageNavigation.get()!!,mainThread))
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            editorSessions = null
            declarationSessions = null
            letRuntime = null
            wireCoordinator.set(null)
            stageNavigation.set(null)
            hallSelection.set(null)
            graphTransport = null
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { editorSessions?.shutdown(); declarationSessions?.shutdown(); letRuntime?.shutdown() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            editorSessions?.joined(handler.player); declarationSessions?.joined(handler.player); letRuntime?.joined(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            editorSessions?.disconnected(handler.player); declarationSessions?.disconnected(handler.player); letRuntime?.disconnected(handler.player)
        }
        ServerTickEvents.END_SERVER_TICK.register { editorSessions?.tick(); declarationSessions?.tick(); letRuntime?.tick() }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ ->
            editorSessions?.worldChanged(player); declarationSessions?.worldChanged(player); letRuntime?.worldChanged(player)
        }
        Networking.register(interactions)
        interactions.register()
        LOGGER.info("bloCKommander Fabric initialized")
        me.buciqua.blockommander.runtime.Diagnostics.event("DIAGNOSTICS_READY",null,"schema" to "ck-diag2-v1")
    }
}
