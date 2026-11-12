package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.Hall
import me.buciqua.blockommander.runtime.graph.GraphStateSnapshot
import me.buciqua.blockommander.runtime.program.PreparedStageSwitch
import me.buciqua.blockommander.runtime.program.ProgramStageResponse
import me.buciqua.blockommander.runtime.program.StageProjectionAssembler

/** Read-side integration with the existing assembler, with no registry/store/world writes. */
internal object BlockEditorReconciliation {
    fun assemble(hall: Hall, capture: EditorCapture, editor: EditorGetResult,
        stage: ProgramStageResponse, graph: GraphStateSnapshot,
    ): PreparedStageSwitch {
        val context = capture.context
        require(hall.transportContext == context.transport && hall.id == context.transport.hallId.toString())
        require(stage.programId == context.transport.programId && graph.stageId == context.stageId)
        require(graph.workspaceEpoch == editor.versionContext.stageWorkspaceEpoch &&
            graph.workingRevision >= editor.versionContext.workingRevision) { "EDITOR_RECONCILIATION_VERSION_MISMATCH" }
        return StageProjectionAssembler.assemble(hall, stage, graph, capture.lifecycle.projection, physicalSelection = true)
    }

    /** Same frame/version rules at preparation and immediately before projection consumption. */
    fun validatePrepared(hall: Hall, capture: EditorCapture, editor: EditorGetResult, frame: PreparedStageSwitch) {
        val c = capture.context
        val target = frame.target
        val graph = frame.graph
        require(hall.id == c.transport.hallId.toString() && hall.transportContext == c.transport &&
            hall.worldUuid == c.worldUuid.toString() && frame.program.programId == c.transport.programId)
        require(editor.transportContext.context == c.transport && editor.transportContext.stageId == c.stageId &&
            editor.target.programId == c.transport.programId && editor.target.stageId == c.stageId &&
            editor.target.instanceId == c.instanceId && editor.target.blockType == c.blockType)
        require(target.id == c.stageId.toString() && graph.stageId == c.stageId &&
            target.workspaceEpoch == graph.workspaceEpoch && graph.workspaceEpoch == capture.lifecycle.epoch &&
            graph.workspaceEpoch == editor.versionContext.stageWorkspaceEpoch &&
            target.workingRevision == graph.workingRevision && graph.workingRevision >= editor.versionContext.workingRevision &&
            target.dirty == graph.dirty && (!target.dirty || frame.programDirty) && frame.projectionLifecycle == capture.lifecycle.projection)
        require(graph.hallId == c.transport.hallId && graph.worldUuid == c.worldUuid &&
            graph.origin.x == target.origin.x && graph.origin.y == target.origin.y && graph.origin.z == target.origin.z &&
            graph.size == me.buciqua.blockommander.runtime.graph.GraphPosition(target.sizeX, target.sizeY, target.sizeZ))
        val block = target.blocks.single { it.instanceId == c.instanceId.toString() }
        require(block.type == c.blockType.name)
        if (target.workingRevision == editor.versionContext.workingRevision) {
            require(block.label == editor.values.label && block.description == editor.values.description.orEmpty() &&
                graph.savedVersion == editor.versionContext.stageSavedVersion && target.dirty == editor.versionContext.stageDirty)
        }
    }
}
