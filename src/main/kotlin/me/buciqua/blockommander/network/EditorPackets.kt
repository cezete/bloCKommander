package me.buciqua.blockommander.network

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Shared, bounded presentation protocol. No HTTP secret, target, epoch or CAS enters C2S. */
internal const val EDITOR_PACKET_VERSION = 1
internal const val EDITOR_PACKET_LIMIT = 16384
data class EditorSessionKey(val player: UUID, val connection: Long, val generation: Long, val nonce: UUID)
enum class EditorIntentAction { APPLY, CLOSE, RELOAD, RECONCILE }
enum class EditorPatchKind { MISSING, VALUE, CLEAR }
data class EditorPatch(val kind: EditorPatchKind = EditorPatchKind.MISSING, val value: String = "")
data class EditorIntent(val session: EditorSessionKey, val action: EditorIntentAction,
    val label: EditorPatch = EditorPatch(), val description: EditorPatch = EditorPatch())
enum class EditorUiState { OPENING, EDITING, PENDING, ACCEPTED, CONFLICT, VALIDATION_ERROR, UNKNOWN, CLOSED }
data class EditorUiField(val key: String, val title: String, val min: Int, val max: Int,
    val required: Boolean, val nullable: Boolean, val readable: Boolean, val editable: Boolean,
    val order: Int, val emptyToNull: Boolean)
data class EditorUiSchema(val id: String, val version: Long, val fields: List<EditorUiField>)
data class EditorUiValues(val label: String, val description: String?)
data class EditorUiDiagnostic(val field: String?, val code: String, val message: String)
data class EditorUiSnapshot(val session: EditorSessionKey, val sequence: Long, val state: EditorUiState,
    val blockType: String, val schema: EditorUiSchema?, val values: EditorUiValues?, val canApply: Boolean,
    val diagnostics: List<EditorUiDiagnostic>, val domainMessage: String, val projection: String,
    val projectionMessage: String, val recoveryPending: Boolean, val recovery: EditorUiValues?,
    val closedReason: String?, val programDirty: Boolean?)

