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

    @Test fun `resource chain and east-west blockstate are consistent`() {
        val base = root.resolve("src/main/resources")
        val blockstate = json(base.resolve("assets/blockommander/blockstates/ck_wire.json"))
        assertEquals("blockommander:block/ck_wire", blockstate.getAsJsonObject("variants")
            .getAsJsonObject("axis=x")["model"].asString)
        assertEquals(90, blockstate.getAsJsonObject("variants").getAsJsonObject("axis=z")["y"].asInt)
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
