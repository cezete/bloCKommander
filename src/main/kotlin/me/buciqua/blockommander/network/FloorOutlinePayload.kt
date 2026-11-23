package me.buciqua.blockommander.network

import me.buciqua.blockommander.BlockCommanderMod
import net.minecraft.core.BlockPos
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import java.util.UUID

data class FloorBounds(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
    fun contains(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ
}

data class FloorOutlineView(val hallId: UUID, val floorY: Int, val hall: FloorBounds, val stage: FloorBounds) {
    fun isValid(): Boolean = hall.maxX.toLong() - hall.minX >= 4 && hall.maxZ.toLong() - hall.minZ >= 4 &&
        stage.minX <= stage.maxX && stage.minZ <= stage.maxZ &&
        stage.minX > hall.minX && stage.maxX < hall.maxX && stage.minZ > hall.minZ && stage.maxZ < hall.maxZ

    fun highlights(target: BlockPos, block: Identifier, viewer: BlockPos): Boolean {
        if (target.y != floorY || viewer.y <= floorY || !hall.contains(viewer.x, viewer.z)) return false
        val dx = target.x.toLong() - hall.minX
        val dz = target.z.toLong() - hall.minZ
        if (dx < 2 || hall.maxX.toLong() - target.x < 2 || dz < 2 || hall.maxZ.toLong() - target.z < 2 ||
            !stage.contains(target.x, target.z)) return false
        val expected = if ((dx + dz) % 2L == 0L) LIGHT_GRAY else WHITE
        return block == expected
    }

    private companion object {
        val LIGHT_GRAY: Identifier = Identifier.withDefaultNamespace("light_gray_concrete")
        val WHITE: Identifier = Identifier.withDefaultNamespace("white_concrete")
    }
}

data class FloorOutlinePayload(val sequence: Long, val dimension: Identifier, val view: FloorOutlineView?) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<FloorOutlinePayload>(BlockCommanderMod.id("floor_outline"))
        val CODEC = object : StreamCodec<RegistryFriendlyByteBuf, FloorOutlinePayload> {
            override fun encode(buf: RegistryFriendlyByteBuf, value: FloorOutlinePayload) {
                buf.writeLong(value.sequence)
                buf.writeIdentifier(value.dimension)
                buf.writeBoolean(value.view != null)
                value.view?.let {
                    buf.writeUUID(it.hallId)
                    buf.writeInt(it.floorY)
                    writeBounds(buf, it.hall)
                    writeBounds(buf, it.stage)
                }
            }

            override fun decode(buf: RegistryFriendlyByteBuf): FloorOutlinePayload {
                val sequence = buf.readLong()
                val dimension = buf.readIdentifier()
                val view = if (buf.readBoolean()) FloorOutlineView(buf.readUUID(), buf.readInt(), readBounds(buf), readBounds(buf)) else null
                return FloorOutlinePayload(sequence, dimension, view)
            }
        }

        private fun writeBounds(buf: RegistryFriendlyByteBuf, bounds: FloorBounds) {
            buf.writeInt(bounds.minX)
            buf.writeInt(bounds.maxX)
            buf.writeInt(bounds.minZ)
            buf.writeInt(bounds.maxZ)
        }

        private fun readBounds(buf: RegistryFriendlyByteBuf) = FloorBounds(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt())
    }
}

/** A presentation snapshot tied to the same connection fence as the CK editor surfaces. */
class FloorOutlineClientState(private val connections: EditorClientConnectionFence) {
    private data class Snapshot(val connection: Any, val level: Any, val view: FloorOutlineView)
    private var lastSequence = 0L
    @Volatile
    private var snapshot: Snapshot? = null

    fun reset() {
        lastSequence = 0L
        snapshot = null
    }

    fun receive(payload: FloorOutlinePayload, connection: Any, receivedLevel: Any?, currentLevel: Any?, dimension: Identifier?) {
        if (!connections.current(connection) || payload.sequence <= lastSequence || receivedLevel == null ||
            receivedLevel !== currentLevel || payload.dimension != dimension) return
        lastSequence = payload.sequence
        snapshot = payload.view?.takeIf(FloorOutlineView::isValid)?.let { Snapshot(connection, receivedLevel, it) }
    }

    fun view(level: Any): FloorOutlineView? = snapshot?.takeIf { connections.current(it.connection) && it.level === level }?.view
}
