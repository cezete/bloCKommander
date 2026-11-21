package me.buciqua.blockommander.client.screen

import me.buciqua.blockommander.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.UUID

internal class LetScreen(initial: LetScreenState, private val dismiss: (EditorSessionKey) -> Unit) :
    Screen(Component.literal("CK LET")) {
    val session = initial.session
    private var state = initial
    private var selected: UUID? = null
    private var offset = 0
    private var waiting = false
    private var sentClose = false
    private var left = 0
    private var contentWidth = 0

    fun receive(next: LetScreenState) {
        if (next.session != session || next.sequence <= state.sequence) return
        if (next.page != state.page || !next.ready) { offset = 0; selected = null }
        state = next
        waiting = false
        rebuildWidgets()
    }

    override fun init() {
        contentWidth = (width - 16).coerceIn(130, 480)
        left = (width - contentWidth) / 2
        val count = ((height - 103) / 19).coerceIn(1, 16)
        val lastOffset = (state.rows.size - count).coerceAtLeast(0)
        offset = offset.coerceIn(0, lastOffset)
        fun button(text: String, x: Int, y: Int, w: Int, active: Boolean = true, action: () -> Unit) =
            addRenderableWidget(Button.builder(Component.literal(text)) { action() }.bounds(x, y, w, 19).build())
                .also { it.active = active }
        val ready = state.ready && !waiting
        state.rows.drop(offset).take(count).forEachIndexed { index, row ->
            val description = "${row.name}   ${row.type} · ${row.mutability} · ${row.origin}"
            val prefix = if (selected == row.id) "› " else ""
            button(font.plainSubstrByWidth(prefix + description, contentWidth - 42), left, 46 + index * 19,
                contentWidth - 30, ready) {
                selected = row.id
                rebuildWidgets()
            }.setTooltip(Tooltip.create(Component.literal("$description\nDeklaráló Stage: ${row.owner}\nUUID: ${row.id}")))
        }
        button("▲", left + contentWidth - 26, 46, 26, ready && offset > 0) { offset -= count; rebuildWidgets() }
        button("▼", left + contentWidth - 26, 65, 26, ready && offset < lastOffset) { offset += count; rebuildWidgets() }
        val column = (contentWidth - 6) / 3
        val bottom = height - 24
        button("Előző", left, bottom - 22, column, ready && state.page > 0) { send(LetAction.PREVIOUS) }
        button("Következő", left + column + 3, bottom - 22, column, ready && state.hasNext) { send(LetAction.NEXT) }
        button("Frissítés", left + 2 * (column + 3), bottom - 22, column, !waiting) { send(LetAction.REFRESH) }
        button("Alkalmaz", left, bottom, column, ready && selected != null) { send(LetAction.APPLY, selected) }
        button("VStage megnyitása", left + column + 3, bottom, column, ready && state.target != null) { send(LetAction.ENTER) }
        button("Bezárás", left + 2 * (column + 3), bottom, column) { onClose() }
    }

    private fun send(action: LetAction, target: UUID? = null) {
        if (waiting && action != LetAction.CLOSE) return
        if (ClientPlayNetworking.canSend(LetIntentPayload.TYPE))
            ClientPlayNetworking.send(LetIntentPayload(session, state.sequence, action, target))
        if (action != LetAction.CLOSE) { waiting = true; rebuildWidgets() }
    }

    override fun onClose() { minecraft.gui.setScreen(null) }
    override fun removed() {
        if (!sentClose) { sentClose = true; send(LetAction.CLOSE); dismiss(session) }
        super.removed()
    }
    override fun isPauseScreen() = false
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fillGradient(0, 0, width, height, 0xF0101018.toInt(), 0xF0101018.toInt())
    }
    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        fun line(text: String, y: Int) {
            g.text(font, Component.literal(font.plainSubstrByWidth(text, contentWidth)), left, y, 0xFFE0E0E0.toInt())
        }
        line("LET  ${state.block}", 6)
        val approved = state.rows.firstOrNull { it.id == state.target }?.let { "${it.name} (${it.mutability}) · ${it.id}" }
        line("Alkalmazott cél  ${approved ?: state.target ?: "nincs"}", 18)
        line("${state.page + 1}. oldal   ${if (waiting) "Várakozás…" else state.status}", 30)
    }
}
