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
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.AttachFace
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf

object WorldRenderer {
    fun buildHall(level: ServerLevel, hall: Hall, clearInterior: Boolean = false) {
        buildHallStructure(level, hall, clearInterior)
        hall.controls.firstOrNull { it.kind == "SAVE_SIGN" }?.let { renderControl(level, hall, it) }
        renderStage(level, hall)
    }

    fun buildHallStructure(level: ServerLevel, hall: Hall, clearInterior: Boolean = false) {
        renderHallShell(hall, clearInterior, level::getBlockState) { pos, state -> level.setBlockAndUpdate(pos, state) }
        renderDoor(level, hall)
        hall.controls.filterNot { it.kind == "SAVE_SIGN" }.forEach { renderControl(level, hall, it) }
    }

    internal fun renderHallShell(hall: Hall, clearInterior: Boolean, read: (BlockPos) -> BlockState,
                                 write: (BlockPos, BlockState) -> Unit) {
        val wall = material(hall.material).defaultBlockState()
        val lightGray = Blocks.CONCRETE.lightGray().defaultBlockState()
        val white = Blocks.CONCRETE.white().defaultBlockState()
        val glass = Blocks.GLASS.defaultBlockState()
        val glowstone = Blocks.GLOWSTONE.defaultBlockState()
        val air = Blocks.AIR.defaultBlockState()
        val upperDoor = hall.door.above()
        for (x in hall.min.x..hall.max.x) for (y in hall.min.y..hall.max.y) for (z in hall.min.z..hall.max.z) {
            val dx = x - hall.min.x
            val dz = z - hall.min.z
            val xEdge = x == hall.min.x || x == hall.max.x
            val zEdge = z == hall.min.z || z == hall.max.z
            val desired = when {
                y == hall.min.y -> {
                    val floorRim = dx < 2 || hall.max.x - x < 2 || dz < 2 || hall.max.z - z < 2
                    val checkerboardOffset = (dx - 2) + (dz - 2)
                    if (floorRim) wall else if (checkerboardOffset % 2 == 0) lightGray else white
                }
                y == hall.max.y -> if (!xEdge && !zEdge && dx % 3 == 2 && dz % 3 == 2) glowstone else wall
                xEdge || zEdge -> {
                    val window = y == hall.min.y + 2 &&
                        ((zEdge && !xEdge && dx % 2 == 1) || (xEdge && !zEdge && dz % 2 == 1))
                    if (window) glass else wall
                }
                clearInterior -> air
                else -> continue
            }
            val pos = BlockPos(x, y, z)
            if (pos != hall.door && pos != upperDoor && read(pos) != desired) write(pos, desired)
        }
    }

    fun demolish(level: ServerLevel, hall: Hall) {
        for (x in hall.min.x..hall.max.x) for (y in hall.min.y..hall.max.y) for (z in hall.min.z..hall.max.z)
            level.setBlockAndUpdate(BlockPos(x, y, z), Blocks.AIR.defaultBlockState())
        hall.controls.forEach { level.setBlockAndUpdate(it.pos, Blocks.AIR.defaultBlockState()) }
    }

    fun renderStage(level: ServerLevel, hall: Hall) {
        val stage = hall.stage ?: return
        renderStageBlocks(level, hall)
        renderSaveState(level, hall, hall.programDirty)
    }

