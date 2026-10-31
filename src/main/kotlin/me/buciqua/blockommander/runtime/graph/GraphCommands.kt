package me.buciqua.blockommander.runtime.graph

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class ServerPlayerIdentity private constructor(val uuid: UUID) {
    companion object {
        fun from(player: ServerPlayer) = ServerPlayerIdentity(player.uuid)
        internal fun trustedForTest(uuid: UUID) = ServerPlayerIdentity(uuid)
    }
}

data class GraphWireCellInput(val channels: List<GraphWireChannel>)
data class GraphWireChangeInput(val position: GraphPosition, val cell: GraphWireCellInput?)
enum class GraphPlacementIntent { AUTO, CROSS }

sealed class GraphCommand(
    val requestId: UUID,
    val stageId: UUID,
    val player: ServerPlayerIdentity,
    val workspaceEpoch: UUID,
    val expectedWorkingRevision: Long,
    val operation: String,
) {
    init {
        require(expectedWorkingRevision >= 0) { "A graph revision nem lehet negatív." }
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("schema_version", GRAPH_SCHEMA_VERSION)
        addProperty("request_id", requestId.toString())
        addProperty("stage_id", stageId.toString())
        addProperty("player_uuid", player.uuid.toString())
        addProperty("workspace_epoch", workspaceEpoch.toString())
        addProperty("expected_working_revision", expectedWorkingRevision)
        addProperty("operation", operation)
        add("payload", payload())
    }

    protected abstract fun payload(): JsonObject
}

class WireLayoutCommand(
    requestId: UUID,
    stageId: UUID,
    player: ServerPlayerIdentity,
    workspaceEpoch: UUID,
    expectedWorkingRevision: Long,
    val changes: List<GraphWireChangeInput>,
) : GraphCommand(requestId, stageId, player, workspaceEpoch, expectedWorkingRevision, "WIRE_LAYOUT") {
    override fun payload() = JsonObject().apply {
        add("changes", JsonArray().apply {
            changes.forEach { change ->
                add(JsonObject().apply {
                    add("position", change.position.toJson())
                    if (change.cell == null) add("cell", JsonNull.INSTANCE) else add("cell", JsonObject().apply {
                        add("channels", JsonArray().apply {
                            change.cell.channels.forEach { channel ->
                                add(JsonObject().apply {
                                    addProperty("channel", channel.channel)
                                    add("connections", JsonArray().apply { channel.connections.forEach(::add) })
                                })
                            }
                        })
                    })
                })
            }
        })
    }
}

class BindEdgesCommand(
    requestId: UUID,
    stageId: UUID,
    player: ServerPlayerIdentity,
    workspaceEpoch: UUID,
    expectedWorkingRevision: Long,
) : GraphCommand(requestId, stageId, player, workspaceEpoch, expectedWorkingRevision, "BIND_EDGES") {
    override fun payload() = JsonObject()
}

class WirePlaceIntentCommand(
    requestId: UUID,
    stageId: UUID,
    player: ServerPlayerIdentity,
    workspaceEpoch: UUID,
    expectedWorkingRevision: Long,
    val position: GraphPosition,
    val intent: GraphPlacementIntent,
) : GraphCommand(requestId, stageId, player, workspaceEpoch, expectedWorkingRevision, "WIRE_PLACE_INTENT") {
    override fun payload() = JsonObject().apply {
        add("position", position.toJson())
        addProperty("intent", intent.name)
    }
}

private fun GraphPosition.toJson() = JsonObject().apply {
    addProperty("x", x); addProperty("y", y); addProperty("z", z)
}
