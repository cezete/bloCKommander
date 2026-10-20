package me.buciqua.blockommander

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import me.buciqua.blockommander.runtime.ApiClient
import me.buciqua.blockommander.runtime.HallRegistry
import me.buciqua.blockommander.runtime.Interactions
import me.buciqua.blockommander.network.Networking
import org.slf4j.LoggerFactory

class BlockCommanderMod : ModInitializer {
    companion object {
        const val MOD_ID = "blockommander"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)
        fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
    }

    override fun onInitialize() {
        ModContent.initialize()
        val interactions = Interactions(ApiClient(), HallRegistry())
        Networking.register(interactions)
        interactions.register()
        LOGGER.info("bloCKommander Fabric initialized")
    }
}
