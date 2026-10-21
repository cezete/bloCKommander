package me.buciqua.blockommander.client.screen

import me.buciqua.blockommander.network.OpenFormPayload
import me.buciqua.blockommander.network.SubmitFormPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
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
            Component.literal(if (payload.kind == "hall") "Csarnok neve" else "Felirat"))
        first.setMaxLength(if (payload.kind == "hall") 96 else 32)
        first.value = payload.value1
        if (payload.kind == "hall") first.setHint(Component.literal("pl. Fejlesztői csarnok"))
        second = EditBox(font, left, height / 2 - 10, 240, 20,
            Component.literal(if (payload.kind == "hall") "Belső magasság" else "Leírás"))
        second.setMaxLength(if (payload.kind == "hall") 2 else 256)
        second.value = payload.value2
        if (payload.kind == "hall") second.setHint(Component.literal("pl. 7"))
        addRenderableWidget(first); addRenderableWidget(second)
        addRenderableWidget(Button.builder(Component.literal(if (payload.kind == "hall") "Létrehozás" else "Mentés")) {
            ClientPlayNetworking.send(SubmitFormPayload(payload.kind, payload.contextId, first.value, second.value))
            minecraft.gui.setScreen(null)
        }.bounds(left, height / 2 + 30, 115, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Mégse")) { minecraft.gui.setScreen(null) }
            .bounds(left + 125, height / 2 + 30, 115, 20).build())
        setInitialFocus(first)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fillGradient(0, 0, width, height, 0xB0101018.toInt(), 0xD0101018.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val left = width / 2 - 120
        graphics.centeredText(font, title, width / 2, height / 2 - 82, 0xFFFFFFFF.toInt())
        graphics.text(font, Component.literal(if (payload.kind == "hall") "Csarnok neve" else "Felirat"),
            left, height / 2 - 58, 0xFFD8D8D8.toInt())
        graphics.text(font, Component.literal(if (payload.kind == "hall") "Belső magasság (blokkokban)" else "Leírás"),
            left, height / 2 - 23, 0xFFD8D8D8.toInt())
    }
}
