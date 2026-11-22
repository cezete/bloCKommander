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
    fun `rectangular Hall at negative coordinates has two structural rings and a checkerboard`() {
        val hall = hall(BlockPos(-19, -4, -7), 8, 7)
        val black = Blocks.CONCRETE.black()
        val white = Blocks.CONCRETE.white()
        assertEquals("minecraft:black_concrete", BuiltInRegistries.BLOCK.getKey(black).toString())
        assertEquals("minecraft:white_concrete", BuiltInRegistries.BLOCK.getKey(white).toString())
        val world = RecordingWorld()
        world.render(hall, clearInterior = true)
        val expected = listOf("FFFFFFFF", "FPPPPPPF", "FPBWBWPF", "FPWBWBPF", "FPBWBWPF", "FPPPPPPF", "FFFFFFFF")
        val materials = mapOf('F' to Blocks.SANDSTONE, 'P' to Blocks.SANDSTONE, 'B' to black, 'W' to white)
        expected.forEachIndexed { dz, row ->
            row.forEachIndexed { dx, cell ->
                val pos = hall.min.offset(dx, 0, dz)
                assertEquals(materials.getValue(cell), world.read(pos).block, "$cell at $pos")
            }
        }
    }

    @Test
    fun `minimum five by five Hall has one black center tile`() {
        val hall = hall(BlockPos(3, -6, 15), 5, 5)
        val world = RecordingWorld()
        world.render(hall, clearInterior = true)

        val floor = world.cells.filterKeys { it.y == hall.min.y }
        val black = Blocks.CONCRETE.black().defaultBlockState()
        assertEquals(25, floor.size)
        assertEquals(mapOf(hall.min.offset(2, 0, 2) to black), floor.filterValues { it == black })
        assertEquals(24, floor.values.count { it.block == Blocks.SANDSTONE })
    }

    @Test
    fun `fresh ROOT Stage aligns with the checkerboard and Stage changes leave the floor intact`() {
        val hall = hall(BlockPos(-8, -4, 12), 7, 8)
        // The repository seeds a fresh ROOT at relative (2,1,2), with Hall width/depth minus four.
        val root = requireNotNull(HallRegistry().install(hardeningHall()).stage).copy(
            origin = BlockPos(-6, -3, 14), sizeX = 3, sizeY = 3, sizeZ = 4,
        )
        val world = RecordingWorld()
        world.render(hall.copy(stage = root), clearInterior = true)
        assertTrue(hall.permitsProjection(root))
        val floor = world.cells.filterKeys { it.y == hall.min.y }
        val concrete = setOf(Blocks.CONCRETE.black(), Blocks.CONCRETE.white())
        floor.forEach { (pos, state) ->
            assertEquals(root.contains(pos.above()), state.block in concrete, "Floor tile at $pos")
        }

        world.writes.clear()
        val smallerStage = root.copy(origin = root.origin.east(), sizeX = 2, kind = "PROGRAM_BODY")
        assertTrue(hall.permitsProjection(smallerStage))
        world.render(hall.copy(stage = smallerStage), clearInterior = false)
        assertTrue(world.writes.isEmpty())
    }

    @Test
    fun `restore of a legacy checkerboard changes only the inner rim and then does no writes`() {
        val hall = hall(BlockPos(-8, -4, 12), 7, 7)
        val world = RecordingWorld()
        world.render(hall, clearInterior = true)

        val legacyRim = listOf("PPPPP", "P...P", "P...P", "P...P", "PPPPP").flatMapIndexed { z, row ->
            row.mapIndexedNotNull { x, cell -> if (cell == 'P') hall.min.offset(x + 1, 0, z + 1) else null }
        }
        legacyRim.forEach { pos ->
            val concrete = if ((pos.x - hall.min.x + pos.z - hall.min.z) % 2 == 0) Blocks.CONCRETE.black() else Blocks.CONCRETE.white()
            world.cells[pos] = concrete.defaultBlockState()
        }
        world.writes.clear()
        world.render(hall, clearInterior = false)
        assertEquals(legacyRim.toSet(), world.writes.map { it.first }.toSet())
        assertEquals(16, world.writes.size)
        assertTrue(world.writes.all { it.second.block == Blocks.SANDSTONE })

        world.writes.clear()
        world.render(hall, clearInterior = false)
        assertTrue(world.writes.isEmpty())
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
        assertEquals(Blocks.CONCRETE.black(), world.read(east.min.offset(2, 0, 2)).block)
        assertEquals(Blocks.CONCRETE.black(), world.read(above.min.offset(2, 0, 2)).block)
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
