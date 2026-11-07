package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.graph.GraphProjectionStore
import me.buciqua.blockommander.runtime.graph.StageGraphView
import me.buciqua.blockommander.runtime.program.MountedProgramResponse
import me.buciqua.blockommander.runtime.program.ProgramWorkspaceState
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
                            val workingRevision: Long, val navigationGeneration: Long? = null,
                            val programStatusGeneration: Long? = null)
data class StageSwitchToken(
    val hallId: String,
    val generation: Long,
    val sourceStageId: UUID,
    val ownerUuid: UUID,
    val programStatusGeneration: Long,
)
data class ProgramStatusFence(val stage: StageUpdateFence, val generation: Long)
data class HallNavigationState(
    val hallId: UUID,
    val mountedProgramId: UUID?,
    val activeStageId: UUID,
    val switchGeneration: Long,
    val switchInProgress: Boolean,
    val pendingStageId: UUID?,
    val programWorkspaceEpoch: UUID?,
    val hierarchyRevision: Long?,
)
data class Hall(val id: String, val name: String, val ownerUuid: String, val ownerName: String,
                val worldUuid: String, val material: String,
                val min: BlockPos, val max: BlockPos, val door: BlockPos, val orientation: String,
                val controls: List<Control>, val stage: Stage?,
                val transportContext: WorkingCopyTransportContext,
                val programDirty: Boolean = true) {
    fun contains(pos: BlockPos) = pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

class HallRegistry(val graphProjections: GraphProjectionStore = GraphProjectionStore()) {
    private enum class StageResponseKind { LEGACY_MUTATION, SAVE }

    private val halls = ConcurrentHashMap<String, Hall>()
    private val navigation = ConcurrentHashMap<String, HallNavigationState>()
    private val programStatusGenerations = ConcurrentHashMap<String, Long>()

    private fun invalidateProgramStatus(hallId: String): Long =
        programStatusGenerations.merge(hallId, 1L, Long::plus)!!

    @Synchronized
    fun beginProgramStatus(hallId: String): ProgramStatusFence? {
        val fence = saveFenceForHall(hallId) ?: return null
        // Unknown while refreshing: only Python may confirm clean.
        halls.computeIfPresent(hallId) { _, hall -> hall.copy(programDirty = true) }
        val generation = invalidateProgramStatus(hallId)
        val currentFence = fence.copy(programStatusGeneration = generation)
        if (!isSaveFenceCurrent(currentFence)) return null
        return ProgramStatusFence(currentFence, generation)
    }

    @Synchronized
    fun applyProgramStatus(fence: ProgramStatusFence, response: MountedProgramResponse): Hall? {
        val stage = fence.stage
        if (programStatusGenerations[stage.hallId] != fence.generation || !isSaveFenceCurrent(stage)) return null
        val hall = halls[stage.hallId] ?: return null
        val program = response.program
        val state = navigation[stage.hallId] ?: return null
        val workspace = program.stages.singleOrNull { it.stageId.toString() == stage.stageId }?.workspace
            ?: return null
        if (response.mount.hallId.toString() != hall.id || !response.mount.current ||
            response.mount.workingCopyId != hall.transportContext.workingCopyId ||
            response.mount.programId != program.programId || program.ownerUuid.toString() != hall.ownerUuid ||
            workspace.epoch != stage.workspaceEpoch || workspace.workingRevision != stage.workingRevision ||
            (state.mountedProgramId != null && state.mountedProgramId != program.programId) ||
            (state.programWorkspaceEpoch != null && state.programWorkspaceEpoch != program.workspaceEpoch)) return null
        return hall.copy(programDirty = program.aggregateDirty ||
            program.workspaceState != ProgramWorkspaceState.ACTIVE).also { halls[hall.id] = it }
    }

    @Synchronized
    fun install(json: JsonObject): Hall {
        val hall = parseHall(json)
        val stage = hall.stage
        val previous = halls[hall.id]
        require(previous == null || previous.transportContext == hall.transportContext) {
            "HALL_TRANSPORT_CONTEXT_CHANGED"
        }
        halls[hall.id] = hall
        invalidateProgramStatus(hall.id)
        if (previous?.stage?.id != null && previous.stage.id != stage?.id) {
            graphProjections.remove(UUID.fromString(previous.stage.id))
        }
        stage?.let { graphProjections.authoritativeStageReload(UUID.fromString(it.id), it.workspaceEpoch, it.workingRevision) }
        stage?.let {
            navigation[hall.id]=HallNavigationState(
                UUID.fromString(hall.id),null,UUID.fromString(it.id),
                (navigation[hall.id]?.switchGeneration ?: 0L)+1L,false,null,null,null,
            )
        }
        return hall
    }

    private fun parseHall(json: JsonObject): Hall {
        val transportContext = TransportContextParser.fromHall(json)
        val hallId = transportContext.hallId.toString()
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
            }, stage, transportContext,
            json.getAsJsonObject("stage")?.get("program_dirty")?.asBoolean ?: true)
    }

    fun transportContext(hallId: String): WorkingCopyTransportContext? = halls[hallId]?.transportContext

    fun stageUpdateFence(stageId: String): StageUpdateFence? = halls.values
        .firstOrNull { it.stage?.id == stageId }
        ?.let { hall -> hall.stage?.let { StageUpdateFence(hall.id, it.id, it.workspaceEpoch, it.workingRevision) } }

    fun stageUpdateFenceForHall(hallId: String): StageUpdateFence? = halls[hallId]?.let { hall ->
        hall.stage?.let { StageUpdateFence(hall.id, it.id, it.workspaceEpoch, it.workingRevision,
            navigation[hall.id]?.switchGeneration) }
    }

    @Synchronized
    fun saveFenceForHall(hallId: String): StageUpdateFence? {
        val fence = stageUpdateFenceForHall(hallId) ?: return null
        val stage = halls[hallId]?.stage ?: return null
        return fence.copy(workingRevision = currentRevision(stage),
            programStatusGeneration = programStatusGenerations[hallId])
    }

    private fun currentRevision(stage: Stage): Long {
        val graph = graphProjections.view(UUID.fromString(stage.id))
        return maxOf(stage.workingRevision,
            graph?.takeIf { it.stageEpoch == stage.workspaceEpoch }?.stageRevision ?: -1)
    }

    @Synchronized
    fun isSaveFenceCurrent(fence: StageUpdateFence): Boolean {
        val stage = halls[fence.hallId]?.stage ?: return false
        val state = navigation[fence.hallId] ?: return false
        return !state.switchInProgress && state.switchGeneration == fence.navigationGeneration &&
            (fence.programStatusGeneration == null ||
                programStatusGenerations[fence.hallId] == fence.programStatusGeneration) &&
            stage.id == fence.stageId && stage.workspaceEpoch == fence.workspaceEpoch &&
            currentRevision(stage) == fence.workingRevision
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
        if (current != null && current.transportContext != incoming.transportContext) return null

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
        invalidateProgramStatus(incoming.id)
        graphProjections.authoritativeStageReconciliation(
            UUID.fromString(incomingStage.id), incomingStage.workspaceEpoch, incomingStage.workingRevision,
        )
        return incoming
    }

    fun saveDirty(stageId: String): Boolean? {
        return hallForStage(stageId)?.programDirty
    }

    @Synchronized
    fun beginStageSwitch(hallId:String,playerUuid:UUID):StageSwitchToken? {
        val hall=halls[hallId] ?: return null
        if (UUID.fromString(hall.ownerUuid)!=playerUuid) return null
        val stage=hall.stage ?: return null
        val previous=navigation[hallId] ?: HallNavigationState(
            UUID.fromString(hallId),null,UUID.fromString(stage.id),0,false,null,null,null,
        )
        val generation=previous.switchGeneration+1
        val statusGeneration = invalidateProgramStatus(hallId)
        navigation[hallId]=previous.copy(
            activeStageId=UUID.fromString(stage.id),switchGeneration=generation,
            switchInProgress=true,pendingStageId=null,
        )
        return StageSwitchToken(hallId,generation,UUID.fromString(stage.id),playerUuid,statusGeneration)
    }

    @Synchronized
    fun bindStageSwitch(
        token:StageSwitchToken,programId:UUID,programEpoch:UUID,
        hierarchyRevision:Long,targetStageId:UUID,
    ):Boolean {
        val state=navigation[token.hallId] ?: return false
        val hall=halls[token.hallId] ?: return false
        if (!state.switchInProgress || state.switchGeneration!=token.generation ||
            programStatusGenerations[token.hallId]!=token.programStatusGeneration ||
            state.activeStageId!=token.sourceStageId || hall.stage?.id!=token.sourceStageId.toString() ||
            (state.mountedProgramId!=null && state.mountedProgramId!=programId)) return false
        navigation[token.hallId]=state.copy(
            mountedProgramId=programId,pendingStageId=targetStageId,
            programWorkspaceEpoch=programEpoch,hierarchyRevision=hierarchyRevision,
        )
        return true
    }

    @Synchronized
    fun isStageSwitchCurrent(
        token:StageSwitchToken,programId:UUID,targetStageId:UUID,
        programEpoch:UUID,hierarchyRevision:Long,
    ):Boolean {
        val state=navigation[token.hallId] ?: return false
        return state.switchInProgress && state.switchGeneration==token.generation &&
            programStatusGenerations[token.hallId]==token.programStatusGeneration &&
            state.activeStageId==token.sourceStageId && state.mountedProgramId==programId &&
            state.pendingStageId==targetStageId && state.programWorkspaceEpoch==programEpoch &&
            state.hierarchyRevision==hierarchyRevision
    }

    @Synchronized
    fun commitStageSwitch(
        token:StageSwitchToken,programId:UUID,programEpoch:UUID,
        hierarchyRevision:Long,target:Stage,programDirty:Boolean = true,
    ):Hall? {
        val targetId=UUID.fromString(target.id)
        if (!isStageSwitchCurrent(token,programId,targetId,programEpoch,hierarchyRevision)) return null
        val hall=halls[token.hallId] ?: return null
        if (hall.stage?.id!=token.sourceStageId.toString()) return null
        val updated=hall.copy(stage=target,programDirty=programDirty)
        invalidateProgramStatus(hall.id)
        halls[token.hallId]=updated
        navigation[token.hallId]=navigation.getValue(token.hallId).copy(
            activeStageId=targetId,switchInProgress=false,pendingStageId=null,
        )
        if (token.sourceStageId!=targetId) graphProjections.remove(token.sourceStageId)
        return updated
    }

    @Synchronized
    fun failStageSwitch(token:StageSwitchToken) {
        val state=navigation[token.hallId] ?: return
        if (state.switchGeneration==token.generation) {
            navigation[token.hallId]=state.copy(switchInProgress=false,pendingStageId=null)
        }
    }

    fun navigationState(hallId:String):HallNavigationState?=navigation[hallId]

    fun isActiveStage(stageId:UUID,epoch:UUID):Boolean=halls.values.any {
        val stage=it.stage
        stage!=null && stage.id==stageId.toString() && stage.workspaceEpoch==epoch &&
            navigation[it.id]?.activeStageId==stageId
    }

    @Synchronized
    fun updateStage(stageJson: JsonObject, fence: StageUpdateFence): Hall? =
        updateStage(stageJson, fence, StageResponseKind.LEGACY_MUTATION)

    /** Map a Stage-domain CRUD response into the currently mounted Hall coordinate system. */
    @Synchronized
    fun updateStageFromDomainResponse(stageJson:JsonObject,fence:StageUpdateFence):Hall? {
        return updateDomainStage(stageJson,fence,StageResponseKind.LEGACY_MUTATION)
    }

    private fun updateDomainStage(stageJson:JsonObject,fence:StageUpdateFence,kind:StageResponseKind):Hall? {
        val current=halls[fence.hallId]?.stage ?: return null
        if (current.id!=fence.stageId) return null
        val normalized=stageJson.deepCopy().apply {
            addProperty("hall_id",fence.hallId)
            add("origin",JsonObject().apply {
                addProperty("x",current.origin.x);addProperty("y",current.origin.y);addProperty("z",current.origin.z)
            })
        }
        val blocks=normalized.getAsJsonArray("blocks") ?: return null
        for (element in blocks) {
            val block=element.asJsonObject
            val relX=block.get("rel_x")?.asInt ?: return null
            val relY=block.get("rel_y")?.asInt ?: return null
            val relZ=block.get("rel_z")?.asInt ?: return null
            if (relX !in 0 until current.sizeX || relY !in 0 until current.sizeY ||
                relZ !in 0 until current.sizeZ) return null
            block.addProperty("x",current.origin.x+relX)
            block.addProperty("y",current.origin.y+relY)
            block.addProperty("z",current.origin.z+relZ)
        }
        return updateStage(normalized,fence,kind)
    }

    @Synchronized
    fun updateStageAfterSave(stageJson: JsonObject, fence: StageUpdateFence): Hall? {
        if (!isSaveFenceCurrent(fence) || stageJson["stage_id"].asString != fence.stageId ||
            stageJson["workspace_epoch"].asString != fence.workspaceEpoch.toString() ||
            stageJson["working_revision"].asLong != fence.workingRevision) return null
        return updateDomainStage(stageJson, fence, StageResponseKind.SAVE)
    }

    private fun updateStage(stageJson: JsonObject, fence: StageUpdateFence, kind: StageResponseKind): Hall? {
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

            val updated = hall.copy(stage = stage, programDirty = stageJson["program_dirty"]?.asBoolean ?: true)
            invalidateProgramStatus(hall.id)
            halls[updated.id] = updated
            graphProjections.authoritativeStageReload(
                stageUuid, stage.workspaceEpoch, stage.workingRevision,
            )
            return updated
        }

        if (fence.workspaceEpoch != currentStage.workspaceEpoch) return null
        val projectedRevision = projection?.stageRevision ?: -1
        if (stage.workingRevision < maxOf(currentStage.workingRevision, projectedRevision)) return null

        val updated = hall.copy(stage = stage, programDirty = stageJson["program_dirty"]?.asBoolean ?: true)
        invalidateProgramStatus(hall.id)
        halls[updated.id] = updated
        if (kind == StageResponseKind.SAVE) {
            graphProjections.authoritativeStageReconciliation(
                stageUuid, stage.workspaceEpoch, stage.workingRevision,
            )
        } else {
            graphProjections.legacyStageUpdated(stageUuid, stage.workspaceEpoch, stage.workingRevision)
        }
        return updated
    }

    fun remove(id: String): Hall? = halls.remove(id)?.also { hall ->
        navigation.remove(id)
        hall.stage?.let { graphProjections.remove(UUID.fromString(it.id)) }
    }
    fun graph(stageId: String): StageGraphView? = runCatching { UUID.fromString(stageId) }
        .getOrNull()?.let(graphProjections::view)
    fun hall(hallId:String):Hall?=halls[hallId]
    fun hallForStage(stageId: String): Hall? = halls.values.firstOrNull { it.stage?.id == stageId }
    fun controlAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        if (hall.worldUuid == worldUuid) hall.controls.firstOrNull { it.pos == pos } else null
    }
    fun stageAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        hall.stage?.takeIf { hall.worldUuid == worldUuid && it.contains(pos) }
    }
    fun hallAt(worldUuid: String, pos: BlockPos): Hall? = halls.values.firstOrNull { hall ->
        hall.worldUuid == worldUuid && hall.stage?.contains(pos) == true
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
