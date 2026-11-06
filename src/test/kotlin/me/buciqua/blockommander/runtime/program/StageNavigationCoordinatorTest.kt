package me.buciqua.blockommander.runtime.program

import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class StageNavigationCoordinatorTest {
    private val direct=Executor(Runnable::run)
    private val player=ServerPlayerIdentity.trustedForTest(OWNER)

    @Test fun `PROGRAM activation ensures child and ROOT child ROOT preserves both dirty states`() {
        val fixture=fixture()
        val entered=fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer)
            .get(3,TimeUnit.SECONDS)
        assertEquals(NavigationStatus.SUCCESS,entered.status)
        assertEquals(CHILD,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
        assertTrue(fixture.registry.hall(HALL.toString())!!.stage!!.dirty)
        assertEquals(listOf(ROOT to ROOT_BLOCK),fixture.programs.ensureCalls)

        val backed=fixture.navigation.back(HALL,player,fixture.committer).get(3,TimeUnit.SECONDS)
        assertEquals(NavigationStatus.SUCCESS,backed.status)
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
        assertTrue(fixture.registry.hall(HALL.toString())!!.stage!!.dirty)

        val enteredAgain=fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer)
            .get(3,TimeUnit.SECONDS)
        assertEquals(NavigationStatus.SUCCESS,enteredAgain.status)
        assertEquals(CHILD,enteredAgain.targetStageId)
        assertFalse(fixture.programs.lastEnsureCreated)
        assertEquals(2,fixture.programs.ensureCalls.size)
    }

    @Test fun `ROOT child grandchild child follows authoritative ACTIVE parents`() {
        val fixture=fixture()
        assertEquals(NavigationStatus.SUCCESS,fixture.navigation.enter(
            HALL,ROOT_BLOCK,player,fixture.committer,
        ).get().status)
        assertEquals(NavigationStatus.SUCCESS,fixture.navigation.enter(
            HALL,CHILD_BLOCK,player,fixture.committer,
        ).get().status)
        assertEquals(GRANDCHILD,fixture.registry.navigationState(HALL.toString())!!.activeStageId)

        val result=fixture.navigation.back(HALL,player,fixture.committer).get()
        assertEquals(NavigationStatus.SUCCESS,result.status)
        assertEquals(CHILD,result.targetStageId)
        assertEquals(CHILD,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
    }

    @Test fun `Program dirty survives ROOT CHILD GRANDCHILD even when target Stage is clean`() {
        val fixture=fixture()
        fixture.programs.stageDirty=false
        for (block in listOf(ROOT_BLOCK,CHILD_BLOCK)) {
            assertEquals(NavigationStatus.SUCCESS,fixture.navigation.enter(
                HALL,block,player,fixture.committer).get().status)
            assertFalse(fixture.registry.hall(HALL.toString())!!.stage!!.dirty)
            assertTrue(fixture.registry.hall(HALL.toString())!!.programDirty)
        }
        fixture.programs.aggregateDirty=false
        assertEquals(NavigationStatus.SUCCESS,fixture.navigation.back(HALL,player,fixture.committer).get().status)
        assertFalse(fixture.registry.hall(HALL.toString())!!.programDirty)
    }

    @Test fun `ckback at root is safe no-op and non-owner cannot begin switch`() {
        val fixture=fixture()
        val root=fixture.navigation.back(HALL,player,fixture.committer).get()
        assertEquals(NavigationStatus.NO_OP,root.status)
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)

        val outsider=ServerPlayerIdentity.trustedForTest(UUID.fromString("f0000000-0000-0000-0000-000000000001"))
        val denied=fixture.navigation.enter(HALL,ROOT_BLOCK,outsider,fixture.committer).get()
        assertEquals(NavigationStatus.FORBIDDEN,denied.status)
        assertTrue(fixture.programs.ensureCalls.isEmpty())
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
    }

    @Test fun `HTTP failure leaves previous Stage visible active and graph intact`() {
        val fixture=fixture()
        fixture.programs.failStage=true
        val rootBefore=fixture.registry.hall(HALL.toString())!!.stage
        val result=fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer).get()
        assertEquals(NavigationStatus.UNAVAILABLE,result.status)
        assertSame(rootBefore,fixture.registry.hall(HALL.toString())!!.stage)
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
        assertEquals(ROOT_EPOCH,fixture.registry.graphProjections.view(ROOT)!!.stageEpoch)
        assertEquals(0,fixture.committer.commits)
    }

    @Test fun `late response loses switch generation and cannot commit`() {
        val fixture=fixture()
        val pending=CompletableFuture<ProgramHttpResult<ProgramStageResponse>>()
        fixture.programs.pendingStages[CHILD]=pending
        val first=fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer)
        assertFalse(first.isDone)

        val newer=fixture.navigation.back(HALL,player,fixture.committer).get()
        assertEquals(NavigationStatus.NO_OP,newer.status)
        pending.complete(ProgramHttpResult.Success(fixture.programs.stageResponse(CHILD)))

        assertEquals(NavigationStatus.STALE,first.get(3,TimeUnit.SECONDS).status)
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
        assertEquals(0,fixture.committer.commits)
    }

    @Test fun `new dirty response during navigation rejects an older clean Program projection`() {
        val fixture=fixture()
        fixture.programs.aggregateDirty=false
        val pending=CompletableFuture<ProgramHttpResult<ProgramStageResponse>>()
        fixture.programs.pendingStages[CHILD]=pending
        val navigation=fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer)
        val fence=fixture.registry.stageUpdateFenceForHall(HALL.toString())!!
        val dirty=JsonParser.parseString(hallJson(BlockPos.ZERO)).asJsonObject.getAsJsonObject("stage")
        dirty.addProperty("program_dirty",true)
        assertNotNull(fixture.registry.updateStage(dirty,fence))
        pending.complete(ProgramHttpResult.Success(fixture.programs.stageResponse(CHILD)))
        assertEquals(NavigationStatus.STALE,navigation.get().status)
        assertEquals(ROOT,fixture.registry.navigationState(HALL.toString())!!.activeStageId)
        assertTrue(fixture.registry.hall(HALL.toString())!!.programDirty)
        assertFalse(fixture.registry.navigationState(HALL.toString())!!.switchInProgress)
    }

    @Test fun `relative domain coordinates map into Hall without rewriting projection DTO`() {
        val fixture=fixture(hallMin=BlockPos(100,20,-30))
        val response=fixture.programs.stageResponse(CHILD)
        fixture.registry.graphProjections.authoritativeStageReload(CHILD,CHILD_EPOCH,2)
        val graph=fixture.graph.snapshotFor(CHILD)
        val prepared=StageProjectionAssembler.assemble(fixture.registry.hall(HALL.toString())!!,response,graph)

        assertEquals(BlockPos(101,21,-29),prepared.target.origin)
        assertEquals(BlockPos(103,21,-28),prepared.target.blocks.single().pos)
        assertEquals(GraphPosition(1,1,1),response.stage.summary.domainLayout.origin)
        assertEquals(GraphPosition(2,0,1),response.stage.blocks.single().position)
    }

    @Test fun `projection commit is physical only and never sends domain delete or save`() {
        val fixture=fixture()
        fixture.navigation.enter(HALL,ROOT_BLOCK,player,fixture.committer).get()
        assertEquals(1,fixture.programs.ensureCalls.size)
        assertEquals(0,fixture.programs.mutationCalls)
        assertTrue(fixture.programs.links.any { it.parentStageId==ROOT && it.childStageId==CHILD })
    }

    private fun fixture(hallMin:BlockPos=BlockPos.ZERO):Fixture {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(hallMin)).asJsonObject)
        val programs=FakePrograms()
        val graph=FakeNavigationGraph(registry.graphProjections,programs)
        val committer=RegistryCommitter(registry)
        return Fixture(
            registry,programs,graph,committer,
            StageNavigationCoordinator(registry,programs,graph,direct),
        )
    }

    private fun hallJson(min:BlockPos):String {
        val origin=min.offset(1,1,1)
        val max=min.offset(10,10,10)
        return """
          {"hall_id":"$HALL","hall_name":"Hall","owner_uuid":"$OWNER","owner_name":"Owner",
           "world_uuid":"$WORLD","material_name":"STONE","min_x":${min.x},"min_y":${min.y},"min_z":${min.z},
           "max_x":${max.x},"max_y":${max.y},"max_z":${max.z},"door":{"x":${min.x},"y":${min.y+1},"z":${min.z+1}},
           "orientation":"NORTH","controls":[],"stage":{"stage_id":"$ROOT","hall_id":"$HALL",
           "origin":{"x":${origin.x},"y":${origin.y},"z":${origin.z}},"size":{"x":7,"y":5,"z":7},
           "dirty":true,"workspace_epoch":"$ROOT_EPOCH","working_revision":1,
           "blocks":[{"instance_id":"$ROOT_BLOCK","type_key":"PROGRAM","x":${origin.x+1},"y":${origin.y},"z":${origin.z+1},
             "orientation":"EAST","label":"PROGRAM","description":""}]}}
        """.trimIndent()
    }

    private data class Fixture(
        val registry:HallRegistry,val programs:FakePrograms,val graph:FakeNavigationGraph,
        val committer:RegistryCommitter,val navigation:StageNavigationCoordinator,
    )

    private class RegistryCommitter(private val registry:HallRegistry):StageSwitchCommitter {
        var commits=0
        override fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch):Boolean {
            commits++
            val context=prepared.program
            return registry.commitStageSwitch(
                token,context.programId,context.workspaceEpoch,context.hierarchyRevision,prepared.target,prepared.programDirty,
            )!=null
        }
    }

    private class FakePrograms:ProgramGateway {
        val ensureCalls=mutableListOf<Pair<UUID,UUID>>()
        val pendingStages=mutableMapOf<UUID,CompletableFuture<ProgramHttpResult<ProgramStageResponse>>>()
        val links=mutableListOf<ProgramLink>()
        var hierarchyRevision=0L
        var failStage=false
        var lastEnsureCreated=false
        var mutationCalls=0
        var stageDirty=true
        var aggregateDirty=true

        override fun mountedProgram(hallId:UUID)=success(MountedProgramResponse(
            ProgramMount(HALL,PROGRAM,true),snapshot(),
        ))
        override fun program(programId:UUID)=success(ProgramViewResponse(snapshot()))
        override fun stage(programId:UUID,stageId:UUID):CompletableFuture<ProgramHttpResult<ProgramStageResponse>> {
            pendingStages[stageId]?.let { return it }
            if (failStage) return CompletableFuture.completedFuture(ProgramHttpResult.Unavailable("offline"))
            return success(stageResponse(stageId))
        }
        override fun ensureChild(
            programId:UUID,parentStageId:UUID,programBlockInstanceId:UUID,playerUuid:UUID,
        ):CompletableFuture<ProgramHttpResult<ChildEnsureResponse>> {
            ensureCalls+=parentStageId to programBlockInstanceId
            val target=when (programBlockInstanceId) { ROOT_BLOCK->CHILD;CHILD_BLOCK->GRANDCHILD;else->error("unknown block") }
            var link=links.firstOrNull {
                it.parentStageId==parentStageId && it.programBlockInstanceId==programBlockInstanceId
            }
            lastEnsureCreated=link==null
            if (link==null) {
                link=ProgramLink(parentStageId,programBlockInstanceId,target,ProgramLinkState.ACTIVE)
                links+=link;hierarchyRevision++
            }
            return success(ChildEnsureResponse(
                PROGRAM,0,PROGRAM_EPOCH,hierarchyRevision,lastEnsureCreated,link,summary(target),
            ))
        }

        fun stageResponse(stageId:UUID)=ProgramStageResponse(
            PROGRAM,0,PROGRAM_EPOCH,hierarchyRevision,ProgramStageProjection(
                summary(stageId),1,"ck-control-v3",when(stageId) {
                    ROOT->listOf(block(ROOT_BLOCK,GraphPosition(1,0,1)))
                    CHILD->listOf(block(CHILD_BLOCK,GraphPosition(2,0,1)))
                    else->emptyList()
                },
            ),aggregateDirty,
        )
        fun summary(stageId:UUID)=ProgramStageSummary(
            stageId,PROGRAM,if(stageId==ROOT) "ROOT" else "PROGRAM_BODY","Stage",stageId==ROOT,0,
            ProgramStageWorkspace(epoch(stageId),revision(stageId),stageDirty),
            ProgramDomainLayout(GraphPosition(1,1,1),GraphPosition(7,5,7)),
            links.filter { it.childStageId==stageId },links.filter { it.parentStageId==stageId },
        )
        fun snapshot()=ProgramSnapshot(
            PROGRAM,OWNER,ROOT,0,true,links.isNotEmpty(),hierarchyRevision,PROGRAM_EPOCH,
            ProgramWorkspaceState.ACTIVE,listOf(ROOT,CHILD,GRANDCHILD).map(::summary),links.toList(),
            listOf(ProgramMount(HALL,PROGRAM,true)),
        )
        private fun block(id:UUID,pos:GraphPosition)=ProgramStageBlock(
            id,"PROGRAM",pos,"EAST","PROGRAM",null,
        )
        private fun epoch(stageId:UUID)=when(stageId) { ROOT->ROOT_EPOCH;CHILD->CHILD_EPOCH;else->GRANDCHILD_EPOCH }
        private fun revision(stageId:UUID)=when(stageId) { ROOT->1L;CHILD->2L;else->3L }
        private fun <T> success(value:T)=CompletableFuture.completedFuture<ProgramHttpResult<T>>(
            ProgramHttpResult.Success(value),
        )
    }

    private class FakeNavigationGraph(
        private val store:GraphProjectionStore,private val programs:FakePrograms,
    ):GraphGateway {
        override fun reconcile(stageId:UUID,player:ServerPlayerIdentity):CompletableFuture<ReconciliationOutcome> {
            val snapshot=snapshotFor(stageId)
            val applied=store.apply(store.begin(stageId,GraphRequestKind.RECONCILIATION),snapshot)
            return CompletableFuture.completedFuture(ReconciliationOutcome(GraphHttpResult.Success(
                GraphSuccessResponse(
                    GRAPH_API_VERSION,"ok",null,stageId,snapshot.workspaceEpoch,snapshot.workingRevision,
                    false,emptyList(),emptyList(),null,snapshot,
                ),
            ),applied))
        }
        override fun send(command:GraphCommand):CompletableFuture<CommandTransportOutcome> =
            error("Navigation must not issue Graph mutations")
        fun snapshotFor(stageId:UUID):GraphStateSnapshot {
            val stage=programs.stageResponse(stageId).stage
            val workspace=stage.summary.workspace!!
            return GraphStateSnapshot(
                stageId,null,null,stage.summary.domainLayout.origin,stage.summary.domainLayout.size,
                workspace.epoch,workspace.workingRevision,0,workspace.dirty,1,"ck-control-v3",true,
                emptyList(),GraphStatuses("DRAFT","NONE","DIRTY","PRESENT","NOT_CHECKED"),
                emptyList(),emptyList(),emptyList(),emptyList(),GraphValidation(null,emptyList()),
            )
        }
    }

    companion object {
        private val HALL=UUID.fromString("20000000-0000-0000-0000-000000000001")
        private val PROGRAM=UUID.fromString("20000000-0000-0000-0000-000000000002")
        private val ROOT=UUID.fromString("20000000-0000-0000-0000-000000000003")
        private val CHILD=UUID.fromString("20000000-0000-0000-0000-000000000004")
        private val GRANDCHILD=UUID.fromString("20000000-0000-0000-0000-000000000005")
        private val ROOT_BLOCK=UUID.fromString("20000000-0000-0000-0000-000000000006")
        private val CHILD_BLOCK=UUID.fromString("20000000-0000-0000-0000-000000000007")
        private val OWNER=UUID.fromString("20000000-0000-0000-0000-000000000008")
        private val WORLD=UUID.fromString("20000000-0000-0000-0000-000000000009")
        private val PROGRAM_EPOCH=UUID.fromString("20000000-0000-0000-0000-000000000010")
        private val ROOT_EPOCH=UUID.fromString("20000000-0000-0000-0000-000000000011")
        private val CHILD_EPOCH=UUID.fromString("20000000-0000-0000-0000-000000000012")
        private val GRANDCHILD_EPOCH=UUID.fromString("20000000-0000-0000-0000-000000000013")
    }
}
