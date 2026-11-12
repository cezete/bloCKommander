package me.buciqua.blockommander.client.screen

import me.buciqua.blockommander.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class BlockEditorScreen(val model: EditorUiModel) : Screen(Component.literal("CK Block Editor")) {
    val session = model.snapshot!!.session
    private lateinit var label: EditBox
    private lateinit var description: EditBox
    private lateinit var apply: Button
    private lateinit var reload: Button
    private lateinit var reconcile: Button
    private lateinit var clear: Button
    private var confirmingReload = false
    private var syncing = false

    override fun init() {
        val left = width / 2 - 150; val top = ((height - 244) / 2).coerceAtLeast(0)
        label = EditBox(font, left, top + 34, 300, 20, Component.literal("Felirat"))
        label.setMaxLength(128); label.value = model.label
        label.setResponder { if (!syncing) { model.editLabel(it); confirmingReload = false; refreshControls() } }
        description = EditBox(font, left, top + 82, 300, 20, Component.literal("Leírás"))
        description.setMaxLength(1024); description.value = model.description
        description.setResponder { if (!syncing) { model.editDescription(it); confirmingReload = false; refreshControls() } }
        addRenderableWidget(label); addRenderableWidget(description)
        clear = addRenderableWidget(Button.builder(Component.literal("Leírás: null")) {
            model.clearDescription(); refresh()
        }.bounds(left, top + 108, 140, 20).build())
        apply = addRenderableWidget(Button.builder(Component.literal("Alkalmazás")) { send(EditorIntentAction.APPLY) }
            .bounds(left, top + 196, 95, 20).build())
        reload = addRenderableWidget(Button.builder(Component.literal("Újratöltés")) {
            if (model.dirty && !confirmingReload) { confirmingReload = true; refreshControls() }
            else send(EditorIntentAction.RELOAD, confirmingReload)
        }.bounds(left + 100, top + 196, 95, 20).build())
        reconcile = addRenderableWidget(Button.builder(Component.literal("Projekció frissítése")) { send(EditorIntentAction.RECONCILE) }
            .bounds(left, top + 220, 195, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Bezárás")) {
            if (confirmingReload) { confirmingReload = false; refreshControls() } else onClose()
        }.bounds(left + 200, top + 196, 100, 20).build())
        refreshControls(); setInitialFocus(label)
    }
    private fun send(action: EditorIntentAction, confirmed: Boolean = false) {
        model.intent(action, confirmed)?.let {
            runCatching { ClientPlayNetworking.send(EditorIntentPayload(it)) }.onFailure { model.transportClosed() }
        }
        refreshControls()
    }
    fun refresh() {
        if (!::label.isInitialized) return
        syncing = true
        try { label.value = model.label; description.value = model.description } finally { syncing = false }
        refreshControls()
    }
    private fun refreshControls() {
        if (!::apply.isInitialized) return
        label.setEditable(model.editable && model.field("label")?.editable == true)
        description.setEditable(model.editable && model.field("description")?.editable == true)
        apply.active = model.canApply; reload.active = model.canReload; reconcile.active = model.canReconcile
        clear.active = model.editable && model.field("description")?.let { it.nullable && it.editable } == true
        reload.message = Component.literal(if (confirmingReload) "Vázlat eldobása" else "Újratöltés")
    }
    override fun onClose() {
        if (model.snapshot?.session == session) send(EditorIntentAction.CLOSE)
        minecraft.gui.setScreen(null)
    }
    override fun removed() {
        if (model.snapshot?.session == session) model.intent(EditorIntentAction.CLOSE)?.let {
            runCatching { ClientPlayNetworking.send(EditorIntentPayload(it)) }.onFailure { model.transportClosed() }
        }
        super.removed()
    }
    override fun isPauseScreen() = false
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fillGradient(0, 0, width, height, 0xE0101018.toInt(), 0xF0101018.toInt())
    }
    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val s = model.snapshot ?: return
        val left = width / 2 - 150; val top = ((height - 244) / 2).coerceAtLeast(0)
        val color = 0xFFD8D8D8.toInt()
        fun line(text: String, y: Int, c: Int = color) { graphics.text(font, Component.literal(font.plainSubstrByWidth(text, 300)), left, top + y, c) }
        graphics.centeredText(font, Component.literal("${s.blockType} – ${if (model.pending) "PENDING" else s.state}"), width / 2, top, 0xFFFFFFFF.toInt())
        line("${model.field("label")?.title ?: "Felirat"} (${model.field("label")?.min ?: 0}–${model.field("label")?.max ?: 0} kódpont)", 22)
        line(model.fieldError("label") ?: s.diagnostics.firstOrNull { it.field == "label" }?.message.orEmpty(), 58, 0xFFFF8888.toInt())
        line("${model.field("description")?.title ?: "Leírás"} (max. ${model.field("description")?.max ?: 0}; ${if (model.descriptionNull) "null" else "szöveg"})", 70)
        line(model.fieldError("description") ?: s.diagnostics.firstOrNull { it.field == "description" }?.message.orEmpty(), 132, 0xFFFF8888.toInt())
        line(if (model.pending && s.state != EditorUiState.PENDING) "Várakozás a szerver visszaigazolására…" else s.domainMessage, 146)
        line("Projekció: ${s.projection}${if (s.recoveryPending) " / GET folyamatban" else ""}", 158)
        line(s.projectionMessage, 170)
        line(if (confirmingReload) "Az újratöltés eldobja a vázlatot. Megerősíted?"
            else if (s.state == EditorUiState.UNKNOWN) "Bizonytalan APPLY; a projekció nem sikerigazolás."
            else "Alkalmazás ≠ Program-SAVE; a mentés külön művelet.", 184, 0xFFFFCC88.toInt())
    }
}
