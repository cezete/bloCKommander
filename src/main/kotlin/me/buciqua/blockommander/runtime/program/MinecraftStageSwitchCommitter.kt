package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.wire.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/** Server-thread-only, rollback-capable physical projection commit. */
class MinecraftStageSwitchCommitter(
    private val level:ServerLevel,
    private val registry:HallRegistry,
    private val wireProjector:WireWorldProjector=WireWorldProjector(),
):StageSwitchCommitter {
    override fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch):Boolean {
        val hall=registry.hall(token.hallId) ?: return false
        val old=hall.stage ?: return false
        val context=prepared.program
        if (!registry.isStageSwitchCurrent(
                token,context.programId,UUID.fromString(prepared.target.id),
                context.workspaceEpoch,context.hierarchyRevision,
            )) return false

        val target=prepared.target
        val affected=linkedSetOf<BlockPos>()
        positions(old).filterTo(affected) { isCk(level.getBlockState(it)) }
        positions(target).filterTo(affected) { isCk(level.getBlockState(it)) }
        target.blocks.mapTo(affected,StageBlock::pos)
        prepared.graph.wireCells.mapTo(affected) {
            target.origin.offset(it.position.x,it.position.y,it.position.z)
        }
        val before=affected.associateWith(level::getBlockState)
        try {
            target.blocks.forEach { block ->
                val existing=level.getBlockState(block.pos)
                require(existing.isAir || isCk(existing)) { "FOREIGN_BLOCK_AT_PROGRAM_POSITION" }
            }
            (positions(old)+positions(target)).distinct().forEach { pos ->
                if (isCk(level.getBlockState(pos))) check(level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState()))
            }
            target.blocks.forEach { block ->
                val direction=runCatching { Direction.valueOf(block.orientation.uppercase()) }
                    .getOrDefault(Direction.NORTH)
                val mapped=WorldRenderer.programBlock(block.type)
                require(mapped!==Blocks.BARRIER) { "UNKNOWN_PROGRAM_BLOCK_TYPE" }
                val state=mapped.defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING,direction)
                check(level.setBlockAndUpdate(block.pos,state))
            }
            val wire=wireProjector.apply(
                WireStageContext(
                    UUID.fromString(target.id),target.origin.graphPosition(),
                    GraphPosition(target.sizeX,target.sizeY,target.sizeZ),target.workspaceEpoch,
                ),prepared.graph.workingRevision,prepared.graph,MinecraftWireWorldAccess(level),
            )
            check(wire.complete) { "WIRE_PROJECTION_FAILED" }
            WorldRenderer.renderSaveState(level,hall,prepared.programDirty)
            val committed=registry.commitStageSwitch(
                token,context.programId,context.workspaceEpoch,context.hierarchyRevision,target,prepared.programDirty,
            )!=null
            check(committed) { "SWITCH_GENERATION_CHANGED" }
            return true
        } catch (_:Exception) {
            before.forEach { (pos,state) -> level.setBlockAndUpdate(pos,state) }
            runCatching { WorldRenderer.renderSaveState(level,hall,hall.programDirty) }
            registry.failStageSwitch(token)
            return false
        }
    }

    private fun positions(stage:Stage)=sequence {
        for (x in stage.origin.x until stage.origin.x+stage.sizeX)
            for (y in stage.origin.y until stage.origin.y+stage.sizeY)
                for (z in stage.origin.z until stage.origin.z+stage.sizeZ) yield(BlockPos(x,y,z))
    }
    private fun isCk(state:BlockState)=ModContent.typeOf(state.block)!=null || state.block===ModContent.CK_WIRE
    private fun BlockPos.graphPosition()=GraphPosition(x,y,z)
}
