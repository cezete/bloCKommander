package me.buciqua.blockommander.network

import java.util.UUID

/** A queued client callback belongs to an actual connection object, not just a player UUID. */
class EditorClientConnectionFence {
    @Volatile
    var token: Any? = null
        private set
    fun join(value: Any) { token = value }
    fun current(expected: Any?) = expected != null && token === expected
    fun disconnect(value: Any): Boolean {
        if (!current(value)) return false
        token = null; return true
    }
}

/** Pure presentation/draft state, shared for deterministic tests; owns no CAS or domain authority. */
class EditorUiModel(private val player: UUID) {
    var snapshot: EditorUiSnapshot? = null
        private set
    var label = ""
        private set
    var description = ""
        private set
    var descriptionNull = true
        private set
    private var basis: EditorUiValues? = null
    private var dismissed: EditorSessionKey? = null
    private var sending = false
    private var reloadSent = false

    fun receive(value: EditorUiSnapshot): Boolean {
        if (value.session.player != player) return false
        val prior = snapshot
        if (prior != null) {
            val incoming = value.session
            val old = prior.session
            if (incoming.connection < old.connection || incoming.connection == old.connection && incoming.generation < old.generation) return false
            if (incoming.connection == old.connection && incoming.generation == old.generation && incoming != old) return false
            if (incoming == old && (value.sequence <= prior.sequence || prior.state == EditorUiState.CLOSED)) return false
        }
        if (value.session == dismissed && value.state != EditorUiState.CLOSED) return false
        val fresh = prior?.session != value.session
        snapshot = value
        reloadSent = false
        sending = value.state == EditorUiState.PENDING || (!fresh && sending && value.state == EditorUiState.EDITING && value.canApply)
        if (fresh) { reloadSent = false; basis = null; label = ""; description = ""; descriptionNull = true }
        if (value.values != null && (fresh || basis == null || value.state == EditorUiState.ACCEPTED)) {
            basis = value.values; label = value.values.label; description = value.values.description.orEmpty(); descriptionNull = value.values.description == null
        }
        return true
    }
    val editable: Boolean get() = snapshot?.let { it.canApply && !sending && !reloadSent && it.state in setOf(EditorUiState.EDITING, EditorUiState.VALIDATION_ERROR) } == true
    val pending: Boolean get() = sending && snapshot?.state != EditorUiState.CLOSED
    fun editLabel(text: String) { if (editable && field("label")?.editable == true) label = text }
    fun editDescription(text: String) { if (editable && field("description")?.editable == true) { description = text; descriptionNull = false } }
    fun clearDescription() { if (editable && field("description")?.let { it.editable && it.nullable } == true) { description = ""; descriptionNull = true } }
    fun field(key: String) = snapshot?.schema?.fields?.singleOrNull { it.key == key }
    private fun labelPatch() = if (basis?.label == label) EditorPatch() else EditorPatch(EditorPatchKind.VALUE, label)
    private fun descriptionPatch(): EditorPatch = when {
        basis?.description == (if (descriptionNull) null else description) -> EditorPatch()
        descriptionNull -> EditorPatch(EditorPatchKind.CLEAR)
        else -> EditorPatch(EditorPatchKind.VALUE, description)
    }
    val dirty: Boolean get() = basis != null && (labelPatch().kind != EditorPatchKind.MISSING || descriptionPatch().kind != EditorPatchKind.MISSING)
    fun fieldError(key: String): String? {
        val f = field(key) ?: return null
        if (key == "description" && descriptionNull) return if (f.nullable) null else "Nem lehet null."
        val text = if (key == "label") label else description
        if (!validUnicode(text)) return "Hibás Unicode szöveg."
        val length = text.codePointCount(0, text.length)
        return if (length !in f.min..f.max) "${f.min}–${f.max} Unicode kódpont szükséges ($length)." else null
    }
    val canApply: Boolean get() = editable && dirty && fieldError("label") == null && fieldError("description") == null
    val canReload: Boolean get() = !reloadSent && snapshot?.state?.let { it !in setOf(EditorUiState.CLOSED, EditorUiState.OPENING, EditorUiState.PENDING) } == true
    val canReconcile: Boolean get() = !reloadSent && snapshot?.let { !it.recoveryPending && it.projection != "PENDING" &&
        it.state in setOf(EditorUiState.ACCEPTED, EditorUiState.UNKNOWN) } == true
    fun intent(action: EditorIntentAction, discardConfirmed: Boolean = false): EditorIntent? {
        val s = snapshot ?: return null
        return when (action) {
            EditorIntentAction.APPLY -> if (!canApply) null else {
                sending = true; EditorIntent(s.session, action, labelPatch(), descriptionPatch())
            }
            EditorIntentAction.RELOAD -> if (!canReload || dirty && !discardConfirmed) null else { reloadSent = true; EditorIntent(s.session, action) }
            EditorIntentAction.RECONCILE -> if (!canReconcile) null else { reloadSent = true; EditorIntent(s.session, action) }
            EditorIntentAction.CLOSE -> if (s.state == EditorUiState.CLOSED || dismissed == s.session) null else {
                dismissed = s.session; sending = true; EditorIntent(s.session, action)
            }
        }
    }
    fun transportClosed() {
        val s = snapshot ?: return
        dismissed = s.session; sending = false; reloadSent = false
        snapshot = s.copy(state = EditorUiState.CLOSED, canApply = false, closedReason = "CLIENT_LIFECYCLE")
    }
    private fun validUnicode(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (Character.isHighSurrogate(c)) { if (i == text.length || !Character.isLowSurrogate(text[i++])) return false }
            else if (Character.isLowSurrogate(c)) return false
        }
        return true
    }
}