    fun renderStageBlocks(level: ServerLevel, hall: Hall) {
        val stage = hall.stage ?: return
        require(hall.permitsProjection(stage)) { "STAGE_OUTSIDE_HALL" }
        val desiredPositions = stage.blocks.mapTo(hashSetOf()) { it.pos }
        for (x in stage.origin.x until stage.origin.x + stage.sizeX)
            for (y in stage.origin.y until stage.origin.y + stage.sizeY)
                for (z in stage.origin.z until stage.origin.z + stage.sizeZ) {
                    val pos = BlockPos(x, y, z)
                    if (pos !in desiredPositions && ModContent.typeOf(level.getBlockState(pos).block) != null)
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())
                }
        stage.blocks.forEach {
            val direction = runCatching { Direction.valueOf(it.orientation.uppercase()) }.getOrDefault(Direction.NORTH)
            setIfChanged(level, it.pos, programBlock(it.type).defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, direction))
        }
    }

    fun renderSaveState(level: ServerLevel, hall: Hall, dirty: Boolean) {
        Diagnostics.event("SAVE_SIGN_REQUEST",hall.stage?.let { hall.projectionKey() },
            "dirty" to dirty,"sign_present" to hall.controls.any { it.kind=="SAVE_SIGN" })
        hall.controls.firstOrNull { it.kind == "SAVE_SIGN" }?.let { renderControl(level, hall, it, dirty) }
    }

    private fun renderDoor(level: ServerLevel, hall: Hall) {
        val facing = runCatching { Direction.valueOf(hall.orientation.uppercase()) }.getOrDefault(Direction.SOUTH)
        val lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
        val upper = lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)
        setIfChanged(level, hall.door, lower)
        setIfChanged(level, hall.door.above(), upper)
    }

    internal fun programBlock(type: String): Block = when (type) {
        "START" -> ModContent.START; "STOP" -> ModContent.STOP; "IF" -> ModContent.IF
        "FOR" -> ModContent.FOR; "FUN" -> ModContent.FUN; "PROGRAM" -> ModContent.PROGRAM
        "LET" -> ModContent.LET
        else -> Blocks.BARRIER
    }

    private fun renderControl(level: ServerLevel, hall: Hall, control: Control, graphDirty: Boolean? = null) {
        val inward = runCatching { Direction.valueOf(hall.orientation.uppercase()) }.getOrDefault(Direction.SOUTH)
        if (control.kind.endsWith("BUTTON")) {
            val state = Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(HorizontalDirectionalBlock.FACING, inward)
            setIfChanged(level, control.pos, state)
            return
        }

        val facing = if (control.kind == "OWNER_SIGN") inward.opposite else inward
        val state = Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing)
        setIfChanged(level, control.pos, state)
        val lines = when (control.kind) {
            "OWNER_SIGN" -> listOf("Fejlesztői", "csarnok", hall.ownerName.take(15), hall.name.take(15))
            "DESTROY_SIGN" -> listOf("Csarnok", "megszüntetése", "Tulajdonos", "gombja")
            "SAVE_SIGN" -> listOf(
                "STAGE", "MENTÉS", "",
                if (graphDirty ?: hall.programDirty) "NINCS MENTVE" else "MENTVE",
            )
            else -> listOf("bloCKommander", "", "", "")
        }
        val sign = level.getBlockEntity(control.pos) as? SignBlockEntity ?: run {
            if(control.kind=="SAVE_SIGN") Diagnostics.event("SAVE_SIGN_SKIPPED",hall.stage?.let { hall.projectionKey() },
                "reason" to "SIGN_BLOCK_ENTITY_MISSING")
            return
        }
        val text = SignText.EMPTY.asMutable().apply {
            lines.forEachIndexed { index, line -> setLine(index, Component.literal(line)) }
        }.asImmutable()
        sign.setText(text, SignTextSlot.FRONT)
        sign.setText(text, SignTextSlot.BACK)
        sign.setChanged()
        level.sendBlockUpdated(control.pos, state, state, Block.UPDATE_ALL)
        if(control.kind=="SAVE_SIGN") Diagnostics.event("SAVE_SIGN_RENDERED",hall.stage?.let { hall.projectionKey() },
            "world_uuid" to hall.worldUuid,"dirty" to (graphDirty ?: hall.programDirty),
            "stage_role" to "RENDER_ARGUMENT","displayed_text" to lines.last(),
            "epoch" to hall.stage?.workspaceEpoch,"revision" to hall.stage?.workingRevision)
    }

    private fun setIfChanged(level: ServerLevel, pos: BlockPos, state: BlockState) {
        if (level.getBlockState(pos) != state) level.setBlockAndUpdate(pos, state)
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
