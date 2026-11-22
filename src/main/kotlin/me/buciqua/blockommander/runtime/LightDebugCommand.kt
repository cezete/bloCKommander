package me.buciqua.blockommander.runtime

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import me.buciqua.blockommander.BlockCommanderMod
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.HitResult

internal object LightDebugCommand {
    // Eight blocks lets an admin inspect a typical hall ceiling from the floor.
    private const val REACH = 8.0

    fun branch(): LiteralArgumentBuilder<CommandSourceStack> {
        val compare = Commands.literal("compare").executes { measure(it.source, Direction.EAST) }
        for (direction in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            compare.then(Commands.literal(direction.serializedName).executes { measure(it.source, direction) })
        }
        return Commands.literal("debug")
            .requires { it.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
            .then(Commands.literal("light").executes { measure(it.source, null) }.then(compare))
    }

    private fun measure(source: CommandSourceStack, direction: Direction?): Int {
        val player = source.playerOrException
        val level = player.level()
        check(level.server.isSameThread)

        val chunks = level.chunkSource
        val probe = LightProbe(
            bounds = level,
            blockAt = { pos -> chunks.getChunkNow(chunkX(pos), chunkZ(pos))?.getBlockState(pos) },
            // Only inspect block entities already in memory, without promoting pending NBT.
            blockEntityAt = { pos -> chunks.getChunkNow(chunkX(pos), chunkZ(pos))?.blockEntities?.get(pos) },
            lightAt = { layer, pos -> chunks.lightEngine.getLayerListener(layer).getLightValue(pos) },
        )
        val eye = player.eyePosition
        val end = eye.add(player.getViewVector(1.0f).scale(REACH))
        val context = ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player)
        val dimension = WorldIdentity.dimension(level)

        fun report(line: String) {
            player.sendSystemMessage(Component.literal("[CK fény] $line"))
            BlockCommanderMod.LOGGER.info("[CK-LIGHT-DIAG] {}", line)
        }

        val target = try {
            probe.target(context)
        } catch (unavailable: UnavailableLightChunk) {
            report("dim=$dimension ${location(unavailable.position)} status=RAY_CHUNK_UNAVAILABLE (a célkeresés útjában nincs betöltött chunk)")
            return 0
        }
        if (target == null) {
            report("dim=$dimension status=NO_TARGET (nincs célzott blokk 8 blokkon belül)")
            return 0
        }

        val readings = probe.sample(target, direction)
        readings.forEachIndexed { index, reading ->
            val label = if (index == 0) "A" else "B"
            val prefix = "$label: dim=$dimension ${location(reading.position)}"
            when (reading) {
                is LightReading.Available -> report(
                    "$prefix block=${BuiltInRegistries.BLOCK.getKey(reading.state.block)} state=${reading.state} " +
                        "BLOCK=${reading.blockLight} SKY=${reading.skyLight} status=OK",
                )
                is LightReading.Unavailable -> report("$prefix status=CHUNK_UNAVAILABLE (nincs betöltött chunk)")
            }
        }
        return if (readings.all { it is LightReading.Available }) 1 else 0
    }

    fun location(pos: BlockPos): String =
        "pos=(${pos.x},${pos.y},${pos.z}) chunk=(${chunkX(pos)},${chunkZ(pos)}) sectionY=${SectionPos.blockToSectionCoord(pos.y)}"

    private fun chunkX(pos: BlockPos): Int = SectionPos.blockToSectionCoord(pos.x)
    private fun chunkZ(pos: BlockPos): Int = SectionPos.blockToSectionCoord(pos.z)
}

internal class LightProbe(
    private val bounds: LevelHeightAccessor,
    private val blockAt: (BlockPos) -> BlockState?,
    private val blockEntityAt: (BlockPos) -> BlockEntity?,
    private val lightAt: (LightLayer, BlockPos) -> Int,
) {
    private val rayView = object : BlockGetter {
        override fun getHeight(): Int = bounds.getHeight()
        override fun getMinY(): Int = bounds.getMinY()
        override fun getBlockState(pos: BlockPos): BlockState {
            if (isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState()
            return blockAt(pos) ?: throw UnavailableLightChunk(pos.immutable())
        }
        override fun getFluidState(pos: BlockPos) = getBlockState(pos).fluidState
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = blockEntityAt(pos)
    }

    fun target(context: ClipContext): BlockPos? {
        val hit = rayView.clip(context)
        return hit.blockPos.takeIf { hit.type == HitResult.Type.BLOCK }
    }

    fun sample(target: BlockPos, direction: Direction?): List<LightReading> {
        val first = sampleCell(target)
        return if (direction == null) listOf(first) else listOf(first, sampleCell(target.relative(direction)))
    }

    private fun sampleCell(pos: BlockPos): LightReading {
        val state = blockAt(pos) ?: return LightReading.Unavailable(pos)
        return LightReading.Available(pos, state, lightAt(LightLayer.BLOCK, pos), lightAt(LightLayer.SKY, pos))
    }
}

internal class UnavailableLightChunk(val position: BlockPos) : RuntimeException()

internal sealed interface LightReading {
    val position: BlockPos

    data class Available(
        override val position: BlockPos,
        val state: BlockState,
        val blockLight: Int,
        val skyLight: Int,
    ) : LightReading

    data class Unavailable(override val position: BlockPos) : LightReading
}
