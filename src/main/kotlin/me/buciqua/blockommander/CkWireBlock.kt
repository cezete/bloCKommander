package me.buciqua.blockommander

import net.minecraft.core.BlockPos
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.util.StringRepresentable
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

enum class CkWireVisualVariant(private val serializedName: String) : StringRepresentable {
    CYAN("cyan"), GREEN("green"), ORANGE("orange");

    override fun getSerializedName(): String = serializedName
}

enum class CkWireGeometry(private val serializedName: String) : StringRepresentable {
    NONE("none"), NORTH("north"), EAST("east"), SOUTH("south"), WEST("west"),
    NORTH_SOUTH("north_south"), EAST_WEST("east_west"), NORTH_EAST("north_east"),
    EAST_SOUTH("east_south"), SOUTH_WEST("south_west"), WEST_NORTH("west_north"), CROSS("cross");

    override fun getSerializedName(): String = serializedName
}

class CkWireBlock(properties: BlockBehaviour.Properties) : Block(properties) {
    companion object {
        val GEOMETRY: EnumProperty<CkWireGeometry> = EnumProperty.create("geometry", CkWireGeometry::class.java)
        val VISUAL: EnumProperty<CkWireVisualVariant> = EnumProperty.create("visual", CkWireVisualVariant::class.java)
        private val CENTER = box(6.0, 1.0, 6.0, 10.0, 3.0, 10.0)
        private val NORTH = box(6.0, 1.0, 0.0, 10.0, 3.0, 8.0)
        private val EAST = box(8.0, 1.0, 6.0, 16.0, 3.0, 10.0)
        private val SOUTH = box(6.0, 1.0, 8.0, 10.0, 3.0, 16.0)
        private val WEST = box(0.0, 1.0, 6.0, 8.0, 3.0, 10.0)
        private val CROSS_NS = box(6.0, 3.0, 0.0, 10.0, 5.0, 16.0)
        private fun shape(vararg arms: VoxelShape): VoxelShape = arms.fold(CENTER, Shapes::or)
        private val SHAPES = mapOf(
            CkWireGeometry.NONE to CENTER,
            CkWireGeometry.NORTH to shape(NORTH), CkWireGeometry.EAST to shape(EAST),
            CkWireGeometry.SOUTH to shape(SOUTH), CkWireGeometry.WEST to shape(WEST),
            CkWireGeometry.NORTH_SOUTH to shape(NORTH, SOUTH),
            CkWireGeometry.EAST_WEST to shape(EAST, WEST),
            CkWireGeometry.NORTH_EAST to shape(NORTH, EAST),
            CkWireGeometry.EAST_SOUTH to shape(EAST, SOUTH),
            CkWireGeometry.SOUTH_WEST to shape(SOUTH, WEST),
            CkWireGeometry.WEST_NORTH to shape(WEST, NORTH),
            CkWireGeometry.CROSS to shape(EAST, WEST, CROSS_NS),
        )
    }

    init {
        registerDefaultState(stateDefinition.any()
            .setValue(GEOMETRY, CkWireGeometry.EAST_WEST)
            .setValue(VISUAL, CkWireVisualVariant.CYAN))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(GEOMETRY, VISUAL)
    }

    override fun getShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPES.getValue(state.getValue(GEOMETRY))

    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = SHAPES.getValue(state.getValue(GEOMETRY))
}
