package me.buciqua.blockommander

import com.google.gson.JsonParser
import net.minecraft.core.Direction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ProgramBlockIntegrationTest {
    private val root: Path = Path.of(System.getProperty("user.dir"))

    @Test fun `PROGRAM has no physical port in any orientation`() {
        listOf(Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST).forEach { direction ->
            assertEquals(direction,CkProgramPortFace.NONE.facingForPlacement(direction))
            assertNull(CkProgramPortFace.NONE.worldPortDirection(direction))
        }
    }

    @Test fun `PROGRAM block item and CK inventory registration are present`() {
        val source=Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/ModContent.kt"))
        assertTrue(source.contains("val PROGRAM: Block = programBlock(\"program\", SoundType.GLASS)"))
        assertTrue(source.contains("output.accept(PROGRAM)"))
        assertTrue(source.contains("PROGRAM -> \"PROGRAM\""))
        assertTrue(source.contains("else -> playerDirection"),"PROGRAM placement uses the portless default orientation")
    }

    @Test fun `PROGRAM resource chain is complete and visually distinct`() {
        val resources=root.resolve("src/main/resources")
        listOf(
            "assets/blockommander/blockstates/program.json",
            "assets/blockommander/models/block/program.json",
            "assets/blockommander/items/program.json",
            "assets/blockommander/models/item/program.json",
            "data/blockommander/loot_table/blocks/program.json",
        ).forEach { assertTrue(Files.isRegularFile(resources.resolve(it)),"missing $it") }
        val model=JsonParser.parseString(Files.readString(
            resources.resolve("assets/blockommander/models/block/program.json"),
        )).asJsonObject
        assertEquals("minecraft:block/blue_glazed_terracotta",
            model.getAsJsonObject("textures")["all"].asString)
        val blockstate=JsonParser.parseString(Files.readString(
            resources.resolve("assets/blockommander/blockstates/program.json"),
        )).asJsonObject
        assertEquals(4,blockstate.getAsJsonObject("variants").size())
    }

    @Test fun `Stage projection recognizes PROGRAM but performs no domain deletion`() {
        val renderer=Files.readString(
            root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt"),
        )
        assertTrue(renderer.contains("\"PROGRAM\" -> ModContent.PROGRAM"))
        val projection=renderer.substring(
            renderer.indexOf("fun renderStageBlocks"),renderer.indexOf("fun renderSaveState"),
        )
        assertFalse(projection.contains("/api/"))
        assertFalse(projection.contains("delete"))
        assertFalse(projection.contains("ProgramService"))
    }

    @Test fun `LET registry assets and CONTROL sockets share the same facing`() {
        val source=Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/ModContent.kt"))
        assertTrue(source.contains("output.accept(LET)"))
        assertTrue(source.contains("LET -> \"LET\""))
        assertTrue(source.contains("programBlock(\"let\", SoundType.GLASS, CkProgramPortFace.FRONT)"))
        val renderer=Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt"))
        assertTrue(renderer.contains("\"LET\" -> ModContent.LET"))
        val resources=root.resolve("src/main/resources")
        fun json(relative:String)=JsonParser.parseString(Files.readString(resources.resolve(relative))).asJsonObject
        val states=json("assets/blockommander/blockstates/let.json").getAsJsonObject("variants")
        listOf("north","east","south","west").forEachIndexed { index,name ->
            assertEquals(index*90,states.getAsJsonObject("facing=$name").get("y")?.asInt ?: 0)
        }
        val sockets=json("assets/blockommander/models/block/let.json").getAsJsonArray("elements")
            .map { it.asJsonObject }.filter { it.has("name") }.associateBy { it["name"].asString }
        assertEquals("#output",sockets.getValue("front_out").getAsJsonObject("faces").getAsJsonObject("north")["texture"].asString)
        assertEquals("#input",sockets.getValue("back_in").getAsJsonObject("faces").getAsJsonObject("south")["texture"].asString)
        json("assets/blockommander/models/item/let.json")
        json("assets/blockommander/items/let.json")
        json("data/blockommander/loot_table/blocks/let.json")
    }
}
