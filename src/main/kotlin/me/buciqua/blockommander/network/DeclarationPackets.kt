package me.buciqua.blockommander.network

import me.buciqua.blockommander.runtime.declarations.*
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal const val DECLARATION_PACKET_LIMIT = 16000 // includes room for Fabric byte-array length prefix below 16 KiB
enum class DeclarationAction { CREATE, UPDATE, DELETE, REFRESH, CLOSE, NEXT, PREV }
data class DeclarationIntent(val session: EditorSessionKey, val operation: Long, val action: DeclarationAction,
    val target: UUID? = null, val fields: DeclarationFields? = null)
data class DeclarationUiSnapshot(val session: EditorSessionKey, val sequence: Long, val dimension: String,
    val program: UUID, val stage: UUID, val state: DeclarationState, val busy: Boolean,
    val rows: List<DeclarationRow>, val page: Int, val total: Int, val hasNext: Boolean,
    val canCreate: Boolean, val canEdit: Boolean, val programDirty: Boolean?, val stageDirty: Boolean?,
    val stageSaved: Long?, val programSaved: Long?, val revision: Long?, val error: String?,
    val projection: String, val modified: Boolean?, val acknowledgedOperation: Long)

internal object DeclarationPacketWire {
    private fun output(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { stream ->
        DataOutputStream(stream).use { it.writeInt(1); block(it) }
    }.toByteArray().also { require(it.size <= DECLARATION_PACKET_LIMIT) }
    private fun <T> input(bytes: ByteArray, block: DataInputStream.() -> T): T {
        require(bytes.size <= DECLARATION_PACKET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { require(it.readInt() == 1); block(it).also { _ -> require(it.available() == 0) } }
    }
    private fun DataOutputStream.text(s: String, max: Int) {
        require(s.length <= max)
        val b = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(s))
        writeInt(b.remaining()); val bytes = ByteArray(b.remaining()); b.get(bytes); write(bytes)
    }
    private fun DataInputStream.text(max: Int): String {
        val n = readInt(); require(n in 0..max * 4 && n <= available())
        val bytes = ByteArray(n); readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().also { require(it.length <= max) }
    }
    private fun DataInputStream.flag() = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> error("INVALID_BOOLEAN") }
    private fun DataOutputStream.uuid(id: UUID) { writeLong(id.mostSignificantBits); writeLong(id.leastSignificantBits) }
    private fun DataInputStream.uuid() = UUID(readLong(), readLong())
    private fun DataOutputStream.key(k: EditorSessionKey) { require(k.connection > 0 && k.generation > 0); uuid(k.player); writeLong(k.connection); writeLong(k.generation); uuid(k.nonce) }
    private fun DataInputStream.key() = EditorSessionKey(uuid(), readLong(), readLong(), uuid()).also { require(it.connection > 0 && it.generation > 0) }
    private fun DataOutputStream.fields(f: DeclarationFields) { text(f.name, 128); writeByte(f.type.ordinal); writeByte(f.mutability.ordinal) }
    private fun DataInputStream.fields() = DeclarationFields(text(128), DeclarationType.entries[readUnsignedByte()], DeclarationMutability.entries[readUnsignedByte()])
    private fun DataOutputStream.nullFlag(b: Boolean?) { writeByte(when (b) { null -> 0; false -> 1; true -> 2 }) }
    private fun DataInputStream.nullFlag(): Boolean? = when (readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true; else -> error("INVALID_BOOLEAN") }
    private fun DataOutputStream.nullLong(n: Long?) { writeBoolean(n != null); if (n != null) { require(n >= 0); writeLong(n) } }
    private fun DataInputStream.nullLong() = if (flag()) readLong().also { require(it >= 0) } else null
    fun validate(i: DeclarationIntent) {
        require(i.operation > 0)
        require((i.target != null) == (i.action in setOf(DeclarationAction.UPDATE, DeclarationAction.DELETE)))
        require((i.fields != null) == (i.action in setOf(DeclarationAction.CREATE, DeclarationAction.UPDATE)))
    }
    fun encode(i: DeclarationIntent) = output {
        validate(i); key(i.session); writeLong(i.operation); writeByte(i.action.ordinal)
        writeBoolean(i.target != null); i.target?.let { uuid(it) }; writeBoolean(i.fields != null); i.fields?.let { fields(it) }
    }
    fun intent(bytes: ByteArray) = input(bytes) {
        DeclarationIntent(key(), readLong(), DeclarationAction.entries[readUnsignedByte()], if (flag()) uuid() else null, if (flag()) fields() else null).also(::validate)
    }
    fun encode(s: DeclarationUiSnapshot) = output {
        key(s.session); require(s.sequence > 0); writeLong(s.sequence); text(s.dimension, 128); uuid(s.program); uuid(s.stage)
        writeByte(s.state.ordinal); writeBoolean(s.busy); require(s.rows.size <= 16); writeByte(s.rows.size)
        s.rows.forEach { r ->
            uuid(r.id); uuid(r.ownerStage); fields(r.fields); writeByte(r.group.ordinal); writeBoolean(r.editable)
            writeBoolean(r.shadowedBy != null); r.shadowedBy?.let { uuid(it) }
        }
        require(s.page >= 0 && s.total >= 0); writeInt(s.page); writeInt(s.total); writeBoolean(s.hasNext)
        writeBoolean(s.canCreate); writeBoolean(s.canEdit); nullFlag(s.programDirty); nullFlag(s.stageDirty)
        nullLong(s.stageSaved); nullLong(s.programSaved); nullLong(s.revision)
        writeBoolean(s.error != null); s.error?.let { text(it, 96) }; text(s.projection, 32); nullFlag(s.modified)
        require(s.acknowledgedOperation >= 0); writeLong(s.acknowledgedOperation)
    }
    fun snapshot(bytes: ByteArray) = input(bytes) {
        val key = key(); val seq = readLong(); require(seq > 0); val dimension = text(128); val program = uuid(); val stage = uuid()
        val state = DeclarationState.entries[readUnsignedByte()]; val busy = flag(); val count = readUnsignedByte(); require(count <= 16)
        val rows = List(count) {
            val id = uuid(); val owner = uuid(); val fields = fields(); val group = DeclarationGroup.entries[readUnsignedByte()]; val edit = flag()
            val shadow = if (flag()) uuid() else null
            require(!edit || (owner == stage && group in setOf(DeclarationGroup.OWN, DeclarationGroup.LOCAL)))
            require((shadow != null) == (group == DeclarationGroup.SHADOWED))
            DeclarationRow(id, owner, fields, group, edit, shadow)
        }
        require(rows.map { it.group to it.id }.toSet().size == rows.size)
        val page = readInt(); val total = readInt(); require(page >= 0 && total >= 0)
        val next = flag(); val create = flag(); val edit = flag()
        require(!(create || edit) || (!busy && state in setOf(DeclarationState.READY, DeclarationState.REJECTED, DeclarationState.APPLIED_TO_WORKSPACE)))
        val dirty = nullFlag(); val stageDirty = nullFlag(); val saved = nullLong(); val programSaved = nullLong(); val rev = nullLong()
        val error = if (flag()) text(96) else null; val projection = text(32); val modified = nullFlag(); val operation = readLong(); require(operation >= 0)
        DeclarationUiSnapshot(key, seq, dimension, program, stage, state, busy, rows, page, total, next, create, edit, dirty, stageDirty, saved, programSaved, rev, error, projection, modified, operation)
    }
}

