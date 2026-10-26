package me.buciqua.blockommander

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.util.StringRepresentable
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

enum class CkWireVisualVariant(private val serializedName: String) : StringRepresentable {
    CYAN("cyan"), GREEN("green"), ORANGE("orange");

    override fun getSerializedName(): String = serializedName
}

class CkWireBlock(properties: BlockBehaviour.Properties) : Block(properties) {
    companion object {
        val AXIS: EnumProperty<Direction.Axis> = BlockStateProperties.HORIZONTAL_AXIS
        val VISUAL: EnumProperty<CkWireVisualVariant> = EnumProperty.create("visual", CkWireVisualVariant::class.java)
        private val X_SHAPE: VoxelShape = box(0.0, 6.0, 6.0, 16.0, 10.0, 10.0)
        private val Z_SHAPE: VoxelShape = box(6.0, 6.0, 0.0, 10.0, 10.0, 16.0)
    }

    init {
        registerDefaultState(stateDefinition.any()
            .setValue(AXIS, Direction.Axis.X)
            .setValue(VISUAL, CkWireVisualVariant.CYAN))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AXIS, VISUAL)
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = if (state.getValue(AXIS) == Direction.Axis.X) X_SHAPE else Z_SHAPE
}
