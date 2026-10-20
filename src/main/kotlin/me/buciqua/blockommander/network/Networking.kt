package me.buciqua.blockommander.network

import me.buciqua.blockommander.runtime.Interactions
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object Networking {
    fun register(interactions: Interactions) {
        PayloadTypeRegistry.clientboundPlay().register(OpenFormPayload.TYPE, OpenFormPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SubmitFormPayload.TYPE, SubmitFormPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(SubmitFormPayload.TYPE) { payload, context ->
            interactions.submitForm(context.player(), payload)
        }
    }
}
