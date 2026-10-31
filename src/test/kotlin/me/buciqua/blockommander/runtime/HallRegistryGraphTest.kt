package me.buciqua.blockommander.runtime

import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.graph.*
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class HallRegistryGraphTest {
    @Test fun `HallRegistry preserves envelope and legacy update invalidates graph`() {
        val registry = HallRegistry()
        val hall = registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        assertEquals(EPOCH_A, hall.stage!!.workspaceEpoch)
        assertEquals(0, hall.stage.workingRevision)
        val store = registry.graphProjections
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(revision = 0))
        val fence = registry.stageUpdateFence(STAGE_ID.toString())!!
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 1)).asJsonObject, fence)
        assertEquals(GraphSyncStatus.STALE, registry.graph(STAGE_ID.toString())!!.status)
        assertEquals(1, registry.graph(STAGE_ID.toString())!!.stageRevision)
    }

    @Test fun `HallRegistry authoritative reload accepts epoch change and drops snapshot`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(revision = 0))
        registry.install(JsonParser.parseString(hallJson(EPOCH_B, 0)).asJsonObject)
        assertEquals(EPOCH_B, registry.graph(STAGE_ID.toString())!!.stageEpoch)
        assertNull(registry.graph(STAGE_ID.toString())!!.snapshot)
    }

    @Test fun `fenced CRUD response accepts a new epoch and dirty block projection`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject)
        val fence = registry.stageUpdateFence(STAGE_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_B, 1, dirty = true, withBlock = true)).asJsonObject,
            fence,
        )

        assertNotNull(returned)
        assertEquals(EPOCH_B, returned!!.stage!!.workspaceEpoch)
        assertEquals(1, returned.stage.workingRevision)
        assertTrue(returned.stage.dirty)
        assertEquals(BlockPos(2, 1, 1), returned.stage.blocks.single().pos)
        assertEquals(EPOCH_B, registry.graph(STAGE_ID.toString())!!.stageEpoch)
        assertEquals(1, registry.graph(STAGE_ID.toString())!!.stageRevision)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(STAGE_ID.toString())!!.status)
    }

    @Test fun `same epoch newer revision remains a normal legacy update`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 2)).asJsonObject)
        val fence = registry.stageUpdateFence(STAGE_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_A, 3, withBlock = true)).asJsonObject,
            fence,
        )

        assertEquals(3, returned!!.stage!!.workingRevision)
        assertEquals(EPOCH_A, returned.stage.workspaceEpoch)
        assertEquals(GraphSyncStatus.STALE, registry.graph(STAGE_ID.toString())!!.status)
    }

    @Test fun `stale revision is rejected instead of returning a renderable old Hall`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 5)).asJsonObject)
        val fence = registry.stageUpdateFence(STAGE_ID.toString())!!

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_A, 4, withBlock = true)).asJsonObject,
            fence,
        )

        assertNull(returned)
        assertEquals(5, registry.hallForStage(STAGE_ID.toString())!!.stage!!.workingRevision)
        assertTrue(registry.hallForStage(STAGE_ID.toString())!!.stage!!.blocks.isEmpty())
    }

    @Test fun `late old epoch response cannot roll HallRegistry lifecycle backward`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val oldFence = registry.stageUpdateFence(STAGE_ID.toString())!!
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
        assertEquals(EPOCH_B, registry.graph(STAGE_ID.toString())!!.stageEpoch)
        assertEquals(1, registry.hallForStage(STAGE_ID.toString())!!.stage!!.workingRevision)
    }

    @Test fun `epoch switch is rejected when its request fence is no longer current`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        val staleFence = registry.stageUpdateFence(STAGE_ID.toString())!!
        val currentFence = registry.stageUpdateFence(STAGE_ID.toString())!!
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 1)).asJsonObject, currentFence)

        val returned = registry.updateStage(
            JsonParser.parseString(stageJson(EPOCH_B, 1, withBlock = true)).asJsonObject,
            staleFence,
        )

        assertNull(returned)
        assertEquals(EPOCH_A, registry.hallForStage(STAGE_ID.toString())!!.stage!!.workspaceEpoch)
        assertEquals(EPOCH_A, registry.graph(STAGE_ID.toString())!!.stageEpoch)
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
        assertEquals(EPOCH_B, registry.hallForStage(STAGE_ID.toString())!!.stage!!.workspaceEpoch)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(STAGE_ID.toString())!!.status)
    }

    @Test fun `save dirty ignores a stale graph snapshot from the current lifecycle`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_A, revision = 0, dirty = true))
        assertTrue(registry.saveDirty(STAGE_ID.toString())!!)

        store.legacyStageUpdated(STAGE_ID, EPOCH_A, 0)

        assertEquals(GraphSyncStatus.STALE, registry.graph(STAGE_ID.toString())!!.status)
        assertFalse(registry.saveDirty(STAGE_ID.toString())!!)
    }

    @Test fun `authoritative reconnect discards same epoch dirty graph snapshot before clean fallback`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = true)).asJsonObject)
        val store = registry.graphProjections
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_A, revision = 0, dirty = true))
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val reloaded = registry.authoritativeReload(
            JsonParser.parseString(hallJson(EPOCH_A, 0, dirty = false)).asJsonObject,
            fence,
        )

        assertNotNull(reloaded)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(STAGE_ID.toString())!!.status)
        assertNull(registry.graph(STAGE_ID.toString())!!.snapshot)
        assertFalse(registry.saveDirty(STAGE_ID.toString())!!)
    }

    @Test fun `SAVE response permits same revision clean graph reconciliation`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_A, 4, dirty = true)).asJsonObject)
        val store = registry.graphProjections
        store.apply(
            store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION),
            snapshot(epoch = EPOCH_A, revision = 4, dirty = true),
        )
        val fence = registry.stageUpdateFenceForHall(HALL_ID.toString())!!

        val saved = registry.updateStageAfterSave(
            JsonParser.parseString(stageJson(EPOCH_A, 4, dirty = false)).asJsonObject,
            fence,
        )

        assertNotNull(saved)
        assertFalse(saved!!.stage!!.dirty)
        assertEquals(GraphSyncStatus.NOT_REQUESTED, registry.graph(STAGE_ID.toString())!!.status)
        assertNull(registry.graph(STAGE_ID.toString())!!.snapshot)

        val applied = store.apply(
            store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION),
            snapshot(epoch = EPOCH_A, revision = 4, dirty = false),
        )
        assertInstanceOf(GraphApplyResult.Applied::class.java, applied)
        assertEquals(GraphSyncStatus.SYNCED, registry.graph(STAGE_ID.toString())!!.status)
        assertFalse(registry.saveDirty(STAGE_ID.toString())!!)
    }

    @Test fun `HallRegistry resolves the Hall owning a Stage for graph status projection`() {
        val registry = HallRegistry()
        val installed = registry.install(JsonParser.parseString(hallJson(EPOCH_A, 0)).asJsonObject)
        assertSame(installed, registry.hallForStage(STAGE_ID.toString()))
        assertNull(registry.hallForStage(OTHER_STAGE_ID.toString()))
    }

    private fun hallJson(epoch: UUID, revision: Long, dirty: Boolean = true) = """
      {"hall_id":"$HALL_ID","hall_name":"Hall","owner_uuid":"$PLAYER_ID","owner_name":"Owner",
       "world_uuid":"$WORLD_ID","material_name":"STONE","min_x":0,"min_y":0,"min_z":0,
       "max_x":10,"max_y":10,"max_z":10,"door":{"x":0,"y":1,"z":1},"orientation":"NORTH",
       "controls":[],"stage":${stageJson(epoch, revision, dirty)}}
    """.trimIndent()

    private fun stageJson(epoch: UUID, revision: Long, dirty: Boolean = true, withBlock: Boolean = false): String {
        val blocks = if (withBlock) """
          [{"instance_id":"00000000-0000-0000-0000-000000000099","type_key":"START",
            "x":2,"y":1,"z":1,"orientation":"EAST","label":"START","description":""}]
        """.trimIndent() else "[]"
        return """
      {"stage_id":"$STAGE_ID","hall_id":"$HALL_ID","origin":{"x":1,"y":1,"z":1},
       "size":{"x":7,"y":5,"z":7},"dirty":$dirty,"workspace_epoch":"$epoch",
       "working_revision":$revision,"blocks":$blocks}
    """.trimIndent()
    }
}
