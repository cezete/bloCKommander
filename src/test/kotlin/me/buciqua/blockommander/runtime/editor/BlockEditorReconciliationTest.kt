package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class BlockEditorReconciliationTest {
    private val f = BlockEditorFixtures
    private val context = f.context()
    private val capture = EditorCapture(context, EditorLifecycle(1, 2, 3, 4, f.epoch))
    private val editor = BlockEditorResponseParser.get(f.fixture("start-get"))
    private val origin = GraphPosition(1, 1, 1)
    private val size = GraphPosition(7, 5, 7)
    private val hall = Hall(f.hall.toString(), "Hall", f.player.toString(), "Owner", context.worldUuid.toString(), "STONE",
        BlockPos.ZERO, BlockPos(10, 10, 10), BlockPos.ZERO, "EAST", emptyList(), null, context.transport)
    private val stage = ProgramStageResponse(f.program, 5, UUID(0, 701), 2, ProgramStageProjection(
        ProgramStageSummary(f.stage, f.program, "ROOT", "Root", true, 3,
            ProgramStageWorkspace(f.epoch, 12, true), ProgramDomainLayout(origin, size), emptyList(), emptyList()),
        1, "ck-control-v3", listOf(ProgramStageBlock(f.start, "START", GraphPosition(1, 0, 1), "EAST", "Authoritative", null)),
        PhysicalStageMetadata(f.hall, context.worldUuid, origin, size)), true)
    private val graph = GraphStateSnapshot(f.stage, f.hall, context.worldUuid, origin, size, f.epoch, 12, 3, true, 1,
        "ck-control-v3", true, emptyList(), GraphStatuses("DRAFT", "NONE", "DIRTY", "PRESENT", "NOT_CHECKED"),
        emptyList(), emptyList(), emptyList(), emptyList(), GraphValidation(null, emptyList()))
    private fun prepare(s: ProgramStageResponse = stage, g: GraphStateSnapshot = graph) =
        BlockEditorReconciliation.assemble(hall, capture, editor, s, g)

    @Test fun `authoritative physical Stage and Graph use existing assembler`() {
        val frame = prepare()
        assertEquals(12L, frame.target.workingRevision); assertEquals("Authoritative", frame.target.blocks.single().label)
        assertEquals(graph, frame.graph); assertEquals(3L, frame.projectionLifecycle)
        assertNull(hall.stage) // input remains untouched
    }
    @Test fun `Graph older than accepted editor is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(workingRevision = 11)) }
    }
    @Test fun `Graph epoch mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(workspaceEpoch = UUID.randomUUID())) }
    }
    @Test fun `Stage and Graph revision disagreement is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(workingRevision = 13)) }
    }
    @Test fun `newer coherent authoritative pair is allowed`() {
        val newer = stage.copy(stage = stage.stage.copy(summary = stage.stage.summary.copy(workspace = ProgramStageWorkspace(f.epoch, 14, true))))
        assertEquals(14L, prepare(newer, graph.copy(workingRevision = 14)).target.workingRevision)
    }
    @Test fun `other Stage is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(stageId = UUID.randomUUID())) }
    }
    @Test fun `other Program is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(s = stage.copy(programId = UUID.randomUUID())) }
    }
    @Test fun `physical Hall mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(hallId = UUID.randomUUID())) }
    }
    @Test fun `physical world mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { prepare(g = graph.copy(worldUuid = UUID.randomUUID())) }
    }
}
