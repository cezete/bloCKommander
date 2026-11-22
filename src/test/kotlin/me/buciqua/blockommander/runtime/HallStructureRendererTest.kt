package me.buciqua.blockommander.runtime

import me.buciqua.blockommander.runtime.graph.HALL_ID
import me.buciqua.blockommander.runtime.graph.WORLD_ID
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

class HallStructureRendererTest {
    @Test
    fun `floor alternates along both axes and keeps the entire rim in the Hall material`() {
        val black = Blocks.CONCRETE.black()
        val white = Blocks.CONCRETE.white()
        assertEquals("minecraft:black_concrete", BuiltInRegistries.BLOCK.getKey(black).toString())
        assertEquals("minecraft:white_concrete", BuiltInRegistries.BLOCK.getKey(white).toString())
        val halls = listOf(hall(BlockPos(-19, -4, -7), 5, 5), hall(BlockPos(13, -1, 15), 8, 11))
        for (hall in halls) {
            val world = RecordingWorld()
            world.render(hall, clearInterior = true)
            for (x in hall.min.x..hall.max.x) for (z in hall.min.z..hall.max.z) {
                val pos = BlockPos(x, hall.min.y, z)
                if (x == hall.min.x || x == hall.max.x || z == hall.min.z || z == hall.max.z) {
                    assertEquals(Blocks.SANDSTONE, world.read(pos).block, "$pos")
                } else {
                    assertTrue(world.read(pos).block in setOf(black, white))
                    if (x + 1 < hall.max.x) assertNotEquals(world.read(pos), world.read(pos.east()))
                    if (z + 1 < hall.max.z) assertNotEquals(world.read(pos), world.read(pos.south()))
                }
            }
            assertEquals(black, world.read(hall.min.offset(1, 0, 1)).block)
        }
    }

    @Test
    fun `first build writes final roof states once and restoring an intact shell does not write them`() {
        val hall = hall(BlockPos(40, -15, 191), 13, 15)
        val world = RecordingWorld()
        val interior = hall.min.offset(2, 1, 2)
        world.cells[interior] = Blocks.DIRT.defaultBlockState()
        world.render(hall, clearInterior = true)
        assertTrue(world.read(interior).isAir)
        val roofWrites = world.writes.filter { it.first.y == hall.max.y }
        val lamps = (hall.min.x + 2 until hall.max.x step 3).flatMap { x ->
            (hall.min.z + 2 until hall.max.z step 3).map { z -> BlockPos(x, hall.max.y, z) }
        }.toSet()
        assertEquals(13 * 15, roofWrites.size)
        assertEquals(roofWrites.size, roofWrites.map { it.first }.toSet().size)
        assertEquals(lamps, roofWrites.filter { it.second.block == Blocks.GLOWSTONE }.map { it.first }.toSet())
        assertEquals(Blocks.GLASS, world.read(hall.min.offset(1, 2, 0)).block)
        assertEquals(Blocks.SANDSTONE, world.read(hall.min.offset(2, 2, 0)).block)
        assertFalse(world.writes.any { it.first == hall.door || it.first == hall.door.above() })

        world.writes.clear()
        world.render(hall, clearInterior = false)
        assertTrue(world.writes.isEmpty())

        val missingLamp = lamps.first()
        world.cells[missingLamp] = Blocks.SANDSTONE.defaultBlockState()
        world.render(hall, clearInterior = false)
        assertEquals(listOf(missingLamp to Blocks.GLOWSTONE.defaultBlockState()), world.writes)
    }

    @Test
    fun `startup shell restore preserves interior work and reloads the same Hall geometry`() {
        val registry = HallRegistry()
        val world = RecordingWorld()
        val inside = BlockPos(2, 1, 2)
        val work = Blocks.CHEST.defaultBlockState()
        world.cells[inside] = work
        val startup = StartupHallProjection(registry, Executor { it.run() }) { world.render(it, clearInterior = false) }
        val fence = startup.begin(WORLD_ID.toString())
        startup.apply(fence, hardeningHall())

        val restored = requireNotNull(registry.hall(HALL_ID.toString()))
        val stage = requireNotNull(restored.stage)
        assertEquals(BlockPos.ZERO, restored.min)
        assertEquals(BlockPos(10, 10, 10), restored.max)
        assertTrue(restored.contains(BlockPos(0, 0, 0)))
        assertTrue(restored.permitsProjection(stage))
        assertTrue(stage.contains(inside))
        assertFalse(stage.contains(BlockPos(2, 0, 2)))
        assertFalse(restored.permitsProjection(stage.copy(origin = BlockPos(0, 1, 1))))
        assertEquals(work, world.read(inside))
        assertFalse(world.writes.any { it.first == inside })
    }

    @Test
    fun `adjacent and stacked Halls keep independent shells and floor parity`() {
        val base = hall(BlockPos(13, -6, 12), 6, 7)
        val east = hall(BlockPos(base.max.x + 1, base.min.y, base.min.z), 5, 7)
        val above = hall(BlockPos(base.min.x, base.max.y + 1, base.min.z), 6, 7)
        val world = RecordingWorld()
        world.render(base, clearInterior = true)
        val baseShell = world.cells.toMap()
        world.writes.clear()
        world.render(east, clearInterior = true)
        world.render(above, clearInterior = true)
        assertTrue(world.writes.none { base.contains(it.first) })
        assertEquals(baseShell, world.cells.filterKeys(base::contains))
        assertEquals(Blocks.CONCRETE.black(), world.read(east.min.offset(1, 0, 1)).block)
        assertEquals(Blocks.CONCRETE.black(), world.read(above.min.offset(1, 0, 1)).block)
    }

    private fun hall(min: BlockPos, width: Int, depth: Int): Hall = HallRegistry().install(hardeningHall()).copy(
        min = min, max = min.offset(width - 1, 4, depth - 1), door = min.offset((width - 1) / 2, 1, 0),
        orientation = "SOUTH", stage = null, material = "minecraft:sandstone",
    )

    private class RecordingWorld {
        val cells = mutableMapOf<BlockPos, BlockState>()
        val writes = mutableListOf<Pair<BlockPos, BlockState>>()
        fun read(pos: BlockPos): BlockState = cells[pos] ?: Blocks.AIR.defaultBlockState()
        fun render(hall: Hall, clearInterior: Boolean) {
            WorldRenderer.renderHallShell(hall, clearInterior, ::read) { pos, state ->
                writes += pos to state
                cells[pos] = state
            }
        }
    }

    companion object {
        @BeforeAll @JvmStatic
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
