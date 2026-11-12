package me.buciqua.blockommander.network

import me.buciqua.blockommander.runtime.Interactions
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import me.buciqua.blockommander.BlockCommanderMod
import me.buciqua.blockommander.runtime.editor.*

object Networking {
    fun register(interactions: Interactions) {
        PayloadTypeRegistry.clientboundPlay().register(OpenFormPayload.TYPE, OpenFormPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(SubmitFormPayload.TYPE, SubmitFormPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(EditorStatePayload.TYPE, EditorStatePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(EditorIntentPayload.TYPE, EditorIntentPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(SubmitFormPayload.TYPE) { payload, context ->
            context.server().execute { interactions.submitForm(context.player(), payload) }
        }
        ServerPlayNetworking.registerGlobalReceiver(EditorIntentPayload.TYPE) { payload, context ->
            context.server().execute {
                val player = context.player()
                val runtime = BlockCommanderMod.editorSessions ?: return@execute
                val port = object : EditorIntentPort {
                    override fun current(key: EditorSessionKey) = runtime.snapshot(player, key.id())?.state?.let { it != EditorSessionState.CLOSED } == true
                    override fun apply(intent: EditorIntent) = runtime.apply(player, intent.session.id(), EditorChanges(intent.label.domain(), intent.description.domain()))
                    override fun close(key: EditorSessionKey) = runtime.close(player, key.id())
                    override fun reload(key: EditorSessionKey) = runtime.reload(player, key.id())
                    override fun reconcile(key: EditorSessionKey) = runtime.reconcileAgain(player, key.id())
                }
                if (!EditorIntentRouter.route(player.uuid, payload.intent, port)) {
                    // Only the authenticated player's still-current session may receive a resync.
                    runtime.snapshot(player, payload.intent.session.id())?.let { runtime.onChanged(it) }
                }
            }
        }
    }
}
