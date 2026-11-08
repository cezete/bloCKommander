package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.wire.*
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState

/** Server-thread-only adapter; the same planner and application run in regression tests. */
class MinecraftStageSwitchCommitter(
    level:ServerLevel,registry:HallRegistry,wireProjector:WireWorldProjector=WireWorldProjector(),
):StageSwitchCommitter {
    private val projection=StageSwitchProjection(registry,MinecraftStageSwitchWorld(level),wireProjector)
    override fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch)=projection.commit(token,prepared)
}

private class MinecraftStageSwitchWorld(
    private val level:ServerLevel,
):StageSwitchWorld<BlockState>,WireWorldAccess by MinecraftWireWorldAccess(level) {
    override fun worldUuid()=WorldIdentity.uuid(level)
    override fun readState(position:GraphPosition)=level.getBlockState(position.blockPos())
    override fun restoreState(position:GraphPosition,state:BlockState) {
        level.setBlockAndUpdate(position.blockPos(),state)
    }
    override fun isManaged(position:GraphPosition):Boolean {
        val block=readState(position).block
        return ModContent.typeOf(block)!=null || block===ModContent.CK_WIRE
    }
    private fun programState(block:StageBlock):BlockState {
        val mapped=WorldRenderer.programBlock(block.type)
        require(mapped!==Blocks.BARRIER) { "UNKNOWN_PROGRAM_BLOCK_TYPE" }
        val direction=Direction.valueOf(block.orientation.uppercase())
        require(direction in setOf(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))
        return mapped.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,direction)
    }
    override fun validateProgramBlock(block:StageBlock)=runCatching { programState(block);true }.getOrDefault(false)
    override fun clearManaged(position:GraphPosition) {
        if (isManaged(position)) check(level.setBlockAndUpdate(position.blockPos(),Blocks.AIR.defaultBlockState()))
    }
    override fun placeProgramBlock(block:StageBlock) {
        check(level.setBlockAndUpdate(block.pos,programState(block)))
    }
    override fun renderSaveState(hall:Hall,dirty:Boolean)=WorldRenderer.renderSaveState(level,hall,dirty)
}
