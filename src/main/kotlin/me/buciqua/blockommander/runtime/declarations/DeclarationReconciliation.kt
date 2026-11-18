package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.editor.MetadataProjectionWorld
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import me.buciqua.blockommander.runtime.wire.*

/** Narrow Stage metadata adapter. It never invokes a block/Wire world writer or
 * fabricates a START/STOP editor target. The Block Editor committer is unchanged. */
internal class DeclarationReconciliation(private val registry: HallRegistry, private val wires: WireInteractionCoordinator) {
    companion object {
        fun metadataOnly(source: Stage, target: Stage, before: GraphStateSnapshot, after: GraphStateSnapshot): Boolean =
            source.id == target.id && source.workspaceEpoch == target.workspaceEpoch && source.origin == target.origin &&
                source.sizeX == target.sizeX && source.sizeY == target.sizeY && source.sizeZ == target.sizeZ &&
                source.blocks.size == target.blocks.size && source.blocks.associateBy { it.instanceId } == target.blocks.associateBy { it.instanceId } &&
                source.blocks.map { it.instanceId }.toSet().size == source.blocks.size &&
                before.stageId == after.stageId && before.workspaceEpoch == after.workspaceEpoch && before.origin == after.origin &&
                before.size == after.size && before.nodes == after.nodes && before.wireCells == after.wireCells &&
                before.edges == after.edges && before.routes == after.routes && before.graphSnapshotVersion == after.graphSnapshotVersion &&
                before.portCatalogVersion == after.portCatalogVersion
    }
    fun commit(capture: DeclarationCapture, view: DeclarationPage, hall: Hall, observed: GraphProjectionObservation,
        frame: PreparedStageSwitch, world: MetadataProjectionWorld, sessionCurrent: () -> Boolean): Boolean {
        val c = capture.context; val key = c.transport.projectionKey(c.stage)
        val fence = registry.stageUpdateFenceForHall(hall.id) ?: return false
        val source = hall.stage ?: return false
        val previous = observed.view.snapshot ?: return false
        val target = frame.target; val graph = frame.graph
        if (!sessionCurrent() || registry.hall(hall.id) !== hall || !registry.isStageFenceCurrent(fence) || observed.pending ||
            !registry.graphProjections.isObservationCurrent(observed) || observed.view.key != key ||
            fence.hallGeneration != capture.fence.hallGeneration || fence.projectionGeneration != capture.fence.projectionGeneration ||
            fence.navigationGeneration != capture.fence.navigationGeneration || fence.workspaceEpoch != capture.fence.workspaceEpoch ||
            registry.navigationState(hall.id)?.switchInProgress == true || hall.transportContext != c.transport || hall.worldUuid != c.world.toString() ||
            frame.program.programId != c.transport.programId || frame.program.workspaceEpoch != view.programEpoch || frame.program.hierarchyRevision != view.hierarchy ||
            frame.projectionLifecycle != capture.fence.projectionGeneration || target.id != c.stage.toString() || graph.stageId != c.stage ||
            target.workspaceEpoch != view.version.epoch || graph.workspaceEpoch != view.version.epoch || graph.workspaceEpoch != capture.fence.workspaceEpoch ||
            target.workingRevision != view.version.revision || graph.workingRevision != view.version.revision ||
            graph.savedVersion != view.version.stageSaved || target.dirty != view.version.stageDirty || graph.dirty != view.version.stageDirty ||
            frame.programDirty != view.version.programDirty || target.workingRevision < maxOf(source.workingRevision, observed.view.stageRevision) ||
            graph.hallId != c.transport.hallId || graph.worldUuid != c.world || world.worldUuid() != c.world || !hall.permitsProjection(target) ||
            !metadataOnly(source, target, previous, graph)) return false
        val nav = registry.navigationState(hall.id)
        if (nav?.programWorkspaceEpoch != null && nav.programWorkspaceEpoch != view.programEpoch ||
            nav?.hierarchyRevision != null && nav.hierarchyRevision != view.hierarchy) return false
        val stage = WireStageContext(c.transport, c.stage, graph.origin, graph.size, graph.workspaceEpoch, capture.fence.projectionGeneration)
        var wrote = false
        try {
            if (!world.blocksMatch(source) || !world.canRenderSaveState(hall)) return false
            val projector = WireWorldProjector()
            val plan = projector.prepare(stage, graph.workingRevision, graph, world)
            if (!projector.isCurrent(plan, stage, world) || !sessionCurrent() || registry.hall(hall.id) !== hall ||
                !registry.isStageFenceCurrent(fence) || !registry.graphProjections.isObservationCurrent(observed)) return false
            wrote = true
            val updated = registry.commitMetadataProjection(hall, fence, target, frame.programDirty, observed, graph) ?: return false
            check(world.blocksMatch(target) && wires.acceptVerifiedProjection(stage, graph, world))
            check(world.renderSaveState(updated))
            check(registry.hall(hall.id) === updated && registry.graphProjections.view(key)?.let {
                it.status == GraphSyncStatus.SYNCED && it.snapshot == graph
            } == true && wires.worldStatus(key) == WireWorldStatus.SYNCED)
            return true
        } catch (_: Exception) {
            if (wrote && registry.graphProjections.isLifecycleCurrent(key, capture.fence.projectionGeneration)) {
                val token = registry.graphProjections.begin(key, GraphRequestKind.RECONCILIATION)
                registry.graphProjections.fail(token, "DECLARATION_PROJECTION_FAILED", true)
                wires.markProjectionNeedsRepair(stage)
            }
            return false
        }
    }
}
