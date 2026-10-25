package me.buciqua.blockommander

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import me.buciqua.blockommander.runtime.ApiClient
import me.buciqua.blockommander.runtime.HallRegistry
import me.buciqua.blockommander.runtime.Interactions
import me.buciqua.blockommander.runtime.graph.GraphApiClient
import me.buciqua.blockommander.runtime.graph.GraphTransportAdapter
import me.buciqua.blockommander.runtime.wire.WireInteractionCoordinator
import me.buciqua.blockommander.runtime.wire.WireWorldProjector
import me.buciqua.blockommander.network.Networking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

class BlockCommanderMod : ModInitializer {
    companion object {
        const val MOD_ID = "blockommander"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
        @Volatile var graphTransport: GraphTransportAdapter? = null
            private set
        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
    }

    override fun onInitialize() {
        ModContent.initialize()
        val registry = HallRegistry()
        val wireCoordinator = AtomicReference<WireInteractionCoordinator?>()
        val interactions = Interactions(ApiClient(), registry, wireCoordinator::get)
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val mainThread = Executor(server::execute)
            val transport = GraphTransportAdapter(
                GraphApiClient.fromEnvironment(), registry.graphProjections, Executor(server::execute),
            )
            graphTransport = transport
            wireCoordinator.set(WireInteractionCoordinator(
                registry.graphProjections, transport, WireWorldProjector(), mainThread,
            ))
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            wireCoordinator.set(null)
            graphTransport = null
        }
        Networking.register(interactions)
        interactions.register()
        LOGGER.info("bloCKommander Fabric initialized")
    }
}
