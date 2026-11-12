package me.buciqua.blockommander.runtime.editor

import com.google.gson.JsonParser
import me.buciqua.blockommander.CkWireGeometry
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Actual HallRegistry, GraphProjectionStore, WireWorldProjector and WireInteractionCoordinator;
 * only Minecraft block/sign storage and HTTP are replaced. No live world is claimed by these tests.
 */
class BlockEditorReconciliationCommitterTest {
    private val f = BlockEditorFixtures
    private inner class Rig(type: EditorBlockType = EditorBlockType.START) {
        val context = f.context(type = type)
        val key = context.transport.projectionKey(context.stageId)
        val registry = HallRegistry()
        val store = registry.graphProjections
        val original = BlockEditorResponseParser.get(f.fixture(if (type == EditorBlockType.START) "start-get" else "stop-get"))
        var editor = original.copy(values = original.values.copy(label = "Accepted", description = "Description"),
            versionContext = original.versionContext.copy(workingRevision = 13, stageDirty = true, programDirty = true))
        val wirePos = GraphPosition(5, 1, 2)
        val relative = GraphPosition(4, 0, 1)
        val edge = GraphEdge(UUID(0, 1), f.start, "out", f.stop, "in", "CONTROL")
        val route = GraphRoute(UUID(0, 2), edge.edgeId, GraphPortRef(f.start, "out"), GraphPortRef(f.stop, "in"),
            listOf(GraphRouteStep(relative, "SINGLE")), "VALID")
        val beforeGraph = GraphStateSnapshot(f.stage, f.hall, context.worldUuid, GraphPosition(1, 1, 1), GraphPosition(7, 5, 7),
            f.epoch, 12, original.versionContext.stageSavedVersion, false, 1, "ck-control-v3", true, emptyList(),
            GraphStatuses("DRAFT", "VALID", "SAVED", "PRESENT", "NOT_CHECKED"),
            listOf(GraphNode(f.start, "START", GraphPosition(1, 0, 1), "EAST"), GraphNode(f.stop, "STOP", GraphPosition(2, 0, 1), "EAST")),
            listOf(GraphWireCell(relative, listOf(GraphWireChannel("SINGLE", emptyList())))), listOf(edge), listOf(route), GraphValidation(null, emptyList()))
        val commands = mutableListOf<GraphCommand>()
        val gateway = object : GraphGateway {
            override fun reconcile(context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<ReconciliationOutcome> =
                error("Already current: no GET needed")
            override fun send(context: WorkingCopyTransportContext, command: GraphCommand): CompletableFuture<CommandTransportOutcome> {
                commands.add(command)
                val snapshot = store.view(key)!!.snapshot!!.let { old ->
                    if (command is WireLayoutCommand) old.copy(workingRevision = old.workingRevision + 1, wireCells = emptyList(), routes = emptyList())
                    else old.copy(workingRevision = old.workingRevision + 1)
                }
                val applied = store.apply(store.begin(key, GraphRequestKind.COMMAND), snapshot)
                return CompletableFuture.completedFuture(CommandTransportOutcome(GraphHttpResult.Success(GraphSuccessResponse(
                    GRAPH_API_VERSION, "ok", command.requestId, snapshot.stageId, snapshot.workspaceEpoch, snapshot.workingRevision,
                    true, emptyList(), emptyList(), null, snapshot)), applied, null))
            }
        }
        val wires = WireInteractionCoordinator(store, gateway, WireWorldProjector(), Executor { it.run() }, registry::permitsWireProjection)
        var blockChecks = 0
        var duringPreflight: () -> Unit = {}
        var blocksOkay = true
        var failAfterBlocks = false
        var failWireAfterBlocks = false
        var wireOkay = true
        var boardAvailable = true
        var boardOkay = true
        var boardDirty: Boolean? = false
        var boardCalls = 0
        var wireWrites = 0
        var sessionOkay = true
        var worldUuid = context.worldUuid
        var cells = mutableSetOf(wirePos)
        val world = object : MetadataProjectionWorld {
            override fun worldUuid() = worldUuid
            override fun blocksMatch(stage: Stage): Boolean {
                blockChecks++
                if (blockChecks == 1) duringPreflight()
                if (failWireAfterBlocks && blockChecks % 2 == 0) wireOkay = false
                return blocksOkay && !(failAfterBlocks && blockChecks % 2 == 0)
            }
            override fun canRenderSaveState(hall: Hall) = boardAvailable
            override fun renderSaveState(hall: Hall): Boolean { boardCalls++; if (boardOkay) boardDirty = hall.programDirty; return boardOkay }
            override fun ckWirePositions(stage: WireStageContext) = cells.toSet()
            override fun cell(position: GraphPosition) = if (!wireOkay) WireWorldCell.FOREIGN
                else if (position in cells) WireWorldCell.CK_WIRE_BOUND_VALID else WireWorldCell.REPLACEABLE
            override fun geometry(position: GraphPosition) = if (position in cells) CkWireGeometry.NONE else null
            override fun setWire(position: GraphPosition, geometry: CkWireGeometry, visualState: WireVisualState) { wireWrites++; cells.add(position) }
            override fun removeWire(position: GraphPosition) { wireWrites++; cells.remove(position) }
            override fun correct(position: GraphPosition) {}
        }
        val capture: EditorCapture
        val id: EditorSessionId
        init {
            registry.install(JsonParser.parseString("""{
              "hall_id":"${f.hall}","program_id":"${f.program}","working_copy_id":"${f.workingCopy}","hall_name":"Hall",
              "owner_uuid":"${f.player}","owner_name":"Owner","world_uuid":"${context.worldUuid}","material_name":"STONE",
              "min_x":0,"min_y":0,"min_z":0,"max_x":10,"max_y":10,"max_z":10,
              "door":{"x":0,"y":1,"z":1},"orientation":"NORTH","controls":[{"kind":"SAVE_SIGN","x":0,"y":2,"z":2}],
              "stage":{"stage_id":"${f.stage}","hall_id":"${f.hall}","origin":{"x":1,"y":1,"z":1},"size":{"x":7,"y":5,"z":7},
              "dirty":false,"program_dirty":false,"workspace_epoch":"${f.epoch}","working_revision":12,"blocks":[
              {"instance_id":"${f.start}","type_key":"START","x":2,"y":1,"z":2,"orientation":"EAST","label":"Initial","description":""},
              {"instance_id":"${f.stop}","type_key":"STOP","x":3,"y":1,"z":2,"orientation":"EAST","label":"Initial","description":""}]}}
            """).asJsonObject)
            store.apply(store.begin(key, GraphRequestKind.RECONCILIATION), beforeGraph)
            val fence = registry.stageUpdateFenceForHall(f.hall.toString())!!
            capture = EditorCapture(context, EditorLifecycle(1, fence.hallGeneration, fence.projectionGeneration, fence.navigationGeneration, f.epoch))
            id = EditorSessionId(f.player, 1, 1, UUID(0, 99))
        }
        fun hall() = registry.hall(f.hall.toString())!!
        fun frame(): EditorMetadataFrame {
            val h = hall(); val version = editor.versionContext
            val target = h.stage!!.copy(workingRevision = version.workingRevision, dirty = version.stageDirty,
                blocks = h.stage.blocks.map { if (it.instanceId == context.instanceId.toString())
                    it.copy(label = editor.values.label, description = editor.values.description.orEmpty()) else it })
            return EditorMetadataFrame(PreparedStageSwitch(target, beforeGraph.copy(workingRevision = target.workingRevision, dirty = target.dirty,
                statuses = beforeGraph.statuses.copy(save = if (target.dirty) "DIRTY" else "SAVED")),
                ProgramSwitchContext(f.program, UUID(0, 701), 0), version.programDirty, capture.lifecycle.projection), h, store.observe(key)!!)
        }
        fun committer(failStore: Boolean = false) = BlockEditorReconciliationCommitter(registry, wires, {}, applyProjection = { h, fence, frame ->
            if (failStore) error("INJECTED_STORE_FAILURE") else registry.commitMetadataProjection(h, fence, frame.prepared.target, frame.prepared.programDirty, frame.observed, frame.prepared.graph)
        })
        fun commit(frame: EditorMetadataFrame = frame(), c: EditorCapture = capture, session: EditorSessionId = id, failStore: Boolean = false) =
            committer(failStore).commit(session, c, editor, frame, world) { sessionOkay }
        fun wireStage() = WireStageContext(context.transport, f.stage, beforeGraph.origin, beforeGraph.size, f.epoch, capture.lifecycle.projection)
    }

    @Test fun `START metadata commit aligns real registry store Wire and dirty board`() {
        val r = Rig(); val beforeLife = r.capture.lifecycle
        assertEquals(MetadataCommitStatus.COMMITTED, r.commit().status)
        val stage = r.hall().stage!!; val graph = r.store.view(r.key)!!
        assertEquals("Accepted", stage.blocks.first().label); assertEquals("Description", stage.blocks.first().description)
        assertEquals(13L, stage.workingRevision); assertEquals(stage.workingRevision, graph.stageRevision)
        assertTrue(stage.dirty); assertTrue(r.boardDirty!!); assertEquals(3L, graph.snapshot!!.savedVersion)
        assertEquals(r.beforeGraph.edges, graph.snapshot.edges); assertEquals(r.beforeGraph.routes, graph.snapshot.routes)
        assertEquals(r.beforeGraph.wireCells, graph.snapshot.wireCells); assertEquals(0, r.wireWrites)
        assertEquals(WireWorldStatus.SYNCED, r.wires.worldStatus(r.key)); assertEquals(beforeLife.projection, graph.lifecycleGeneration)
    }
    @Test fun `STOP description is authoritative metadata`() {
        val r = Rig(EditorBlockType.STOP); assertEquals(MetadataCommitStatus.COMMITTED, r.commit().status)
        assertEquals("Description", r.hall().stage!!.blocks.last().description)
    }
    @Test fun `no-op frame keeps revision and clean board`() {
        val r = Rig(); r.editor = r.original.copy(values = EditorValues("Initial", ""))
        assertEquals(MetadataCommitStatus.COMMITTED, r.commit().status); assertEquals(12L, r.store.view(r.key)!!.stageRevision)
        assertFalse(r.boardDirty!!); assertEquals(0, r.wireWrites)
    }
    @Test fun `repeated consumed frame is rejected without duplicate graph or wire changes`() {
        val r = Rig(); val frame = r.frame(); assertEquals(MetadataCommitStatus.COMMITTED, r.commit(frame).status)
        val before = r.store.observe(r.key); val calls = r.boardCalls
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status)
        assertEquals(before, r.store.observe(r.key)); assertEquals(calls, r.boardCalls); assertEquals(0, r.wireWrites)
    }
    @Test fun `stale session does not mutate any projection`() {
        val r = Rig(); val hall = r.hall(); val graph = r.store.observe(r.key); r.sessionOkay = false
        assertEquals(MetadataCommitStatus.STALE_SESSION, r.commit().status); assertSame(hall, r.hall())
        assertEquals(graph, r.store.observe(r.key)); assertEquals(0, r.boardCalls)
    }
    @Test fun `connection generation mismatch is stale session`() {
        val r = Rig(); assertEquals(MetadataCommitStatus.STALE_SESSION, r.commit(session = r.id.copy(connection = 2)).status)
    }
    @Test fun `navigation generation ABA rejects frame`() {
        val r = Rig(); val frame = r.frame(); val token = r.registry.beginStageSwitch(f.hall.toString(), f.player)!!
        r.registry.failStageSwitch(token); assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status)
        assertEquals(0, r.boardCalls)
    }
    @Test fun `Hall Selection invalidates frame before any write`() {
        val r = Rig(); val frame = r.frame(); assertNotNull(r.registry.beginHallSelection(f.hall.toString(), f.player, r.context.worldUuid))
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status); assertEquals(0, r.wireWrites)
    }
    @Test fun `different Program context is conflict`() {
        val r = Rig(); val context = r.context.copy(transport = r.context.transport.copy(programId = UUID.randomUUID()))
        assertEquals(MetadataCommitStatus.CONFLICT, r.commit(c = r.capture.copy(context = context)).status)
    }
    @Test fun `different WorkingCopy context is conflict`() {
        val r = Rig(); val context = r.context.copy(transport = r.context.transport.copy(workingCopyId = UUID.randomUUID()))
        assertEquals(MetadataCommitStatus.CONFLICT, r.commit(c = r.capture.copy(context = context)).status)
    }
    private fun badFrame(change: (EditorMetadataFrame) -> EditorMetadataFrame) {
        val r = Rig(); val before = r.store.observe(r.key); val result = r.commit(change(r.frame()))
        assertEquals(MetadataCommitStatus.CONFLICT, result.status); assertEquals(before, r.store.observe(r.key)); assertEquals(0, r.boardCalls)
    }
    @Test fun `Stage Graph revision mismatch rejected`() = badFrame { it.copy(prepared = it.prepared.copy(graph = it.prepared.graph.copy(workingRevision = 14))) }
    @Test fun `wrong Stage identity rejected`() = badFrame { it.copy(prepared = it.prepared.copy(target = it.prepared.target.copy(id = UUID.randomUUID().toString()))) }
    @Test fun `wrong instance type rejected`() = badFrame { frame -> frame.copy(prepared = frame.prepared.copy(target = frame.prepared.target.copy(
        blocks = frame.prepared.target.blocks.map { if (it.type == "START") it.copy(type = "STOP") else it }))) }
    @Test fun `wrong epoch rejected`() = badFrame { it.copy(prepared = it.prepared.copy(graph = it.prepared.graph.copy(workspaceEpoch = UUID.randomUUID()))) }
    @Test fun `frame older than accepted editor rejected`() = badFrame { it.copy(prepared = it.prepared.copy(
        target = it.prepared.target.copy(workingRevision = 12), graph = it.prepared.graph.copy(workingRevision = 12))) }
    @Test fun `world identity mismatch rejected before writes`() {
        val r = Rig(); r.worldUuid = UUID.randomUUID(); assertEquals(MetadataCommitStatus.CONFLICT, r.commit().status); assertEquals(0, r.boardCalls)
    }
    @Test fun `physical origin mismatch rejected`() = badFrame { it.copy(prepared = it.prepared.copy(graph = it.prepared.graph.copy(origin = GraphPosition(99, 1, 1)))) }
    @Test fun `newer Graph completion after GET rejects old frame`() {
        val r = Rig(); val frame = r.frame(); r.store.apply(r.store.begin(r.key, GraphRequestKind.COMMAND), r.beforeGraph.copy(workingRevision = 14))
        val observation = r.store.observe(r.key)
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status); assertEquals(observation, r.store.observe(r.key))
    }
    @Test fun `newer Stage update after GET rejects old frame`() {
        val r = Rig(); val frame = r.frame(); val fence = r.registry.stageUpdateFenceForHall(f.hall.toString())!!
        r.registry.updateStage(JsonParser.parseString("""{"stage_id":"${f.stage}","hall_id":"${f.hall}","origin":{"x":1,"y":1,"z":1},
          "size":{"x":7,"y":5,"z":7},"dirty":true,"workspace_epoch":"${f.epoch}","working_revision":14,"blocks":[]}""").asJsonObject, fence)
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status); assertEquals(0, r.boardCalls)
    }
    @Test fun `pending Graph command blocks metadata commit even with unchanged snapshot`() {
        val r = Rig(); r.store.begin(r.key, GraphRequestKind.COMMAND)
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit().status)
    }
    @Test fun `superseded pending command is still detected`() {
        val r = Rig(); val old = r.store.begin(r.key, GraphRequestKind.COMMAND)
        r.store.apply(r.store.begin(r.key, GraphRequestKind.RECONCILIATION), r.beforeGraph)
        assertTrue(r.store.observe(r.key)!!.pending); assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit().status)
        r.store.fail(old, "LATE", true); assertFalse(r.store.observe(r.key)!!.pending)
    }
    @Test fun `identical newer GET still invalidates prepared observation`() {
        val r = Rig(); val frame = r.frame(); r.store.apply(r.store.begin(r.key, GraphRequestKind.RECONCILIATION), r.beforeGraph)
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(frame).status)
    }
    @Test fun `changed topology is unsupported and never deletes Edge Route or WireCell`() {
        val r = Rig(); val frame = r.frame(); val modified = frame.copy(prepared = frame.prepared.copy(graph = frame.prepared.graph.copy(edges = emptyList())))
        assertEquals(MetadataCommitStatus.UNSUPPORTED, r.commit(modified).status); assertEquals(r.beforeGraph, r.store.view(r.key)!!.snapshot)
    }
    @Test fun `missing physical Wire fails preflight without repair writes`() {
        val r = Rig(); r.cells.clear(); assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit().status)
        assertEquals(12L, r.store.view(r.key)!!.stageRevision); assertEquals(0, r.wireWrites); assertEquals(0, r.boardCalls)
    }
    @Test fun `missing board is detected before registry commit`() {
        val r = Rig(); r.boardAvailable = false; val old = r.hall(); assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit().status)
        assertSame(old, r.hall()); assertEquals(0, r.boardCalls)
    }
    @Test fun `physical block mismatch fails preflight`() {
        val r = Rig(); r.blocksOkay = false; assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit().status); assertEquals(12L, r.hall().stage!!.workingRevision)
    }
    @Test fun `store failure is never COMMITTED`() {
        val r = Rig(); assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit(failStore = true).status)
        assertEquals(GraphSyncStatus.STALE, r.store.view(r.key)!!.status); assertEquals(0, r.boardCalls)
    }
    @Test fun `world verification failure after update keeps accepted metadata but requires recovery`() {
        val r = Rig(); r.failAfterBlocks = true; val result = r.commit()
        assertEquals(MetadataCommitStatus.PROJECTION_FAILED, result.status); assertTrue(result.reconciliationRequired)
        assertEquals(13L, r.hall().stage!!.workingRevision); assertEquals(GraphSyncStatus.STALE, r.store.view(r.key)!!.status)
        assertEquals(WireWorldStatus.NEEDS_REPAIR, r.wires.worldStatus(r.key)); assertEquals(0, r.boardCalls)
    }
    @Test fun `Wire verification failure cannot mark world synchronized`() {
        val r = Rig(); r.failWireAfterBlocks = true; assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit().status)
        assertEquals(WireWorldStatus.NEEDS_REPAIR, r.wires.worldStatus(r.key)); assertEquals(0, r.wireWrites)
    }
    @Test fun `board failure keeps dirty Stage and blocks misleading success`() {
        val r = Rig(); r.boardOkay = false; assertEquals(MetadataCommitStatus.PROJECTION_FAILED, r.commit().status)
        assertTrue(r.hall().stage!!.dirty); assertTrue(r.hall().programDirty); assertEquals(GraphSyncStatus.STALE, r.store.view(r.key)!!.status)
    }
    @Test fun `fresh authoritative frame recovers partial board failure without APPLY or rollback`() {
        val r = Rig(); r.boardOkay = false; val old = r.frame(); r.commit(old); r.boardOkay = true
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, r.commit(old).status)
        assertEquals(MetadataCommitStatus.COMMITTED, r.commit(r.frame()).status)
        assertTrue(r.boardDirty!!); assertEquals(0, r.commands.size); assertEquals(0, r.wireWrites)
    }
    @Test fun `next Wire break uses new epoch revision and existing remove path`() {
        val r = Rig(); r.commit(); val result = r.wires.remove(r.wireStage(), ServerPlayerIdentity.trustedForTest(f.player), r.wirePos, r.world).join()
        assertEquals(WireActionStatus.SUCCESS, result.status); assertEquals(13L, r.commands.single().expectedWorkingRevision)
        assertEquals(f.epoch, r.commands.single().workspaceEpoch); assertFalse(r.wirePos in r.cells)
    }
    @Test fun `next bind uses new revision without metadata creating edges`() {
        val r = Rig(); r.commit(); assertTrue(r.commands.isEmpty()); val result = r.wires.bind(r.wireStage(), ServerPlayerIdentity.trustedForTest(f.player), r.world).join()
        assertEquals(WireActionStatus.SUCCESS, result.status); assertInstanceOf(BindEdgesCommand::class.java, r.commands.single())
        assertEquals(13L, r.commands.single().expectedWorkingRevision); assertEquals(listOf(r.edge), r.store.view(r.key)!!.snapshot!!.edges)
    }
    @Test fun `next placement reroute intent uses new revision`() {
        val r = Rig(); r.commit(); val result = r.wires.place(r.wireStage(), ServerPlayerIdentity.trustedForTest(f.player), r.wirePos, r.world).join()
        assertEquals(WireActionStatus.SUCCESS, result.status); assertInstanceOf(WirePlaceIntentCommand::class.java, r.commands.single())
        assertEquals(13L, r.commands.single().expectedWorkingRevision)
    }
    @Test fun `wrong server thread fails before any access`() {
        val r = Rig(); val committer = BlockEditorReconciliationCommitter(r.registry, r.wires, { error("NOT_SERVER_THREAD") })
        assertThrows(IllegalStateException::class.java) { committer.commit(r.id, r.capture, r.editor, r.frame(), r.world) { true } }
        assertEquals(0, r.boardCalls)
    }

    private inner class SessionRig {
        val r = Rig()
        var getCalls = 0
        var applyCalls = 0
        var unknown = false
        var failRead = false
        val prepared = mutableListOf<Pair<EditorMetadataFrame, CompletableFuture<EditorMetadataFrame>>>()
        val events = mutableListOf<EditorSessionSnapshot>()
        val sessions: BlockEditorSessionCoordinator<EditorMetadataFrame>
        init {
            sessions = BlockEditorSessionCoordinator(
                recapture = { _, opened, request -> if (!r.sessionOkay) null else r.capture.copy(context = r.context.copy(requestId = request,
                    applyBasis = opened?.let { EditorApplyBasis(it.schemaRef, it.versionContext.stageWorkspaceEpoch, it.versionContext.workingRevision) })) },
                get = { c -> getCalls++; CompletableFuture.completedFuture(BlockEditorTransportOutcome(c,
                    if (failRead) BlockEditorResult.TransportFailure(EditorTransportFailureKind.UNAVAILABLE, false)
                    else BlockEditorResult.Success(if (getCalls == 1) r.original else r.editor))) },
                apply = { c, _ -> applyCalls++; CompletableFuture.completedFuture(BlockEditorTransportOutcome(c,
                    if (unknown) BlockEditorResult.TransportFailure(EditorTransportFailureKind.TIMEOUT, true)
                    else BlockEditorResult.Success(EditorApplyResult(r.editor, c.requestId, true)))) },
                reconcile = { _, _ -> CompletableFuture<EditorMetadataFrame>().also { prepared.add(r.frame() to it) } },
                mainThread = Executor { it.run() },
                changed = ::deliver,
            )
        }
        private fun deliver(snapshot: EditorSessionSnapshot) {
            if (snapshot.reconciliation == EditorReconciliationState.AVAILABLE_NOT_PROJECTED && snapshot.state != EditorSessionState.CLOSED) {
                sessions.withReconciliationContext(snapshot.id) { capture, editor, frame ->
                    val result = r.committer().commit(snapshot.id, capture, editor, frame, r.world) { sessions.isCurrentSession(snapshot.id) }
                    sessions.projectionCompleted(snapshot.id, result)
                }
            } else events.add(snapshot)
        }
        fun start(): EditorSessionId = sessions.open(r.capture)!!.also {
            assertTrue(sessions.apply(it, EditorChanges(label = EditorStringChange.Value("input"))))
        }
        fun finish(index: Int = prepared.lastIndex) { val (frame, future) = prepared[index]; future.complete(frame) }
    }

    @Test fun `session handoff automatically commits and exposes structured projection result`() {
        val s = SessionRig(); val id = s.start(); s.finish()
        val snapshot = s.sessions.snapshot(id)!!
        assertEquals(EditorSessionState.ACCEPTED, snapshot.state)
        assertEquals(EditorReconciliationState.COMMITTED, snapshot.reconciliation)
        assertEquals(MetadataCommitStatus.COMMITTED, snapshot.projectionResult!!.status)
        assertTrue(s.r.boardDirty!!); assertEquals(1, s.applyCalls)
    }
    @Test fun `UNKNOWN recovery can project but never proves original APPLY success`() {
        val s = SessionRig(); s.unknown = true; val id = s.start(); s.finish()
        val snapshot = s.sessions.snapshot(id)!!
        assertEquals(EditorSessionState.UNKNOWN, snapshot.state); assertNull(snapshot.accepted)
        assertNotNull(snapshot.recovery); assertEquals(EditorReconciliationState.COMMITTED, snapshot.reconciliation)
        assertEquals(1, s.applyCalls); assertEquals(2, s.getCalls); assertFalse(snapshot.canApply)
    }
    @Test fun `session partial failure retries only authoritative GET and commits fresh frame`() {
        val s = SessionRig(); s.r.boardOkay = false; val id = s.start(); s.finish()
        assertEquals(EditorReconciliationState.FAILED, s.sessions.snapshot(id)!!.reconciliation)
        assertEquals(EditorSessionState.ACCEPTED, s.sessions.snapshot(id)!!.state)
        s.r.boardOkay = true; assertTrue(s.sessions.reconcileAgain(id)); assertEquals(2, s.getCalls)
        s.finish(); assertEquals(EditorReconciliationState.COMMITTED, s.sessions.snapshot(id)!!.reconciliation)
        assertEquals(1, s.applyCalls); assertTrue(s.r.boardDirty!!)
    }
    @Test fun `disconnect drops delayed frame without registry or board write`() {
        val s = SessionRig(); val id = s.start(); s.r.sessionOkay = false; s.sessions.invalidate(f.player, "DISCONNECT"); s.finish()
        assertEquals(EditorSessionState.CLOSED, s.sessions.snapshot(id)!!.state)
        assertEquals(12L, s.r.hall().stage!!.workingRevision); assertEquals(0, s.r.boardCalls)
    }
    @Test fun `same UUID reconnect discards old session frame`() {
        val s = SessionRig(); val old = s.start(); s.sessions.invalidate(f.player, "CONNECTION_CHANGED")
        val next = s.sessions.open(s.r.capture.copy(lifecycle = s.r.capture.lifecycle.copy(connection = 2)))!!
        s.finish(); assertNull(s.sessions.snapshot(old)); assertFalse(s.sessions.withReconciliation(old) {})
        assertEquals(EditorSessionState.CLOSED, s.sessions.snapshot(next)!!.state) // fake recapture still represents old connection
        assertEquals(0, s.r.boardCalls)
    }
    @Test fun `session A B A drops late first frame`() {
        val s = SessionRig(); val a = s.start(); s.sessions.open(s.r.capture); val again = s.sessions.open(s.r.capture)!!
        s.finish(); assertNull(s.sessions.snapshot(a)); assertEquals(EditorSessionState.EDITING, s.sessions.snapshot(again)!!.state)
        assertEquals(0, s.r.boardCalls); assertEquals(12L, s.r.store.view(s.r.key)!!.stageRevision)
    }
    @Test fun `metadata commit invalidates older SAVE and program status replies`() {
        val r = Rig(); val saved = r.registry.saveFenceForHall(f.hall.toString())!!
        val status = r.registry.beginProgramStatus(f.hall.toString())!!; r.commit()
        assertFalse(r.registry.isSaveFenceCurrent(saved)); assertEquals(13L, r.registry.saveFenceForHall(f.hall.toString())!!.workingRevision)
        assertTrue(r.hall().programDirty); assertNotNull(status)
    }
    @Test fun `Graph request started during physical preflight is stale with no committer mutation`() {
        val r = Rig(); r.duringPreflight = { r.store.begin(r.key, GraphRequestKind.COMMAND) }
        val old = r.hall(); val result = r.commit()
        assertEquals(MetadataCommitStatus.STALE_PROJECTION, result.status); assertSame(old, r.hall())
        assertEquals(GraphSyncStatus.SYNCED, r.store.view(r.key)!!.status); assertTrue(r.store.observe(r.key)!!.pending)
        assertEquals(12L, r.store.view(r.key)!!.stageRevision); assertEquals(0, r.boardCalls)
    }
    @Test fun `session invalidated during preflight never starts write`() {
        val r = Rig(); r.duringPreflight = { r.sessionOkay = false }; val old = r.hall(); val before = r.store.observe(r.key)
        assertEquals(MetadataCommitStatus.STALE_SESSION, r.commit().status); assertSame(old, r.hall())
        assertEquals(before, r.store.observe(r.key)); assertEquals(0, r.boardCalls)
    }
    @Test fun `same revision but different Graph content is conflict before any write`() {
        val r = Rig(); r.editor = r.original.copy(values = EditorValues("Initial", ""))
        val frame = r.frame(); val bad = frame.copy(prepared = frame.prepared.copy(graph = frame.prepared.graph.copy(statuses = frame.prepared.graph.statuses.copy(graph = "OTHER"))))
        val before = r.store.observe(r.key)
        assertEquals(MetadataCommitStatus.CONFLICT, r.commit(bad).status); assertEquals(before, r.store.observe(r.key))
    }
    @Test fun `dirty Stage cannot produce clean board from inconsistent frame`() {
        val r = Rig(); val frame = r.frame(); assertEquals(MetadataCommitStatus.CONFLICT,
            r.commit(frame.copy(prepared = frame.prepared.copy(programDirty = false))).status)
        assertEquals(0, r.boardCalls)
    }
    @Test fun `failed explicit refresh cannot retain an older COMMITTED indication`() {
        val s = SessionRig(); val id = s.start(); s.finish()
        assertEquals(EditorReconciliationState.COMMITTED, s.sessions.snapshot(id)!!.reconciliation)
        s.failRead = true; assertTrue(s.sessions.reconcileAgain(id)); val snapshot = s.sessions.snapshot(id)!!
        assertEquals(EditorReconciliationState.FAILED, snapshot.reconciliation); assertNull(snapshot.projectionResult)
        assertNotNull(snapshot.recoveryFailure); assertEquals(EditorSessionState.ACCEPTED, snapshot.state)
        assertEquals(1, s.applyCalls)
    }
}
