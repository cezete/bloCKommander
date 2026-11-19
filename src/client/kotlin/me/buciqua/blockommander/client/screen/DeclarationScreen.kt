package me.buciqua.blockommander.client.screen

import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.declarations.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

internal class DeclarationScreen(val model: DeclarationUiModel) : Screen(Component.literal("CK Deklarációk")) {
    val session = model.snapshot!!.session
    private lateinit var layout: DeclarationLayout
    private lateinit var name: EditBox
    private lateinit var type: Button
    private lateinit var mutability: Button
    private lateinit var create: Button
    private lateinit var update: Button
    private lateinit var delete: Button
    private lateinit var fresh: Button
    private lateinit var next: Button
    private lateinit var previous: Button
    private lateinit var refreshButton: Button
    private lateinit var scrollUp: Button
    private lateinit var scrollDown: Button
    private val rowButtons = mutableListOf<Button>()
    private var syncing = false
    private var discardTarget: Pair<DeclarationGroup, java.util.UUID>? = null
    private var confirmingDiscard = false
    override fun init() {
        layout = DeclarationLayout(width, height); val l = layout.left; val w = layout.contentWidth; val y = layout.formY
        model.viewport.resize(layout.rowCount)
        fun button(text: String, x: Int, yy: Int, ww: Int, action: () -> Unit) = addRenderableWidget(
            Button.builder(Component.literal(text)) { action() }.bounds(x, yy, ww, 20).build())
        rowButtons.clear()
        repeat(layout.rowCount) { index ->
            rowButtons += button("", l, layout.listY + index * 18, w - 26) {
                val row = model.viewport.visibleRows.getOrNull(index) ?: return@button
                val target = row.group to row.id
                if (!model.select(row, confirmingDiscard && discardTarget == target)) { confirmingDiscard = true; discardTarget = target }
                else confirmingDiscard = false
                refresh()
            }.also { it.height = 18 }
        }
        scrollUp = button("▲", l + w - 23, layout.listY, 23) { model.viewport.up(); refresh() }
        scrollDown = button("▼", l + w - 23, layout.listY + 21, 23) { model.viewport.down(); refresh() }
        scrollUp.setTooltip(Tooltip.create(Component.literal("Görgetés felfelé ezen az oldalon")))
        scrollDown.setTooltip(Tooltip.create(Component.literal("Görgetés lefelé ezen az oldalon")))
        name = addRenderableWidget(EditBox(font, l, y, w, 20, Component.literal("Név")))
        name.setMaxLength(128); name.value = model.fields.name
        name.setResponder { if (!syncing) { model.edit(model.fields.copy(name = it)); confirmingDiscard = false; refreshControls() } }
        type = button("", l, y + 24, w / 2 - 2) {
            model.edit(model.fields.copy(type = DeclarationType.entries[(model.fields.type.ordinal + 1) % DeclarationType.entries.size])); refreshControls()
        }
        mutability = button("", l + w / 2 + 2, y + 24, w / 2 - 2) {
            model.edit(model.fields.copy(mutability = DeclarationMutability.entries[(model.fields.mutability.ordinal + 1) % DeclarationMutability.entries.size])); refreshControls()
        }
        val col = (w - 9) / 4
        fresh = button("Új", l, y + 48, col) {
            if (!model.select(null, confirmingDiscard && discardTarget == null)) { confirmingDiscard = true; discardTarget = null }
            else confirmingDiscard = false
            refresh()
        }
        create = button("Létrehoz", l + col + 3, y + 48, col) { send(DeclarationAction.CREATE) }
        update = button("Módosít", l + 2 * (col + 3), y + 48, col) { send(DeclarationAction.UPDATE) }
        delete = button("Töröl", l + 3 * (col + 3), y + 48, col) { send(DeclarationAction.DELETE, model.confirmDelete) }
        previous = button("PREV", l, y + 72, col) { send(DeclarationAction.PREV) }
        next = button("NEXT", l + col + 3, y + 72, col) { send(DeclarationAction.NEXT) }
        previous.setTooltip(Tooltip.create(Component.literal("Előző adatoldal")))
        next.setTooltip(Tooltip.create(Component.literal("Következő adatoldal")))
        refreshButton = button("REFRESH", l + 2 * (col + 3), y + 72, col) { send(DeclarationAction.REFRESH) }
        button("CLOSE", l + 3 * (col + 3), y + 72, col) { onClose() }
        refresh(); setInitialFocus(name)
    }
    private fun send(action: DeclarationAction, confirmed: Boolean = false) {
        model.intent(action, confirmed)?.let {
            try { ClientPlayNetworking.send(DeclarationIntentPayload(it)) } catch (_: Exception) { model.transportClosed() }
        }
        refreshControls()
    }
    fun refresh() {
        if (!::name.isInitialized) return
        syncing = true
        try { name.value = model.fields.name } finally { syncing = false }
        refreshControls()
    }
    private fun refreshControls() {
        if (!::create.isInitialized) return
        val s = model.snapshot ?: return
        val viewport = model.viewport
        val visibleRows = viewport.visibleRows
        rowButtons.forEachIndexed { index, button ->
            val row = visibleRows.getOrNull(index)
            button.visible = row != null; button.active = !model.busy
            button.message = Component.literal(row?.let {
                val own = if (it.group == DeclarationGroup.OWN) "LOCAL/saját" else it.group.name
                val mark = if (model.selected == (it.group to it.id)) "> " else ""
                font.plainSubstrByWidth("$mark$own ${it.fields.name}: ${it.fields.type}", layout.contentWidth - 34)
            }.orEmpty())
        }
        name.setEditable(model.editable); type.active = model.editable; mutability.active = model.editable
        type.message = Component.literal(model.fields.type.name); mutability.message = Component.literal(model.fields.mutability.name)
        fresh.active = !model.busy && s.canCreate
        create.active = model.editable && model.selected == null && s.canCreate
        update.active = model.editable && model.selectedRow != null
        delete.active = model.editable && model.selectedRow != null
        delete.message = Component.literal(if (model.confirmDelete) "Tényleg?" else "Töröl")
        scrollUp.active = !model.busy && viewport.canUp
        scrollDown.active = !model.busy && viewport.canDown
        val currentPage = viewport.pageIndex == s.page && s.projection == "CURRENT"
        previous.active = !model.busy && currentPage && s.page > 0 && s.state !in setOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED)
        next.active = !model.busy && currentPage && s.hasNext && s.state !in setOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED)
        refreshButton.active = !model.busy && s.state != DeclarationState.CLOSED
    }
    override fun onClose() { send(DeclarationAction.CLOSE); minecraft.gui.setScreen(null) }
    override fun removed() { model.intent(DeclarationAction.CLOSE)?.let { runCatching { ClientPlayNetworking.send(DeclarationIntentPayload(it)) } }; super.removed() }
    override fun isPauseScreen() = false
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fillGradient(0, 0, width, height, 0xF0101018.toInt(), 0xF0101018.toInt())
    }
    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val s = model.snapshot ?: return
        fun line(text: String, y: Int, color: Int = 0xFFE0E0E0.toInt()) {
            g.text(font, Component.literal(font.plainSubstrByWidth(text, layout.contentWidth)), layout.left, y, color)
        }
        line("Program: ${s.program}", 6); line("Stage: ${s.stage}", 18)
        line("${s.state}${if (model.busy) " / várakozás" else ""} | ${model.viewport.indicator}", 32)
        val selected = model.selectedRow
        line(selected?.let { "Owner Stage: ${it.ownerStage}" }
            ?: if (model.selected != null) "Kijelölés másik oldalon" else "Új saját deklaráció", layout.formY - 22)
        line(model.selected?.let { "UUID: ${it.second}" } ?: "Név / típus / mutability", layout.formY - 11)
        line(if (confirmingDiscard) "Vázlat eldobása? Ugyanazt válaszd újra a megerősítéshez."
            else declarationError(s.error), height - 36, 0xFFFF9999.toInt())
        val freshness = when {
            s.state == DeclarationState.UNKNOWN -> "UNKNOWN | ${if (s.projection == "CURRENT") "friss nézet" else "nézet: ${s.projection}"} | csak olvasás"
            s.state == DeclarationState.STALE || s.projection == "STALE" -> "Nézet elavult: REFRESH szükséges."
            s.state == DeclarationState.RELOAD_REQUIRED -> "Újratöltés szükséges; a session nem szerkeszthető."
            else -> "Working copy: ${if (s.stageDirty == true) "módosított" else "változatlan/ismeretlen"}; rev ${s.revision ?: "?"}; projekció ${s.projection}"
        }
        line(freshness, height - 24)
        line("Program SAVE: ${when (s.programDirty) { true -> "NINCS MENTVE"; false -> "MENTVE"; null -> "ISMERETLEN" }} (${s.programSaved ?: "?"}) | Alkalmazás ≠ SAVE", height - 12, 0xFFFFCC88.toInt())
    }
}
