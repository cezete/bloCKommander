package me.buciqua.blockommander.runtime

import io.netty.buffer.Unpooled
import me.buciqua.blockommander.BlockCommanderMod
import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.graph.*
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class FloorOutlineTest {
    private val overworld = Identifier.withDefaultNamespace("overworld")
    private val nether = Identifier.withDefaultNamespace("the_nether")
    private val lightGray = Identifier.withDefaultNamespace("light_gray_concrete")
    private val white = Identifier.withDefaultNamespace("white_concrete")
    private val bounds = FloorBounds(-9, -2, -17, -11)
    private val view = FloorOutlineView(HALL_ID, -4, bounds, FloorBounds(-7, -4, -15, -13))
    private val viewer = BlockPos(-8, -3, -16)

    @Test
    fun `only correctly colored floor tiles within both boundaries are emphasized`() {
        assertTrue(view.highlights(BlockPos(-7, -4, -15), lightGray, viewer))
        assertTrue(view.highlights(BlockPos(-6, -4, -14), lightGray, viewer))
        assertTrue(view.highlights(BlockPos(-4, -4, -13), white, viewer))
        assertFalse(view.highlights(BlockPos(-7, -4, -15), white, viewer))
        assertFalse(view.highlights(BlockPos(-7, -4, -15), Identifier.withDefaultNamespace("black_concrete"), viewer))
        assertFalse(view.highlights(BlockPos(-7, -4, -15), lightGray, viewer.east(12)))

        val narrower = view.copy(stage = FloorBounds(-6, -5, -15, -14))
        assertFalse(narrower.highlights(BlockPos(-7, -4, -15), lightGray, viewer))
        assertTrue(narrower.highlights(BlockPos(-6, -4, -15), white, viewer))
        val small = FloorOutlineView(HALL_ID, 3, FloorBounds(0, 4, 0, 4), FloorBounds(2, 2, 2, 2))
        assertTrue(small.highlights(BlockPos(2, 3, 2), lightGray, BlockPos(1, 4, 2)))
    }

    @Test
    fun `all four aisle sides and corners are excluded even with a wider Stage`() {
        val wide = view.copy(stage = FloorBounds(-8, -3, -16, -12))
        for (x in -8..-3) for (z in -16..-12) {
            val pos = BlockPos(x, -4, z)
            val tile = if ((x + 9 + z + 17) % 2 == 0) lightGray else white
            assertEquals(x in -7..-4 && z in -15..-13, wide.highlights(pos, tile, viewer), "$pos")
        }
        assertFalse(wide.highlights(BlockPos(-9, -4, -17), lightGray, viewer))
        assertFalse(wide.highlights(BlockPos(-10, -4, -18), lightGray, viewer))
    }

    @Test
    fun `walls roof program blocks and Wire keep their vanilla outline`() {
        assertFalse(view.highlights(BlockPos(-9, -3, -15), lightGray, viewer))
        assertFalse(view.highlights(BlockPos(-7, 0, -15), lightGray, viewer))
        assertFalse(view.highlights(BlockPos(-7, -4, -15), Identifier.withDefaultNamespace("stone"), viewer))
        for (block in listOf(BlockCommanderMod.id("start"), BlockCommanderMod.id("let"), BlockCommanderMod.id("ck_wire"))) {
            assertFalse(view.highlights(BlockPos(-7, -3, -15), block, viewer))
            assertFalse(view.highlights(BlockPos(-7, -4, -15), block, viewer))
        }
    }

    @Test
    fun `server view follows committed Stage and disambiguates stacked Halls`() {
        val registry = HallRegistry()
        val lower = registry.install(hardeningHall())
        val upperId = OTHER_STAGE_ID
        val upperData = hardeningHall(context = TRANSPORT_CONTEXT.copy(hallId = upperId)).apply {
            addProperty("min_y", 11)
            addProperty("max_y", 21)
            getAsJsonObject("door").addProperty("y", 12)
            getAsJsonObject("stage").getAsJsonObject("origin").addProperty("y", 12)
        }
        registry.install(upperData)
        val world = WORLD_ID.toString()
        val lowerViewer = BlockPos(1, 1, 1)
        assertEquals(HALL_ID, registry.floorOutlineView(world, lowerViewer)?.hallId)
        assertEquals(upperId, registry.floorOutlineView(world, BlockPos(1, 12, 1))?.hallId)
        assertNull(registry.floorOutlineView("other dimension", lowerViewer))
        assertNull(registry.floorOutlineView(world, BlockPos(11, 1, 1)))

        val token = requireNotNull(registry.beginStageSwitch(lower.id, PLAYER_ID))
        assertNull(registry.floorOutlineView(world, lowerViewer))
        val target = requireNotNull(lower.stage).copy(id = OTHER_STAGE_ID.toString(), origin = BlockPos(3, 1, 4), sizeX = 3, sizeZ = 4)
        assertTrue(registry.prepareStageSwitchProjection(token, PROGRAM_ID, EPOCH_B, 1, OTHER_STAGE_ID, EPOCH_A, 0))
        assertNotNull(registry.commitStageSwitch(token, PROGRAM_ID, EPOCH_B, 1, target))
        assertEquals(FloorBounds(3, 5, 4, 7), registry.floorOutlineView(world, lowerViewer)?.stage)
        registry.remove(lower.id)
        assertNull(registry.floorOutlineView(world, lowerViewer))
    }

    @Test
    fun `client rejects old connections levels and sequences and clears invalid or removed views`() {
        val fence = EditorClientConnectionFence()
        val cache = FloorOutlineClientState(fence)
        val firstConnection = Any()
        val secondConnection = Any()
        val firstLevel = Any()
        val secondLevel = Any()
        fence.join(firstConnection)
        assertNull(cache.view(firstLevel))
        cache.receive(FloorOutlinePayload(1, overworld, view), firstConnection, firstLevel, firstLevel, overworld)
        assertEquals(view, cache.view(firstLevel))
        val nextHall = view.copy(hallId = OTHER_STAGE_ID)
        cache.receive(FloorOutlinePayload(2, overworld, nextHall), firstConnection, firstLevel, firstLevel, overworld)
        cache.receive(FloorOutlinePayload(1, overworld, view), firstConnection, firstLevel, firstLevel, overworld)
        assertEquals(nextHall, cache.view(firstLevel))
        cache.receive(FloorOutlinePayload(3, overworld, view), firstConnection, firstLevel, secondLevel, nether)
        assertNull(cache.view(secondLevel))
        cache.receive(FloorOutlinePayload(4, nether, view), firstConnection, secondLevel, secondLevel, nether)
        assertEquals(view, cache.view(secondLevel))
        assertNull(cache.view(firstLevel))
        val malformed = view.copy(stage = FloorBounds(-9, -2, -17, -11))
        cache.receive(FloorOutlinePayload(5, nether, malformed), firstConnection, secondLevel, secondLevel, nether)
        assertNull(cache.view(secondLevel))
        cache.receive(FloorOutlinePayload(4, nether, view), firstConnection, secondLevel, secondLevel, nether)
        assertNull(cache.view(secondLevel))

        assertTrue(fence.disconnect(firstConnection))
        cache.reset()
        fence.join(secondConnection)
        cache.receive(FloorOutlinePayload(6, nether, view), firstConnection, secondLevel, secondLevel, nether)
        assertNull(cache.view(secondLevel))
        cache.receive(FloorOutlinePayload(1, nether, nextHall), secondConnection, secondLevel, secondLevel, nether)
        assertEquals(nextHall, cache.view(secondLevel))
        cache.receive(FloorOutlinePayload(2, nether, null), secondConnection, secondLevel, secondLevel, nether)
        assertNull(cache.view(secondLevel))
    }

    @Test
    fun `snapshot and clear packets round trip`() {
        for (value in listOf(FloorOutlinePayload(1, overworld, view), FloorOutlinePayload(2, nether, null))) {
            val buf = RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY)
            try {
                FloorOutlinePayload.CODEC.encode(buf, value)
                assertEquals(value, FloorOutlinePayload.CODEC.decode(buf))
                assertEquals(0, buf.readableBytes())
            } finally {
                buf.release()
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
