package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class HallProjectionIsolationTest {
    private val a=TRANSPORT_CONTEXT
    private val b=a.copy(hallId=UUID.randomUUID(),workingCopyId=UUID.randomUUID())
    private val player=ServerPlayerIdentity.trustedForTest(PLAYER_ID)
    private val direct=Executor(Runnable::run)

    private fun hall(context:WorkingCopyTransportContext, x:Int=10):JsonObject=JsonParser.parseString("""
      {"hall_id":"${context.hallId}","program_id":"${context.programId}","working_copy_id":"${context.workingCopyId}",
       "hall_name":"Hall","owner_uuid":"$PLAYER_ID","owner_name":"Owner","world_uuid":"$WORLD_ID",
       "material_name":"STONE","min_x":$x,"min_y":20,"min_z":30,"max_x":${x+10},"max_y":30,"max_z":40,
       "door":{"x":$x,"y":21,"z":31},"orientation":"NORTH","controls":[],
       "stage":{"stage_id":"$STAGE_ID","hall_id":"${context.hallId}","origin":{"x":${x+1},"y":21,"z":31},
        "size":{"x":7,"y":5,"z":7},"dirty":true,"program_dirty":true,"workspace_epoch":"$EPOCH_A",
        "working_revision":0,"blocks":[]}}
    """).asJsonObject

    private fun installed():HallRegistry=HallRegistry().also {
        it.install(hall(a)); it.install(hall(b,100))
        for (context in listOf(a,b)) publish(it.graphProjections,context.projectionKey(STAGE_ID),snapshot(revision=0))
    }
    private fun publish(store:GraphProjectionStore,key:ProjectionKey,value:GraphStateSnapshot)=
        store.apply(store.begin(key,GraphRequestKind.RECONCILIATION),value)
    private fun wire(registry:HallRegistry,context:WorkingCopyTransportContext):WireStageContext {
        val hall=registry.hall(context.hallId.toString())!!
        val stage=hall.stage!!
        return WireStageContext(context,UUID.fromString(stage.id),
            GraphPosition(stage.origin.x,stage.origin.y,stage.origin.z),
            GraphPosition(stage.sizeX,stage.sizeY,stage.sizeZ),stage.workspaceEpoch,
            registry.graphProjections.view(hall.projectionKey())!!.lifecycleGeneration)
    }

    @Test fun `same shared Stage has independent slots request streams and removal`() {
        val registry=installed(); val store=registry.graphProjections
        val ak=a.projectionKey(STAGE_ID); val bk=b.projectionKey(STAGE_ID)
        val oldA=store.begin(ak,GraphRequestKind.COMMAND)
        publish(store,bk,snapshot(revision=4))
        assertInstanceOf(GraphApplyResult.Applied::class.java,store.apply(oldA,snapshot(revision=2)))
        assertEquals(2,store.view(ak)!!.stageRevision)
        assertEquals(4,store.view(bk)!!.stageRevision)
        registry.remove(a.hallId.toString())
        assertNull(store.view(ak))
        assertEquals(4,store.view(bk)!!.stageRevision)
    }

    @Test fun `remove reinstall cannot resurrect a token even with matching epoch revision and request count`() {
        val registry=installed(); val store=registry.graphProjections; val key=a.projectionKey(STAGE_ID)
        val old=store.begin(key,GraphRequestKind.COMMAND)
        registry.remove(a.hallId.toString()); registry.install(hall(a))
        store.begin(key,GraphRequestKind.RECONCILIATION)
        assertInstanceOf(GraphApplyResult.IgnoredStaleRequest::class.java,store.apply(old,snapshot(revision=9)))
        assertNull(store.view(key)!!.snapshot)
        store.fail(old,"OLD_FAILURE",true)
        assertEquals(GraphSyncStatus.RECONCILING,store.view(key)!!.status)
    }

    @Test fun `A Wire command and B Graph GET can complete in reverse order without crossing worlds`() {
        val registry=installed(); val gateway=DelayedGateway(registry.graphProjections)
        val world=FakeWireWorld()
        val coordinator=WireInteractionCoordinator(registry.graphProjections,gateway,WireWorldProjector(),direct,
            registry::permitsWireProjection)
        val wa=wire(registry,a); val wb=wire(registry,b)
        val placed=coordinator.place(wa,player,wa.origin+GraphPosition(1,0,0),world)
        val refreshed=coordinator.reconcileAndRender(wb,player,world)
        gateway.finishRead(b,wireSnapshot(1))
        assertEquals(WireActionStatus.RECOVERED,refreshed.get().status)
        val bPosition=wb.origin+GraphPosition(1,0,0)
        assertTrue(world.cell(bPosition).isCkWire)
        gateway.finishCommand(a,wireSnapshot(1))
        assertEquals(WireActionStatus.SUCCESS,placed.get().status)
        assertTrue(world.cell(bPosition).isCkWire)
        assertTrue(world.cell(wa.origin+GraphPosition(1,0,0)).isCkWire)
        assertEquals(WireWorldStatus.SYNCED,coordinator.worldStatus(wa.key))
        assertEquals(WireWorldStatus.SYNCED,coordinator.worldStatus(wb.key))
    }

    @Test fun `late Wire completion after mount replacement performs zero writes`() {
        val registry=installed(); val gateway=DelayedGateway(registry.graphProjections); val world=FakeWireWorld()
        val coordinator=WireInteractionCoordinator(registry.graphProjections,gateway,WireWorldProjector(),direct,
            registry::permitsWireProjection)
        val old=wire(registry,b)
        val pending=coordinator.place(old,player,old.origin+GraphPosition(1,0,0),world)
        registry.install(hall(b.copy(workingCopyId=UUID.randomUUID()),100))
        gateway.finishCommand(b,wireSnapshot(1))
        assertFalse(pending.get().status in setOf(WireActionStatus.SUCCESS,WireActionStatus.RECOVERED))
        assertEquals(0,world.setCalls); assertEquals(0,world.removeCalls)
        assertEquals(WireWorldStatus.NEEDS_REPAIR,coordinator.worldStatus(old.key))
    }

    @Test fun `wrong WorkingCopy or physical origin never starts a Wire request or writes world`() {
        val registry=installed(); val gateway=DelayedGateway(registry.graphProjections); val world=FakeWireWorld()
        val coordinator=WireInteractionCoordinator(registry.graphProjections,gateway,WireWorldProjector(),direct,
            registry::permitsWireProjection)
        val valid=wire(registry,a)
        for (bad in listOf(valid.copy(transportContext=a.copy(workingCopyId=b.workingCopyId)),
                valid.copy(origin=wire(registry,b).origin))) {
            coordinator.reconcileAndRender(bad,player,world).get()
        }
        assertTrue(gateway.reads.isEmpty()); assertTrue(gateway.commands.isEmpty())
        assertEquals(0,world.setCalls); assertEquals(0,world.removeCalls)
    }

    @Test fun `navigation in A leaves B active and late A callback fenced`() {
        val registry=installed(); val old=wire(registry,a)
        val token=registry.beginStageSwitch(a.hallId.toString(),PLAYER_ID)!!
        val target=UUID.randomUUID()
        assertTrue(registry.bindStageSwitch(token,a.programId,EPOCH_B,1,target))
        val changed=registry.hall(a.hallId.toString())!!.stage!!.copy(id=target.toString())
        assertNotNull(registry.commitStageSwitch(token,a.programId,EPOCH_B,1,changed))
        assertFalse(registry.permitsWireProjection(old))
        assertTrue(registry.permitsWireProjection(wire(registry,b)))
        assertEquals(STAGE_ID.toString(),registry.hall(b.hallId.toString())!!.stage!!.id)
    }

    @Test fun `SAVE and reconnect completions belong to original Hall and installation`() {
        val registry=installed()
        val save=registry.saveFenceForHall(a.hallId.toString())!!
        val other=hall(b,100).getAsJsonObject("stage")
        val beforeA=registry.hall(a.hallId.toString())
        val saveB=registry.saveFenceForHall(b.hallId.toString())!!
        assertNotNull(registry.updateStageAfterSave(other,saveB))
        assertSame(beforeA,registry.hall(a.hallId.toString()))
        val futures=mutableMapOf<UUID,CompletableFuture<ReconnectGraphResult>>()
        val rendered=mutableListOf<UUID>()
        val reconnect=StageReconnectReconciler(registry,{}, { h ->
            CompletableFuture<ReconnectGraphResult>().also { futures[h.transportContext.hallId]=it }
        }, { h,_ -> rendered+=h.transportContext.hallId })
        for (context in listOf(a,b)) {
            val fence=registry.stageUpdateFenceForHall(context.hallId.toString())
            assertTrue(reconnect.reconcile(hall(context,if(context==a)10 else 100),fence))
        }
        registry.remove(a.hallId.toString()); registry.install(hall(a))
        futures.getValue(a.hallId).complete(ReconnectGraphResult(true))
        futures.getValue(b.hallId).complete(ReconnectGraphResult(true))
        assertEquals(listOf(b.hallId),rendered)
        assertNull(registry.updateStageAfterSave(hall(a).getAsJsonObject("stage"),save))
    }

    @Test fun `out of Hall Stage and blocks are rejected before installation`() {
        val registry=HallRegistry()
        val invalid=hall(a)
        invalid.getAsJsonObject("stage").getAsJsonObject("size").addProperty("x",100)
        assertThrows(IllegalArgumentException::class.java) { registry.install(invalid) }
        assertNull(registry.hall(a.hallId.toString()))
    }

    @Test fun `queued world callback cannot survive same identity slot replacement`() {
        val registry=installed(); val gateway=DelayedGateway(registry.graphProjections)
        val tasks=mutableListOf<Runnable>(); val world=FakeWireWorld()
        val coordinator=WireInteractionCoordinator(registry.graphProjections,gateway,WireWorldProjector(),
            Executor { tasks+=it },registry::permitsWireProjection)
        val stage=wire(registry,a)
        val pending=coordinator.place(stage,player,stage.origin+GraphPosition(1,0,0),world)
        gateway.finishCommand(a,wireSnapshot(1))
        registry.install(hall(a))
        tasks.forEach(Runnable::run)
        assertEquals(WireActionStatus.STALE,pending.get().status)
        assertEquals(0,world.setCalls); assertEquals(0,world.removeCalls)
    }

    @Test fun `SAVE fence cannot survive a same epoch graph reconciliation reset`() {
        val registry=installed(); val fence=registry.saveFenceForHall(a.hallId.toString())!!
        registry.graphProjections.authoritativeStageReconciliation(fence.key,EPOCH_A,0)
        assertNull(registry.updateStageAfterSave(hall(a).getAsJsonObject("stage"),fence))
        assertTrue(registry.hall(a.hallId.toString())!!.programDirty)
    }

    @Test fun `removed mount cannot bootstrap itself from an old reconnect list`() {
        val registry=installed()
        registry.remove(a.hallId.toString())
        var writes=0
        val reconnect=StageReconnectReconciler(registry,{ writes++ },
            { CompletableFuture.completedFuture(ReconnectGraphResult(true)) },{ _,_ -> writes++ })
        assertFalse(reconnect.reconcile(hall(a),null))
        assertEquals(0,writes)
    }

    private class DelayedGateway(val store:GraphProjectionStore):GraphGateway {
        data class Pending<T>(val token:GraphRequestToken,val future:CompletableFuture<T>)
        val reads=mutableMapOf<WorkingCopyTransportContext,Pending<ReconciliationOutcome>>()
        val commands=mutableMapOf<WorkingCopyTransportContext,Pending<CommandTransportOutcome>>()
        override fun reconcile(context:WorkingCopyTransportContext,stageId:UUID,player:ServerPlayerIdentity)=
            CompletableFuture<ReconciliationOutcome>().also {
                reads[context]=Pending(store.begin(context.projectionKey(stageId),GraphRequestKind.RECONCILIATION),it)
            }
        override fun send(context:WorkingCopyTransportContext,command:GraphCommand)=
            CompletableFuture<CommandTransportOutcome>().also {
                commands[context]=Pending(store.begin(context.projectionKey(command.stageId),GraphRequestKind.COMMAND),it)
            }
        private fun success(value:GraphStateSnapshot)=GraphHttpResult.Success(GraphSuccessResponse(
            GRAPH_API_VERSION,"ok",null,value.stageId,value.workspaceEpoch,value.workingRevision,
            true,emptyList(),emptyList(),null,value,
        ))
        fun finishRead(context:WorkingCopyTransportContext,value:GraphStateSnapshot) {
            val pending=reads.getValue(context)
            pending.future.complete(ReconciliationOutcome(success(value),store.apply(pending.token,value)))
        }
        fun finishCommand(context:WorkingCopyTransportContext,value:GraphStateSnapshot) {
            val pending=commands.getValue(context)
            pending.future.complete(CommandTransportOutcome(success(value),store.apply(pending.token,value),null))
        }
    }
}
