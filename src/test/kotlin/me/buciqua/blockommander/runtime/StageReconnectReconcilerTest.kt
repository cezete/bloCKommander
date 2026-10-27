package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture

class StageReconnectReconcilerTest {
    @Test fun `empty clean authoritative Stage removes stale block projection before graph and save`() {
        val registry = HallRegistry()
        registry.install(hall(EPOCH_A, 4, dirty = true, blocks = fourStarts()))
        val fence = registry.stageUpdateFenceForHall(HALL_ID)!!
        val events = mutableListOf<String>()
        var projectedBlocks = -1
        var projectedDirty: Boolean? = null
        val reconciler = StageReconnectReconciler(
            registry,
            projectStage = { events += "stage"; projectedBlocks = it.stage!!.blocks.size },
            reconcileGraphAndWire = { events += "graph-wire"; CompletableFuture.completedFuture(ReconnectGraphResult(true)) },
            projectSaveState = { _, dirty -> events += "save"; projectedDirty = dirty },
        )

        assertTrue(reconciler.reconcile(hall(EPOCH_B, 0, dirty = false), fence))

        assertEquals(listOf("stage", "graph-wire", "save"), events)
        assertEquals(0, projectedBlocks)
        assertEquals(false, projectedDirty)
    }

    @Test fun `saved Stage projects its blocks then graph wire then clean save state under new epoch`() {
        val registry = HallRegistry()
        registry.install(hall(EPOCH_A, 4, dirty = false, blocks = startStop()))
        val fence = registry.stageUpdateFenceForHall(HALL_ID)!!
        val events = mutableListOf<String>()
        var projectedTypes = emptyList<String>()
        var projectedDirty: Boolean? = null
        val reconciler = StageReconnectReconciler(
            registry,
            projectStage = { events += "stage"; projectedTypes = it.stage!!.blocks.map(StageBlock::type) },
            reconcileGraphAndWire = { stage ->
                events += "graph-wire:${stage.workspaceEpoch}"
                CompletableFuture.completedFuture(ReconnectGraphResult(true))
            },
            projectSaveState = { _, dirty -> events += "save"; projectedDirty = dirty },
        )

        assertTrue(reconciler.reconcile(hall(EPOCH_B, 0, dirty = false, blocks = startStop()), fence))

        assertEquals(listOf("stage", "graph-wire:$EPOCH_B", "save"), events)
        assertEquals(listOf("START", "STOP"), projectedTypes)
        assertEquals(false, projectedDirty)
    }

    @Test fun `late authoritative response cannot project after a newer lifecycle won`() {
        val registry = HallRegistry()
        registry.install(hall(EPOCH_A, 0, dirty = false))
        val oldFence = registry.stageUpdateFenceForHall(HALL_ID)!!
        var projections = 0
        val reconciler = StageReconnectReconciler(
            registry,
            projectStage = { projections++ },
            reconcileGraphAndWire = { CompletableFuture.completedFuture(ReconnectGraphResult(true)) },
            projectSaveState = { _, _ -> },
        )
        assertTrue(reconciler.reconcile(hall(EPOCH_B, 0, dirty = false), oldFence))

        assertFalse(reconciler.reconcile(hall(EPOCH_A, 9, dirty = true, blocks = fourStarts()), oldFence))

        assertEquals(1, projections)
        assertEquals(EPOCH_B, registry.hallForStage(STAGE_ID)!!.stage!!.workspaceEpoch)
    }

    @Test fun `partial failures are diagnosed and never project an unaccepted Stage`() {
        val registry = HallRegistry()
        registry.install(hall(EPOCH_A, 0, dirty = false))
        val fence = registry.stageUpdateFenceForHall(HALL_ID)!!
        val diagnostics = mutableListOf<String>()
        var stageProjections = 0
        var saveDirty: Boolean? = null
        val reconciler = StageReconnectReconciler(
            registry,
            projectStage = { stageProjections++ },
            reconcileGraphAndWire = {
                CompletableFuture.completedFuture(ReconnectGraphResult(false, "GRAPH_HTTP_503"))
            },
            projectSaveState = { _, dirty -> saveDirty = dirty },
            diagnose = diagnostics::add,
        )

        assertTrue(reconciler.reconcile(hall(EPOCH_B, 1, dirty = true), fence))
        assertEquals(1, stageProjections)
        assertEquals(true, saveDirty)
        assertTrue(diagnostics.contains("GRAPH_HTTP_503"))

        assertFalse(reconciler.reconcile(JsonObject(), null))
        assertEquals(1, stageProjections)
        assertTrue(diagnostics.any { it.startsWith("STAGE_RELOAD_INVALID") })
    }

    private fun hall(epoch: UUID, revision: Long, dirty: Boolean, blocks: String = "[]") = JsonParser.parseString("""
      {"hall_id":"$HALL_ID","hall_name":"Hall","owner_uuid":"$OWNER_ID","owner_name":"Owner",
       "world_uuid":"$WORLD_ID","material_name":"STONE","min_x":0,"min_y":0,"min_z":0,
       "max_x":12,"max_y":8,"max_z":12,"door":{"x":0,"y":1,"z":1},"orientation":"NORTH",
       "controls":[{"kind":"SAVE_SIGN","x":0,"y":2,"z":2}],
       "stage":{"stage_id":"$STAGE_ID","hall_id":"$HALL_ID","origin":{"x":1,"y":1,"z":1},
       "size":{"x":11,"y":6,"z":11},"dirty":$dirty,"workspace_epoch":"$epoch",
       "working_revision":$revision,"blocks":$blocks}}
    """.trimIndent()).asJsonObject

    private fun fourStarts() = (0 until 4).joinToString(prefix = "[", postfix = "]") { index ->
        block("00000000-0000-0000-0000-00000000010$index", "START", index + 1)
    }

    private fun startStop() = "[${block("00000000-0000-0000-0000-000000000201", "START", 1)}," +
        "${block("00000000-0000-0000-0000-000000000202", "STOP", 3)}]"

    private fun block(id: String, type: String, x: Int) =
        """{"instance_id":"$id","type_key":"$type","x":$x,"y":1,"z":1,"orientation":"EAST","label":"$type","description":""}"""

    companion object {
        private const val HALL_ID = "11111111-1111-1111-1111-111111111111"
        private const val STAGE_ID = "22222222-2222-2222-2222-222222222222"
        private const val OWNER_ID = "33333333-3333-3333-3333-333333333333"
        private const val WORLD_ID = "44444444-4444-4444-4444-444444444444"
        private val EPOCH_A: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val EPOCH_B: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }
}
