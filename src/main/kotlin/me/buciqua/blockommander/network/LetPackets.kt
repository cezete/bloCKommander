package me.buciqua.blockommander.network

import me.buciqua.blockommander.BlockCommanderMod
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

enum class LetAction { APPLY, ENTER, NEXT, PREVIOUS, REFRESH, CLOSE }
data class LetRow(val id: UUID, val name: String, val type: String, val mutability: String,
    val owner: UUID, val origin: String)
data class LetScreenState(
    val session: EditorSessionKey, val sequence: Long, val dimension: String, val block: UUID, val target: UUID?,
    val page: Int, val hasNext: Boolean, val rows: List<LetRow>, val status: String,
    val ready: Boolean, val closed: Boolean,
)

private fun RegistryFriendlyByteBuf.writeSession(value: EditorSessionKey) {
    writeUUID(value.player); writeLong(value.connection); writeLong(value.generation); writeUUID(value.nonce)
}
private fun RegistryFriendlyByteBuf.readSession() =
    EditorSessionKey(readUUID(), readLong(), readLong(), readUUID()).also { require(it.connection > 0 && it.generation > 0) }
private fun RegistryFriendlyByteBuf.writeOptionalUuid(value: UUID?) {
    writeBoolean(value != null)
    if (value != null) writeUUID(value)
}
private fun RegistryFriendlyByteBuf.readOptionalUuid() = if (readBoolean()) readUUID() else null

data class LetIntentPayload(val session: EditorSessionKey, val sequence: Long,
    val action: LetAction, val target: UUID? = null) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LetIntentPayload>(BlockCommanderMod.id("let_intent_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, LetIntentPayload> {
            override fun encode(b: RegistryFriendlyByteBuf, v: LetIntentPayload) {
                b.writeSession(v.session); b.writeLong(v.sequence); b.writeVarInt(v.action.ordinal); b.writeOptionalUuid(v.target)
            }
            override fun decode(b: RegistryFriendlyByteBuf) = LetIntentPayload(
                b.readSession(), b.readLong(), LetAction.entries[b.readVarInt()], b.readOptionalUuid(),
            ).also { require(it.sequence > 0 && (it.target != null) == (it.action == LetAction.APPLY) && b.readableBytes() == 0) }
        }
    }
}

data class LetStatePayload(val state: LetScreenState) : CustomPacketPayload {
    override fun type() = TYPE
    companion object {
        val TYPE = CustomPacketPayload.Type<LetStatePayload>(BlockCommanderMod.id("let_state_v1"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, LetStatePayload> {
            override fun encode(b: RegistryFriendlyByteBuf, v: LetStatePayload) = with(v.state) {
                b.writeSession(session); b.writeLong(sequence); b.writeUtf(dimension, 192); b.writeUUID(block)
                b.writeOptionalUuid(target); b.writeVarInt(page); b.writeBoolean(hasNext); b.writeUtf(status, 192)
                b.writeBoolean(ready); b.writeBoolean(closed); b.writeVarInt(rows.size)
                for (row in rows) {
                    b.writeUUID(row.id); b.writeUtf(row.name, 64); b.writeUtf(row.type, 16); b.writeUtf(row.mutability, 3)
                    b.writeUUID(row.owner); b.writeUtf(row.origin, 16)
                }
            }
            override fun decode(b: RegistryFriendlyByteBuf): LetStatePayload {
                val session = b.readSession(); val sequence = b.readLong(); val dimension = b.readUtf(192)
                val block = b.readUUID(); val target = b.readOptionalUuid(); val page = b.readVarInt()
                val hasNext = b.readBoolean(); val status = b.readUtf(192); val ready = b.readBoolean(); val closed = b.readBoolean()
                val count = b.readVarInt()
                require(sequence > 0 && page >= 0 && count in 0..16)
                val rows = List(count) { LetRow(b.readUUID(), b.readUtf(64), b.readUtf(16), b.readUtf(3),
                    b.readUUID(), b.readUtf(16)) }
                require(b.readableBytes() == 0)
                return LetStatePayload(LetScreenState(session, sequence, dimension, block, target, page, hasNext, rows, status, ready, closed))
            }
        }
    }
}
