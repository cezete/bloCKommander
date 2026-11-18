package me.buciqua.blockommander.network

import me.buciqua.blockommander.runtime.declarations.*
import java.util.UUID

/** One monotonic S2C stream for both surfaces. Local dismissal and world change
 * tombstone the current keys; delayed packets cannot reopen those sessions. */
class EditorSurfaceFence(private val player: UUID) {
    private var sequence = 0L
    private var active: Pair<String, EditorSessionKey>? = null
    private val dismissed = mutableMapOf<String, EditorSessionKey>()
    fun receive(surface: String, key: EditorSessionKey, seq: Long, closed: Boolean): Boolean {
        if (key.player != player || seq <= sequence || dismissed[surface] == key && !closed) return false
        sequence = seq
        if (closed) { dismissed[surface] = key; return active == (surface to key) }
        active?.takeIf { it.first != surface || it.second != key }?.let { dismissed[it.first] = it.second }
        active = surface to key
        return true
    }
    fun invalidate() { active?.let { dismissed[it.first] = it.second }; active = null }
}

/** Client presentation only. No transport context, epoch, CAS or scope algorithm. */
class DeclarationUiModel(private val player: UUID) {
    var snapshot: DeclarationUiSnapshot? = null
        private set
    var selected: Pair<DeclarationGroup, UUID>? = null
        private set
    var fields = DeclarationFields("", DeclarationType.INTEGER, DeclarationMutability.MUTABLE)
        private set
    private var basis = fields
    private var dismissed: EditorSessionKey? = null
    private var operation = 0L
    private var pending: Long? = null
    private var mutation: Long? = null
    var confirmDelete = false
        private set
    val draftDirty get() = fields != basis
    val selectedRow get() = snapshot?.rows?.singleOrNull { (it.group to it.id) == selected }
    val busy get() = pending != null || snapshot?.busy == true
    val editable get() = !busy && snapshot?.let { if (selected == null) it.canCreate else it.canEdit && selectedRow?.editable == true } == true
    fun receive(s: DeclarationUiSnapshot): Boolean {
        if (s.session.player != player || dismissed == s.session && s.state != DeclarationState.CLOSED) return false
        val prior = snapshot
        if (prior != null && (s.sequence <= prior.sequence || s.session.connection < prior.session.connection ||
                s.session == prior.session && prior.state == DeclarationState.CLOSED)) return false
        if (s.session != prior?.session) {
            selected = null; fields = DeclarationFields("", DeclarationType.INTEGER, DeclarationMutability.MUTABLE); basis = fields
            pending = null; mutation = null; operation = 0; confirmDelete = false
        }
        snapshot = s
        if (pending != null && s.acknowledgedOperation >= pending!!) pending = null
        if (mutation != null && s.acknowledgedOperation >= mutation!! && s.state == DeclarationState.APPLIED_TO_WORKSPACE && !s.busy && s.projection == "CURRENT") {
            fields = selectedRow?.fields ?: DeclarationFields("", DeclarationType.INTEGER, DeclarationMutability.MUTABLE)
            if (selectedRow == null) selected = null
            basis = fields; mutation = null
        } else if (!draftDirty && selectedRow != null && !busy) { fields = selectedRow!!.fields; basis = fields }
        return true
    }
    fun select(row: DeclarationRow?, discard: Boolean = false): Boolean {
        if (busy || draftDirty && !discard) return false
        selected = row?.let { it.group to it.id }; fields = row?.fields ?: DeclarationFields("", DeclarationType.INTEGER, DeclarationMutability.MUTABLE)
        basis = fields; confirmDelete = false; return true
    }
    fun edit(value: DeclarationFields) { if (editable) { fields = value; confirmDelete = false } }
    fun intent(action: DeclarationAction, confirmed: Boolean = false): DeclarationIntent? {
        val s = snapshot ?: return null
        if (s.state == DeclarationState.CLOSED || dismissed == s.session) return null
        if (action != DeclarationAction.CLOSE && busy) return null
        when (action) {
            DeclarationAction.CREATE -> if (!s.canCreate || selected != null) return null
            DeclarationAction.UPDATE -> if (!editable || selectedRow == null) return null
            DeclarationAction.DELETE -> {
                if (!editable || selectedRow == null) return null
                if (!confirmed) { confirmDelete = true; return null }
            }
            DeclarationAction.NEXT -> if (!s.hasNext || s.state in setOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED)) return null
            DeclarationAction.PREV -> if (s.page == 0 || s.state in setOf(DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED)) return null
            else -> Unit
        }
        operation = Math.addExact(operation, 1); pending = operation; confirmDelete = false
        if (action == DeclarationAction.CLOSE) dismissed = s.session
        if (action in setOf(DeclarationAction.CREATE, DeclarationAction.UPDATE, DeclarationAction.DELETE)) mutation = operation
        return DeclarationIntent(s.session, operation, action,
            if (action in setOf(DeclarationAction.UPDATE, DeclarationAction.DELETE)) selectedRow!!.id else null,
            if (action in setOf(DeclarationAction.CREATE, DeclarationAction.UPDATE)) fields else null)
    }
    fun transportClosed() {
        snapshot?.let { dismissed = it.session; snapshot = it.copy(state = DeclarationState.CLOSED, busy = false, canCreate = false, canEdit = false) }
        pending = null
    }
}

/** Minecraft's minimum scaled viewport is 320x240. Fewer visible rows scroll
 * within the 16-row HTTP page; no transport rows are discarded. */
data class DeclarationLayout(val width: Int, val height: Int) {
    val left = 8
    val contentWidth = (width - 16).coerceAtMost(640)
    val formY = height - 132
    val rowCount = ((height - 202) / 18).coerceIn(1, 16)
    val listY = 48
}

fun declarationError(code: String?): String = when (code) {
    null -> ""
    "INVALID_DECLARATION_NAME" -> "Név: 1–64 ASCII betű/szám/_, kezdete betű vagy _."
    "DUPLICATE_DECLARATION_NAME" -> "Ilyen nevű saját deklaráció már létezik."
    "STALE_RESOLUTION_BASIS", "STALE_PAGE", "STALE_WORKING_REVISION", "LOCAL_PROGRAM_CHANGED" -> "Elavult nézet: REFRESH szükséges. A vázlat megmarad."
    "PROJECTION_RECONCILIATION_REQUIRED" -> "Projekció nincs szinkronban; a Python-műveletet ez nem vonja vissza."
    "TIMEOUT", "TRANSPORT_FAILURE" -> "Kapcsolati hiba. UNKNOWN esetén ne küldd újra a műveletet."
    "FORBIDDEN" -> "Csak a tulajdonos szerkeszthet."
    "SCOPE_UNAVAILABLE" -> "A scope nem érhető el; saját adatok csak olvashatók."
    else -> code
}
