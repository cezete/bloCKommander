package me.buciqua.blockommander.client

import net.fabricmc.api.ClientModInitializer
import me.buciqua.blockommander.client.screen.CkFormScreen
import me.buciqua.blockommander.network.OpenFormPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import me.buciqua.blockommander.client.screen.BlockEditorScreen
import me.buciqua.blockommander.network.*

class BlockCommanderClient : ClientModInitializer {
    override fun onInitializeClient() {
        var model: EditorUiModel? = null
        var levelIdentity: Any? = null
        val connections = EditorClientConnectionFence()
        ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
            connections.join(handler); model?.transportClosed(); model = null; levelIdentity = client.level
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorStatePayload.TYPE) { payload, context ->
            val connection = connections.token
            context.client().execute {
                if (!connections.current(connection)) return@execute
                val client = context.client(); val player = client.player ?: return@execute
                val current = model ?: EditorUiModel(player.uuid).also { model = it; levelIdentity = client.level }
                if (current.receive(payload.snapshot)) {
                    val screen = client.gui.screen()
                    if (screen is BlockEditorScreen && screen.model === current && screen.session == payload.snapshot.session) screen.refresh()
                    else if (payload.snapshot.state != EditorUiState.CLOSED) client.gui.setScreen(BlockEditorScreen(current))
                }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (connections.disconnect(handler)) { model?.transportClosed(); model = null; levelIdentity = null }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (model != null && levelIdentity !== client.level) {
                model?.transportClosed()
                if (client.gui.screen() is BlockEditorScreen) client.gui.setScreen(null)
                levelIdentity = client.level
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(OpenFormPayload.TYPE) { payload, context ->
            context.client().execute { context.client().gui.setScreen(CkFormScreen(payload)) }
        }
    }
}
