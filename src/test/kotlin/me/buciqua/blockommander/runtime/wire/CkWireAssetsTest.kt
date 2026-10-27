package me.buciqua.blockommander.runtime.wire

import com.google.gson.JsonParser
import me.buciqua.blockommander.CkWireBlock
import net.minecraft.world.level.block.Block
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class CkWireAssetsTest {
    private val root: Path = Path.of(System.getProperty("user.dir"))

    @Test fun `CK Wire block item and creative tab registration are present`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/ModContent.kt"))
        assertTrue(source.contains("CkWireBlock"))
        assertTrue(source.contains("val CK_WIRE_ITEM"))
        assertTrue(source.contains("output.accept(CK_WIRE)"))
        assertEquals(Block::class.java, CkWireBlock::class.java.superclass)
        assertTrue(CkWireBlock::class.java.declaredMethods.none {
            it.name.contains("signal", ignoreCase = true) || it.name.contains("redstone", ignoreCase = true)
        })
    }

    @Test fun `resource chain axis and visual variants are consistent`() {
        val base = root.resolve("src/main/resources")
        val blockstate = json(base.resolve("assets/blockommander/blockstates/ck_wire.json"))
        assertEquals("blockommander:block/ck_wire", blockstate.getAsJsonObject("variants")
            .getAsJsonObject("axis=x,visual=cyan")["model"].asString)
        assertEquals(90, blockstate.getAsJsonObject("variants").getAsJsonObject("axis=z,visual=cyan")["y"].asInt)
        assertEquals("blockommander:block/ck_wire_green", blockstate.getAsJsonObject("variants")
            .getAsJsonObject("axis=x,visual=green")["model"].asString)
        assertEquals("blockommander:block/ck_wire_orange", blockstate.getAsJsonObject("variants")
            .getAsJsonObject("axis=x,visual=orange")["model"].asString)
        val model = json(base.resolve("assets/blockommander/models/block/ck_wire.json"))
        val element = model.getAsJsonArray("elements")[0].asJsonObject
        assertEquals(listOf(0, 6, 6), element.getAsJsonArray("from").map { it.asInt })
        assertEquals(listOf(16, 10, 10), element.getAsJsonArray("to").map { it.asInt })
        assertFalse(Files.readString(base.resolve("assets/blockommander/models/block/ck_wire.json")).contains("redstone"))
        listOf(
            "assets/blockommander/items/ck_wire.json",
            "assets/blockommander/models/item/ck_wire.json",
            "data/blockommander/loot_table/blocks/ck_wire.json",
        ).forEach { assertTrue(Files.isRegularFile(base.resolve(it)), "missing $it") }
        listOf("ck_wire_green.json", "ck_wire_orange.json").forEach {
            assertTrue(Files.isRegularFile(base.resolve("assets/blockommander/models/block/$it")))
        }
        val blockSource = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/CkWireBlock.kt"))
        assertTrue(blockSource.contains("BlockStateProperties.HORIZONTAL_AXIS"))
        assertTrue(blockSource.contains("EnumProperty.create(\"visual\""))
        assertTrue(blockSource.contains("builder.add(AXIS, VISUAL)"))
    }

    @Test fun `admin bind command uses the real ServerPlayer identity and existing coordinator`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("Commands.literal(\"ckbind\")"))
        assertTrue(source.contains("Permissions.COMMANDS_ADMIN"))
        assertTrue(source.contains("ServerPlayerIdentity.from(player)"))
        assertTrue(source.contains("coordinator.bind("))
    }

    @Test fun `player join reconciles already loaded current-world Stage wires`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("registry.inWorld(worldUuid).forEach"))
        assertTrue(source.contains("stages.values.forEach { stage -> reconcileWire(player, level, stage) }"))
        assertTrue(source.contains("player.uuid.toString() == hall.ownerUuid"))
    }

    @Test fun `graph operations refresh save sign from authoritative graph snapshot`() {
        val interactions = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        val renderer = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt"))
        assertTrue(interactions.contains("registry.graph(stageId)?.snapshot?.dirty"))
        assertTrue(interactions.contains("WorldRenderer.renderSaveState(level, hall, dirty)"))
        assertTrue(renderer.contains("if (graphDirty ?: (hall.stage?.dirty == true))"))
    }

    @Test fun `wire interactions cancel vanilla placement and break paths`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("held.item === ModContent.CK_WIRE_ITEM"))
        assertTrue(source.contains("level.getBlockState(pos).block === ModContent.CK_WIRE"))
        assertTrue(source.contains("return@register false"))
        assertTrue(source.indexOf("if (stage == null || coordinator == null)") < source.indexOf("coordinator.place("))
    }

    private fun json(path: Path) = JsonParser.parseString(Files.readString(path)).asJsonObject
}
