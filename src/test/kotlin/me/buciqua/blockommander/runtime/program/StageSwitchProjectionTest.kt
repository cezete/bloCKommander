package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.CkWireGeometry
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import me.buciqua.blockommander.runtime.wire.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class StageSwitchProjectionTest {
    private val wirePosition=GraphPosition(2,1,1)
    private val programPosition=GraphPosition(3,1,1)
    private data class Fixture(
        val registry:HallRegistry,val token:StageSwitchToken,val prepared:PreparedStageSwitch,
        val world:RecordingWorld,val committer:StageSwitchProjection<String>,
    )
    private fun fixture(transform:(GraphStateSnapshot)->GraphStateSnapshot={it}):Fixture {
        val registry=HallRegistry(); val hall=registry.install(hardeningHall())
        val token=registry.beginStageSwitch(HALL_ID.toString(),PLAYER_ID)!!
        val target=hall.stage!!.copy(id=OTHER_STAGE_ID.toString(),workingRevision=1,blocks=listOf(
            StageBlock(UUID.randomUUID().toString(),"START",programPosition.blockPos(),"NORTH","START","")))
        val graph=transform(wireSnapshot(1).copy(stageId=OTHER_STAGE_ID))
        assertTrue(registry.prepareStageSwitchProjection(token,PROGRAM_ID,EPOCH_B,1,OTHER_STAGE_ID,EPOCH_A,1))
        val key=hall.projectionKey(OTHER_STAGE_ID); val store=registry.graphProjections
        assertInstanceOf(GraphApplyResult.Applied::class.java,
            store.apply(store.begin(key,GraphRequestKind.RECONCILIATION),graph))
        val prepared=PreparedStageSwitch(target,graph,ProgramSwitchContext(PROGRAM_ID,EPOCH_B,1),true,
            store.view(key)!!.lifecycleGeneration)
        val world=RecordingWorld()
        // This managed source program cell becomes a valid Wire cell in the target.
        world.cells[wirePosition]="CK:START"
        return Fixture(registry,token,prepared,world,StageSwitchProjection(registry,world))
    }

    @Test fun `invalid Wire geometry causes zero writes in the full physical committer`() {
        val f=fixture { it.copy(wireCells=listOf(GraphWireCell(GraphPosition(1,0,0),
            listOf(GraphWireChannel("SINGLE",listOf("NORTH","SOUTH","EAST")))))) }
        assertFalse(f.committer.commit(f.token,f.prepared))
        assertEquals(0,f.world.writes)
    }

    @Test fun `duplicate Wire cell causes zero writes including clears rollback and SAVE board`() {
        val f=fixture { it.copy(wireCells=it.wireCells+it.wireCells) }
        assertFalse(f.committer.commit(f.token,f.prepared))
        assertEquals(0,f.world.writes)
    }

    @Test fun `foreign Wire and program targets both cause zero writes`() {
        for (position in listOf(wirePosition,programPosition)) {
            val f=fixture(); f.world.cells[position]="STONE"
            val before=f.world.cells.toMap()
            assertFalse(f.committer.commit(f.token,f.prepared))
            assertEquals(0,f.world.writes); assertEquals(before,f.world.cells)
        }
    }

    @Test fun `planned program and Wire collision causes zero writes`() {
        val f=fixture { it.copy(wireCells=listOf(it.wireCells.single().copy(position=GraphPosition(2,0,0)))) }
        assertFalse(f.committer.commit(f.token,f.prepared))
        assertEquals(0,f.world.writes)
    }

    @Test fun `successful full switch allows managed source removal before target Wire placement`() {
        val f=fixture()
        assertTrue(f.committer.commit(f.token,f.prepared))
        assertTrue(f.world.cells.getValue(wirePosition).startsWith("WIRE:"))
        assertEquals("CK:START",f.world.cells[programPosition])
        assertEquals(1,f.world.boards)
        assertTrue(f.world.writes>0)
        assertEquals(OTHER_STAGE_ID,f.registry.navigationState(HALL_ID.toString())!!.activeStageId)
    }

    @Test fun `world change discovered on final read prevents every write`() {
        val f=fixture()
        f.world.onRead={ position,seen ->
            if(position==wirePosition && seen==2) f.world.cells[position]="STONE"
        }
        assertFalse(f.committer.commit(f.token,f.prepared))
        assertEquals(0,f.world.writes)
    }

    @Test fun `execution failure still rolls physical state back`() {
        val f=fixture(); val before=f.world.cells.toMap()
        f.world.failPlacement=true
        assertFalse(f.committer.commit(f.token,f.prepared))
        assertEquals(before,f.world.cells)
        assertTrue(f.world.writes>0)
        assertEquals(STAGE_ID,f.registry.navigationState(HALL_ID.toString())!!.activeStageId)
    }

    private class RecordingWorld:StageSwitchWorld<String> {
        val cells=mutableMapOf<GraphPosition,String>()
        private val reads=mutableMapOf<GraphPosition,Int>()
        var writes=0; var boards=0; var failPlacement=false
        var onRead:(GraphPosition,Int)->Unit={_,_->}
        override fun readState(position:GraphPosition):String {
            val seen=reads.merge(position,1,Int::plus)!!
            onRead(position,seen)
            return cells[position] ?: "AIR"
        }
        override fun restoreState(position:GraphPosition,state:String) {
            writes++
            if(state=="AIR") cells.remove(position) else cells[position]=state
        }
        override fun isManaged(position:GraphPosition)=cells[position]?.let {
            it.startsWith("CK:") || it.startsWith("WIRE:")
        } ?: false
        override fun validateProgramBlock(block:StageBlock)=block.type=="START" && block.orientation=="NORTH"
        override fun clearManaged(position:GraphPosition) { writes++;cells.remove(position) }
        override fun placeProgramBlock(block:StageBlock) {
            writes++;cells[GraphPosition(block.pos.x,block.pos.y,block.pos.z)]="CK:${block.type}"
            if(failPlacement) error("write failed")
        }
        override fun renderSaveState(hall:Hall,dirty:Boolean) { writes++;boards++ }
        override fun cell(position:GraphPosition)=when {
            cells[position]==null -> WireWorldCell.REPLACEABLE
            cells.getValue(position).startsWith("WIRE:") -> WireWorldCell.CK_WIRE_UNBOUND
            else -> WireWorldCell.FOREIGN
        }
        override fun geometry(position:GraphPosition)=cells[position]?.takeIf { it.startsWith("WIRE:") }
            ?.removePrefix("WIRE:")?.let(CkWireGeometry::valueOf)
        override fun ckWirePositions(stage:WireStageContext)=cells.keys.filter { cell(it).isCkWire }.toSet()
        override fun setWire(position:GraphPosition,geometry:CkWireGeometry,visualState:WireVisualState) {
            writes++;cells[position]="WIRE:${geometry.name}"
        }
        override fun removeWire(position:GraphPosition) { writes++;cells.remove(position) }
        override fun correct(position:GraphPosition) {}
    }
}
