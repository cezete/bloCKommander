package me.buciqua.blockommander.client

import net.fabricmc.api.ClientModInitializer
import me.buciqua.blockommander.client.screen.CkFormScreen
import me.buciqua.blockommander.network.OpenFormPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

class BlockCommanderClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(OpenFormPayload.TYPE) { payload, context ->
            context.client().execute { context.client().gui.setScreen(CkFormScreen(payload)) }
        }
    }
}
