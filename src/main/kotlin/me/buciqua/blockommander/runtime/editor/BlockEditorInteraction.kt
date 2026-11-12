package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.Hall
import me.buciqua.blockommander.runtime.StageBlock
import net.minecraft.core.BlockPos
import java.util.UUID

/** The only editor OPEN target is resolved from a live server registry and the clicked block. */
internal fun resolveBlockEditorTarget(hall: Hall, player: UUID, world: UUID, position: BlockPos,
    physicalType: String?, navigationInProgress: Boolean, editingAllowed: Boolean,
): StageBlock? {
    if (hall.ownerUuid != player.toString() || hall.worldUuid != world.toString() || navigationInProgress || !editingAllowed ||
        hall.id != hall.transportContext.hallId.toString()) return null
    val stage = hall.stage ?: return null
    if (!stage.contains(position)) return null
    return stage.blocks.singleOrNull { it.pos == position && it.type in setOf("START", "STOP") && it.type == physicalType }
}
internal fun permitsLegacyBlockMetadata(block: StageBlock) = block.type !in setOf("START", "STOP")