/** Strict binary envelope; unknown versions/tags/extra bytes fail closed before any runtime dispatch. */
internal object EditorPacketWire {
    private fun output(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).use { it.writeInt(EDITOR_PACKET_VERSION); block(it) }
    }.toByteArray().also { require(it.size <= EDITOR_PACKET_LIMIT) }
    private fun <T> input(bytes: ByteArray, block: DataInputStream.() -> T): T {
        require(bytes.size <= EDITOR_PACKET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readInt() == EDITOR_PACKET_VERSION)
            val result = block(it); require(it.available() == 0) { "TRAILING_EDITOR_DATA" }; result
        }
    }
    private fun DataOutputStream.text(value: String, max: Int) {
        require(value.length <= max)
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value)).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
        require(bytes.size <= max * 4); writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(max: Int): String {
        val length = readInt(); require(length in 0..max * 4 && length <= available())
        val bytes = ByteArray(length); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().also { require(it.length <= max) }
    }
    private fun DataInputStream.flag(): Boolean = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("INVALID_BOOLEAN") }
    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.key(key: EditorSessionKey) {
        require(key.connection > 0 && key.generation > 0)
        uuid(key.player); writeLong(key.connection); writeLong(key.generation); uuid(key.nonce)
    }
    private fun DataInputStream.key() = EditorSessionKey(uuid(), readLong(), readLong(), uuid()).also {
        require(it.connection > 0 && it.generation > 0)
    }
    private fun DataOutputStream.nullText(value: String?, max: Int) { writeBoolean(value != null); if (value != null) text(value, max) }
    private fun DataInputStream.nullText(max: Int) = if (flag()) text(max) else null
    private fun DataOutputStream.patch(p: EditorPatch, max: Int) {
        require(p.kind == EditorPatchKind.VALUE || p.value.isEmpty())
        writeByte(p.kind.ordinal); if (p.kind == EditorPatchKind.VALUE) text(p.value, max)
    }
    private fun DataInputStream.patch(max: Int): EditorPatch {
        val kind = EditorPatchKind.entries[readUnsignedByte()]; return EditorPatch(kind, if (kind == EditorPatchKind.VALUE) text(max) else "")
    }
    fun validate(intent: EditorIntent) {
        require(intent.label.kind != EditorPatchKind.CLEAR)
        if (intent.action == EditorIntentAction.APPLY) require(intent.label.kind != EditorPatchKind.MISSING || intent.description.kind != EditorPatchKind.MISSING)
        else require(intent.label.kind == EditorPatchKind.MISSING && intent.description.kind == EditorPatchKind.MISSING)
    }
    fun encode(intent: EditorIntent): ByteArray = output {
        validate(intent); key(intent.session); writeByte(intent.action.ordinal); patch(intent.label, 128); patch(intent.description, 1024)
    }
    fun intent(bytes: ByteArray): EditorIntent = input(bytes) {
        EditorIntent(key(), EditorIntentAction.entries[readUnsignedByte()], patch(128), patch(1024)).also(::validate)
    }
    private fun DataOutputStream.values(v: EditorUiValues?) {
        writeBoolean(v != null); if (v != null) { text(v.label, 128); nullText(v.description, 1024) }
    }
    private fun DataInputStream.values() = if (flag()) EditorUiValues(text(128), nullText(1024)) else null
    fun encode(s: EditorUiSnapshot): ByteArray = output {
        key(s.session); require(s.sequence > 0); writeLong(s.sequence); writeByte(s.state.ordinal); text(s.blockType, 8)
        writeBoolean(s.schema != null)
        s.schema?.let { schema ->
            text(schema.id, 96); writeLong(schema.version); require(schema.fields.size == 2); writeByte(schema.fields.size)
            schema.fields.forEach { f ->
                text(f.key, 16); text(f.title, 96); writeInt(f.min); writeInt(f.max)
                writeBoolean(f.required); writeBoolean(f.nullable); writeBoolean(f.readable); writeBoolean(f.editable)
                writeInt(f.order); writeBoolean(f.emptyToNull)
            }
        }
        values(s.values); writeBoolean(s.canApply); require(s.diagnostics.size <= 16); writeByte(s.diagnostics.size)
        s.diagnostics.forEach { nullText(it.field, 16); text(it.code, 96); text(it.message, 256) }
        text(s.domainMessage, 256); text(s.projection, 32); text(s.projectionMessage, 256); writeBoolean(s.recoveryPending)
        values(s.recovery); nullText(s.closedReason, 96); writeByte(when (s.programDirty) { null -> 0; false -> 1; true -> 2 })
    }
    fun snapshot(bytes: ByteArray): EditorUiSnapshot = input(bytes) {
        val key = key(); val sequence = readLong(); require(sequence > 0)
        val state = EditorUiState.entries[readUnsignedByte()]; val type = text(8); require(type in setOf("START", "STOP"))
        val schema = if (!flag()) null else {
            val id = text(96); val version = readLong(); require(version == 1L && id == "ck.block.$type.metadata")
            require(readUnsignedByte() == 2)
            val fields = List(2) {
                EditorUiField(text(16), text(96), readInt(), readInt(), flag(), flag(), flag(), flag(), readInt(), flag()).also {
                    require(it.min in 0..1024 && it.max in it.min..1024)
                }
            }
            require(fields.map { it.key }.toSet() == setOf("label", "description")); EditorUiSchema(id, version, fields)
        }
        val values = values(); val canApply = flag(); val count = readUnsignedByte(); require(count <= 16)
        val diagnostics = List(count) { EditorUiDiagnostic(nullText(16), text(96), text(256)) }
        val message = text(256); val projection = text(32); val detail = text(256); val pending = flag(); val recovery = values()
        val closed = nullText(96); val dirty = when (readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> error("INVALID_DIRTY") }
        require(!canApply || (schema != null && values != null && state in setOf(EditorUiState.EDITING, EditorUiState.VALIDATION_ERROR)))
        EditorUiSnapshot(key, sequence, state, type, schema, values, canApply, diagnostics, message, projection, detail, pending, recovery, closed, dirty)
    }
}

data class EditorIntentPayload(val intent: EditorIntent) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorIntentPayload>(Identifier.fromNamespaceAndPath("blockommander", "editor_intent_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, EditorIntentPayload> {
            override fun encode(buffer: RegistryFriendlyByteBuf, value: EditorIntentPayload) { buffer.writeByteArray(EditorPacketWire.encode(value.intent)) }
            override fun decode(buffer: RegistryFriendlyByteBuf): EditorIntentPayload {
                val bytes = buffer.readByteArray(EDITOR_PACKET_LIMIT); require(buffer.readableBytes() == 0)
                return EditorIntentPayload(EditorPacketWire.intent(bytes))
            }
        }
    }
}
data class EditorStatePayload(val snapshot: EditorUiSnapshot) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorStatePayload>(Identifier.fromNamespaceAndPath("blockommander", "editor_state_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, EditorStatePayload> {
            override fun encode(buffer: RegistryFriendlyByteBuf, value: EditorStatePayload) { buffer.writeByteArray(EditorPacketWire.encode(value.snapshot)) }
            override fun decode(buffer: RegistryFriendlyByteBuf): EditorStatePayload {
                val bytes = buffer.readByteArray(EDITOR_PACKET_LIMIT); require(buffer.readableBytes() == 0)
                return EditorStatePayload(EditorPacketWire.snapshot(bytes))
            }
        }
    }
}
