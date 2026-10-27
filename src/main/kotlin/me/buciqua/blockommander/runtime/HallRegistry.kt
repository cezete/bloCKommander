package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.graph.GraphProjectionStore
import me.buciqua.blockommander.runtime.graph.StageGraphView
import net.minecraft.core.BlockPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Control(val hallId: String, val kind: String, val pos: BlockPos)
data class StageBlock(val instanceId: String, val type: String, val pos: BlockPos, val orientation: String,
                      val label: String, val description: String)
data class Stage(val id: String, val origin: BlockPos, val sizeX: Int, val sizeY: Int, val sizeZ: Int,
                 val dirty: Boolean, val workspaceEpoch: UUID, val workingRevision: Long,
                 val blocks: List<StageBlock>) {
    fun contains(pos: BlockPos) = pos.x in origin.x until origin.x + sizeX &&
        pos.y in origin.y until origin.y + sizeY && pos.z in origin.z until origin.z + sizeZ
}
data class StageUpdateFence(val hallId: String, val stageId: String, val workspaceEpoch: UUID,
                            val workingRevision: Long)
data class Hall(val id: String, val name: String, val ownerUuid: String, val ownerName: String,
                val worldUuid: String, val material: String,
                val min: BlockPos, val max: BlockPos, val door: BlockPos, val orientation: String,
                val controls: List<Control>, val stage: Stage?) {
    fun contains(pos: BlockPos) = pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

class HallRegistry(val graphProjections: GraphProjectionStore = GraphProjectionStore()) {
    private val halls = ConcurrentHashMap<String, Hall>()

    @Synchronized
    fun install(json: JsonObject): Hall {
        val hall = parseHall(json)
        val stage = hall.stage
        val previous = halls.put(hall.id, hall)
        if (previous?.stage?.id != null && previous.stage.id != stage?.id) {
            graphProjections.remove(UUID.fromString(previous.stage.id))
        }
        stage?.let { graphProjections.authoritativeStageReload(UUID.fromString(it.id), it.workspaceEpoch, it.workingRevision) }
        return hall
    }

    private fun parseHall(json: JsonObject): Hall {
        val hallId = json["hall_id"].asString
        val stage = json.getAsJsonObject("stage")?.let(::parseStage)
        return Hall(hallId, json["hall_name"].asString, json["owner_uuid"].asString,
            json["owner_name"].asString, json["world_uuid"].asString,
            json["material_name"].asString,
            BlockPos(json["min_x"].asInt, json["min_y"].asInt, json["min_z"].asInt),
            BlockPos(json["max_x"].asInt, json["max_y"].asInt, json["max_z"].asInt),
            json.getAsJsonObject("door").let { BlockPos(it["x"].asInt, it["y"].asInt, it["z"].asInt) },
            json["orientation"].asString,
            json.getAsJsonArray("controls").map {
                val c = it.asJsonObject
                Control(hallId, c["kind"].asString, BlockPos(c["x"].asInt, c["y"].asInt, c["z"].asInt))
            }, stage)
    }

    fun stageUpdateFence(stageId: String): StageUpdateFence? = halls.values
        .firstOrNull { it.stage?.id == stageId }
        ?.let { hall -> hall.stage?.let { StageUpdateFence(hall.id, it.id, it.workspaceEpoch, it.workingRevision) } }

    fun stageUpdateFenceForHall(hallId: String): StageUpdateFence? = halls[hallId]?.let { hall ->
        hall.stage?.let { StageUpdateFence(hall.id, it.id, it.workspaceEpoch, it.workingRevision) }
    }

    fun stageUpdateFencesForOwner(ownerUuid: String): Map<String, StageUpdateFence> = halls.values
        .filter { it.ownerUuid == ownerUuid }
        .mapNotNull { hall -> hall.stage?.let { hall.id to StageUpdateFence(hall.id, it.id, it.workspaceEpoch, it.workingRevision) } }
        .toMap()

    /** Apply a direct authoritative Hall/Stage GET response captured under [fence]. */
    @Synchronized
    fun authoritativeReload(hallJson: JsonObject, fence: StageUpdateFence?): Hall? {
        val incoming = parseHall(hallJson)
        val incomingStage = incoming.stage ?: return null
        val current = halls[incoming.id]

        if (fence == null) {
            if (current != null) return null
        } else {
            if (incoming.id != fence.hallId || incomingStage.id != fence.stageId) return null
            val currentStage = current?.stage ?: return null
            if (currentStage.id != fence.stageId || currentStage.workspaceEpoch != fence.workspaceEpoch ||
                currentStage.workingRevision != fence.workingRevision) return null
            if (incomingStage.workspaceEpoch == currentStage.workspaceEpoch) {
                val projectedRevision = graphProjections.view(UUID.fromString(incomingStage.id))?.stageRevision ?: -1
                if (incomingStage.workingRevision < maxOf(currentStage.workingRevision, projectedRevision)) return null
            }
        }

        halls[incoming.id] = incoming
        graphProjections.authoritativeStageReconciliation(
            UUID.fromString(incomingStage.id), incomingStage.workspaceEpoch, incomingStage.workingRevision,
        )
        return incoming
    }

    fun saveDirty(stageId: String): Boolean? {
        val hall = hallForStage(stageId) ?: return null
        val stage = hall.stage ?: return null
        val graph = graph(stageId)
        val synchronizedSnapshot = graph?.snapshot?.takeIf {
            graph.status == me.buciqua.blockommander.runtime.graph.GraphSyncStatus.SYNCED &&
                graph.stageEpoch == stage.workspaceEpoch &&
                it.workspaceEpoch == stage.workspaceEpoch &&
                it.workingRevision >= stage.workingRevision
        }
        return synchronizedSnapshot?.dirty ?: stage.dirty
    }

    @Synchronized
    fun updateStage(stageJson: JsonObject, fence: StageUpdateFence): Hall? {
        val stage = parseStage(stageJson)
        val hallId = stageJson["hall_id"].asString
        if (hallId != fence.hallId || stage.id != fence.stageId) return null
        val hall = halls[hallId] ?: return null
        val currentStage = hall.stage ?: return null
        if (currentStage.id != stage.id) return null
        val stageUuid = UUID.fromString(stage.id)
        val projection = graphProjections.view(stageUuid)

        if (stage.workspaceEpoch != currentStage.workspaceEpoch) {
            val fenceStillCurrent = currentStage.workspaceEpoch == fence.workspaceEpoch &&
                currentStage.workingRevision == fence.workingRevision
            if (!fenceStillCurrent) return null

            val updated = hall.copy(stage = stage)
            halls[updated.id] = updated
            graphProjections.authoritativeStageReload(
                stageUuid, stage.workspaceEpoch, stage.workingRevision,
            )
            return updated
        }

        if (fence.workspaceEpoch != currentStage.workspaceEpoch) return null
        val projectedRevision = projection?.stageRevision ?: -1
        if (stage.workingRevision < maxOf(currentStage.workingRevision, projectedRevision)) return null

        val updated = hall.copy(stage = stage)
        halls[updated.id] = updated
        graphProjections.legacyStageUpdated(stageUuid, stage.workspaceEpoch, stage.workingRevision)
        return updated
    }

    fun remove(id: String): Hall? = halls.remove(id)?.also { hall ->
        hall.stage?.let { graphProjections.remove(UUID.fromString(it.id)) }
    }
    fun graph(stageId: String): StageGraphView? = runCatching { UUID.fromString(stageId) }
        .getOrNull()?.let(graphProjections::view)
    fun hallForStage(stageId: String): Hall? = halls.values.firstOrNull { it.stage?.id == stageId }
    fun controlAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        if (hall.worldUuid == worldUuid) hall.controls.firstOrNull { it.pos == pos } else null
    }
    fun stageAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        hall.stage?.takeIf { hall.worldUuid == worldUuid && it.contains(pos) }
    }
    fun stageBlockAt(worldUuid: String, pos: BlockPos) = stageAt(worldUuid, pos)?.let { stage ->
        stage.blocks.firstOrNull { it.pos == pos }?.let { stage to it }
    }
    fun isProtected(worldUuid: String, pos: BlockPos) = halls.values.any { hall ->
        hall.worldUuid == worldUuid && (
            hall.controls.any { it.pos == pos } ||
                (hall.contains(pos) && (pos.x == hall.min.x || pos.x == hall.max.x ||
                    pos.y == hall.min.y || pos.y == hall.max.y || pos.z == hall.min.z || pos.z == hall.max.z))
            )
    }
    fun inWorld(worldUuid: String) = halls.values.filter { it.worldUuid == worldUuid }

    private fun parseStage(json: JsonObject): Stage {
        val origin = json.getAsJsonObject("origin"); val size = json.getAsJsonObject("size")
        return Stage(json["stage_id"].asString, BlockPos(origin["x"].asInt, origin["y"].asInt, origin["z"].asInt),
            size["x"].asInt, size["y"].asInt, size["z"].asInt, json["dirty"].asBoolean,
            UUID.fromString(json["workspace_epoch"].asString), json["working_revision"].asLong,
            json.getAsJsonArray("blocks").map {
                val b = it.asJsonObject
                StageBlock(b["instance_id"].asString, b["type_key"].asString,
                    BlockPos(b["x"].asInt, b["y"].asInt, b["z"].asInt), b["orientation"].asString,
                    b["label"].asString, b.get("description")?.takeUnless { it.isJsonNull }?.asString ?: "")
            })
    }
}
