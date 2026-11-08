package me.buciqua.blockommander.runtime

import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class DiagnosticsTest {
    private fun capture(action:(MutableList<Map<String,Any?>>)->Unit) {
        val previous=Diagnostics.sink;val events=mutableListOf<Map<String,Any?>>()
        try { Diagnostics.sink={ events+=it };action(events) } finally { Diagnostics.sink=previous }
    }

    @Test fun `reconnect reports Stage mismatch with all identities without changing installed Hall`()=capture { events ->
        val registry=HallRegistry();val hall=registry.install(hardeningHall())
        val fence=registry.stageUpdateFenceForHall(hall.id)!!
        assertNull(registry.authoritativeReload(hardeningHall(stage=OTHER_STAGE_ID),fence))
        assertSame(hall,registry.hall(hall.id))
        val log=events.last { it["event"]=="RECONNECT_STAGE_RESULT" }
        assertEquals("RESPONSE_STAGE_MISMATCH",log["reason"])
        assertEquals(STAGE_ID.toString(),log["requested_stage_id"])
        assertEquals(STAGE_ID.toString(),log["current_stage_id"])
        assertEquals(OTHER_STAGE_ID.toString(),log["response_stage_id"])
        assertEquals(HALL_ID,log["hall_id"].toString().let(UUID::fromString))
        assertEquals(PROGRAM_ID,log["program_id"])
        assertEquals(TRANSPORT_CONTEXT.workingCopyId,log["working_copy_id"])
        assertNotNull(log["hall_generation"]);assertNotNull(log["navigation_generation"])
    }

    @Test fun `reconnect reason codes distinguish lifecycle navigation context and missing fence`()=capture { events ->
        for(change in listOf("hall","projection","navigation","context","unfenced")) {
            val registry=HallRegistry();registry.install(hardeningHall())
            val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
            val incoming=hardeningHall()
            val reason=when(change) {
                "hall" -> { registry.install(hardeningHall());"HALL_GENERATION_CHANGED" }
                "projection" -> { registry.graphProjections.authoritativeStageReconciliation(fence.key,EPOCH_A,0);"PROJECTION_LIFECYCLE_CHANGED" }
                "navigation" -> { registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID);"NAVIGATION_GENERATION_CHANGED" }
                "context" -> { incoming.addProperty("working_copy_id",UUID.randomUUID().toString());"TRANSPORT_CONTEXT_CHANGED" }
                else -> "UNFENCED_EXISTING_OR_TOMBSTONED_HALL"
            }
            assertNull(registry.authoritativeReload(incoming,if(change=="unfenced") null else fence))
            assertEquals(reason,events.last { it["event"]=="RECONNECT_STAGE_RESULT" }["reason"])
        }
    }

    @Test fun `accepted epoch change logs old and new epoch while preserving existing reload result`()=capture { events ->
        val registry=HallRegistry();registry.install(hardeningHall())
        val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val incoming=hardeningHall().apply { getAsJsonObject("stage").addProperty("workspace_epoch",EPOCH_B.toString()) }
        assertEquals(EPOCH_B,registry.authoritativeReload(incoming,fence)!!.stage!!.workspaceEpoch)
        val record=events.last { it["event"]=="RECONNECT_STAGE_RESULT" }
        assertEquals("ACCEPTED",record["reason"])
        assertEquals(EPOCH_A,record["current_epoch"]);assertEquals(EPOCH_B,record["response_epoch"])
    }

    @Test fun `Graph store logs prior versions epoch conflict stale token and successful application`()=capture { events ->
        val store=GraphProjectionStore();val key=TRANSPORT_CONTEXT.projectionKey(STAGE_ID)
        store.authoritativeStageReload(key,EPOCH_A,3)
        val request=store.begin(key,GraphRequestKind.RECONCILIATION)
        val conflict=store.apply(request,snapshot(epoch=EPOCH_B,revision=0))
        assertInstanceOf(GraphApplyResult.Rejected::class.java,conflict)
        val log=events.last()
        assertEquals("EPOCH_CONFLICT",log["reason"]);assertEquals(EPOCH_A,log["prior_epoch"])
        assertEquals(3L,log["prior_revision"]);assertEquals(EPOCH_B,log["response_epoch"])
        store.authoritativeStageReconciliation(key,EPOCH_B,0)
        assertInstanceOf(GraphApplyResult.IgnoredStaleRequest::class.java,store.apply(request,snapshot()))
        assertEquals("STALE_REQUEST_TOKEN",events.last()["reason"])
        val fresh=store.begin(key,GraphRequestKind.RECONCILIATION)
        assertInstanceOf(GraphApplyResult.Applied::class.java,store.apply(fresh,snapshot(epoch=EPOCH_B,revision=0)))
        assertEquals("ACCEPTED",events.last()["reason"])
    }

    @Test fun `status response acceptance and stale rejection log dirty and root separately from active Stage`()=capture { events ->
        val registry=HallRegistry();registry.install(hardeningHall())
        val summary=ProgramStageSummary(STAGE_ID,PROGRAM_ID,"ROOT","Root",true,0,
            ProgramStageWorkspace(EPOCH_A,0,false),ProgramDomainLayout(GraphPosition(1,1,1),GraphPosition(7,5,7)),emptyList(),emptyList())
        val program=ProgramSnapshot(PROGRAM_ID,PLAYER_ID,STAGE_ID,0,false,false,0,EPOCH_B,
            ProgramWorkspaceState.ACTIVE,listOf(summary),emptyList(),emptyList())
        val response=MountedProgramResponse(ProgramMount(HALL_ID,PROGRAM_ID,TRANSPORT_CONTEXT.workingCopyId,true),program)
        val first=registry.beginProgramStatus(HALL_ID.toString())!!
        assertFalse(registry.applyProgramStatus(first,response)!!.programDirty)
        assertEquals("ACCEPTED",events.last()["reason"])
        val stale=registry.beginProgramStatus(HALL_ID.toString())!!
        registry.beginProgramStatus(HALL_ID.toString())
        assertNull(registry.applyProgramStatus(stale,response))
        assertEquals("STATUS_GENERATION_CHANGED",events.last()["reason"])
        assertEquals(false,events.last()["response_dirty"])
        assertEquals(STAGE_ID,events.last()["response_root_stage_id"])
        assertEquals(EPOCH_A,events.last()["response_stage_epoch"])
        assertNotNull(events.last()["requested_hall_generation"])
        assertTrue(registry.hall(HALL_ID.toString())!!.programDirty)
    }

    @Test fun `HTTP diagnostic fields exclude headers bodies and Graph content`()=capture { events ->
        val trace=UUID.randomUUID()
        Diagnostics.http("TEST_HTTP",trace,TRANSPORT_CONTEXT,STAGE_ID,"POST","/test?secret=DO_NOT_LOG",409,
            """{"error_code":"STALE_WORKSPACE_EPOCH","password":"DO_NOT_LOG","blocks":["DO_NOT_LOG"]}""")
        assertEquals(trace,events.single()["diagnostic_id"])
        assertEquals(409,events.single()["http_status"])
        assertEquals("STALE_WORKSPACE_EPOCH",events.single()["error_code"])
        assertFalse(events.toString().contains("DO_NOT_LOG"))
    }

    @Test fun `broken logger cannot reject a valid Graph snapshot or authoritative Stage reload`() {
        val previous=Diagnostics.sink
        try {
            Diagnostics.sink={ error("logging unavailable") }
            val registry=HallRegistry();registry.install(hardeningHall())
            val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
            assertNotNull(registry.authoritativeReload(hardeningHall(),fence))
            val key=TRANSPORT_CONTEXT.projectionKey(STAGE_ID);val store=registry.graphProjections
            assertInstanceOf(GraphApplyResult.Applied::class.java,
                store.apply(store.begin(key,GraphRequestKind.RECONCILIATION),snapshot(revision=0)))
        } finally { Diagnostics.sink=previous }
    }

    @Test fun `local Wire rejection identifies sync gate and sends no mutation HTTP`()=capture { events ->
        val store=GraphProjectionStore();var requests=0
        val gateway=object:GraphGateway {
            override fun reconcile(context:WorkingCopyTransportContext,stageId:UUID,player:ServerPlayerIdentity):CompletableFuture<ReconciliationOutcome> {
                requests++;error("Must reject before HTTP")
            }
            override fun send(context:WorkingCopyTransportContext,command:GraphCommand):CompletableFuture<CommandTransportOutcome> {
                requests++;error("Must reject before HTTP")
            }
        }
        val stage=WireStageContext(TRANSPORT_CONTEXT,STAGE_ID,GraphPosition(1,1,1),GraphPosition(7,5,7),EPOCH_A,1)
        val coordinator=WireInteractionCoordinator(store,gateway,WireWorldProjector(),Executor(Runnable::run))
        val world=FakeWireWorld()
        val result=coordinator.remove(stage,ServerPlayerIdentity.trustedForTest(PLAYER_ID),GraphPosition(2,1,1),world).get()
        assertEquals(WireActionStatus.NOT_SYNCED,result.status);assertEquals(0,requests);assertEquals(0,world.setCalls)
        assertEquals(false,events.single { it["event"]=="WIRE_SYNC_CHECK" }["valid_lifecycle"])
        val rejected=events.single { it["event"]=="WIRE_LOCAL_REJECT" }
        assertEquals("GRAPH_NOT_SYNCED",rejected["reason"]);assertEquals(false,rejected["http_sent"])
    }
}
