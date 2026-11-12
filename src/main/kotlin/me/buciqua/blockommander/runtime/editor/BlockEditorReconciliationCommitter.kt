package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.PreparedStageSwitch
import me.buciqua.blockommander.runtime.wire.*
import java.util.UUID

internal enum class MetadataCommitStatus { COMMITTED, STALE_SESSION, STALE_PROJECTION, CONFLICT, PROJECTION_FAILED, UNSUPPORTED }
internal data class MetadataCommitResult(val status: MetadataCommitStatus, val reason: String, val reconciliationRequired: Boolean = status != MetadataCommitStatus.COMMITTED)
internal data class EditorMetadataFrame(val prepared: PreparedStageSwitch, val hall: Hall, val observed: GraphProjectionObservation)

/** Metadata is registry state: START/STOP are ordinary blocks with no label/description block entity. */
internal interface MetadataProjectionWorld : WireWorldAccess {
    fun worldUuid(): UUID
    fun blocksMatch(stage: Stage): Boolean
    fun canRenderSaveState(hall: Hall): Boolean
    fun renderSaveState(hall: Hall): Boolean
}

/** Server-thread-only, metadata-only projection; never a replacement Stage-switch/world engine. */
internal class BlockEditorReconciliationCommitter(
    private val registry: HallRegistry,
    private val wires: WireInteractionCoordinator,
    private val checkThread: () -> Unit,
    private val projector: WireWorldProjector = WireWorldProjector(),
    private val applyProjection: (Hall, StageUpdateFence, EditorMetadataFrame) -> Hall? = { hall, fence, frame ->
        registry.commitMetadataProjection(hall, fence, frame.prepared.target, frame.prepared.programDirty,
            frame.observed, frame.prepared.graph)
    },
) {
    fun commit(id: EditorSessionId, capture: EditorCapture, editor: EditorGetResult, frame: EditorMetadataFrame,
        world: MetadataProjectionWorld, sessionCurrent: () -> Boolean,
    ): MetadataCommitResult {
        checkThread()
        val c = capture.context
        val key = c.transport.projectionKey(c.stageId)
        fun result(status: MetadataCommitStatus, reason: String): MetadataCommitResult {
            Diagnostics.event("EDITOR_METADATA_COMMIT", key, "session_generation" to id.generation,
                "connection_generation" to id.connection, "status" to status, "reason" to reason,
                "epoch" to frame.prepared.target.workspaceEpoch, "revision" to frame.prepared.target.workingRevision)
            return MetadataCommitResult(status, reason)
        }
        if (id.player != c.playerUuid || id.connection != capture.lifecycle.connection || !sessionCurrent())
            return result(MetadataCommitStatus.STALE_SESSION, "SESSION_CHANGED")
        val hall = registry.hall(c.transport.hallId.toString())
            ?: return result(MetadataCommitStatus.STALE_PROJECTION, "HALL_MISSING")
        val fence = registry.stageUpdateFenceForHall(hall.id)
            ?: return result(MetadataCommitStatus.STALE_PROJECTION, "PROJECTION_MISSING")
        if (hall !== frame.hall || fence.hallGeneration != capture.lifecycle.hall ||
            fence.projectionGeneration != capture.lifecycle.projection || fence.navigationGeneration != capture.lifecycle.navigation ||
            fence.workspaceEpoch != capture.lifecycle.epoch || registry.navigationState(hall.id)?.switchInProgress == true ||
            !registry.isStageFenceCurrent(fence) || !registry.graphProjections.isObservationCurrent(frame.observed) || frame.observed.pending)
            return result(MetadataCommitStatus.STALE_PROJECTION, "PROJECTION_CHANGED_OR_REQUEST_PENDING")
        val prepared = frame.prepared
        val source = hall.stage ?: return result(MetadataCommitStatus.UNSUPPORTED, "STAGE_MISSING")
        val previous = frame.observed.view.snapshot ?: return result(MetadataCommitStatus.UNSUPPORTED, "GRAPH_BASELINE_MISSING")
        try { BlockEditorReconciliation.validatePrepared(hall, capture, editor, prepared) }
        catch (_: Exception) { return result(MetadataCommitStatus.CONFLICT, "INVALID_AUTHORITATIVE_FRAME") }
        val target = prepared.target
        if (target.workingRevision < maxOf(source.workingRevision, frame.observed.view.stageRevision) ||
            previous.workspaceEpoch != target.workspaceEpoch || frame.observed.view.stageEpoch != target.workspaceEpoch ||
            frame.observed.view.key != key)
            return result(MetadataCommitStatus.STALE_PROJECTION, "VERSION_REGRESSION")
        if (prepared.graph.workingRevision == previous.workingRevision && prepared.graph != previous)
            return result(MetadataCommitStatus.CONFLICT, "SAME_REVISION_CONFLICT")
        val nav = registry.navigationState(hall.id)
        if ((nav?.programWorkspaceEpoch != null && nav.programWorkspaceEpoch != prepared.program.workspaceEpoch) ||
            (nav?.hierarchyRevision != null && nav.hierarchyRevision != prepared.program.hierarchyRevision))
            return result(MetadataCommitStatus.CONFLICT, "PROGRAM_HIERARCHY_CHANGED")
        if (!metadataOnly(source, target) || !sameTopology(previous, prepared.graph) || previous.savedVersion != prepared.graph.savedVersion)
            return result(MetadataCommitStatus.UNSUPPORTED, "NOT_METADATA_ONLY")
        if (runCatching { world.worldUuid() }.getOrNull() != c.worldUuid || source.origin != target.origin || source.sizeX != target.sizeX ||
            source.sizeY != target.sizeY || source.sizeZ != target.sizeZ || !hall.permitsProjection(target))
            return result(MetadataCommitStatus.CONFLICT, "PHYSICAL_TARGET_MISMATCH")
        val stage = WireStageContext(c.transport, c.stageId, prepared.graph.origin, prepared.graph.size,
            target.workspaceEpoch, capture.lifecycle.projection)
        var wrote = false
        var phase = "WORLD_PREFLIGHT"
        try {
            if (!world.blocksMatch(source) || !world.canRenderSaveState(hall))
                return result(MetadataCommitStatus.PROJECTION_FAILED, "WORLD_PREFLIGHT_FAILED")
            phase = "WIRE_PREFLIGHT"
            val plan = projector.prepare(stage, target.workingRevision, prepared.graph, world)
            if (!projector.isCurrent(plan, stage, world))
                return result(MetadataCommitStatus.PROJECTION_FAILED, "WIRE_PREFLIGHT_FAILED")
            // No callbacks/async boundary between the final fence and the main-thread writes.
            if (!sessionCurrent()) return result(MetadataCommitStatus.STALE_SESSION, "SESSION_CHANGED_BEFORE_WRITE")
            if (registry.hall(hall.id) !== hall || !registry.isStageFenceCurrent(fence) ||
                !registry.graphProjections.isObservationCurrent(frame.observed))
                return result(MetadataCommitStatus.STALE_PROJECTION, "PROJECTION_CHANGED_DURING_PREFLIGHT")
            phase = "GRAPH_STAGE_UPDATE"
            wrote = true // a failed adapter may already have performed part of its operation
            val updated = applyProjection(hall, fence, frame)
                ?: return result(MetadataCommitStatus.STALE_PROJECTION, "PROJECTION_CHANGED_BEFORE_STORE_WRITE")
            phase = "WORLD_VERIFICATION"
            check(world.blocksMatch(target)) { "WORLD_VERIFICATION_FAILED" }
            phase = "WIRE_VERIFICATION"
            check(wires.acceptVerifiedProjection(stage, prepared.graph, world)) { "WIRE_VERIFICATION_FAILED" }
            phase = "SAVE_BOARD"
            check(world.renderSaveState(updated)) { "SAVE_BOARD_FAILED" }
            phase = "POST_COMMIT_VERIFICATION"
            check(registry.hall(hall.id) === updated && registry.graphProjections.view(key)?.let {
                it.status == GraphSyncStatus.SYNCED && it.snapshot == prepared.graph
            } == true && wires.worldStatus(key) == WireWorldStatus.SYNCED) { "POST_COMMIT_CHANGED" }
            return result(MetadataCommitStatus.COMMITTED, "METADATA_GRAPH_WIRE_BOARD_CURRENT")
        } catch (_: Exception) {
            if (wrote && registry.graphProjections.isLifecycleCurrent(key, capture.lifecycle.projection)) {
                // Do not restore older snapshots or the Python domain. Block subsequent Wire writes until reconciliation.
                val token = registry.graphProjections.begin(key, GraphRequestKind.RECONCILIATION)
                registry.graphProjections.fail(token, "EDITOR_PROJECTION_FAILED", true)
                wires.markProjectionNeedsRepair(stage)
            }
            return result(MetadataCommitStatus.PROJECTION_FAILED, if (wrote) "${phase}_FAILED_RECONCILE_REQUIRED" else "${phase}_EXCEPTION")
        }
    }

    private fun metadataOnly(before: Stage, after: Stage): Boolean {
        if (before.id != after.id || before.workspaceEpoch != after.workspaceEpoch || before.blocks.size != after.blocks.size) return false
        val old = before.blocks.associateBy { it.instanceId }
        return old.size == before.blocks.size && after.blocks.map { it.instanceId }.toSet() == old.keys && after.blocks.all { block ->
            val original = old.getValue(block.instanceId)
            if (block.type in setOf("START", "STOP")) block.copy(label = original.label, description = original.description) == original
            else block == original
        }
    }
    private fun sameTopology(a: GraphStateSnapshot, b: GraphStateSnapshot) = a.stageId == b.stageId &&
        a.origin == b.origin && a.size == b.size && a.nodes == b.nodes && a.wireCells == b.wireCells &&
        a.edges == b.edges && a.routes == b.routes && a.graphSnapshotVersion == b.graphSnapshotVersion && a.portCatalogVersion == b.portCatalogVersion
}