data class DeclarationIntentPayload(val intent: DeclarationIntent) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<DeclarationIntentPayload>(Identifier.fromNamespaceAndPath("blockommander", "declaration_intent_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, DeclarationIntentPayload> {
            override fun encode(b: RegistryFriendlyByteBuf, v: DeclarationIntentPayload) { b.writeByteArray(DeclarationPacketWire.encode(v.intent)) }
            override fun decode(b: RegistryFriendlyByteBuf) = DeclarationIntentPayload(DeclarationPacketWire.intent(b.readByteArray(DECLARATION_PACKET_LIMIT))).also { require(b.readableBytes() == 0) }
        }
    }
}
data class DeclarationStatePayload(val snapshot: DeclarationUiSnapshot) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<DeclarationStatePayload>(Identifier.fromNamespaceAndPath("blockommander", "declaration_state_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, DeclarationStatePayload> {
            override fun encode(b: RegistryFriendlyByteBuf, v: DeclarationStatePayload) { b.writeByteArray(DeclarationPacketWire.encode(v.snapshot)) }
            override fun decode(b: RegistryFriendlyByteBuf) = DeclarationStatePayload(DeclarationPacketWire.snapshot(b.readByteArray(DECLARATION_PACKET_LIMIT))).also { require(b.readableBytes() == 0) }
        }
    }
}

internal fun DeclarationSessionSnapshot.ui(sequence: Long): DeclarationUiSnapshot {
    val p = page; val v = p?.version
    return DeclarationUiSnapshot(key, sequence, capture.dimension, capture.context.transport.programId, capture.context.stage,
        state, busy, p?.rows.orEmpty(), p?.index ?: 0, p?.total ?: 0, p?.hasNext ?: false, editable && p?.canCreate == true,
        editable && p?.canEdit == true, when {
            state == DeclarationState.PENDING -> true
            state in setOf(DeclarationState.UNKNOWN, DeclarationState.STALE, DeclarationState.RELOAD_REQUIRED) ||
                projection in setOf("STALE", "FAILED", "NOT_CHECKED") -> null
            else -> v?.programDirty
        },
        v?.stageDirty, v?.stageSaved, v?.programSaved, v?.revision, error, projection, receipt?.modified, lastOperation)
}
