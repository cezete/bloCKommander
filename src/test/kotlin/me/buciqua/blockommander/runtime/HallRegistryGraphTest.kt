package me.buciqua.blockommander.runtime

import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.program.*
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class HallRegistryGraphTest {
    private fun testKey(stageId:UUID)=WorkingCopyTransportContext(HALL_ID,EPOCH_B,WORKING_COPY_ID).projectionKey(stageId)
    private fun programStatus(dirty: Boolean, revision: Long = 0): MountedProgramResponse {
        val summary=ProgramStageSummary(STAGE_ID,EPOCH_B,"ROOT","root",true,0,ProgramStageWorkspace(EPOCH_A,revision,false),
            ProgramDomainLayout(GraphPosition(1,1,1),GraphPosition(7,5,7)),emptyList(),emptyList())
        return MountedProgramResponse(ProgramMount(HALL_ID,EPOCH_B,WORKING_COPY_ID,true),ProgramSnapshot(
            EPOCH_B,PLAYER_ID,STAGE_ID,0,dirty,false,0,EPOCH_A,ProgramWorkspaceState.ACTIVE,
            listOf(summary),emptyList(),listOf(ProgramMount(HALL_ID,EPOCH_B,WORKING_COPY_ID,true))))
    }

    @Test fun `late Program clean status and SAVE cannot overwrite newer dirty status`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,4,true)).asJsonObject)
        val savedFence=registry.saveFenceForHall(HALL_ID.toString())!!
        val old=registry.beginProgramStatus(HALL_ID.toString())!!
        val newer=registry.beginProgramStatus(HALL_ID.toString())!!
        assertNotNull(registry.applyProgramStatus(newer,programStatus(true,4)))
        assertNull(registry.applyProgramStatus(old,programStatus(false,4)))
        assertNull(registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_A,4,false)).asJsonObject,savedFence))
        assertTrue(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `Program status is authoritative when active Stage is clean and ignores late clean graph`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,0,false)).asJsonObject)
        val pending=registry.beginProgramStatus(HALL_ID.toString())!!
        assertTrue(registry.saveDirty(HALL_ID.toString())!!)
        assertNotNull(registry.applyProgramStatus(pending,programStatus(true)))
        val store=registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID),GraphRequestKind.RECONCILIATION),snapshot(revision=0,dirty=false))
        assertTrue(registry.saveDirty(HALL_ID.toString())!!)
        val clean=registry.beginProgramStatus(HALL_ID.toString())!!
        assertNotNull(registry.applyProgramStatus(clean,programStatus(false)))
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `Program status requested before navigation cannot mark new projection clean`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,0,true)).asJsonObject)
        val old=registry.beginProgramStatus(HALL_ID.toString())!!
        switchTo(registry,OTHER_STAGE_ID,false)
        assertNull(registry.applyProgramStatus(old,programStatus(false)))
        assertTrue(registry.saveDirty(HALL_ID.toString())!!)
    }
    @Test fun `HallRegistry preserves envelope and legacy update invalidates graph`() {
        val registry = HallRegistry()
        val hall = registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        assertEquals(EPOCH_A, hall.stage!!.workspaceEpoch)
        assertEquals(0, hall.stage.workingRevision)
        val store = registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION), snapshot(revision = 0))
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 1)).asJsonObject, fence)
        assertEquals(GraphSyncStatus.STALE, registry.graph(HALL_ID.toString())!!.status)
        assertEquals(1, registry.graph(HALL_ID.toString())!!.stageRevision)
    }

    @Test fun `HallRegistry authoritative reload accepts epoch change and drops snapshot`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION), snapshot(revision = 0))
        registry.install(JsonParser.parseString(hallJson(EPOCH_B, 0)).asJsonObject)
        assertEquals(EPOCH_B, registry.graph(HALL_ID.toString())!!.stageEpoch)
        assertNull(registry.graph(HALL_ID.toString())!!.snapshot)
    }

    @Test fun `fenced CRUD response accepts a new epoch and dirty block projection`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject)
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_B, 1, dirty = true, withBlock = true)).asJsonObject,
            fence,
        )

        assertNotNull(returned)
        assertEquals(EPOCH_B, returned!!.stage!!.workspaceEpoch)
        assertEquals(1, returned.stage.workingRevision)
        assertTrue(returned.stage.dirty)
        assertEquals(BlockPos(2, 1, 1), returned.stage.blocks.single().pos)
        assertEquals(EPOCH_B, registry.graph(HALL_ID.toString())!!.stageEpoch)
        assertEquals(1, registry.graph(HALL_ID.toString())!!.stageRevision)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(HALL_ID.toString())!!.status)
    }

    @Test fun `same epoch newer revision remains a normal legacy update`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 2)).asJsonObject)
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_A, 3, withBlock = true)).asJsonObject,
            fence,
        )

        assertEquals(3, returned!!.stage!!.workingRevision)
        assertEquals(EPOCH_A, returned.stage.workspaceEpoch)
        assertEquals(GraphSyncStatus.STALE, registry.graph(HALL_ID.toString())!!.status)
    }

    @Test fun `domain relative CRUD response is projected into active Hall coordinates`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,0)).asJsonObject)
        val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val response=JsonParser.parseString(stageJson(EPOCH_A,1,withBlock=true)).asJsonObject
        response.add("hall_id",com.google.gson.JsonNull.INSTANCE)
        response.getAsJsonArray("blocks")[0].asJsonObject.addProperty("x",999)

        val updated=registry.updateStageFromDomainResponse(response,fence)

        assertNotNull(updated)
        assertEquals(BlockPos(2,1,1),updated!!.stage!!.blocks.single().pos)
        assertEquals(GraphSyncStatus.STALE,registry.graph(HALL_ID.toString())!!.status)
    }

    @Test fun `stale revision is rejected instead of returning a renderable old Hall`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 5)).asJsonObject)
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_A, 4, withBlock = true)).asJsonObject,
            fence,
        )

        assertNull(returned)
        assertEquals(5, registry.hall(HALL_ID.toString())!!.stage!!.workingRevision)
        assertTrue(registry.hall(HALL_ID.toString())!!.stage!!.blocks.isEmpty())
    }

    @Test fun `late old epoch response cannot roll HallRegistry lifecycle backward`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val oldFence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val switched = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_B, 1, withBlock = true)).asJsonObject,
            oldFence,
        )
        assertNotNull(switched)

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_A, 9)).asJsonObject,
            oldFence,
        )
        assertNull(returned)
        assertEquals(EPOCH_B, registry.graph(HALL_ID.toString())!!.stageEpoch)
        assertEquals(1, registry.hall(HALL_ID.toString())!!.stage!!.workingRevision)
    }

    @Test fun `epoch switch is rejected when its request fence is no longer current`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val staleFence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val currentFence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 1)).asJsonObject, currentFence)

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_B, 1, withBlock = true)).asJsonObject,
            staleFence,
        )

        assertNull(returned)
        assertEquals(EPOCH_A, registry.hall(HALL_ID.toString())!!.stage!!.workspaceEpoch)
        assertEquals(EPOCH_A, registry.graph(HALL_ID.toString())!!.stageEpoch)
    }

    @Test fun `authoritative Hall reload accepts new lifecycle only under its captured fence`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = true)).asJsonObject)
        val oldFence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val reloaded = registry.authoritativeReload(
            JsonParser.parseString(hallJson(EPOCH_B, 0, dirty = false)).asJsonObject,
            oldFence,
        )
        val late = registry.authoritativeReload(
            JsonParser.parseString(hallJson(EPOCH_A, 9, dirty = true)).asJsonObject,
            oldFence,
        )

        assertNotNull(reloaded)
        assertFalse(reloaded!!.stage!!.dirty)
        assertNull(late)
        assertEquals(EPOCH_B, registry.hall(HALL_ID.toString())!!.stage!!.workspaceEpoch)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(HALL_ID.toString())!!.status)
    }

    @Test fun `save dirty ignores graph dirty and uses Python Program state`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_A, revision = 0, dirty = true))
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)

        store.legacyStageUpdated(testKey(STAGE_ID), EPOCH_A, 0)

        assertEquals(GraphSyncStatus.STALE, registry.graph(HALL_ID.toString())!!.status)
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `authoritative reconnect discards same epoch dirty graph snapshot before clean fallback`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = true)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_A, revision = 0, dirty = true))
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val reloaded = registry.authoritativeReload(
            JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject,
            fence,
        )

        assertNotNull(reloaded)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(HALL_ID.toString())!!.status)
        assertNull(registry.graph(HALL_ID.toString())!!.snapshot)
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `SAVE response permits same revision clean graph reconciliation`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 4, dirty = true)).asJsonObject)
        val store = registry.graphProjections
        store.apply(
            store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION),
            snapshot(epoch = EPOCH_A, revision = 4, dirty = true),
        )
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val saved = registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_A, 4, dirty = false)).asJsonObject,
            fence,
        )

        assertNotNull(saved)
        assertFalse(saved!!.stage!!.dirty)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(HALL_ID.toString())!!.status)
        assertNull(registry.graph(HALL_ID.toString())!!.snapshot)

        val applied = store.apply(
            store.begin(testKey(STAGE_ID), GraphRequestKind.RECONCILIATION),
            snapshot(epoch = EPOCH_A, revision = 4, dirty = false),
        )
        assertInstanceOf(GraphApplyResult.Applied::class.java, applied)
        assertEquals(GraphSyncStatus.SYNCED, registry.graph(HALL_ID.toString())!!.status)
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `HallRegistry resolves the Hall owning a Stage for graph status projection`() {
        val registry = HallRegistry()
        val installed = registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        assertSame(installed, registry.hall(HALL_ID.toString()))
        assertNull(registry.hall(OTHER_STAGE_ID.toString()))
    }

    private fun switchTo(registry: HallRegistry, id: UUID, dirty: Boolean = true) {
        val token=registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID)!!
        assertTrue(registry.bindStageSwitch(token,EPOCH_B,EPOCH_A,0,id))
        val old=registry.hall(HALL_ID.toString())!!.stage!!
        registry.graphProjections.authoritativeStageReconciliation(
            registry.hall(HALL_ID.toString())!!.projectionKey(id),old.workspaceEpoch,old.workingRevision)
        assertNotNull(registry.commitStageSwitch(token,EPOCH_B,EPOCH_A,0,
            old.copy(id=id.toString(),dirty=dirty)))
    }

    @Test fun `child SAVE projects domain coordinates and reloads clean graph`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,4)).asJsonObject)
        switchTo(registry,OTHER_STAGE_ID)
        val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val response=JsonParser.parseString(stageJson(EPOCH_A,4,false,true)).asJsonObject
        response.addProperty("stage_id",OTHER_STAGE_ID.toString())
        response.add("hall_id",com.google.gson.JsonNull.INSTANCE)
        response.getAsJsonObject("origin").addProperty("x",999)
        val saved=registry.updateStageAfterSave(response,fence)
        assertNotNull(saved)
        assertEquals(OTHER_STAGE_ID.toString(),saved!!.stage!!.id)
        assertEquals(BlockPos(2,1,1),saved.stage!!.blocks.single().pos)
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
        val store=registry.graphProjections
        assertInstanceOf(GraphApplyResult.Applied::class.java,store.apply(
            store.begin(testKey(OTHER_STAGE_ID),GraphRequestKind.RECONCILIATION),
            snapshot(epoch=EPOCH_A,revision=4,dirty=false).copy(stageId=OTHER_STAGE_ID)))
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `SAVE response is rejected during navigation and after returning to same Stage`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,4)).asJsonObject)
        val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        val response=JsonParser.parseString(stageJson(EPOCH_A,4,false)).asJsonObject
        val pending=registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID)!!
        assertNull(registry.updateStageAfterSave(response,fence))
        registry.failStageSwitch(pending)
        switchTo(registry,OTHER_STAGE_ID)
        assertNull(registry.updateStageAfterSave(response,fence))
        switchTo(registry,STAGE_ID)
        assertNull(registry.updateStageAfterSave(response,fence))
        assertFalse(registry.isSaveFenceCurrent(fence))
        assertTrue(registry.hall(HALL_ID.toString())!!.stage!!.dirty)
    }

    @Test fun `SAVE cannot clear newer edits or replace a changed epoch`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,4)).asJsonObject)
        val fence=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A,5)).asJsonObject,fence)
        assertNull(registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_A,4,false)).asJsonObject,fence))
        val current=registry.stageUpdateFenceForHall(HALL_ID.toString())!!
        assertNull(registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_B,5,false)).asJsonObject,current))
        assertTrue(registry.saveDirty(HALL_ID.toString())!!)
    }

    @Test fun `SAVE captures latest Graph revision after wire edits`() {
        val registry=HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A,0,false)).asJsonObject)
        val store=registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID),GraphRequestKind.RECONCILIATION),
            snapshot(epoch=EPOCH_A,revision=2,dirty=true))
        val fence=registry.saveFenceForHall(HALL_ID.toString())!!
        assertEquals(2,fence.workingRevision)
        assertTrue(registry.isSaveFenceCurrent(fence))
        assertNotNull(registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_A,2,false)).asJsonObject,fence))
        assertFalse(registry.saveDirty(HALL_ID.toString())!!)
    }

    private fun hallJson(epoch: UUID, revision: Long, dirty: Boolean = true) = """
      {"hall_id":"$HALL_ID","program_id":"$EPOCH_B","working_copy_id":"$WORKING_COPY_ID","hall_name":"Hall","owner_uuid":"$PLAYER_ID","owner_name":"Owner",
       "world_uuid":"$WORLD_ID","material_name":"STONE","min_x":0,"min_y":0,"min_z":0,
       "max_x":10,"max_y":10,"max_z":10,"door":{"x":0,"y":1,"z":1},"orientation":"NORTH",
       "controls":[],"stage":${stageJson(epoch, revision, dirty)}}
    """.trimIndent()

    private fun stageJson(epoch: UUID, revision: Long, dirty: Boolean = true, withBlock: Boolean = false): String {
        val blocks = if (withBlock) """
          [{"instance_id":"00000000-0000-0000-0000-000000000099","type_key":"START",
            "x":2,"y":1,"z":1,"rel_x":1,"rel_y":0,"rel_z":0,
            "orientation":"EAST","label":"START","description":""}]
        """.trimIndent() else "[]"
        return """
      {"stage_id":"$STAGE_ID","hall_id":"$HALL_ID","origin":{"x":1,"y":1,"z":1},
       "size":{"x":7,"y":5,"z":7},"dirty":$dirty,"program_dirty":$dirty,"workspace_epoch":"$epoch",
       "working_revision":$revision,"blocks":$blocks}
    """.trimIndent()
    }
}
