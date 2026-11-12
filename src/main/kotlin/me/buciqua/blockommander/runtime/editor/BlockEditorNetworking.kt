package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.network.*
import java.util.UUID

internal fun EditorSessionId.key() = EditorSessionKey(player, connection, generation, nonce)
internal fun EditorSessionKey.id() = EditorSessionId(player, connection, generation, nonce)

internal interface EditorIntentPort {
    fun current(key: EditorSessionKey): Boolean
    fun apply(intent: EditorIntent): Boolean
    fun close(key: EditorSessionKey)
    fun reload(key: EditorSessionKey): Boolean
    fun reconcile(key: EditorSessionKey): Boolean
}

/** Called only from the server executor; identity comes from the network's ServerPlayer. */
internal object EditorIntentRouter {
    fun route(player: UUID, intent: EditorIntent, port: EditorIntentPort): Boolean {
        if (intent.session.player != player) return false
        try { EditorPacketWire.validate(intent); EditorPacketWire.encode(intent) } catch (_: Exception) { return false }
        if (!port.current(intent.session)) return false
        return when (intent.action) {
            EditorIntentAction.APPLY -> port.apply(intent)
            EditorIntentAction.CLOSE -> { port.close(intent.session); true }
            EditorIntentAction.RELOAD -> port.reload(intent.session)
            EditorIntentAction.RECONCILE -> port.reconcile(intent.session)
        }
    }
}

internal object EditorStateMapper {
    fun map(s: EditorSessionSnapshot, sequence: Long): EditorUiSnapshot {
        val view = s.accepted?.editor ?: s.opened
        val effective = s.recovery ?: view
        fun values(v: EditorGetResult?) = v?.values?.let { EditorUiValues(it.label, it.description) }
        val diagnostics = (s.failure as? BlockEditorResult.ApiError)?.error?.diagnostics.orEmpty().take(16).map {
            EditorUiDiagnostic(it.field?.take(16), it.code.take(96), it.message.take(256))
        }
        val message = when (s.state) {
            EditorSessionState.OPENING -> "Authoritative adatok lekérése…"
            EditorSessionState.EDITING -> if (s.canApply) "Szerkeszthető munkapéldány." else "Csak olvasható."
            EditorSessionState.PENDING -> "Alkalmazás folyamatban…"
            EditorSessionState.ACCEPTED -> "Alkalmazva a munkapéldányban; ez nem Program-SAVE."
            EditorSessionState.VALIDATION_ERROR -> "Mezőhiba: a módosítás nem történt meg."
            EditorSessionState.CONFLICT -> "Konfliktus: explicit újratöltés szükséges."
            EditorSessionState.UNKNOWN -> "Az APPLY kimenete bizonytalan. Ne küldd újra automatikusan."
            EditorSessionState.CLOSED -> "A szerkesztési munkamenet lezárult."
        }
        val failureCode = when (val failure = s.recoveryFailure ?: s.failure) {
            is BlockEditorResult.ApiError -> failure.error.errorCode
            is BlockEditorResult.TransportFailure -> failure.kind.name
            is BlockEditorResult.ProtocolFailure -> failure.reason
            else -> null
        }
        return EditorUiSnapshot(s.id.key(), sequence, EditorUiState.valueOf(s.state.name), s.target.blockType!!.name,
            view?.schema?.let { schema -> EditorUiSchema(schema.reference.schemaId, schema.reference.schemaVersion, schema.fields.map {
                EditorUiField(it.key, it.presentation.label, it.constraints.minLength.toInt(), it.constraints.maxLength.toInt(),
                    it.required, it.nullable, it.readable, it.editable, it.presentation.order.toInt(), it.normalization.emptyToNull)
            }) }, values(view), s.canApply, diagnostics, (failureCode?.take(96)?.let { "$it: " }.orEmpty() + message).take(256), s.reconciliation.name,
            s.projectionResult?.let { "${it.status}: ${it.reason}" } ?: "", s.recoveryPending, values(s.recovery),
            s.closedReason, effective?.versionContext?.programDirty)
    }
}

internal fun EditorPatch.domain(): EditorStringChange = when (kind) {
    EditorPatchKind.MISSING -> EditorStringChange.Missing
    EditorPatchKind.CLEAR -> EditorStringChange.Clear
    EditorPatchKind.VALUE -> EditorStringChange.Value(value)
}
