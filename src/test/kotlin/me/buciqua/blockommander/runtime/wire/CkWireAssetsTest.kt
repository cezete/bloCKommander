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

    @Test fun `resource chain geometry and visual variants are consistent`() {
        val base = root.resolve("src/main/resources")
        val blockstate = json(base.resolve("assets/blockommander/blockstates/ck_wire.json"))
        val multipart = blockstate.getAsJsonArray("multipart")
        assertEquals(18, multipart.size())
        assertTrue(multipart.any { part -> part.asJsonObject.getAsJsonObject("when")["geometry"]?.asString == "cross" &&
            part.asJsonObject.getAsJsonObject("apply")["model"].asString == "blockommander:block/ck_wire_cross_ns" })
        assertTrue(multipart.any { part -> part.asJsonObject.getAsJsonObject("when")["geometry"]?.asString
            ?.contains("north_east") == true })
        listOf(
            "assets/blockommander/items/ck_wire.json",
            "assets/blockommander/models/item/ck_wire.json",
            "data/blockommander/loot_table/blocks/ck_wire.json",
        ).forEach { assertTrue(Files.isRegularFile(base.resolve(it)), "missing $it") }
        listOf(
            "ck_wire_center.json", "ck_wire_center_green.json", "ck_wire_center_orange.json",
            "ck_wire_arm.json", "ck_wire_arm_green.json", "ck_wire_arm_orange.json",
            "ck_wire_cross_ns.json", "ck_wire_cross_ns_green.json", "ck_wire_cross_ns_orange.json",
        ).forEach {
            assertTrue(Files.isRegularFile(base.resolve("assets/blockommander/models/block/$it")))
            assertFalse(Files.readString(base.resolve("assets/blockommander/models/block/$it")).contains("redstone"))
        }
        val blockSource = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/CkWireBlock.kt"))
        assertTrue(blockSource.contains("EnumProperty.create(\"geometry\""))
        assertTrue(blockSource.contains("EnumProperty.create(\"visual\""))
        assertTrue(blockSource.contains("builder.add(GEOMETRY, VISUAL)"))
    }

    @Test fun `wire models and selection collision stay close to the floor`() {
        val models = root.resolve("src/main/resources/assets/blockommander/models/block")
        val center = json(models.resolve("ck_wire_center.json")).getAsJsonArray("elements")[0].asJsonObject
        val arm = json(models.resolve("ck_wire_arm.json")).getAsJsonArray("elements")[0].asJsonObject
        val bridge = json(models.resolve("ck_wire_cross_ns.json")).getAsJsonArray("elements")[0].asJsonObject
        assertEquals(listOf(6, 1, 6), center.getAsJsonArray("from").map { it.asInt })
        assertEquals(listOf(10, 3, 10), center.getAsJsonArray("to").map { it.asInt })
        assertEquals(1, arm.getAsJsonArray("from")[1].asInt)
        assertEquals(3, arm.getAsJsonArray("to")[1].asInt)
        assertEquals(3, bridge.getAsJsonArray("from")[1].asInt)
        assertEquals(5, bridge.getAsJsonArray("to")[1].asInt)

        val blockSource = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/CkWireBlock.kt"))
        assertTrue(blockSource.contains("private val CENTER = box(6.0, 1.0, 6.0, 10.0, 3.0, 10.0)"))
        assertTrue(blockSource.contains("private val CROSS_NS = box(6.0, 3.0, 0.0, 10.0, 5.0, 16.0)"))
        assertTrue(blockSource.contains("CkWireGeometry.CROSS to shape(EAST, WEST, CROSS_NS)"))
        assertTrue(blockSource.contains("override fun getCollisionShape("))
    }

    @Test fun `START OUT and STOP IN markers rotate with all four block facings`() {
        val resources = root.resolve("src/main/resources/assets/blockommander")
        val expectedRotations = mapOf("north" to 0, "east" to 90, "south" to 180, "west" to 270)
        listOf("start" to "start_port", "stop" to "stop_port").forEach { (block, marker) ->
            val parts = json(resources.resolve("blockstates/$block.json")).getAsJsonArray("multipart")
            assertEquals(8, parts.size())
            expectedRotations.forEach { (facing, rotation) ->
                val matching = parts.filter { part ->
                    val obj = part.asJsonObject
                    obj.getAsJsonObject("when")["facing"].asString == facing &&
                        obj.getAsJsonObject("apply")["model"].asString == "blockommander:block/$marker"
                }
                assertEquals(1, matching.size, "$block $facing marker")
                assertEquals(rotation, matching.single().asJsonObject.getAsJsonObject("apply")["y"]?.asInt ?: 0)
            }
            val model = json(resources.resolve("models/block/$marker.json"))
            assertTrue(model.getAsJsonArray("elements").size() >= 7)
        }

        val startPort = json(resources.resolve("models/block/start_port.json"))
        val stopPort = json(resources.resolve("models/block/stop_port.json"))
        assertEquals("minecraft:block/cyan_concrete", startPort.getAsJsonObject("textures")["port"].asString)
        assertEquals("minecraft:block/orange_concrete", stopPort.getAsJsonObject("textures")["port"].asString)
        assertEquals(1, startPort.getAsJsonArray("elements")[0].asJsonObject.getAsJsonArray("from")[1].asInt)
        assertEquals(1, stopPort.getAsJsonArray("elements")[0].asJsonObject.getAsJsonArray("from")[1].asInt)
        val startElements = startPort.getAsJsonArray("elements")
        assertTrue(
            startElements[1].asJsonObject.getAsJsonArray("from")[0].asDouble >
                startElements[8].asJsonObject.getAsJsonArray("from")[0].asDouble,
            "OUT strokes must be mirrored in model coordinates for a north-face viewer",
        )
        val stopDiagonal = stopPort.getAsJsonArray("elements").last().asJsonObject.getAsJsonObject("rotation")
        assertEquals(45, stopDiagonal["angle"].asInt, "IN must use a Latin-N diagonal from the south face")
    }

    @Test fun `IF IN 0 1 markers expose FRONT LEFT RIGHT and no BACK port`() {
        val resources = root.resolve("src/main/resources/assets/blockommander")
        val blockstate = json(resources.resolve("blockstates/if.json"))
        val parts = blockstate.getAsJsonArray("multipart")
        val expectedRotations = mapOf("north" to 0, "east" to 90, "south" to 180, "west" to 270)
        assertEquals(8, parts.size())
        expectedRotations.forEach { (facing, rotation) ->
            val marker = parts.filter { part ->
                val obj = part.asJsonObject
                obj.getAsJsonObject("when")["facing"].asString == facing &&
                    obj.getAsJsonObject("apply")["model"].asString == "blockommander:block/if_ports"
            }
            assertEquals(1, marker.size, "IF $facing marker")
            assertEquals(rotation, marker.single().asJsonObject.getAsJsonObject("apply")["y"]?.asInt ?: 0)
        }

        val model = json(resources.resolve("models/block/if_ports.json"))
        assertEquals("minecraft:block/orange_concrete", model.getAsJsonObject("textures")["in"].asString)
        assertEquals("minecraft:block/cyan_concrete", model.getAsJsonObject("textures")["out"].asString)
        val elements = model.getAsJsonArray("elements").associateBy {
            it.asJsonObject["name"].asString
        }
        assertEquals(1, elements.getValue("front_in_socket").asJsonObject.getAsJsonArray("from")[1].asInt)
        assertTrue(elements.getValue("front_in_socket").asJsonObject.getAsJsonObject("faces").has("north"))
        assertTrue(elements.getValue("left_0_socket").asJsonObject.getAsJsonObject("faces").has("west"))
        assertTrue(elements.getValue("right_1_socket").asJsonObject.getAsJsonObject("faces").has("east"))
        assertEquals(-45, elements.getValue("front_n_diagonal").asJsonObject
            .getAsJsonObject("rotation")["angle"].asInt)
        assertFalse(model.getAsJsonArray("elements").any { element ->
            element.asJsonObject.getAsJsonArray("from")[2].asDouble >= 16.0 ||
                element.asJsonObject.getAsJsonArray("to")[2].asDouble > 16.0
        }, "IF BACK must have no exterior marker geometry")

        val content = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/ModContent.kt"))
        assertTrue(content.contains("programBlock(\"if\", SoundType.GLASS, CkProgramPortFace.FRONT)"))
        assertTrue(content.contains("\"IF\" -> (IF as CkProgramBlock).facingForPlacement(playerDirection)"))
    }

    @Test fun `admin bind command uses the real ServerPlayer identity and existing coordinator`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("Commands.literal(\"ckbind\")"))
        assertTrue(source.contains("Permissions.COMMANDS_ADMIN"))
        assertTrue(source.contains("ServerPlayerIdentity.from(player)"))
        assertTrue(source.contains("coordinator.bind("))
    }

    @Test fun `player join reloads authoritative current-world Stage before wire reconciliation`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("/api/v1/worlds/\$worldUuid/halls"))
        assertTrue(source.contains("StageReconnectReconciler("))
        assertTrue(source.contains("WorldRenderer.renderStageBlocks(level, hall)"))
        assertTrue(source.contains("reconcileGraphAndWire = { stage ->"))
    }

    @Test fun `save sign accepts only lifecycle-current synchronized graph dirty state`() {
        val interactions = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        val registry = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/HallRegistry.kt"))
        val renderer = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/WorldRenderer.kt"))
        assertTrue(interactions.contains("registry.saveDirty(stageId)"))
        assertTrue(registry.contains("GraphSyncStatus.SYNCED"))
        assertTrue(registry.contains("graph.stageEpoch == stage.workspaceEpoch"))
        assertTrue(interactions.contains("WorldRenderer.renderSaveState(level, hall, dirty)"))
        assertTrue(renderer.contains("if (graphDirty ?: (hall.stage?.dirty == true))"))
    }

    @Test fun `wire interactions cancel vanilla placement and break paths`() {
        val source = Files.readString(root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"))
        assertTrue(source.contains("held.item === ModContent.CK_WIRE_ITEM"))
        assertTrue(source.contains("level.getBlockState(pos).block === ModContent.CK_WIRE"))
        assertTrue(source.contains("return@register false"))
        assertTrue(source.indexOf("if (stage == null || coordinator == null)") < source.indexOf("coordinator.place("))
        assertTrue(source.contains("player.isShiftKeyDown"))
        assertTrue(source.contains("GraphPlacementIntent.CROSS"))
        assertTrue(source.contains("GraphPlacementIntent.AUTO"))
        assertTrue(source.contains("MinecraftWireWorldAccess(level), intent"))
        assertTrue(source.contains("result.discoveryDiagnostics.forEach"))
    }

    @Test fun `SAVE response resets and immediately reconciles the graph projection`() {
        val source = Files.readString(
            root.resolve("src/main/kotlin/me/buciqua/blockommander/runtime/Interactions.kt"),
        )
        assertTrue(source.contains("registry.updateStageAfterSave"))
        assertTrue(source.contains("reconcileGraphAndWire(player, level, savedStage)"))
        assertTrue(source.contains("refreshSaveSign(level, savedStage.id)"))
    }

    private fun json(path: Path) = JsonParser.parseString(Files.readString(path)).asJsonObject
}
