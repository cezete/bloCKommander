package me.buciqua.blockommander.runtime.declarations

import com.google.gson.JsonParser
import me.buciqua.blockommander.CkWireGeometry
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.editor.MetadataProjectionWorld
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import me.buciqua.blockommander.runtime.wire.*
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class DeclarationReconciliationTest {
    private val f = DeclarationFixtures
    internal inner class Rig(basePage: DeclarationPage = f.page("editor-empty")) {
        val c = f.context
        val registry = HallRegistry()
        val key = c.transport.projectionKey(c.stage)
        val relative = GraphPosition(4, 0, 1)
        val wirePosition = GraphPosition(5, 1, 2)
        val edge = GraphEdge(UUID(0, 10), f.f.start, "out", f.f.stop, "in", "CONTROL")
        val route = GraphRoute(UUID(0, 11), edge.edgeId, GraphPortRef(f.f.start, "out"), GraphPortRef(f.f.stop, "in"),
            listOf(GraphRouteStep(relative, "SINGLE")), "VALID")
        val before = GraphStateSnapshot(c.stage, c.transport.hallId, c.world, GraphPosition(1, 1, 1), GraphPosition(7, 5, 7),
            f.f.epoch, basePage.version.revision, basePage.version.stageSaved, false, 1, "ck-control-v3", true, emptyList(),
            GraphStatuses("DRAFT", "VALID", "SAVED", "PRESENT", "NOT_CHECKED"),
            listOf(GraphNode(f.f.start, "START", GraphPosition(1, 0, 1), "EAST"), GraphNode(f.f.stop, "STOP", GraphPosition(2, 0, 1), "EAST")),
            listOf(GraphWireCell(relative, listOf(GraphWireChannel("SINGLE", emptyList())))), listOf(edge), listOf(route), GraphValidation(null, emptyList()))
        val wires = WireInteractionCoordinator(registry.graphProjections, object : GraphGateway {
            override fun reconcile(context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<ReconciliationOutcome> = error("No Graph request")
            override fun send(context: WorkingCopyTransportContext, command: GraphCommand): CompletableFuture<CommandTransportOutcome> = error("No mutation")
        }, WireWorldProjector(), Executor { it.run() }, registry::permitsWireProjection)
        var wireWrites = 0; var boardCalls = 0; var boardDirty: Boolean? = null
        var boardOkay = true; var blocksOkay = true; var current = true
        var preflight: () -> Unit = {}
        val world = object : MetadataProjectionWorld {
            override fun worldUuid() = c.world
            override fun blocksMatch(stage: Stage): Boolean { preflight(); return blocksOkay }
            override fun canRenderSaveState(hall: Hall) = true
            override fun renderSaveState(hall: Hall): Boolean { boardCalls++; boardDirty = hall.programDirty; return boardOkay }
            override fun ckWirePositions(stage: WireStageContext) = setOf(wirePosition)
            override fun cell(position: GraphPosition) = if (position == wirePosition) WireWorldCell.CK_WIRE_BOUND_VALID else WireWorldCell.REPLACEABLE
            override fun geometry(position: GraphPosition) = if (position == wirePosition) CkWireGeometry.NONE else null
            override fun setWire(position: GraphPosition, geometry: CkWireGeometry, visualState: WireVisualState) { wireWrites++ }
            override fun removeWire(position: GraphPosition) { wireWrites++ }
            override fun correct(position: GraphPosition) {}
        }
        val capture: DeclarationCapture
        init {
            registry.install(JsonParser.parseString("""{
              "hall_id":"${f.f.hall}","program_id":"${f.f.program}","working_copy_id":"${f.f.workingCopy}","hall_name":"Test",
              "owner_uuid":"${f.f.player}","owner_name":"Owner","world_uuid":"${c.world}","material_name":"STONE",
              "min_x":0,"min_y":0,"min_z":0,"max_x":10,"max_y":10,"max_z":10,
              "door":{"x":0,"y":1,"z":1},"orientation":"NORTH","active_stage_id":"${UUID(0, 999)}",
              "controls":[{"kind":"SAVE_SIGN","x":0,"y":2,"z":2}],
              "stage":{"stage_id":"${f.f.stage}","hall_id":"${f.f.hall}","origin":{"x":1,"y":1,"z":1},"size":{"x":7,"y":5,"z":7},
              "dirty":false,"program_dirty":false,"workspace_epoch":"${f.f.epoch}","working_revision":${basePage.version.revision},"blocks":[
              {"instance_id":"${f.f.start}","type_key":"START","x":2,"y":1,"z":2,"orientation":"EAST","label":"Initial","description":""},
              {"instance_id":"${f.f.stop}","type_key":"STOP","x":3,"y":1,"z":2,"orientation":"EAST","label":"Initial","description":""}]}}
            """).asJsonObject)
            registry.graphProjections.apply(registry.graphProjections.begin(key, GraphRequestKind.RECONCILIATION), before)
            capture = DeclarationCapture(c, 1, registry.stageUpdateFenceForHall(f.f.hall.toString())!!, "minecraft:overworld")
        }
        fun hall() = registry.hall(f.f.hall.toString())!!
        fun frame(page: DeclarationPage = f.page()): PreparedStageSwitch {
            val v = page.version
            return PreparedStageSwitch(hall().stage!!.copy(workingRevision = v.revision, dirty = v.stageDirty),
                before.copy(workingRevision = v.revision, savedVersion = v.stageSaved, dirty = v.stageDirty),
                ProgramSwitchContext(f.f.program, page.programEpoch, page.hierarchy), v.programDirty, capture.fence.projectionGeneration)
        }
        fun commit(frame: PreparedStageSwitch = frame(), page: DeclarationPage = f.page()) = DeclarationReconciliation(registry, wires)
            .commit(capture, page, hall(), registry.graphProjections.observe(key)!!, frame, world) { current }
    }
    @Test fun `physical Hall capture uses displayed Stage instead of persisted selection and entire Hall volume`() {
        val r = Rig(); val hall = captureDeclarationHall(r.registry, f.context.player, f.context.world, BlockPos(0, 1, 1))!!
        assertNotEquals(hall.stage!!.id, hall.selectedStageId.toString())
        assertEquals(f.context.stage.toString(), hall.stage.id)
        assertNull(captureDeclarationHall(r.registry, UUID.randomUUID(), f.context.world, BlockPos(1, 1, 1)))
        assertNull(captureDeclarationHall(r.registry, f.context.player, UUID.randomUUID(), BlockPos(1, 1, 1)))
        assertNull(captureDeclarationHall(r.registry, f.context.player, f.context.world, BlockPos(99, 1, 1)))
        r.registry.beginStageSwitch(hall.id, f.context.player)
        assertNull(captureDeclarationHall(r.registry, f.context.player, f.context.world, BlockPos(1, 1, 1)))
    }
    @Test fun `real registry Graph Wire and dirty sign align without block or Wire writes`() {
        val r = Rig(); val original = r.hall().stage!!.blocks
        assertTrue(r.commit()); assertEquals(0, r.wireWrites); assertEquals(1, r.boardCalls); assertTrue(r.boardDirty!!)
        val graph = r.registry.graphProjections.view(r.key)!!.snapshot!!
        assertEquals(1L, graph.workingRevision); assertEquals(r.before.nodes, graph.nodes)
        assertEquals(r.before.edges, graph.edges); assertEquals(r.before.routes, graph.routes); assertEquals(r.before.wireCells, graph.wireCells)
        assertEquals(original, r.hall().stage!!.blocks); assertEquals(WireWorldStatus.SYNCED, r.wires.worldStatus(r.key))
    }
    @Test fun `no-op frame preserves revision and authoritative clean SAVE state`() {
        val r = Rig(); val page = f.page("editor-empty")
        assertTrue(r.commit(r.frame(page), page)); assertFalse(r.boardDirty!!)
        assertEquals(0L, r.hall().stage!!.workingRevision); assertEquals(0, r.wireWrites)
    }
    @Test fun `block metadata geometry topology or epoch change rejects without world write`() {
        val changes = listOf<(PreparedStageSwitch) -> PreparedStageSwitch>(
            { it.copy(target = it.target.copy(blocks = it.target.blocks.map { b -> b.copy(label = "changed") })) },
            { it.copy(target = it.target.copy(origin = BlockPos(2, 1, 1))) },
            { it.copy(graph = it.graph.copy(edges = emptyList())) }, { it.copy(graph = it.graph.copy(wireCells = emptyList())) },
            { it.copy(graph = it.graph.copy(routes = emptyList())) }, { it.copy(graph = it.graph.copy(workspaceEpoch = UUID.randomUUID())) })
        for (change in changes) { val r = Rig(); assertFalse(r.commit(change(r.frame()))); assertEquals(0, r.boardCalls); assertEquals(0, r.wireWrites) }
    }
    @Test fun `navigation lifecycle and concurrent preflight change fence out old frame`() {
        val r = Rig(); r.preflight = { r.registry.beginStageSwitch(r.hall().id, f.context.player) }
        assertFalse(r.commit()); assertEquals(0, r.boardCalls); assertEquals(0, r.wireWrites)
        val other = Rig(); other.current = false
        assertFalse(other.commit()); assertEquals(0, other.boardCalls)
    }
    @Test fun `SAVE board failure fences projection for repair without rolling back authoritative revision`() {
        val r = Rig(); r.boardOkay = false
        assertFalse(r.commit()); assertEquals(1L, r.hall().stage!!.workingRevision)
        assertEquals(WireWorldStatus.NEEDS_REPAIR, r.wires.worldStatus(r.key)); assertEquals(0, r.wireWrites)
    }
    @Test fun `older clean status fence cannot overwrite declaration dirty result`() {
        val r = Rig(); val fence = r.registry.beginProgramStatus(r.hall().id)!!
        assertTrue(r.commit()); assertFalse(r.registry.isSaveFenceCurrent(fence.stage))
        assertTrue(r.hall().programDirty)
    }
}
