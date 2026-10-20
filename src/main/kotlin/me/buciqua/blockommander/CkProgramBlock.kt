package me.buciqua.blockommander

import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition

class CkProgramBlock(properties: BlockBehaviour.Properties) : HorizontalDirectionalBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)) }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.player?.direction ?: Direction.NORTH)

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>) {
        builder.add(FACING)
    }
}
