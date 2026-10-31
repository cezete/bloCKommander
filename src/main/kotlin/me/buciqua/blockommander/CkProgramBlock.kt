package me.buciqua.blockommander

import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition

enum class CkProgramPortFace {
    FRONT, RIGHT, BACK, LEFT, NONE;

    fun facingForPlacement(playerDirection: Direction): Direction = when (this) {
        FRONT -> playerDirection.opposite
        BACK, RIGHT, LEFT, NONE -> playerDirection
    }

    fun worldPortDirection(blockFacing: Direction): Direction? = when (this) {
        FRONT -> blockFacing
        RIGHT -> rotateRight(blockFacing)
        BACK -> blockFacing.opposite
        LEFT -> rotateLeft(blockFacing)
        NONE -> null
    }

    private fun rotateRight(facing: Direction): Direction = when (facing) {
        Direction.NORTH -> Direction.EAST
        Direction.EAST -> Direction.SOUTH
        Direction.SOUTH -> Direction.WEST
        Direction.WEST -> Direction.NORTH
        else -> facing
    }

    private fun rotateLeft(facing: Direction): Direction = when (facing) {
        Direction.NORTH -> Direction.WEST
        Direction.WEST -> Direction.SOUTH
        Direction.SOUTH -> Direction.EAST
        Direction.EAST -> Direction.NORTH
        else -> facing
    }
}

class CkProgramBlock(
    properties: BlockBehaviour.Properties,
    val dominantPortFace: CkProgramPortFace = CkProgramPortFace.NONE,
) : HorizontalDirectionalBlock(properties) {
    init { registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)) }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(
            FACING,
            dominantPortFace.facingForPlacement(context.player?.direction ?: Direction.NORTH),
        )

    fun facingForPlacement(playerDirection: Direction): Direction =
        dominantPortFace.facingForPlacement(playerDirection)

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>) {
        builder.add(FACING)
    }
}
