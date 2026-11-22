package me.buciqua.blockommander.runtime

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.SharedConstants
import net.minecraft.commands.CommandSource
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.Bootstrap
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.PermissionSet
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LightDebugCommandTest {
    private val west = BlockPos(47, -6, 196)
    private val east = BlockPos(48, -6, 196)

    @Test
    fun `vanilla outline raycast chooses the first block and returns no target through air`() {
        val world = TestWorld(blocks = mapOf(west to Blocks.GLOWSTONE.defaultBlockState(), east to Blocks.STONE.defaultBlockState()))
        assertEquals(west, world.probe.target(ray(44.5, 49.5)))
        assertNull(world.probe.target(ray(44.5, 46.9)))
        assertTrue(world.lightReads.isEmpty())
    }

    @Test
    fun `samples BLOCK and SKY separately at the actual target`() {
        val world = TestWorld(lights = mapOf((LightLayer.BLOCK to west) to 4, (LightLayer.SKY to west) to 12))
        val reading = world.probe.sample(west, null).single() as LightReading.Available
        assertEquals(west, reading.position)
        assertEquals(Blocks.AIR.defaultBlockState(), reading.state)
        assertEquals(4, reading.blockLight)
        assertEquals(12, reading.skyLight)
        assertEquals(listOf(LightLayer.BLOCK to west, LightLayer.SKY to west), world.lightReads)
    }

    @Test
    fun `location shows signed section Y rather than a world relative section index`() {
        assertEquals("pos=(47,-6,196) chunk=(2,12) sectionY=-1", LightDebugCommand.location(west))
        assertEquals("pos=(-1,-17,-16) chunk=(-1,-1) sectionY=-2", LightDebugCommand.location(BlockPos(-1, -17, -16)))
        assertEquals("pos=(48,-16,196) chunk=(3,12) sectionY=-1", LightDebugCommand.location(BlockPos(48, -16, 196)))
        assertEquals("pos=(48,0,196) chunk=(3,12) sectionY=0", LightDebugCommand.location(BlockPos(48, 0, 196)))
    }

    @Test
    fun `comparison reads independent samples across a chunk boundary without changing state`() {
        val blocks = mutableMapOf(west to Blocks.GLOWSTONE.defaultBlockState(), east to Blocks.AIR.defaultBlockState())
        val lights = mutableMapOf((LightLayer.BLOCK to west) to 15, (LightLayer.BLOCK to east) to 3)
        val originalBlocks = blocks.toMap()
        val originalLights = lights.toMap()
        val world = TestWorld(blocks = blocks, lights = lights)
        val readings = world.probe.sample(west, Direction.EAST).map { it as LightReading.Available }
        assertEquals(listOf(west, east), readings.map { it.position })
        assertEquals(listOf(15, 3), readings.map { it.blockLight })
        assertEquals(listOf(west, east), world.blockReads)
        assertEquals(originalBlocks, blocks)
        assertEquals(originalLights, lights)
    }

    @Test
    fun `raycast stops at first unavailable chunk instead of seeing through it`() {
        val world = TestWorld(blocks = mapOf(east.east(16) to Blocks.STONE.defaultBlockState()), loadedChunkX = setOf(2, 4))
        val error = assertThrows(UnavailableLightChunk::class.java) { world.probe.target(ray(47.5, 65.5)) }
        assertEquals(east, error.position)
        assertEquals(east, world.blockReads.last())
        assertTrue(world.lightReads.isEmpty())
    }

    @Test
    fun `missing comparison chunk preserves first sample and skips light reads for its neighbor`() {
        val world = TestWorld(loadedChunkX = setOf(2))
        val readings = world.probe.sample(west, Direction.EAST)
        assertTrue(readings.first() is LightReading.Available)
        assertEquals(LightReading.Unavailable(east), readings.last())
        assertEquals(listOf(LightLayer.BLOCK to west, LightLayer.SKY to west), world.lightReads)
    }

    @Test
    fun `Brigadier shares ck root and restricts all light commands to admins`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(Commands.literal("ck").then(Commands.literal("ordinary").executes { 1 }))
        dispatcher.register(Commands.literal("ck").then(LightDebugCommand.branch()))
        val admin = source(PermissionSet.ALL_PERMISSIONS)
        val regular = source(PermissionSet.NO_PERMISSIONS)
        val debug = requireNotNull(dispatcher.findNode(listOf("ck", "debug")))
        assertTrue(debug.canUse(admin))
        assertFalse(debug.canUse(regular))
        assertEquals(1, dispatcher.execute("ck ordinary", regular))
        for (syntax in listOf("", " compare", " compare north", " compare south", " compare east", " compare west")) {
            val command = "ck debug light$syntax"
            assertThrows(CommandSyntaxException::class.java) { dispatcher.execute(command, regular) }
            val error = assertThrows(CommandSyntaxException::class.java) { dispatcher.execute(command, admin) }
            assertEquals(CommandSourceStack.ERROR_NOT_PLAYER, error.type)
        }
    }

    private fun ray(startX: Double, endX: Double): ClipContext = ClipContext(
        Vec3(startX, -5.5, 196.5), Vec3(endX, -5.5, 196.5),
        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty(),
    )

    // Permission and player checks run before either world or server is consulted.
    private fun source(permissions: PermissionSet): CommandSourceStack = CommandSourceStack::class.java.getConstructor(
        CommandSource::class.java, Vec3::class.java, Vec2::class.java, ServerLevel::class.java,
        PermissionSet::class.java, Component::class.java, MinecraftServer::class.java,
    ).newInstance(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permissions, Component.literal("diagnostic test"), null)

    private class TestWorld(
        blocks: Map<BlockPos, BlockState> = emptyMap(),
        lights: Map<Pair<LightLayer, BlockPos>, Int> = emptyMap(),
        loadedChunkX: Set<Int> = setOf(2, 3),
    ) {
        val blockReads = mutableListOf<BlockPos>()
        val lightReads = mutableListOf<Pair<LightLayer, BlockPos>>()
        val probe = LightProbe(
            bounds = LevelHeightAccessor.create(-64, 384),
            blockAt = { pos ->
                blockReads += pos.immutable()
                if (Math.floorDiv(pos.x, 16) in loadedChunkX) blocks[pos] ?: Blocks.AIR.defaultBlockState() else null
            },
            blockEntityAt = { null },
            lightAt = { layer, pos ->
                val key = layer to pos
                lightReads += key
                lights[key] ?: 0
            },
        )
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
