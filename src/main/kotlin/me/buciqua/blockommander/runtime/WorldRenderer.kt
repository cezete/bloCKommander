package me.buciqua.blockommander.runtime

import me.buciqua.blockommander.ModContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf

object WorldRenderer {
    fun buildHall(level: ServerLevel, hall: Hall) {
        val wall = material(hall.material)
        for (x in hall.min.x..hall.max.x) for (y in hall.min.y..hall.max.y) for (z in hall.min.z..hall.max.z) {
            val shell = x == hall.min.x || x == hall.max.x || y == hall.min.y || y == hall.max.y || z == hall.min.z || z == hall.max.z
            level.setBlockAndUpdate(BlockPos(x, y, z), if (shell) wall.defaultBlockState() else Blocks.AIR.defaultBlockState())
        }
        val windowY = hall.min.y + 2
        for (x in hall.min.x + 1 until hall.max.x step 2) for (z in listOf(hall.min.z, hall.max.z))
            level.setBlockAndUpdate(BlockPos(x, windowY, z), Blocks.GLASS.defaultBlockState())
        for (z in hall.min.z + 1 until hall.max.z step 2) for (x in listOf(hall.min.x, hall.max.x))
            level.setBlockAndUpdate(BlockPos(x, windowY, z), Blocks.GLASS.defaultBlockState())
        for (x in hall.min.x + 2 until hall.max.x step 3) for (z in hall.min.z + 2 until hall.max.z step 3)
            level.setBlockAndUpdate(BlockPos(x, hall.max.y, z), Blocks.GLOWSTONE.defaultBlockState())
        renderDoor(level, hall)
        hall.controls.forEach { renderControl(level, hall, it) }
        renderStage(level, hall)
    }

    fun demolish(level: ServerLevel, hall: Hall) {
        for (x in hall.min.x..hall.max.x) for (y in hall.min.y..hall.max.y) for (z in hall.min.z..hall.max.z)
            level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
        hall.controls.forEach { level.setBlockAndUpdate(it.pos, Blocks.AIR.defaultBlockState()) }
    }

    fun renderStage(level: ServerLevel, hall: Hall) {
        val stage = hall.stage ?: return
        for (x in stage.origin.x until stage.origin.x + stage.sizeX)
            for (y in stage.origin.y until stage.origin.y + stage.sizeY)
                for (z in stage.origin.z until stage.origin.z + stage.sizeZ) {
                    val pos = BlockPos(x, y, z)
                    if (ModContent.typeOf(level.getBlockState(pos).block) != null)
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
                }
        stage.blocks.forEach {
            val direction = runCatching { Direction.valueOf(it.orientation.uppercase()) }.getOrDefault(Direction.NORTH)
            level.setBlockAndUpdate(it.pos, block(it.type).defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, direction))
        }
        hall.controls.firstOrNull { it.kind == "SAVE_SIGN" }?.let { renderControl(level, hall, it) }
    }

    private fun renderDoor(level: ServerLevel, hall: Hall) {
        val facing = runCatching { Direction.valueOf(hall.orientation.uppercase()) }.getOrDefault(Direction.SOUTH)
        val lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
        val upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
        level.setBlockAndUpdate(hall.door, lower)
        level.setBlockAndUpdate(hall.door.above(), upper)
    }

    private fun block(type: String): Block = when (type) {
        "START" -> ModContent.START; "STOP" -> ModContent.STOP; "IF" -> ModContent.IF
        "FOR" -> ModContent.FOR; "FUN" -> ModContent.FUN; else -> Blocks.BARRIER
    }

    private fun renderControl(level: ServerLevel, hall: Hall, control: Control) {
        val inward = runCatching { Direction.valueOf(hall.orientation.uppercase()) }.getOrDefault(Direction.SOUTH)
        if (control.kind.endsWith("BUTTON")) {
            val state = Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(HorizontalDirectionalBlock.FACING, inward)
            level.setBlockAndUpdate(control.pos, state)
            return
        }

        val facing = if (control.kind == "OWNER_SIGN") inward.opposite else inward
        val state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing)
        level.setBlockAndUpdate(control.pos, state)
        val lines = when (control.kind) {
            "OWNER_SIGN" -> listOf("Fejlesztői", "csarnok", hall.ownerName.take(15), hall.name.take(15))
            "DESTROY_SIGN" -> listOf("Csarnok", "megszüntetése", "Tulajdonos", "gombja")
            "SAVE_SIGN" -> listOf("STAGE", "MENTÉS", "", if (hall.stage?.dirty == true) "NINCS MENTVE" else "MENTVE")
            else -> listOf("bloCKommander", "", "", "")
        }
        val sign = level.getBlockEntity(control.pos) as? SignBlockEntity ?: return
        val text = SignText.EMPTY.asMutable().apply {
            lines.forEachIndexed { index, line -> setLine(index, Component.literal(line)) }
        }.asImmutable()
        sign.setText(text, SignTextSlot.FRONT)
        sign.setText(text, SignTextSlot.BACK)
        sign.setChanged()
        level.sendBlockUpdated(control.pos, state, state, Block.UPDATE_ALL)
    }

    private fun material(name: String): Block {
        val legacy = when (name.uppercase()) {
            "GOLD_BLOCK" -> Blocks.GOLD_BLOCK; "IRON_BLOCK" -> Blocks.IRON_BLOCK
            "BRICK_BLOCK", "BRICKS" -> Blocks.BRICKS; "OBSIDIAN" -> Blocks.OBSIDIAN
            "DIAMOND_BLOCK" -> Blocks.DIAMOND_BLOCK; "OAK_PLANKS" -> Blocks.OAK_PLANKS
            "COBBLESTONE" -> Blocks.COBBLESTONE; "STONE" -> Blocks.STONE; else -> null
        }
        if (legacy != null) return legacy
        val id = Identifier.tryParse(name.lowercase()) ?: return Blocks.STONE
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(Blocks.STONE)
    }
}
