package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.graph.GraphProjectionStore
import me.buciqua.blockommander.runtime.graph.StageGraphView
import me.buciqua.blockommander.runtime.program.MountedProgramResponse
import me.buciqua.blockommander.runtime.program.ProgramWorkspaceState
import me.buciqua.blockommander.runtime.program.HallSelection
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
                            val workingRevision: Long, val key: ProjectionKey, val hallGeneration: Long,
                            val projectionGeneration: Long,
                            val navigationGeneration: Long? = null,
                            val programStatusGeneration: Long? = null)
data class StageSwitchToken(
    val hallId: String,
    val generation: Long,
    val sourceStageId: UUID,
    val ownerUuid: UUID,
    val programStatusGeneration: Long,
    val transportContext: WorkingCopyTransportContext,
    val hallGeneration: Long,
    val selection: Boolean = false,
)
data class ProgramStatusFence(val stage: StageUpdateFence, val generation: Long)
class BootstrapFence internal constructor(val worldUuid: String, internal val generations: Map<String,Long>)
data class WireCompletionFence(val stage: StageUpdateFence, val worldUuid: String)
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
                val programDirty: Boolean = true,
                val code: String = "",
                val selectionRevision: Long = 0,
                val selectedStageId: UUID? = null) {
    fun contains(pos: BlockPos) = pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

class HallRegistry(val graphProjections: GraphProjectionStore = GraphProjectionStore()) {
    private enum class StageResponseKind { LEGACY_MUTATION, SAVE }

    private val halls = ConcurrentHashMap<String, Hall>()
    private val navigation = ConcurrentHashMap<String, HallNavigationState>()
    private val programStatusGenerations = ConcurrentHashMap<String, Long>()
    private val hallGenerations = ConcurrentHashMap<String, Long>()
    private val generations = java.util.concurrent.atomic.AtomicLong()
    private data class PendingProjection(val owner: Long, val key: ProjectionKey, val lifecycle: Long)
    private val pendingProjections=HashMap<String,PendingProjection>()
    private val selections=ConcurrentHashMap<String,HallSelectionState>()
    fun selectionState(hallId:String)=selections[hallId]
    fun editingAllowed(hallId:String)=selections[hallId]?.phase.let {
        it==null || it==HallSelectionPhase.DISPLAYED
    }

    @Synchronized
    fun beginHallSelection(hallId:String,player:UUID,world:UUID):HallSelectionAttempt? {
        val hall=halls[hallId] ?: return null
        if(hall.ownerUuid!=player.toString() || hall.worldUuid!=world.toString() || hall.stage==null) return null
        val previous=selections.remove(hallId)
        val known=previous?.desired?.takeIf { it.revision>=hall.selectionRevision }
            ?: HallSelection(hall.transportContext,world,hall.selectedStageId ?: UUID.fromString(hall.stage.id),hall.selectionRevision)
        renewHall(hallId) // fences startup/reconnect and all pre-existing completions
        val token=beginStageSwitch(hallId,player)?.copy(selection=true) ?: return null
        val attempt=HallSelectionAttempt(token,world,known.revision,
            graphProjections.view(hall.projectionKey())?.lifecycleGeneration,known)
        selections[hallId]=HallSelectionState(attempt,HallSelectionPhase.SELECTION_REQUESTED)
        return attempt
    }

    @Synchronized
    fun selectionCurrent(attempt:HallSelectionAttempt):Boolean {
        val token=attempt.token
        val session=selections[token.hallId] ?: return false
        val hall=halls[token.hallId] ?: return false
        val nav=navigation[token.hallId] ?: return false
        return session.attempt==attempt && session.phase !in setOf(HallSelectionPhase.DISPLAYED,HallSelectionPhase.PROJECTION_FAILED) &&
            hall.worldUuid==attempt.worldUuid.toString() && hall.transportContext==token.transportContext &&
            hallGenerations[token.hallId]==token.hallGeneration && nav.switchGeneration==token.generation &&
            nav.switchInProgress && programStatusGenerations[token.hallId]==token.programStatusGeneration &&
            (session.phase!=HallSelectionPhase.SELECTION_REQUESTED ||
                graphProjections.view(hall.projectionKey())?.lifecycleGeneration==attempt.sourceLifecycle)
    }

    @Synchronized
    fun acceptSelection(attempt:HallSelectionAttempt,desired:HallSelection):Boolean {
        if(selections[attempt.token.hallId]?.phase!=HallSelectionPhase.SELECTION_REQUESTED ||
            !selectionCurrent(attempt) || desired.context.hallId.toString()!=attempt.token.hallId ||
            desired.worldUuid!=attempt.worldUuid || desired.revision<attempt.minimumRevision) return false
        selections[attempt.token.hallId]=HallSelectionState(attempt,HallSelectionPhase.DESIRED_COMMITTED,desired)
        graphProjections.removeHall(desired.context.hallId)
        return true
    }

    @Synchronized
    fun selectionPreparing(attempt:HallSelectionAttempt):Boolean {
        if(selections[attempt.token.hallId]?.phase!=HallSelectionPhase.DESIRED_COMMITTED || !selectionCurrent(attempt)) return false
        selections.computeIfPresent(attempt.token.hallId) { _,state->state.copy(phase=HallSelectionPhase.PROJECTION_PREPARING) }
        return true
    }

    @Synchronized
    fun failSelection(attempt:HallSelectionAttempt,detail:String) {
        val session=selections[attempt.token.hallId] ?: return
        if(session.attempt!=attempt || session.phase==HallSelectionPhase.DISPLAYED ||
            hallGenerations[attempt.token.hallId]!=attempt.token.hallGeneration ||
            navigation[attempt.token.hallId]?.switchGeneration!=attempt.token.generation) return
        failStageSwitch(attempt.token)
        selections.computeIfPresent(attempt.token.hallId) { _,state->state.copy(
            phase=HallSelectionPhase.PROJECTION_FAILED,failure=detail) }
    }

    fun projectionContext(token:StageSwitchToken):WorkingCopyTransportContext =
        selections[token.hallId]?.takeIf { token.selection && it.attempt.token==token }?.desired?.context
            ?: token.transportContext
    private fun renewHall(hallId: String) { hallGenerations[hallId] = generations.incrementAndGet() }

    @Synchronized
    fun bootstrapFence(worldUuid: String) = BootstrapFence(worldUuid,
        java.util.Collections.unmodifiableMap(HashMap(hallGenerations)))

    /** Called on the server thread: validation, install and projection have no queued gap. */
    @Synchronized
    fun applyBootstrap(json: JsonObject, fence: BootstrapFence, project: (Hall)->Unit): Boolean {
        val context=TransportContextParser.fromHall(json)
        val id=context.hallId.toString()
        if (!editingAllowed(id) || json["world_uuid"].asString!=fence.worldUuid ||
            hallGenerations[id]!=fence.generations[id]) return false
        project(install(json))
        return true
    }

    @Synchronized
    fun wireCompletionFence(hallId: String, worldUuid: String): WireCompletionFence? {
        val hall=halls[hallId] ?: return null
        if (hall.worldUuid!=worldUuid || navigation[hallId]?.switchInProgress!=false) return null
        return stageUpdateFenceForHall(hallId)?.let { WireCompletionFence(it,worldUuid) }
    }

    @Synchronized
    fun completeWire(fence: WireCompletionFence, worldUuid: String, effect: ()->Unit): Boolean {
        val hall=halls[fence.stage.hallId] ?: return false
        if (worldUuid!=fence.worldUuid || hall.worldUuid!=worldUuid ||
            !isStageFenceCurrent(fence.stage) || hall.stage?.workspaceEpoch!=fence.stage.workspaceEpoch ||
            navigation[hall.id]?.switchInProgress!=false) return false
        effect()
        return true
    }

    private fun invalidateProgramStatus(hallId: String): Long =
        programStatusGenerations.merge(hallId, 1L, Long::plus)!!

    @Synchronized
    fun beginProgramStatus(hallId: String): ProgramStatusFence? {
        val fence = saveFenceForHall(hallId) ?: run {
            Diagnostics.event("SAVE_STATUS_BEGIN",null,"hall_id" to hallId,"reason" to "NO_SAVE_FENCE")
            return null
        }
        val before=halls[hallId]?.programDirty
        // Unknown while refreshing: only Python may confirm clean.
        halls.computeIfPresent(hallId) { _, hall -> hall.copy(programDirty = true) }
        val generation = invalidateProgramStatus(hallId)
        val currentFence = fence.copy(programStatusGeneration = generation)
        Diagnostics.event("SAVE_STATUS_BEGIN",fence.key,"reason" to "STARTED",
            "dirty_before" to before,"dirty_after" to halls[hallId]?.programDirty,
            "status_generation" to generation,"navigation_generation" to fence.navigationGeneration,
            "hall_generation" to fence.hallGeneration,"epoch" to fence.workspaceEpoch,"revision" to fence.workingRevision)
        if (!isSaveFenceCurrent(currentFence)) return null
        return ProgramStatusFence(currentFence, generation)
    }

    @Synchronized
    fun applyProgramStatus(fence: ProgramStatusFence, response: MountedProgramResponse): Hall? {
        val stage = fence.stage
        fun trace(reason:String) {
            val current=halls[stage.hallId]
            val slot=graphProjections.view(stage.key)
            val responseStage=response.program.stages.singleOrNull { it.stageId.toString()==stage.stageId }?.workspace
            Diagnostics.event("SAVE_STATUS_APPLY",stage.key,"reason" to reason,
                "requested_status_generation" to fence.generation,"status_generation" to programStatusGenerations[stage.hallId],
                "requested_epoch" to stage.workspaceEpoch,"requested_revision" to stage.workingRevision,
                "response_program_epoch" to response.program.workspaceEpoch,"response_dirty" to response.program.aggregateDirty,
                "response_root_stage_id" to response.program.rootStageId,"displayed_stage_id" to halls[stage.hallId]?.stage?.id,
                "response_stage_epoch" to responseStage?.epoch,"response_stage_revision" to responseStage?.workingRevision,
                "current_epoch" to current?.stage?.workspaceEpoch,"current_revision" to current?.stage?.workingRevision,
                "slot_epoch" to slot?.stageEpoch,"slot_revision" to slot?.stageRevision,"slot_lifecycle" to slot?.lifecycleGeneration,
                "requested_lifecycle" to stage.projectionGeneration,"hall_generation" to hallGenerations[stage.hallId],
                "requested_hall_generation" to stage.hallGeneration,"navigation_generation" to navigation[stage.hallId]?.switchGeneration,
                "requested_navigation_generation" to stage.navigationGeneration,
                "dirty_current" to halls[stage.hallId]?.programDirty)
        }
        fun reject(reason:String):Hall? { trace(reason);return null }
        if (programStatusGenerations[stage.hallId] != fence.generation) return reject("STATUS_GENERATION_CHANGED")
        if (!isSaveFenceCurrent(stage)) return reject("SAVE_FENCE_CHANGED")
        val hall = halls[stage.hallId] ?: return reject("HALL_MISSING")
        val program = response.program
        val state = navigation[stage.hallId] ?: return null
        val workspace = program.stages.singleOrNull { it.stageId.toString() == stage.stageId }?.workspace
            ?: return reject("RESPONSE_STAGE_WORKSPACE_MISSING")
        if (response.mount.hallId.toString() != hall.id || !response.mount.current ||
            response.mount.workingCopyId != hall.transportContext.workingCopyId ||
            response.mount.programId != program.programId || program.ownerUuid.toString() != hall.ownerUuid ||
            workspace.epoch != stage.workspaceEpoch || workspace.workingRevision != stage.workingRevision ||
            (state.mountedProgramId != null && state.mountedProgramId != program.programId) ||
            (state.programWorkspaceEpoch != null && state.programWorkspaceEpoch != program.workspaceEpoch)) return reject(when {
                response.mount.hallId.toString()!=hall.id || response.mount.workingCopyId!=hall.transportContext.workingCopyId ||
                    response.mount.programId!=program.programId || !response.mount.current -> "RESPONSE_MOUNT_MISMATCH"
                program.ownerUuid.toString()!=hall.ownerUuid -> "RESPONSE_OWNER_MISMATCH"
                workspace.epoch!=stage.workspaceEpoch || workspace.workingRevision!=stage.workingRevision -> "RESPONSE_STAGE_VERSION_MISMATCH"
                state.mountedProgramId!=null && state.mountedProgramId!=program.programId -> "MOUNTED_PROGRAM_CHANGED"
                else -> "PROGRAM_WORKSPACE_EPOCH_CHANGED"
            })
        return hall.copy(programDirty = program.aggregateDirty ||
            program.workspaceState != ProgramWorkspaceState.ACTIVE).also { halls[hall.id] = it;trace("ACCEPTED") }
    }

    @Synchronized
    fun install(json: JsonObject): Hall {
        val hall = parseHall(json)
        selections.remove(hall.id)
        val stage = hall.stage
        pendingProjections.remove(hall.id)
        graphProjections.removeHall(hall.transportContext.hallId)
        renewHall(hall.id)
        halls[hall.id] = hall
        invalidateProgramStatus(hall.id)
        stage?.let { graphProjections.authoritativeStageReload(hall.projectionKey(), it.workspaceEpoch, it.workingRevision) }
        stage?.let {
            navigation[hall.id]=HallNavigationState(
                UUID.fromString(hall.id),hall.transportContext.programId,UUID.fromString(it.id),
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
            json.getAsJsonObject("stage")?.get("program_dirty")?.asBoolean ?: true,
            json.get("hall_code")?.asString.orEmpty(),json.get("selection_revision")?.asLong ?: 0,
            json.get("active_stage_id")?.asString?.let(UUID::fromString) ?: stage?.id?.let(UUID::fromString)).also {
                require(stage==null || it.permitsProjection(stage)) { "STAGE_OUTSIDE_HALL" }
            }
    }

    fun transportContext(hallId: String): WorkingCopyTransportContext? = halls[hallId]?.transportContext

    fun stageUpdateFenceForHall(hallId: String): StageUpdateFence? {
        if(!editingAllowed(hallId)) return null
        val hall=halls[hallId] ?: return null
        val stage=hall.stage ?: return null
        val projection=graphProjections.view(hall.projectionKey()) ?: return null
        return StageUpdateFence(hall.id,stage.id,stage.workspaceEpoch,stage.workingRevision,
            hall.projectionKey(),hallGenerations.getValue(hallId),projection.lifecycleGeneration,
            navigation[hall.id]?.switchGeneration)
    }

    fun isStageFenceCurrent(fence: StageUpdateFence): Boolean {
        if(!editingAllowed(fence.hallId)) return false
        val hall=halls[fence.hallId] ?: return false
        return fence.key.hallId.toString()==fence.hallId && fence.key.stageId.toString()==fence.stageId &&
            hallGenerations[fence.hallId]==fence.hallGeneration &&
            graphProjections.isLifecycleCurrent(fence.key,fence.projectionGeneration) &&
            hall.transportContext==fence.key.transportContext &&
            hall.stage?.id==fence.stageId &&
            navigation[fence.hallId]?.switchGeneration==fence.navigationGeneration
    }

    @Synchronized
    fun saveFenceForHall(hallId: String): StageUpdateFence? {
        val fence = stageUpdateFenceForHall(hallId) ?: return null
        val stage = halls[hallId]?.stage ?: return null
        return fence.copy(workingRevision = currentRevision(halls.getValue(hallId), stage),
            programStatusGeneration = programStatusGenerations[hallId])
    }

    private fun currentRevision(hall: Hall, stage: Stage): Long {
        val graph = graphProjections.view(hall.projectionKey())
        return maxOf(stage.workingRevision,
            graph?.takeIf { it.stageEpoch == stage.workspaceEpoch }?.stageRevision ?: -1)
    }

    @Synchronized
    fun isSaveFenceCurrent(fence: StageUpdateFence): Boolean {
        val stage = halls[fence.hallId]?.stage ?: return false
        val state = navigation[fence.hallId] ?: return false
        return isStageFenceCurrent(fence) && !state.switchInProgress && state.switchGeneration == fence.navigationGeneration &&
            (fence.programStatusGeneration == null ||
                programStatusGenerations[fence.hallId] == fence.programStatusGeneration) &&
            stage.id == fence.stageId && stage.workspaceEpoch == fence.workspaceEpoch &&
            currentRevision(halls.getValue(fence.hallId), stage) == fence.workingRevision
    }

    fun stageUpdateFencesForOwner(ownerUuid: String): Map<String, StageUpdateFence> = halls.values
        .filter { it.ownerUuid == ownerUuid }
        .mapNotNull { hall -> stageUpdateFenceForHall(hall.id)?.let { hall.id to it } }
        .toMap()

    /** Apply a direct authoritative Hall/Stage GET response captured under [fence]. */
    @Synchronized
    fun authoritativeReload(hallJson: JsonObject, fence: StageUpdateFence?): Hall? {
        val incoming = parseHall(hallJson)
        val observedCurrent=halls[incoming.id]
        val observedProjection=observedCurrent?.stage?.let { graphProjections.view(observedCurrent.projectionKey()) }
        val observedHallGeneration=hallGenerations[incoming.id]
        val observedNavigationGeneration=navigation[incoming.id]?.switchGeneration
        fun trace(reason:String) {
            val current=observedCurrent
            val projection=observedProjection
            Diagnostics.event("RECONNECT_STAGE_RESULT",incoming.stage?.let { incoming.projectionKey() },
                "reason" to reason,"hall_id" to incoming.id,
                "requested_program_id" to fence?.key?.programId,"current_program_id" to current?.transportContext?.programId,
                "response_program_id" to incoming.transportContext.programId,
                "requested_working_copy_id" to fence?.key?.workingCopyId,
                "current_working_copy_id" to current?.transportContext?.workingCopyId,
                "response_working_copy_id" to incoming.transportContext.workingCopyId,
                "requested_stage_id" to fence?.stageId,"current_stage_id" to current?.stage?.id,
                "response_stage_id" to incoming.stage?.id,"response_stage_source" to "HALL_READ_MODEL",
                "requested_epoch" to fence?.workspaceEpoch,"current_epoch" to current?.stage?.workspaceEpoch,
                "response_epoch" to incoming.stage?.workspaceEpoch,"response_revision" to incoming.stage?.workingRevision,
                "requested_revision" to fence?.workingRevision,"current_revision" to current?.stage?.workingRevision,
                "slot_epoch" to projection?.stageEpoch,"slot_revision" to projection?.stageRevision,
                "slot_lifecycle" to projection?.lifecycleGeneration,
                "requested_hall_generation" to fence?.hallGeneration,"hall_generation" to observedHallGeneration,
                "requested_navigation_generation" to fence?.navigationGeneration,
                "navigation_generation" to observedNavigationGeneration,
                "requested_projection_lifecycle" to fence?.projectionGeneration)
        }
        fun reject(reason:String):Hall? { trace(reason);return null }
        if(!editingAllowed(incoming.id)) return reject("SELECTION_UNRESOLVED")
        val incomingStage = incoming.stage ?: return reject("RESPONSE_STAGE_MISSING")
        val current = halls[incoming.id]
        if (current != null && current.transportContext != incoming.transportContext) return reject("TRANSPORT_CONTEXT_CHANGED")

        if (fence == null) {
            if (current != null || hallGenerations.containsKey(incoming.id)) return reject("UNFENCED_EXISTING_OR_TOMBSTONED_HALL")
        } else {
            if (!isStageFenceCurrent(fence)) return reject(when {
                hallGenerations[fence.hallId]!=fence.hallGeneration -> "HALL_GENERATION_CHANGED"
                !graphProjections.isLifecycleCurrent(fence.key,fence.projectionGeneration) -> "PROJECTION_LIFECYCLE_CHANGED"
                navigation[fence.hallId]?.switchGeneration!=fence.navigationGeneration -> "NAVIGATION_GENERATION_CHANGED"
                else -> "STAGE_FENCE_CHANGED"
            })
            if (incoming.id != fence.hallId) return reject("RESPONSE_HALL_MISMATCH")
            if (incomingStage.id != fence.stageId) return reject("RESPONSE_STAGE_MISMATCH")
            val currentStage = current?.stage ?: return reject("CURRENT_STAGE_MISSING")
            if (currentStage.id != fence.stageId || currentStage.workspaceEpoch != fence.workspaceEpoch ||
                currentStage.workingRevision != fence.workingRevision) return reject("CURRENT_STAGE_VERSION_CHANGED")
            if (incomingStage.workspaceEpoch == currentStage.workspaceEpoch) {
                val projectedRevision = graphProjections.view(incoming.projectionKey())?.stageRevision ?: -1
                if (incomingStage.workingRevision < maxOf(currentStage.workingRevision, projectedRevision)) return reject("RESPONSE_REVISION_BEHIND")
            }
        }

        halls[incoming.id] = incoming
        renewHall(incoming.id)
        navigation[incoming.id]=HallNavigationState(
            incoming.transportContext.hallId,incoming.transportContext.programId,UUID.fromString(incomingStage.id),
            (navigation[incoming.id]?.switchGeneration ?: 0L)+1L,false,null,null,null,
        )
        invalidateProgramStatus(incoming.id)
        graphProjections.authoritativeStageReconciliation(
            incoming.projectionKey(), incomingStage.workspaceEpoch, incomingStage.workingRevision,
        )
        trace("ACCEPTED")
        return incoming
    }

    fun saveDirty(hallId: String): Boolean? = halls[hallId]?.programDirty

    @Synchronized
    fun beginStageSwitch(hallId:String,playerUuid:UUID):StageSwitchToken? {
        if(!editingAllowed(hallId)) return null
        val hall=halls[hallId] ?: return null
        if (UUID.fromString(hall.ownerUuid)!=playerUuid) return null
        val stage=hall.stage ?: return null
        pendingProjections.remove(hallId)?.let {
            graphProjections.removeIfLifecycleCurrent(it.key,it.lifecycle)
        }
        val previous=navigation[hallId] ?: HallNavigationState(
            UUID.fromString(hallId),null,UUID.fromString(stage.id),0,false,null,null,null,
        )
        val generation=previous.switchGeneration+1
        val statusGeneration = invalidateProgramStatus(hallId)
        navigation[hallId]=previous.copy(
            activeStageId=UUID.fromString(stage.id),switchGeneration=generation,
            switchInProgress=true,pendingStageId=null,
        )
        return StageSwitchToken(hallId,generation,UUID.fromString(stage.id),playerUuid,statusGeneration,
            hall.transportContext,hallGenerations.getValue(hallId))
    }

    @Synchronized
    fun bindStageSwitch(
        token:StageSwitchToken,programId:UUID,programEpoch:UUID,
        hierarchyRevision:Long,targetStageId:UUID,
    ):Boolean {
        val state=navigation[token.hallId] ?: return false
        val hall=halls[token.hallId] ?: return false
        if (hall.transportContext!=token.transportContext || hallGenerations[token.hallId]!=token.hallGeneration) return false
        val permittedProgram=if(token.selection) selections[token.hallId]?.let {
            selectionCurrent(it.attempt) && it.desired?.context?.programId==programId && it.desired.stageId==targetStageId
        }==true else state.mountedProgramId==null || state.mountedProgramId==programId
        if (!state.switchInProgress || state.switchGeneration!=token.generation ||
            programStatusGenerations[token.hallId]!=token.programStatusGeneration ||
            state.activeStageId!=token.sourceStageId || hall.stage?.id!=token.sourceStageId.toString() ||
            !permittedProgram) return false
        navigation[token.hallId]=state.copy(
            mountedProgramId=programId,pendingStageId=targetStageId,
            programWorkspaceEpoch=programEpoch,hierarchyRevision=hierarchyRevision,
        )
        return true
    }

    @Synchronized
    fun prepareStageSwitchProjection(
        token:StageSwitchToken, programId:UUID, programEpoch:UUID, hierarchyRevision:Long,
        targetStageId:UUID, stageEpoch:UUID, revision:Long,
    ):Boolean {
        if (!bindStageSwitch(token,programId,programEpoch,hierarchyRevision,targetStageId)) return false
        val key=projectionContext(token).projectionKey(targetStageId)
        val view=graphProjections.authoritativeStageReconciliation(key,stageEpoch,revision)
        pendingProjections[token.hallId]=PendingProjection(token.generation,key,view.lifecycleGeneration)
        return true
    }

    /** Serializes physical commit against navigation/install/cleanup on other continuations. */
    @Synchronized
    fun withStageSwitch(
        token:StageSwitchToken, programId:UUID, targetId:UUID, epoch:UUID, revision:Long,
        effect:(Hall)->Boolean,
    ):Boolean {
        if (!isStageSwitchCurrent(token,programId,targetId,epoch,revision)) return false
        val hall=halls[token.hallId] ?: return false
        return effect(hall.copy(transportContext=projectionContext(token)))
    }

    @Synchronized
    fun isStageSwitchCurrent(
        token:StageSwitchToken,programId:UUID,targetStageId:UUID,
        programEpoch:UUID,hierarchyRevision:Long,
    ):Boolean {
        val state=navigation[token.hallId] ?: return false
        if(token.selection && selections[token.hallId]?.let { selectionCurrent(it.attempt) }!=true) return false
        if (halls[token.hallId]?.transportContext!=token.transportContext ||
            hallGenerations[token.hallId]!=token.hallGeneration) return false
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
        if (!hall.permitsProjection(target)) return null
        val desired=selections[token.hallId]?.takeIf { token.selection }?.desired
        val updated=hall.copy(stage=target,programDirty=programDirty,
            transportContext=projectionContext(token),selectionRevision=desired?.revision ?: hall.selectionRevision,
            selectedStageId=desired?.stageId ?: hall.selectedStageId)
        invalidateProgramStatus(hall.id)
        halls[token.hallId]=updated
        pendingProjections.remove(token.hallId)
        navigation[token.hallId]=navigation.getValue(token.hallId).copy(
            activeStageId=targetId,switchInProgress=false,pendingStageId=null,
        )
        if (token.sourceStageId!=targetId) graphProjections.remove(hall.projectionKey(token.sourceStageId))
        if(token.selection) {
            selections.computeIfPresent(token.hallId) { _,session->session.copy(phase=HallSelectionPhase.DISPLAYED) }
            renewHall(token.hallId)
        }
        return updated
    }

    @Synchronized
    fun failStageSwitch(token:StageSwitchToken) {
        val state=navigation[token.hallId] ?: return
        if (state.switchGeneration==token.generation &&
            halls[token.hallId]?.transportContext==token.transportContext &&
            hallGenerations[token.hallId]==token.hallGeneration) {
            pendingProjections[token.hallId]?.takeIf { it.owner==token.generation }?.let {
                graphProjections.removeIfLifecycleCurrent(it.key,it.lifecycle)
                pendingProjections.remove(token.hallId)
            }
            navigation[token.hallId]=state.copy(switchInProgress=false,pendingStageId=null)
            if(token.selection) selections.computeIfPresent(token.hallId) { _,session->
                if(session.attempt.token==token) session.copy(phase=HallSelectionPhase.PROJECTION_FAILED) else session }
        }
    }

    fun navigationState(hallId:String):HallNavigationState?=navigation[hallId]

    fun isActiveStage(key: ProjectionKey, epoch:UUID):Boolean {
        if(!editingAllowed(key.hallId.toString())) return false
        val hall=halls[key.hallId.toString()] ?: return false
        val state=navigation[hall.id] ?: return false
        return hall.transportContext==key.transportContext && hall.stage?.id==key.stageId.toString() &&
            hall.stage.workspaceEpoch==epoch && state.activeStageId==key.stageId && !state.switchInProgress
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
            stageJson["working_revision"].asLong != fence.workingRevision) {
            Diagnostics.event("SAVE_RESPONSE_APPLY",fence.key,"reason" to "FENCE_OR_RESPONSE_VERSION_MISMATCH",
                "requested_epoch" to fence.workspaceEpoch,"requested_revision" to fence.workingRevision,
                "hall_generation" to hallGenerations[fence.hallId],"navigation_generation" to navigation[fence.hallId]?.switchGeneration)
            return null
        }
        return updateDomainStage(stageJson, fence, StageResponseKind.SAVE).also {
            Diagnostics.event("SAVE_RESPONSE_APPLY",fence.key,"reason" to if(it==null) "STAGE_UPDATE_REJECTED" else "ACCEPTED",
                "epoch" to fence.workspaceEpoch,"revision" to fence.workingRevision,"program_dirty" to it?.programDirty)
        }
    }

    private fun updateStage(stageJson: JsonObject, fence: StageUpdateFence, kind: StageResponseKind): Hall? {
        if (!isStageFenceCurrent(fence)) return null
        val stage = parseStage(stageJson)
        val hallId = stageJson["hall_id"].asString
        if (hallId != fence.hallId || stage.id != fence.stageId) return null
        val hall = halls[hallId] ?: return null
        if (!hall.permitsProjection(stage)) return null
        val currentStage = hall.stage ?: return null
        if (currentStage.id != stage.id) return null
        val projection = graphProjections.view(fence.key)

        if (stage.workspaceEpoch != currentStage.workspaceEpoch) {
            val fenceStillCurrent = currentStage.workspaceEpoch == fence.workspaceEpoch &&
                currentStage.workingRevision == fence.workingRevision
            if (!fenceStillCurrent) return null

            val updated = hall.copy(stage = stage, programDirty = stageJson["program_dirty"]?.asBoolean ?: true)
            invalidateProgramStatus(hall.id)
            halls[updated.id] = updated
            graphProjections.authoritativeStageReload(
                fence.key, stage.workspaceEpoch, stage.workingRevision,
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
                fence.key, stage.workspaceEpoch, stage.workingRevision,
            )
        } else {
            graphProjections.legacyStageUpdated(fence.key, stage.workspaceEpoch, stage.workingRevision)
        }
        return updated
    }

    @Synchronized
    fun remove(id: String): Hall? = halls.remove(id)?.also { hall ->
        selections.remove(id)
        pendingProjections.remove(id)
        navigation.remove(id)
        graphProjections.removeHall(hall.transportContext.hallId)
        renewHall(id)
    }
    fun graph(hallId: String): StageGraphView? = halls[hallId]?.takeIf { it.stage!=null }
        ?.let { graphProjections.view(it.projectionKey()) }
    fun hall(hallId:String):Hall?=halls[hallId]
    fun controlAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        if (hall.worldUuid == worldUuid) hall.controls.firstOrNull { it.pos == pos } else null
    }
    fun stageAt(worldUuid: String, pos: BlockPos) = halls.values.firstNotNullOfOrNull { hall ->
        hall.stage?.takeIf { hall.worldUuid == worldUuid && it.contains(pos) }
    }
    fun hallAt(worldUuid: String, pos: BlockPos): Hall? = halls.values.singleOrNull { hall ->
        hall.worldUuid == worldUuid && hall.stage?.contains(pos) == true
    }

    fun permitsWireProjection(context: me.buciqua.blockommander.runtime.wire.WireStageContext): Boolean {
        if (!isActiveStage(context.key,context.workspaceEpoch) ||
            !graphProjections.isLifecycleCurrent(context.key,context.lifecycleGeneration)) return false
        val hall=halls[context.key.hallId.toString()] ?: return false
        val stage=hall.stage ?: return false
        return hall.permitsProjection(stage) &&
            context.origin==me.buciqua.blockommander.runtime.graph.GraphPosition(stage.origin.x,stage.origin.y,stage.origin.z) &&
            context.size==me.buciqua.blockommander.runtime.graph.GraphPosition(stage.sizeX,stage.sizeY,stage.sizeZ)
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
