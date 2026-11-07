package me.buciqua.blockommander.runtime

import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executor

internal fun hardeningHall(
    context:WorkingCopyTransportContext=TRANSPORT_CONTEXT, stage:UUID=STAGE_ID, world:UUID=WORLD_ID,
)=JsonParser.parseString("""
    {"hall_id":"${context.hallId}","program_id":"${context.programId}","working_copy_id":"${context.workingCopyId}",
     "hall_name":"Hall","owner_uuid":"$PLAYER_ID","owner_name":"Owner","world_uuid":"$world",
     "material_name":"STONE","min_x":0,"min_y":0,"min_z":0,"max_x":10,"max_y":10,"max_z":10,
     "door":{"x":0,"y":1,"z":1},"orientation":"NORTH","controls":[],
     "stage":{"stage_id":"$stage","origin":{"x":1,"y":1,"z":1},"size":{"x":7,"y":5,"z":7},
       "dirty":true,"program_dirty":true,"workspace_epoch":"$EPOCH_A","working_revision":0,"blocks":[]}}
""").asJsonObject

class LifecycleHardeningTest {
    @Test fun `startup unknown unchanged Hall installs and projects once`() {
        val registry=HallRegistry(); val queue=mutableListOf<Runnable>(); var writes=0
        val startup=StartupHallProjection(registry,Executor { queue+=it }) { writes++ }
        val fence=startup.begin(WORLD_ID.toString())
        startup.apply(fence,hardeningHall())
        assertNull(registry.hall(HALL_ID.toString()))
        queue.forEach(Runnable::run)
        assertNotNull(registry.hall(HALL_ID.toString())); assertEquals(1,writes)
    }

    @Test fun `actual startup application cannot reinstall a deleted Hall`() {
        val registry=HallRegistry(); registry.install(hardeningHall())
        val queue=mutableListOf<Runnable>(); var writes=0
        val startup=StartupHallProjection(registry,Executor { queue+=it }) { writes++ }
        val fence=startup.begin(WORLD_ID.toString())
        startup.apply(fence,hardeningHall())
        registry.remove(HALL_ID.toString())
        queue.forEach(Runnable::run)
        assertNull(registry.hall(HALL_ID.toString())); assertEquals(0,writes)
        assertNull(registry.graphProjections.view(testKey(STAGE_ID)))
    }

    @Test fun `actual startup application cannot replace a newer install including initially unknown Hall`() {
        for (known in listOf(false,true)) {
            val registry=HallRegistry()
            if (known) registry.install(hardeningHall())
            val queue=mutableListOf<Runnable>(); var writes=0
            val startup=StartupHallProjection(registry,Executor { queue+=it }) { writes++ }
            val fence=startup.begin(WORLD_ID.toString())
            startup.apply(fence,hardeningHall())
            val current=registry.install(hardeningHall(TRANSPORT_CONTEXT.copy(workingCopyId=UUID.randomUUID())))
            queue.forEach(Runnable::run)
            assertSame(current,registry.hall(HALL_ID.toString())); assertEquals(0,writes)
        }
    }

    @Test fun `outer Wire completion cannot acquire fresh authority after lifecycle or world changes`() {
        for (change in listOf("mount","stage","reinstall","hall-world","level-world")) {
            val registry=HallRegistry(); registry.install(hardeningHall())
            val fence=registry.wireCompletionFence(HALL_ID.toString(),WORLD_ID.toString())!!
            val queue=mutableListOf<Runnable>()
            var physicalWorld=WORLD_ID.toString(); var writes=0; var gets=0; var accepted=0
            val completion=WireProjectionCompletion(registry,Executor { queue+=it },{ physicalWorld }) { writes++;gets++ }
            completion.complete(fence,WireActionResult(WireAction.PLACE,WireActionStatus.SUCCESS,REQUEST_ID)) { accepted++ }
            when(change) {
                "mount" -> registry.install(hardeningHall(TRANSPORT_CONTEXT.copy(workingCopyId=UUID.randomUUID())))
                "stage" -> registry.install(hardeningHall(stage=OTHER_STAGE_ID))
                "reinstall" -> registry.install(hardeningHall())
                "hall-world" -> registry.install(hardeningHall(world=UUID.randomUUID()))
                "level-world" -> physicalWorld=UUID.randomUUID().toString()
            }
            queue.forEach(Runnable::run)
            assertEquals(0,writes,change); assertEquals(0,gets,change); assertEquals(0,accepted,change)
        }
    }

    @Test fun `stale Wire result never refreshes but a valid revision advance does`() {
        val registry=HallRegistry(); registry.install(hardeningHall())
        val fence=registry.wireCompletionFence(HALL_ID.toString(),WORLD_ID.toString())!!
        var writes=0; var gets=0
        val completion=WireProjectionCompletion(registry,Executor(Runnable::run),{ WORLD_ID.toString() }) { writes++;gets++ }
        completion.complete(fence,WireActionResult(WireAction.PLACE,WireActionStatus.STALE,REQUEST_ID))
        assertEquals(0,writes); assertEquals(0,gets)
        val store=registry.graphProjections
        store.apply(store.begin(testKey(STAGE_ID),GraphRequestKind.COMMAND),snapshot(revision=1))
        completion.complete(fence,WireActionResult(WireAction.PLACE,WireActionStatus.SUCCESS,REQUEST_ID))
        assertEquals(1,writes); assertEquals(1,gets)
    }
}
