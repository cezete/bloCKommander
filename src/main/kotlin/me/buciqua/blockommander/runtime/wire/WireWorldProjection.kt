package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.CkWireBlock
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.GraphStateSnapshot
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.util.UUID

data class WireStageContext(
    val stageId: UUID,
    val origin: GraphPosition,
    val size: GraphPosition,
    val workspaceEpoch: UUID,
)

enum class WireWorldCell { REPLACEABLE, CK_WIRE, FOREIGN }

interface WireWorldAccess {
    fun ckWirePositions(stage: WireStageContext): Set<GraphPosition>
    fun cell(position: GraphPosition): WireWorldCell
    fun setEastWestWire(position: GraphPosition)
    fun removeWire(position: GraphPosition)
    fun correct(position: GraphPosition)
}

data class WireProjectionIssue(val code: String, val position: GraphPosition?, val message: String)
data class WireProjectionResult(
    val rendered: Set<GraphPosition>,
    val removed: Set<GraphPosition>,
    val issues: List<WireProjectionIssue>,
) {
    val complete: Boolean get() = issues.isEmpty()
}

class WireWorldProjector {
    fun apply(
        stage: WireStageContext,
        authoritativeRevision: Long,
        snapshot: GraphStateSnapshot,
        world: WireWorldAccess,
    ): WireProjectionResult {
        if (snapshot.stageId != stage.stageId || snapshot.workspaceEpoch != stage.workspaceEpoch ||
            snapshot.workingRevision != authoritativeRevision) {
            return WireProjectionResult(emptySet(), emptySet(), listOf(
                WireProjectionIssue("STALE_WORLD_PROJECTION", null, "A graph snapshot nem az aktuális Stage lifecycle-hoz tartozik."),
            ))
        }

        val issues = mutableListOf<WireProjectionIssue>()
        val desired = linkedSetOf<GraphPosition>()
        snapshot.wireCells.forEach { cell ->
            val relative = cell.position
            val inside = relative.x in 0 until stage.size.x && relative.y in 0 until stage.size.y &&
                relative.z in 0 until stage.size.z
            val supported = cell.channels.size == 1 && cell.channels.single().channel == "SINGLE" &&
                cell.channels.single().connections.toSet() == setOf("EAST", "WEST")
            val absolute = relative + stage.origin
            when {
                !inside -> issues += WireProjectionIssue(
                    "WIRE_OUTSIDE_STAGE", absolute, "A wire snapshot cella a Stage területén kívül van.",
                )
                !supported -> issues += WireProjectionIssue(
                    "UNSUPPORTED_WIRE_GEOMETRY", absolute, "Csak a SINGLE E–W CK Wire jeleníthető meg B3-ban.",
                )
                else -> desired += absolute
            }
        }

        val rendered = linkedSetOf<GraphPosition>()
        desired.forEach { position ->
            when (world.cell(position)) {
                WireWorldCell.CK_WIRE -> rendered += position
                WireWorldCell.REPLACEABLE -> {
                    world.setEastWestWire(position)
                    rendered += position
                }
                WireWorldCell.FOREIGN -> issues += WireProjectionIssue(
                    "FOREIGN_BLOCK_AT_WIRE_POSITION", position, "A CK Wire nem írhat felül idegen blokkot.",
                )
            }
        }

        val removed = linkedSetOf<GraphPosition>()
        (world.ckWirePositions(stage) - desired).forEach { position ->
            if (world.cell(position) == WireWorldCell.CK_WIRE) {
                world.removeWire(position)
                removed += position
            }
        }
        return WireProjectionResult(rendered, removed, issues)
    }
}

class MinecraftWireWorldAccess(private val level: ServerLevel) : WireWorldAccess {
    override fun ckWirePositions(stage: WireStageContext): Set<GraphPosition> = buildSet {
        for (x in 0 until stage.size.x) for (y in 0 until stage.size.y) for (z in 0 until stage.size.z) {
            val position = stage.origin + GraphPosition(x, y, z)
            if (level.getBlockState(position.blockPos()).block === ModContent.CK_WIRE) add(position)
        }
    }

    override fun cell(position: GraphPosition): WireWorldCell {
        val state = level.getBlockState(position.blockPos())
        return when {
            state.block === ModContent.CK_WIRE -> WireWorldCell.CK_WIRE
            state.isAir -> WireWorldCell.REPLACEABLE
            else -> WireWorldCell.FOREIGN
        }
    }

    override fun setEastWestWire(position: GraphPosition) {
        val changed = level.setBlockAndUpdate(
            position.blockPos(), ModContent.CK_WIRE.defaultBlockState().setValue(CkWireBlock.AXIS, Direction.Axis.X),
        )
        check(changed) { "A CK Wire világvetítés nem írható ki." }
    }

    override fun removeWire(position: GraphPosition) {
        val pos = position.blockPos()
        if (level.getBlockState(pos).block === ModContent.CK_WIRE) {
            check(level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())) { "A CK Wire világvetítés nem távolítható el." }
        }
    }

    override fun correct(position: GraphPosition) {
        val pos = position.blockPos()
        val state = level.getBlockState(pos)
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL)
    }
}

operator fun GraphPosition.plus(other: GraphPosition) = GraphPosition(x + other.x, y + other.y, z + other.z)
operator fun GraphPosition.minus(other: GraphPosition) = GraphPosition(x - other.x, y - other.y, z - other.z)
fun GraphPosition.blockPos() = BlockPos(x, y, z)
