package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.runtime.wire.*
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot

internal class MinecraftMetadataProjectionWorld(private val level: ServerLevel) :
    MetadataProjectionWorld, WireWorldAccess by MinecraftWireWorldAccess(level) {
    override fun worldUuid() = WorldIdentity.uuid(level)
    override fun blocksMatch(stage: Stage): Boolean {
        val expected = stage.blocks.map { it.pos }.toSet()
        if (expected.size != stage.blocks.size) return false
        for (x in 0 until stage.sizeX) for (y in 0 until stage.sizeY) for (z in 0 until stage.sizeZ) {
            val pos = stage.origin.offset(x, y, z)
            if ((ModContent.typeOf(level.getBlockState(pos).block) != null) != (pos in expected)) return false
        }
        return stage.blocks.all { block ->
        val state = level.getBlockState(block.pos)
        state.block === WorldRenderer.programBlock(block.type) &&
            state.hasProperty(HorizontalDirectionalBlock.FACING) &&
            state.getValue(HorizontalDirectionalBlock.FACING) == Direction.valueOf(block.orientation.uppercase())
        }
    }
    override fun canRenderSaveState(hall: Hall): Boolean = hall.controls.singleOrNull { it.kind == "SAVE_SIGN" }
        ?.let { level.getBlockEntity(it.pos) is SignBlockEntity } == true
    override fun renderSaveState(hall: Hall): Boolean {
        WorldRenderer.renderSaveState(level, hall, hall.programDirty)
        val control = hall.controls.singleOrNull { it.kind == "SAVE_SIGN" } ?: return false
        val sign = level.getBlockEntity(control.pos) as? SignBlockEntity ?: return false
        val expected = listOf("STAGE", "MENTÉS", "", if (hall.programDirty) "NINCS MENTVE" else "MENTVE")
        return listOf(SignTextSlot.FRONT, SignTextSlot.BACK).all { slot ->
            listOf(false, true).all { filtered -> sign.getText(slot).getMessages(filtered).map { it.string } == expected }
        }
    }
}
