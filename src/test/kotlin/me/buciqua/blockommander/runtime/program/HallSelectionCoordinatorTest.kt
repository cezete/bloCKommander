package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.CkWireGeometry
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class HallSelectionCoordinatorTest {
    private val direct=Executor(Runnable::run)
    private val player=ServerPlayerIdentity.trustedForTest(PLAYER_ID)
    private val otherProgram=UUID.randomUUID()
    private val wire=GraphPosition(2,1,1)
    private val block=GraphPosition(3,1,1)

    private class Api:HallSelectionGateway {
        var selected=HallSelection(TRANSPORT_CONTEXT,WORLD_ID,STAGE_ID,0)
        var pendingGet:CompletableFuture<ProgramHttpResult<SelectionResponse>>?=null
        var pendingPut:CompletableFuture<ProgramHttpResult<SelectionResponse>>?=null
        var gets=0;var puts=0;var error:String?=null
        override fun get(hall:UUID,world:UUID,player:UUID):CompletableFuture<ProgramHttpResult<SelectionResponse>> {
            gets++;return pendingGet ?: ok(SelectionResponse(selected))
        }
        override fun put(base:HallSelection,program:UUID,stage:UUID,player:UUID):CompletableFuture<ProgramHttpResult<SelectionResponse>> {
            puts++
            error?.let { return CompletableFuture.completedFuture(ProgramHttpResult.ApiError(ProgramApiError(409,it,it,emptyList()))) }
            val changed=program!=selected.context.programId || stage!=selected.stageId
            selected=selected.copy(context=selected.context.copy(programId=program,workingCopyId=
                if(program==selected.context.programId) selected.context.workingCopyId else UUID.randomUUID()),
                stageId=stage,revision=selected.revision+if(changed) 1 else 0)
            return pendingPut ?: ok(SelectionResponse(selected,changed))
        }
    }
    private class Programs(val registry:HallRegistry):ProgramGateway {
        var pending:CompletableFuture<ProgramHttpResult<ProgramStageResponse>>?=null
        var calls=0;var statusGets=0;var badOrigin=false;var badBounds=false
        override fun mountedProgram(context:WorkingCopyTransportContext):CompletableFuture<ProgramHttpResult<MountedProgramResponse>> {
            statusGets++;error("Selection must not launch unfenced status requests")
        }
        override fun program(context:WorkingCopyTransportContext):CompletableFuture<ProgramHttpResult<ProgramViewResponse>> = error("unused")
        override fun ensureChild(context:WorkingCopyTransportContext,parentStageId:UUID,programBlockInstanceId:UUID,playerUuid:UUID):CompletableFuture<ProgramHttpResult<ChildEnsureResponse>> = error("No implicit SAVE/ensure in selection")
        override fun stage(context:WorkingCopyTransportContext,stageId:UUID):CompletableFuture<ProgramHttpResult<ProgramStageResponse>> {
            calls++;return pending ?: ok(response(context,stageId))
        }
        fun response(context:WorkingCopyTransportContext,stageId:UUID):ProgramStageResponse {
            val hall=registry.hall(context.hallId.toString())!!
            val origin=GraphPosition(hall.min.x+1,hall.min.y+1,hall.min.z+1)
            val size=GraphPosition(if(badBounds) 100 else 7,5,7)
            val summary=ProgramStageSummary(stageId,context.programId,"ROOT","Selected",true,0,
                ProgramStageWorkspace(EPOCH_A,0,false),ProgramDomainLayout(GraphPosition(1,1,1),size),emptyList(),emptyList())
            return ProgramStageResponse(context.programId,0,EPOCH_B,0,ProgramStageProjection(summary,1,"ck-control-v3",
                listOf(ProgramStageBlock(UUID.randomUUID(),"START",GraphPosition(2,0,0),"NORTH","START",null)),
                PhysicalStageMetadata(context.hallId,WORLD_ID,if(badOrigin) GraphPosition(999,1,1) else origin,size)),false)
        }
    }
    private class Graph(val registry:HallRegistry,val programs:Programs):GraphGateway {
        var calls=0;var delay=false;var invalid=false
        var command:CompletableFuture<CommandTransportOutcome>?=null
        private data class Pending(val token:GraphRequestToken,val snapshot:GraphStateSnapshot,val future:CompletableFuture<ReconciliationOutcome>)
        private val pending=mutableListOf<Pending>()
        override fun reconcile(context:WorkingCopyTransportContext,stageId:UUID,player:ServerPlayerIdentity):CompletableFuture<ReconciliationOutcome> {
            calls++
            val response=programs.response(context,stageId)
            val state=wireSnapshot(0).copy(stageId=stageId,hallId=context.hallId,worldUuid=WORLD_ID,
                portCatalogVersion=response.stage.portCatalogVersion,dirty=false,
                origin=response.stage.physical!!.origin,size=response.stage.summary.domainLayout.size,
                wireCells=if(invalid) listOf(GraphWireCell(GraphPosition(1,0,0),
                    listOf(GraphWireChannel("SINGLE",listOf("NORTH","SOUTH","EAST"))))) else wireSnapshot(0).wireCells)
            val token=registry.graphProjections.begin(context.projectionKey(stageId),GraphRequestKind.RECONCILIATION)
            val future=CompletableFuture<ReconciliationOutcome>()
            val request=Pending(token,state,future)
            if(delay) pending+=request else complete(request)
            return future
        }
        fun completeNext()=complete(pending.removeAt(0))
        private fun complete(request:Pending) {
            val apply=registry.graphProjections.apply(request.token,request.snapshot)
            request.future.complete(ReconciliationOutcome(GraphHttpResult.Success(GraphSuccessResponse(
                GRAPH_API_VERSION,"ok",null,request.snapshot.stageId,EPOCH_A,0,false,emptyList(),emptyList(),null,request.snapshot)),apply))
        }
        override fun send(context:WorkingCopyTransportContext,command:GraphCommand):CompletableFuture<CommandTransportOutcome> =
            this.command ?: error("No graph domain mutations during selection")
    }
    private class World:StageSwitchWorld<String> {
        var uuid=WORLD_ID
        val cells=mutableMapOf<GraphPosition,String>();val events=mutableListOf<String>()
        var fail=false
        var onRead:(GraphPosition,Int)->Unit={_,_->}
        private val reads=mutableMapOf<GraphPosition,Int>()
        override fun worldUuid()=uuid
        override fun readState(position:GraphPosition):String {
            onRead(position,reads.merge(position,1,Int::plus)!!)
            return cells[position] ?: "AIR"
        }
        override fun restoreState(position:GraphPosition,state:String) { events+="restore";if(state=="AIR") cells.remove(position) else cells[position]=state }
        override fun isManaged(position:GraphPosition)=cells[position]?.let { it.startsWith("CK:") || it.startsWith("WIRE:") } ?: false
        override fun validateProgramBlock(block:StageBlock)=block.type=="START"
        override fun clearManaged(position:GraphPosition) { events+="clear";cells.remove(position) }
        override fun placeProgramBlock(block:StageBlock) { events+="program";check(!fail);cells[GraphPosition(block.pos.x,block.pos.y,block.pos.z)]="CK:START" }
        override fun renderSaveState(hall:Hall,dirty:Boolean) { events+="save:$dirty" }
        override fun cell(position:GraphPosition)=when {
            cells[position]==null -> WireWorldCell.REPLACEABLE
            cells.getValue(position).startsWith("WIRE:") -> WireWorldCell.CK_WIRE_UNBOUND
            else -> WireWorldCell.FOREIGN
        }
        override fun geometry(position:GraphPosition)=cells[position]?.takeIf { it.startsWith("WIRE:") }?.removePrefix("WIRE:")?.let(CkWireGeometry::valueOf)
        override fun ckWirePositions(stage:WireStageContext)=cells.keys.filter { cell(it).isCkWire }.toSet()
        override fun setWire(position:GraphPosition,geometry:CkWireGeometry,visualState:WireVisualState) { events+="wire";cells[position]="WIRE:${geometry.name}" }
        override fun removeWire(position:GraphPosition) { events+="remove-wire";cells.remove(position) }
        override fun correct(position:GraphPosition) {}
    }
    private inner class Fixture(val registry:HallRegistry=HallRegistry(),main:Executor=direct) {
        val api=Api();val programs=Programs(registry);val graph=Graph(registry,programs);val world=World()
        val navigation=StageNavigationCoordinator(registry,programs,graph,main)
        val selection=HallSelectionCoordinator(registry,api,navigation,main)
        val committer=StageSwitchProjection(registry,world)
        init { registry.install(hardeningHall().apply { addProperty("hall_code","a");addProperty("selection_revision",0) });world.cells[wire]="CK:OLD" }
        fun select(program:UUID=otherProgram,stage:UUID=OTHER_STAGE_ID)=selection.select(HALL_ID,WORLD_ID,player,program,stage,committer)
        fun retry()=selection.retry(HALL_ID,WORLD_ID,player,committer)
        fun hall()=registry.hall(HALL_ID.toString())!!
        fun phase()=registry.selectionState(HALL_ID.toString())!!.phase
        fun noEffects() { assertTrue(world.events.isEmpty(),world.events.toString());assertEquals(0,programs.statusGets) }
    }

    @Test fun `success uses production planner and commits clear program wire SAVE displayed in order`() {
        val f=Fixture();val old=f.hall().transportContext
        val result=f.select().get()
        assertEquals(NavigationStatus.SUCCESS,result.status,result.toString())
        assertEquals(listOf("clear","program","wire","save:false"),f.world.events)
        assertEquals("CK:START",f.world.cells[block]);assertTrue(f.world.cells[wire]!!.startsWith("WIRE:"))
        assertEquals(otherProgram,f.hall().transportContext.programId);assertNotEquals(old.workingCopyId,f.hall().transportContext.workingCopyId)
        assertEquals(1,f.hall().selectionRevision);assertEquals(HallSelectionPhase.DISPLAYED,f.phase())
        assertTrue(f.registry.editingAllowed(HALL_ID.toString()));assertEquals(0,f.programs.statusGets)
    }

    @Test fun `physical failure keeps backend desired old physical identity and blocks edits SAVE until retry`() {
        val f=Fixture();val old=f.hall();val before=f.world.cells.toMap();f.world.cells[block]="STONE"
        assertEquals(NavigationStatus.PROJECTION_FAILED,f.select().get().status)
        f.noEffects();assertEquals(old,f.hall());assertEquals(otherProgram,f.api.selected.context.programId)
        assertEquals(HallSelectionPhase.PROJECTION_FAILED,f.phase());assertNull(f.registry.saveFenceForHall(HALL_ID.toString()))
        assertNull(f.registry.stageUpdateFenceForHall(HALL_ID.toString()));assertNull(f.registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID))
        assertEquals("STONE",f.world.cells.remove(block));assertEquals(before,f.world.cells)
        assertEquals(NavigationStatus.SUCCESS,f.retry().get().status)
        assertEquals(1,f.api.puts);assertEquals(2,f.api.gets)
    }

    @Test fun `bad origin bounds and wire preflight all make zero writes and preserve foreign cells`() {
        for(kind in listOf("origin","bounds","wire","foreign-wire","world")) {
            val f=Fixture()
            when(kind) {
                "origin"->f.programs.badOrigin=true
                "bounds"->f.programs.badBounds=true
                "wire"->f.graph.invalid=true
                "foreign-wire"->f.world.cells[wire]="STONE"
                "world"->f.world.uuid=UUID.randomUUID()
            }
            val before=f.world.cells.toMap()
            assertNotEquals(NavigationStatus.SUCCESS,f.select().get().status,kind)
            f.noEffects();assertEquals(before,f.world.cells,kind);assertEquals(TRANSPORT_CONTEXT,f.hall().transportContext)
        }
    }

    @Test fun `late PUT after delete reinstall cannot start Stage GET or write world`() {
        val f=Fixture();f.api.pendingPut=CompletableFuture()
        val future=f.select();val desired=f.api.selected
        f.registry.remove(HALL_ID.toString());val newer=f.registry.install(hardeningHall())
        f.api.pendingPut!!.complete(ProgramHttpResult.Success(SelectionResponse(desired,true)))
        assertEquals(NavigationStatus.STALE,future.get().status);assertSame(newer,f.hall())
        assertEquals(0,f.programs.calls);f.noEffects()
    }

    @Test fun `late Stage response after reinstall cannot start Graph GET`() {
        val f=Fixture();f.programs.pending=CompletableFuture()
        val future=f.select();val response=f.programs.response(f.api.selected.context,OTHER_STAGE_ID)
        f.registry.install(hardeningHall());f.programs.pending!!.complete(ProgramHttpResult.Success(response))
        assertEquals(NavigationStatus.STALE,future.get().status);assertEquals(0,f.graph.calls);f.noEffects()
    }

    @Test fun `late Graph and queued physical commit after reinstall make zero world SAVE and status effects`() {
        val f=Fixture();f.graph.delay=true
        val future=f.select();f.registry.install(hardeningHall());f.graph.completeNext()
        assertEquals(NavigationStatus.STALE,future.get().status);f.noEffects()
        val queue=ArrayDeque<Runnable>();val queued=Fixture(main=Executor { queue.add(it) })
        val pending=queued.select()
        // GET continuation, then handoff; graph is immediate but physical commit is queued.
        queue.removeFirst().run();queue.removeFirst().run()
        queued.registry.install(hardeningHall())
        while(queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(NavigationStatus.STALE,pending.get().status);queued.noEffects()
    }

    @Test fun `two concurrent selections supersede old navigation generation without stale cleanup`() {
        val f=Fixture();f.api.pendingPut=CompletableFuture()
        val first=f.select();val firstResponse=f.api.selected
        val held=f.api.pendingPut!!;f.api.pendingPut=null
        val second=f.select(PROGRAM_ID,STAGE_ID)
        assertEquals(NavigationStatus.SUCCESS,second.get().status)
        val displayed=f.hall();val writes=f.world.events.toList()
        held.complete(ProgramHttpResult.Success(SelectionResponse(firstResponse,true)))
        assertEquals(NavigationStatus.STALE,first.get().status)
        assertEquals(displayed,f.hall());assertEquals(writes,f.world.events)
        assertEquals(HallSelectionPhase.DISPLAYED,f.phase())
    }

    @Test fun `P Q P ABA allocates new WC and rejects prehandoff Wire and status fences`() {
        val f=Fixture();val old=f.hall().transportContext
        val wireFence=f.registry.wireCompletionFence(HALL_ID.toString(),WORLD_ID.toString())!!
        var completions=0;var statusGets=0
        val completion=WireProjectionCompletion(f.registry,direct,{WORLD_ID.toString()}) { statusGets++ }
        assertEquals(NavigationStatus.SUCCESS,f.select().get().status)
        assertEquals(NavigationStatus.SUCCESS,f.select(PROGRAM_ID,STAGE_ID).get().status)
        assertEquals(PROGRAM_ID,f.hall().transportContext.programId);assertNotEquals(old.workingCopyId,f.hall().transportContext.workingCopyId)
        completion.complete(wireFence,WireActionResult(WireAction.PLACE,WireActionStatus.SUCCESS,REQUEST_ID)) { completions++ }
        assertEquals(0,completions);assertEquals(0,statusGets);assertEquals(2,f.hall().selectionRevision)
    }

    @Test fun `pending production Wire operation cannot render after selection handoff`() {
        val f=Fixture();val store=f.registry.graphProjections
        val snapshot=wireSnapshot(0).copy(origin=GraphPosition(1,1,1))
        val applied=store.apply(store.begin(TRANSPORT_CONTEXT.projectionKey(STAGE_ID),GraphRequestKind.RECONCILIATION),snapshot)
        val life=store.view(TRANSPORT_CONTEXT.projectionKey(STAGE_ID))!!.lifecycleGeneration
        val stage=WireStageContext(TRANSPORT_CONTEXT,STAGE_ID,GraphPosition(1,1,1),GraphPosition(7,5,7),EPOCH_A,life)
        f.graph.command=CompletableFuture()
        val coordinator=WireInteractionCoordinator(store,f.graph,WireWorldProjector(),direct,f.registry::permitsWireProjection)
        val pending=coordinator.place(stage,player,wire,f.world)
        assertFalse(pending.isDone)
        assertEquals(NavigationStatus.SUCCESS,f.select().get().status)
        val writes=f.world.events.toList();val reads=f.graph.calls
        f.graph.command!!.complete(CommandTransportOutcome(GraphHttpResult.Success(GraphSuccessResponse(
            GRAPH_API_VERSION,"ok",REQUEST_ID,STAGE_ID,EPOCH_A,0,false,emptyList(),emptyList(),null,snapshot)),applied,null))
        assertEquals(WireActionStatus.STALE,pending.get().status)
        assertEquals(writes,f.world.events);assertEquals(reads,f.graph.calls);assertEquals(0,f.programs.statusGets)
    }

    @Test fun `lifecycle and world changes on last preflight read cause zero writes`() {
        for(change in listOf("reinstall","world")) {
            val f=Fixture()
            f.world.onRead={ position,seen ->
                if(position==GraphPosition(7,5,7) && seen==2) {
                    if(change=="reinstall") f.registry.install(hardeningHall()) else f.world.uuid=UUID.randomUUID()
                }
            }
            assertNotEquals(NavigationStatus.SUCCESS,f.select().get().status)
            f.noEffects()
        }
    }

    @Test fun `execution failure rolls back physical source but leaves backend desired and retry available`() {
        val f=Fixture();val before=f.world.cells.toMap();f.world.fail=true
        assertEquals(NavigationStatus.PROJECTION_FAILED,f.select().get().status)
        assertEquals(before,f.world.cells);assertEquals(TRANSPORT_CONTEXT,f.hall().transportContext)
        assertEquals(otherProgram,f.api.selected.context.programId);assertEquals(HallSelectionPhase.PROJECTION_FAILED,f.phase())
        f.world.fail=false
        assertEquals(NavigationStatus.SUCCESS,f.retry().get().status)
    }

    @Test fun `wrong player world and owner reject before any backend request`() {
        val f=Fixture()
        val denied=f.selection.select(HALL_ID,UUID.randomUUID(),player,otherProgram,OTHER_STAGE_ID,f.committer).get()
        assertEquals(NavigationStatus.FORBIDDEN,denied.status)
        assertEquals(NavigationStatus.FORBIDDEN,f.selection.select(HALL_ID,WORLD_ID,
            ServerPlayerIdentity.trustedForTest(UUID.randomUUID()),otherProgram,OTHER_STAGE_ID,f.committer).get().status)
        assertEquals(0,f.api.gets);assertEquals(0,f.api.puts);f.noEffects()
    }

    @Test fun `player world change during PUT blocks projection and retry reads desired`() {
        val f=Fixture();var currentWorld=WORLD_ID;f.api.pendingPut=CompletableFuture()
        val future=f.selection.select(HALL_ID,WORLD_ID,player,otherProgram,OTHER_STAGE_ID,f.committer) { currentWorld }
        currentWorld=UUID.randomUUID();f.api.pendingPut!!.complete(ProgramHttpResult.Success(SelectionResponse(f.api.selected,true)))
        assertEquals(NavigationStatus.STALE,future.get().status);f.noEffects()
        assertEquals(NavigationStatus.SUCCESS,f.retry().get().status);assertEquals(1,f.api.puts)
    }

    @Test fun `backend CAS and owner failures are reported and never write world`() {
        for(code in listOf("FORBIDDEN","SELECTION_CONFLICT")) {
            val f=Fixture();f.api.error=code
            val response=f.select().get()
            assertTrue(response.detail!!.contains(code));f.noEffects()
            assertEquals(HallSelectionPhase.PROJECTION_FAILED,f.phase())
        }
    }

    @Test fun `GET cannot change known identity at the same revision or move revision backwards`() {
        for(change in listOf("program","copy","stage")) {
            val f=Fixture()
            f.api.selected=when(change) {
                "program" -> f.api.selected.copy(context=TRANSPORT_CONTEXT.copy(programId=otherProgram))
                "copy" -> f.api.selected.copy(context=TRANSPORT_CONTEXT.copy(workingCopyId=UUID.randomUUID()))
                else -> f.api.selected.copy(stageId=OTHER_STAGE_ID)
            }
            assertTrue(f.retry().get().detail!!.contains("SAME_SELECTION_REVISION_CONFLICT"))
            assertEquals(0,f.api.puts);assertEquals(0,f.programs.calls);f.noEffects()
        }
        val f=Fixture();assertEquals(NavigationStatus.SUCCESS,f.select().get().status)
        val writes=f.world.events.toList();f.api.selected=f.api.selected.copy(revision=0)
        assertNotEquals(NavigationStatus.SUCCESS,f.retry().get().status)
        assertEquals(writes,f.world.events)
    }

    @Test fun `PUT validates target revision changed flag and WorkingCopy lifecycle before any Stage GET`() {
        for(kind in listOf("revision","copy","target","changed")) {
            val f=Fixture();f.api.pendingPut=CompletableFuture()
            val result=f.select();var response=SelectionResponse(f.api.selected,true)
            response=when(kind) {
                "revision" -> response.copy(selection=response.selection.copy(revision=7))
                "copy" -> response.copy(selection=response.selection.copy(context=response.selection.context.copy(workingCopyId=TRANSPORT_CONTEXT.workingCopyId)))
                "target" -> response.copy(selection=response.selection.copy(stageId=STAGE_ID))
                else -> response.copy(changed=false)
            }
            f.api.pendingPut!!.complete(ProgramHttpResult.Success(response))
            assertNotEquals(NavigationStatus.SUCCESS,result.get().status)
            assertEquals(0,f.programs.calls);f.noEffects()
        }
    }

    @Test fun `persistent selected Stage remains distinct from temporary legacy navigation`() {
        val f=Fixture()
        val token=f.registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID)!!
        assertTrue(f.registry.bindStageSwitch(token,PROGRAM_ID,EPOCH_B,0,OTHER_STAGE_ID))
        val child=f.hall().stage!!.copy(id=OTHER_STAGE_ID.toString())
        assertNotNull(f.registry.commitStageSwitch(token,PROGRAM_ID,EPOCH_B,0,child))
        assertEquals(STAGE_ID,f.hall().selectedStageId)
        assertEquals(NavigationStatus.SUCCESS,f.retry().get().status)
        assertEquals(STAGE_ID,UUID.fromString(f.hall().stage!!.id));assertEquals(0,f.api.puts)
    }

    @Test fun `same stage retry uses new slot lifecycle and successful projection preserves unrelated foreign block`() {
        val f=Fixture();val oldLife=f.registry.graphProjections.view(TRANSPORT_CONTEXT.projectionKey(STAGE_ID))!!.lifecycleGeneration
        val foreign=GraphPosition(5,2,5);f.world.cells[foreign]="STONE"
        assertEquals(NavigationStatus.SUCCESS,f.select(PROGRAM_ID,STAGE_ID).get().status)
        assertEquals("STONE",f.world.cells[foreign]);assertEquals(TRANSPORT_CONTEXT,f.hall().transportContext)
        assertTrue(f.registry.graphProjections.view(TRANSPORT_CONTEXT.projectionKey(STAGE_ID))!!.lifecycleGeneration>oldLife)
        assertEquals(0,f.hall().selectionRevision)
    }

    @Test fun `two Hall same Program Stage projection remains physically and lifecycle independent`() {
        val f=Fixture();val second=TRANSPORT_CONTEXT.copy(hallId=UUID.randomUUID(),workingCopyId=UUID.randomUUID())
        val json=hardeningHall(second).apply {
            addProperty("min_x",30);addProperty("max_x",40)
            getAsJsonObject("stage").getAsJsonObject("origin").addProperty("x",31)
        }
        val hallB=f.registry.install(json);val bLife=f.registry.graphProjections.view(hallB.projectionKey())!!.lifecycleGeneration
        val bApi=Api().apply { selected=HallSelection(second,WORLD_ID,STAGE_ID,0) }
        val bSelector=HallSelectionCoordinator(f.registry,bApi,f.navigation,direct)
        assertEquals(NavigationStatus.SUCCESS,bSelector.retry(second.hallId,WORLD_ID,player,f.committer).get().status)
        val bDisplayed=f.registry.hall(second.hallId.toString())!!
        assertEquals("CK:START",f.world.cells[GraphPosition(33,1,1)])
        assertEquals(NavigationStatus.SUCCESS,f.select().get().status)
        assertEquals(bDisplayed,f.registry.hall(second.hallId.toString()))
        assertEquals("CK:START",f.world.cells[GraphPosition(33,1,1)])
        assertTrue(f.registry.graphProjections.view(bDisplayed.projectionKey())!!.lifecycleGeneration>bLife)
        assertEquals(second,bDisplayed.transportContext)
    }

    companion object {
        private fun <T> ok(value:T)=CompletableFuture.completedFuture<ProgramHttpResult<T>>(ProgramHttpResult.Success(value))
    }
}
