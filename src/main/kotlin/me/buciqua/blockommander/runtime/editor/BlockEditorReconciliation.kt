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
}
