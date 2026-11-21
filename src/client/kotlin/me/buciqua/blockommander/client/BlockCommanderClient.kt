package me.buciqua.blockommander.client

import net.fabricmc.api.ClientModInitializer
import me.buciqua.blockommander.client.screen.CkFormScreen
import me.buciqua.blockommander.network.OpenFormPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import me.buciqua.blockommander.client.screen.BlockEditorScreen
import me.buciqua.blockommander.client.screen.DeclarationScreen
import me.buciqua.blockommander.client.screen.LetScreen
import me.buciqua.blockommander.runtime.declarations.DeclarationState
import me.buciqua.blockommander.network.*

class BlockCommanderClient : ClientModInitializer {
    override fun onInitializeClient() {
        var model: EditorUiModel? = null
        var declarations: DeclarationUiModel? = null
        var surfaces: EditorSurfaceFence? = null
        var levelIdentity: Any? = null
        val connections = EditorClientConnectionFence()
        ClientPlayConnectionEvents.JOIN.register { handler, _, client ->
            connections.join(handler); model?.transportClosed(); declarations?.transportClosed()
            model = null; declarations = null; surfaces = null; levelIdentity = client.level
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorStatePayload.TYPE) { payload, context ->
            val connection = connections.token
            val receivedLevel = context.client().level
            context.client().execute {
                if (!connections.current(connection)) return@execute
                val client = context.client(); val player = client.player ?: return@execute
                if (client.level !== receivedLevel || client.level?.dimension()?.identifier()?.toString() != payload.snapshot.dimension) return@execute
                val fence = surfaces ?: EditorSurfaceFence(player.uuid).also { surfaces = it }
                if (!fence.receive("BLOCK", payload.snapshot.session, payload.snapshot.sequence, payload.snapshot.state == EditorUiState.CLOSED)) return@execute
                val current = model ?: EditorUiModel(player.uuid).also { model = it; levelIdentity = client.level }
                if (current.receive(payload.snapshot)) {
                    if (payload.snapshot.state != EditorUiState.CLOSED) declarations?.transportClosed()
                    val screen = client.gui.screen()
                    if (screen is BlockEditorScreen && screen.model === current && screen.session == payload.snapshot.session) screen.refresh()
                    else if (payload.snapshot.state != EditorUiState.CLOSED) client.gui.setScreen(BlockEditorScreen(current))
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(DeclarationStatePayload.TYPE) { payload, context ->
            val connection = connections.token
            val receivedLevel = context.client().level
            context.client().execute {
                if (!connections.current(connection)) return@execute
                val client = context.client(); val player = client.player ?: return@execute
                if (client.level !== receivedLevel || client.level?.dimension()?.identifier()?.toString() != payload.snapshot.dimension) return@execute
                val fence = surfaces ?: EditorSurfaceFence(player.uuid).also { surfaces = it }
                if (!fence.receive("DECLARATION", payload.snapshot.session, payload.snapshot.sequence, payload.snapshot.state == DeclarationState.CLOSED)) return@execute
                val current = declarations ?: DeclarationUiModel(player.uuid).also { declarations = it; levelIdentity = client.level }
                if (current.receive(payload.snapshot)) {
                    if (payload.snapshot.state != DeclarationState.CLOSED) model?.transportClosed()
                    val screen = client.gui.screen()
                    if (screen is DeclarationScreen && screen.model === current && screen.session == payload.snapshot.session) screen.refresh()
                    else if (payload.snapshot.state != DeclarationState.CLOSED) client.gui.setScreen(DeclarationScreen(current))
                }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(LetStatePayload.TYPE) { payload, context ->
            val connection = connections.token
            val receivedLevel = context.client().level
            context.client().execute {
                if (!connections.current(connection)) return@execute
                val client = context.client()
                val player = client.player ?: return@execute
                val state = payload.state
                if (client.level !== receivedLevel || client.level?.dimension()?.identifier()?.toString() != state.dimension) return@execute
                val fence = surfaces ?: EditorSurfaceFence(player.uuid).also { surfaces = it }
                if (!fence.receive("LET", state.session, state.sequence, state.closed)) return@execute
                val screen = client.gui.screen()
                if (state.closed) {
                    if (screen is LetScreen && screen.session == state.session) client.gui.setScreen(null)
                } else if (screen is LetScreen && screen.session == state.session) screen.receive(state)
                else {
                    model?.transportClosed(); declarations?.transportClosed()
                    levelIdentity = client.level
                    client.gui.setScreen(LetScreen(state) { fence.dismiss("LET", it) })
                }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            if (connections.disconnect(handler)) {
                model?.transportClosed(); declarations?.transportClosed(); surfaces?.invalidate()
                model = null; declarations = null; surfaces = null; levelIdentity = null
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if ((model != null || declarations != null || client.gui.screen() is LetScreen) && levelIdentity !== client.level) {
                model?.transportClosed()
                declarations?.transportClosed(); surfaces?.invalidate()
                if (client.gui.screen() is BlockEditorScreen || client.gui.screen() is DeclarationScreen ||
                    client.gui.screen() is LetScreen) client.gui.setScreen(null)
                levelIdentity = client.level
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(OpenFormPayload.TYPE) { payload, context ->
            context.client().execute { context.client().gui.setScreen(CkFormScreen(payload)) }
        }
    }
}
