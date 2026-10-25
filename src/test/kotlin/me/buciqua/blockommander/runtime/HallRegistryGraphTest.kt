package me.buciqua.blockommander.runtime

import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.graph.*
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
        registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 1)).asJsonObject)
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

    @Test fun `late legacy Stage response cannot roll HallRegistry epoch backward`() {
        val registry = HallRegistry()
        registry.install(JsonParser.parseString(hallJson(EPOCH_B, 5)).asJsonObject)
        val returned = registry.updateStage(JsonParser.parseString(stageJson(EPOCH_A, 9)).asJsonObject)
        assertEquals(EPOCH_B, returned!!.stage!!.workspaceEpoch)
        assertEquals(EPOCH_B, registry.graph(STAGE_ID.toString())!!.stageEpoch)
        assertEquals(GraphSyncStatus.CONFLICT, registry.graph(STAGE_ID.toString())!!.status)
    }

    private fun hallJson(epoch: UUID, revision: Long) = """
      {"hall_id":"$HALL_ID","hall_name":"Hall","owner_uuid":"$PLAYER_ID","owner_name":"Owner",
       "world_uuid":"$WORLD_ID","material_name":"STONE","min_x":0,"min_y":0,"min_z":0,
       "max_x":10,"max_y":10,"max_z":10,"door":{"x":0,"y":1,"z":1},"orientation":"NORTH",
       "controls":[],"stage":${stageJson(epoch, revision)}}
    """.trimIndent()

    private fun stageJson(epoch: UUID, revision: Long) = """
      {"stage_id":"$STAGE_ID","hall_id":"$HALL_ID","origin":{"x":1,"y":1,"z":1},
       "size":{"x":7,"y":5,"z":7},"dirty":true,"workspace_epoch":"$epoch",
       "working_revision":$revision,"blocks":[]}
    """.trimIndent()
}
