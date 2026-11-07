package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.wire.*
import java.util.UUID

/** Tests replace only the physical backend, not the production planner or application. */
interface StageSwitchWorld<S>:WireWorldAccess {
    fun readState(position:GraphPosition):S
    fun restoreState(position:GraphPosition,state:S)
    fun isManaged(position:GraphPosition):Boolean
    fun validateProgramBlock(block:StageBlock):Boolean
    fun clearManaged(position:GraphPosition)
    fun placeProgramBlock(block:StageBlock)
    fun renderSaveState(hall:Hall,dirty:Boolean)
}

class StageSwitchProjection<S>(
    private val registry:HallRegistry,
    private val world:StageSwitchWorld<S>,
    private val projector:WireWorldProjector=WireWorldProjector(),
):StageSwitchCommitter {
    private data class Plan<S>(
        val before:Map<GraphPosition,S>, val cleared:Set<GraphPosition>, val affected:Set<GraphPosition>,
        val wire:WireProjectionPlan,
    )

    override fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch):Boolean {
        val context=prepared.program
        val targetId=UUID.fromString(prepared.target.id)
        return registry.withStageSwitch(token,context.programId,targetId,context.workspaceEpoch,context.hierarchyRevision) { hall ->
            val plan=try { prepare(hall,prepared) } catch (_:Exception) { null }
                ?: return@withStageSwitch false
            if (!current(hall,prepared) || !registry.isStageSwitchCurrent(
                    token,context.programId,targetId,context.workspaceEpoch,context.hierarchyRevision,
                ) || plan.before.any { (position,state)->world.readState(position)!=state }) return@withStageSwitch false
            try {
                plan.cleared.forEach(world::clearManaged)
                prepared.target.blocks.forEach(world::placeProgramBlock)
                check(projector.applyPrepared(plan.wire,world).complete) { "WIRE_PROJECTION_FAILED" }
                world.renderSaveState(hall,prepared.programDirty)
                check(registry.commitStageSwitch(token,context.programId,context.workspaceEpoch,
                    context.hierarchyRevision,prepared.target,prepared.programDirty)!=null) { "SWITCH_GENERATION_CHANGED" }
                true
            } catch (_:Exception) {
                plan.affected.forEach { position -> world.restoreState(position,plan.before.getValue(position)) }
                runCatching { world.renderSaveState(hall,hall.programDirty) }
                false
            }
        }
    }

    private fun current(hall:Hall,prepared:PreparedStageSwitch):Boolean {
        val view=registry.graphProjections.view(hall.projectionKey(UUID.fromString(prepared.target.id))) ?: return false
        return view.status==GraphSyncStatus.SYNCED && view.snapshot==prepared.graph &&
            view.lifecycleGeneration==prepared.projectionLifecycle
    }

    private fun prepare(hall:Hall,prepared:PreparedStageSwitch):Plan<S>? {
        val source=hall.stage ?: return null
        val target=prepared.target
        if (target.workingRevision!=prepared.graph.workingRevision) return null
        if (!current(hall,prepared) || !hall.permitsProjection(source) || !hall.permitsProjection(target)) return null
        if (target.blocks.map { it.pos }.toSet().size!=target.blocks.size ||
            target.blocks.map { it.instanceId }.toSet().size!=target.blocks.size ||
            target.blocks.any { !world.validateProgramBlock(it) }) return null
        val volume=(positions(source)+positions(target)).toSet()
        val before=volume.associateWith(world::readState)
        val cleared=volume.filter(world::isManaged).toSet()
        val programPositions=target.blocks.map { GraphPosition(it.pos.x,it.pos.y,it.pos.z) }.toSet()
        if (programPositions.any { it !in cleared && world.cell(it)!=WireWorldCell.REPLACEABLE }) return null
        // Model the planned final space: removed managed CK cells are replaceable,
        // but target program blocks must be treated as obstacles by Wire planning.
        val plannedSpace=object:WireWorldAccess by world {
            override fun cell(position:GraphPosition)=when(position) {
                in programPositions -> WireWorldCell.FOREIGN
                in cleared -> WireWorldCell.REPLACEABLE
                else -> world.cell(position)
            }
            override fun ckWirePositions(stage:WireStageContext)=emptySet<GraphPosition>()
        }
        val wireStage=WireStageContext(hall.transportContext,UUID.fromString(target.id),
            GraphPosition(target.origin.x,target.origin.y,target.origin.z),
            GraphPosition(target.sizeX,target.sizeY,target.sizeZ),target.workspaceEpoch,prepared.projectionLifecycle)
        val wire=projector.prepare(wireStage,prepared.graph.workingRevision,prepared.graph,plannedSpace)
        if (wire.issues.isNotEmpty()) return null
        val wirePositions=prepared.graph.wireCells.map { wireStage.origin+it.position }.toSet()
        if (!volume.containsAll(wirePositions)) return null
        return Plan(before,cleared,cleared+programPositions+wirePositions,wire)
    }

    private fun positions(stage:Stage)=sequence {
        for (x in 0 until stage.sizeX) for(y in 0 until stage.sizeY) for(z in 0 until stage.sizeZ)
            yield(GraphPosition(stage.origin.x+x,stage.origin.y+y,stage.origin.z+z))
    }
}
