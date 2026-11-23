package me.buciqua.blockommander.runtime

import me.buciqua.blockommander.network.FloorBounds
import me.buciqua.blockommander.network.FloorOutlinePayload
import me.buciqua.blockommander.network.FloorOutlineView
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.UUID

/** Observe committed Hall/Stage state on the server thread and send changes to compatible clients. */
internal class FloorOutlinePublisher(private val registry: HallRegistry) {
    private data class Published(val player: ServerPlayer, val payload: FloorOutlinePayload)
    private val published = mutableMapOf<ServerGamePacketListenerImpl, Published>()

    fun update(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, FloorOutlinePayload.TYPE)) return
        val level = player.level()
        val dimension = level.dimension().identifier()
        val view = registry.floorOutlineView(WorldIdentity.uuid(level).toString(), player.blockPosition())
        val previous = published[player.connection]
        if (previous != null && previous.player === player && previous.payload.dimension == dimension && previous.payload.view == view) return
        val payload = FloorOutlinePayload((previous?.payload?.sequence ?: 0L) + 1, dimension, view)
        ServerPlayNetworking.send(player, payload)
        published[player.connection] = Published(player, payload)
    }

    fun disconnected(handler: ServerGamePacketListenerImpl) { published.remove(handler) }
    fun clear() { published.clear() }
}

internal fun HallRegistry.floorOutlineView(world: String, viewer: BlockPos): FloorOutlineView? = synchronized(this) {
    // The Hall command selects a mounted Stage for a particular Hall. The viewer's physical
    // Hall chooses which mount to display, including when the viewer stands in its aisle.
    val hall = inWorld(world).singleOrNull { it.contains(viewer) } ?: return null
    val stage = hall.stage ?: return null
    val navigation = navigationState(hall.id) ?: return null
    if (!editingAllowed(hall.id) || navigation.switchInProgress || navigation.activeStageId.toString() != stage.id) return null
    FloorOutlineView(
        UUID.fromString(hall.id), hall.min.y,
        FloorBounds(hall.min.x, hall.max.x, hall.min.z, hall.max.z),
        FloorBounds(stage.origin.x, stage.origin.x + stage.sizeX - 1, stage.origin.z, stage.origin.z + stage.sizeZ - 1),
    ).takeIf(FloorOutlineView::isValid)
}
