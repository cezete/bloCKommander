package me.buciqua.blockommander.client.screen

import me.buciqua.blockommander.network.OpenFormPayload
import me.buciqua.blockommander.network.SubmitFormPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class CkFormScreen(private val payload: OpenFormPayload) : Screen(
    Component.literal(if (payload.kind == "hall") "Új fejlesztői csarnok" else "CK-blokk szerkesztése")
) {
    private lateinit var first: EditBox
    private lateinit var second: EditBox

    override fun init() {
        val left = width / 2 - 120
        first = EditBox(font, left, height / 2 - 45, 240, 20,
            Component.literal(if (payload.kind == "hall") "Csarnokkód" else "Felirat"))
        first.setMaxLength(if (payload.kind == "hall") 24 else 32); first.value = payload.value1
        second = EditBox(font, left, height / 2 - 10, 240, 20,
            Component.literal(if (payload.kind == "hall") "Csarnoknév" else "Leírás"))
        second.setMaxLength(if (payload.kind == "hall") 96 else 256); second.value = payload.value2
        addRenderableWidget(first); addRenderableWidget(second)
        addRenderableWidget(Button.builder(Component.literal("Mentés")) {
            ClientPlayNetworking.send(SubmitFormPayload(payload.kind, payload.contextId, first.value, second.value))
            minecraft.gui.setScreen(null)
        }.bounds(left, height / 2 + 30, 115, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Mégse")) { minecraft.gui.setScreen(null) }
            .bounds(left + 125, height / 2 + 30, 115, 20).build())
        setInitialFocus(first)
    }
}
