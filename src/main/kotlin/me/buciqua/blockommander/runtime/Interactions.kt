package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.network.OpenFormPayload
import me.buciqua.blockommander.network.SubmitFormPayload
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.ServerPlayerIdentity
import me.buciqua.blockommander.runtime.wire.*
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.commands.Commands
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.core.registries.BuiltInRegistries
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Interactions(
    private val api: ApiClient,
    private val registry: HallRegistry,
    private val wireCoordinator: () -> WireInteractionCoordinator? = { null },
) {
    private data class Selection(val first: BlockPos, val material: String)
    private val selections = ConcurrentHashMap<UUID, Selection>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("ckbind")
                .requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                .executes { context ->
                    bindCurrentStage(context.source.playerOrException)
                    1
                })
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            server.allLevels.forEach { level -> refresh(level) }
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> restoreFor(handler.player) }
        AttackBlockCallback.EVENT.register { player, level, hand, pos, _ ->
            if (level.isClientSide || hand != net.minecraft.world.InteractionHand.MAIN_HAND || player.getItemInHand(hand).item !== ModContent.HANGAR_SWORD)
                return@register InteractionResult.PASS
            val serverPlayer = player as ServerPlayer
            val material = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).block).toString()
            selections[player.uuid] = Selection(pos.immutable(), material)
            serverPlayer.sendSystemMessage(Component.literal("§a[CK] A pont: ${pos.x}, ${pos.y}, ${pos.z}. Jobb kattintás a B ponthoz."))
            InteractionResult.SUCCESS
        }
        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            if (player.getItemInHand(hand).item === ModContent.CK_WIRE_ITEM && level.isClientSide)
                return@register InteractionResult.SUCCESS
            if (level.isClientSide) return@register InteractionResult.PASS
            val serverPlayer = player as ServerPlayer; val serverLevel = level as ServerLevel
            val worldUuid = WorldIdentity.uuid(serverLevel).toString()
            registry.controlAt(worldUuid, hit.blockPos)?.let { control ->
                handleControl(serverPlayer, serverLevel, control); return@register InteractionResult.SUCCESS
            }
            val held = player.getItemInHand(hand)
            if (held.item === ModContent.CK_WIRE_ITEM) {
                val target = hit.blockPos.relative(hit.direction)
                placeWire(serverPlayer, serverLevel, worldUuid, target, held)
                return@register InteractionResult.SUCCESS
            }
            if (held.item === ModContent.HANGAR_SWORD) {
                val selection = selections[player.uuid]
                if (selection == null) serverPlayer.sendSystemMessage(Component.literal("§e[CK] Előbb bal kattintással jelöld ki az A pontot."))
                else {
                    val defaultCode = "hall-${System.currentTimeMillis().toString().takeLast(8)}"
                    ServerPlayNetworking.send(serverPlayer, OpenFormPayload(
                        "hall", "${hit.blockPos.x},${hit.blockPos.y},${hit.blockPos.z}|$defaultCode", "", "7"
                    ))
                }
                return@register InteractionResult.SUCCESS
            }
            registry.stageBlockAt(worldUuid, hit.blockPos)?.let { (stage, block) ->
                ServerPlayNetworking.send(serverPlayer, OpenFormPayload("block", "${stage.id}|${block.instanceId}", block.label, block.description))
                return@register InteractionResult.SUCCESS
            }
            val type = (held.item as? BlockItem)?.block?.let(ModContent::typeOf)
            if (type != null) {
                val target = hit.blockPos.relative(hit.direction)
                placeProgramBlock(serverPlayer, serverLevel, worldUuid, target, type)
                return@register InteractionResult.SUCCESS
            }
            InteractionResult.PASS
        }
        PlayerBlockBreakEvents.BEFORE.register { level, player, pos, _, _ ->
            val serverPlayer = player as? ServerPlayer ?: return@register true
            val serverLevel = level as ServerLevel
            val worldUuid = WorldIdentity.uuid(serverLevel).toString()
            if (level.getBlockState(pos).block === ModContent.CK_WIRE) {
                removeWire(serverPlayer, serverLevel, worldUuid, pos)
                return@register false
            }
            registry.stageBlockAt(worldUuid, pos)?.let { (stage, block) ->
                val payload = playerPayload(serverPlayer, serverLevel)
                api.post(serverPlayer, "/api/v1/stages/${stage.id}/blocks/${block.instanceId}/delete", payload) { response ->
                    registry.updateStage(response.getAsJsonObject("stage"))?.let { WorldRenderer.renderStage(serverLevel, it) }
                }
                return@register false
            }
            !registry.isProtected(worldUuid, pos)
        }
    }

    fun submitForm(player: ServerPlayer, payload: SubmitFormPayload) {
        val level = player.level()
        when (payload.kind) {
            "hall" -> {
                val selection = selections[player.uuid] ?: run {
                    player.sendSystemMessage(Component.literal("§c[CK] A kijelölés már nem él; jelöld ki újra az A pontot.")); return
                }
                val context = payload.contextId.split('|', limit = 2)
                val coordinates = context[0].split(',').mapNotNull(String::toIntOrNull)
                if (coordinates.size != 3) return
                val code = context.getOrNull(1) ?: "hall-${System.currentTimeMillis().toString().takeLast(8)}"
                createHall(player, level, selection, BlockPos(coordinates[0], coordinates[1], coordinates[2]), code, payload.value1, payload.value2)
            }
            "block" -> {
                val ids = payload.contextId.split('|', limit = 2); if (ids.size != 2) return
                if (payload.value1.isBlank() || payload.value1.length > 32 || payload.value2.length > 256) {
                    player.sendSystemMessage(Component.literal("§c[CK] A felirat 1–32, a leírás legfeljebb 256 karakteres lehet.")); return
                }
                val body = playerPayload(player, level).apply { addProperty("label", payload.value1); addProperty("description", payload.value2) }
                api.post(player, "/api/v1/stages/${ids[0]}/blocks/${ids[1]}", body) { response ->
                    registry.updateStage(response.getAsJsonObject("stage"))?.let { WorldRenderer.renderStage(level, it) }
                }
            }
        }
    }

    private fun refresh(level: ServerLevel) {
        val worldUuid = WorldIdentity.uuid(level).toString()
        api.get(null, "/api/v1/worlds/$worldUuid/halls") { response ->
            level.server.execute {
                response.getAsJsonArray("halls").forEach {
                    val hall = registry.install(it.asJsonObject)
                    WorldRenderer.buildHall(level, hall)
                    val owner = level.players().firstOrNull { player -> player.uuid.toString() == hall.ownerUuid }
                    if (owner != null) hall.stage?.let { stage -> reconcileWire(owner, level, stage) }
                }
            }
        }
    }

    private fun restoreFor(player: ServerPlayer) {
        val level = player.level()
        val worldUuid = WorldIdentity.uuid(level).toString()
        api.post(player, "/api/v1/halls/restore", playerPayload(player, level)) { response ->
            val stages = linkedMapOf<String, Stage>()
            response.getAsJsonArray("halls").forEach {
                val hall = registry.install(it.asJsonObject)
                WorldRenderer.buildHall(level, hall)
                hall.stage?.let { stage -> stages[stage.id] = stage }
            }
            registry.inWorld(worldUuid).forEach { hall ->
                if (hall.ownerUuid == player.uuid.toString()) hall.stage?.let { stage -> stages[stage.id] = stage }
            }
            stages.values.forEach { stage -> reconcileWire(player, level, stage) }
        }
    }

    private fun placeWire(player: ServerPlayer, level: ServerLevel, worldUuid: String, target: BlockPos,
                          held: net.minecraft.world.item.ItemStack) {
        val stage = registry.stageAt(worldUuid, target)
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            correct(level, target)
            player.sendSystemMessage(Component.literal("§c[CK] A CK Wire csak elérhető Stage graphban helyezhető el."))
            return
        }
        val state = level.getBlockState(target)
        val alreadyWire = state.block === ModContent.CK_WIRE
        if (!state.isAir && !alreadyWire) {
            correct(level, target)
            player.sendSystemMessage(Component.literal("§c[CK] A célcellát idegen blokk foglalja."))
            return
        }
        coordinator.place(stage.wireContext(), ServerPlayerIdentity.from(player), target.graphPosition(),
            MinecraftWireWorldAccess(level)).thenAccept { result ->
            if ((result.status == WireActionStatus.SUCCESS || result.status == WireActionStatus.RECOVERED) && !alreadyWire &&
                !player.abilities.instabuild) held.shrink(1)
            refreshSaveSign(level, stage.id)
            wireFeedback(player, result)
        }
    }

    private fun removeWire(player: ServerPlayer, level: ServerLevel, worldUuid: String, target: BlockPos) {
        val stage = registry.stageAt(worldUuid, target)
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            correct(level, target)
            player.sendSystemMessage(Component.literal("§c[CK] A CK Wire eltávolításához szinkronizált Stage szükséges."))
            return
        }
        coordinator.remove(stage.wireContext(), ServerPlayerIdentity.from(player), target.graphPosition(),
            MinecraftWireWorldAccess(level)).thenAccept {
            refreshSaveSign(level, stage.id)
            wireFeedback(player, it)
        }
    }

    private fun reconcileWire(player: ServerPlayer, level: ServerLevel, stage: Stage) {
        wireCoordinator()?.reconcileAndRender(
            stage.wireContext(), ServerPlayerIdentity.from(player), MinecraftWireWorldAccess(level),
        )?.thenAccept { result ->
            refreshSaveSign(level, stage.id)
            if (result.status == WireActionStatus.NEEDS_REPAIR) wireFeedback(player, result)
        }
    }

    private fun bindCurrentStage(player: ServerPlayer) {
        val level = player.level()
        val worldUuid = WorldIdentity.uuid(level).toString()
        val stage = registry.stageAt(worldUuid, player.blockPosition())
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            player.sendSystemMessage(Component.literal("§c[CK] Az Élbekötéshez állj az aktuális Stage területére."))
            return
        }
        player.sendSystemMessage(Component.literal("§e[CK] Élbekötés indítása a Python graph szolgáltatáson keresztül…"))
        coordinator.bind(
            stage.wireContext(), ServerPlayerIdentity.from(player), MinecraftWireWorldAccess(level),
        ).thenAccept {
            refreshSaveSign(level, stage.id)
            wireFeedback(player, it)
        }
    }

    private fun refreshSaveSign(level: ServerLevel, stageId: String) {
        level.server.execute {
            val hall = registry.hallForStage(stageId) ?: return@execute
            val dirty = registry.graph(stageId)?.snapshot?.dirty ?: hall.stage?.dirty ?: return@execute
            WorldRenderer.renderSaveState(level, hall, dirty)
        }
    }

    private fun wireFeedback(player: ServerPlayer, result: WireActionResult) {
        val message = when (result.status) {
            WireActionStatus.SUCCESS -> if (result.action == WireAction.BIND)
                "§a[CK] Az Élbekötést a Python elfogadta; a CK Wire vetítése frissült."
            else "§a[CK] A CK Wire állapotát a Python elfogadta."
            WireActionStatus.RECOVERED -> if (result.action == WireAction.BIND)
                "§a[CK] Az Élbekötés reconciliation után helyreállt; a vetítés frissült."
            else "§a[CK] A CK Wire állapota reconciliation után helyreállt."
            WireActionStatus.NEEDS_REPAIR -> "§c[CK] A graph mentve, de a világvetítés helyreállítást igényel."
            WireActionStatus.NOT_SYNCED, WireActionStatus.STALE -> "§e[CK] A Stage graph állapota nem aktuális; próbáld újra."
            WireActionStatus.REJECTED -> "§c[CK] A Python elutasította a CK Wire műveletet: ${result.detail ?: "ismeretlen hiba"}."
        }
        player.sendSystemMessage(Component.literal(message))
    }

    private fun correct(level: ServerLevel, position: BlockPos) {
        val state = level.getBlockState(position)
        level.sendBlockUpdated(position, state, state, net.minecraft.world.level.block.Block.UPDATE_ALL)
    }

    private fun createHall(player: ServerPlayer, level: ServerLevel, selection: Selection, second: BlockPos,
                           requestedCode: String, requestedName: String, requestedHeight: String) {
        val code = requestedCode.trim().take(24)
        val name = requestedName.trim().take(96)
        val interiorHeight = requestedHeight.trim().toIntOrNull()
        if (!code.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,23}")) || name.isBlank() || interiorHeight == null || interiorHeight !in 3..64) {
            player.sendSystemMessage(Component.literal("§c[CK] A csarnok neve nem lehet üres, a belső magasság 3–64 blokk lehet.")); return
        }
        val payload = playerPayload(player, level).apply {
            addProperty("request_id", UUID.randomUUID().toString()); addProperty("hall_code", code); addProperty("hall_name", name)
            addProperty("orientation", player.direction.name); addProperty("material", selection.material)
            addProperty("interior_height", interiorHeight)
            add("p1", pos(selection.first)); add("p2", pos(BlockPos(second.x, selection.first.y, second.z)))
        }
        api.post(player, "/api/v1/halls", payload) { response ->
            val hall = registry.install(response.getAsJsonObject("hall")); WorldRenderer.buildHall(level, hall); selections.remove(player.uuid)
        }
    }

    private fun placeProgramBlock(player: ServerPlayer, level: ServerLevel, worldUuid: String, target: BlockPos, type: String) {
        val stage = registry.stageAt(worldUuid, target)
        if (stage == null) { player.sendSystemMessage(Component.literal("§c[CK] CK-blokk csak a saját stage területére helyezhető.")); return }
        val payload = playerPayload(player, level).apply {
            addProperty("type_key", type); addProperty("x", target.x); addProperty("y", target.y); addProperty("z", target.z)
            addProperty("orientation", player.direction.name)
        }
        api.post(player, "/api/v1/stages/${stage.id}/blocks", payload) { response ->
            registry.updateStage(response.getAsJsonObject("stage"))?.let { WorldRenderer.renderStage(level, it) }
        }
    }

    private fun handleControl(player: ServerPlayer, level: ServerLevel, control: Control) {
        val payload = playerPayload(player, level).apply {
            addProperty("x", control.pos.x); addProperty("y", control.pos.y); addProperty("z", control.pos.z)
        }
        when (control.kind) {
            "DESTROY_BUTTON" -> api.post(player, "/api/v1/halls/button", payload) { response ->
                registry.remove(response["hall_id"].asString)?.let { WorldRenderer.demolish(level, it) }
            }
            "SAVE_BUTTON" -> api.post(player, "/api/v1/halls/save-button", payload) { response ->
                registry.updateStage(response.getAsJsonObject("stage"))?.let { WorldRenderer.renderStage(level, it) }
            }
        }
    }

    private fun playerPayload(player: ServerPlayer, level: ServerLevel) = JsonObject().apply {
        addProperty("player_uuid", player.uuid.toString()); addProperty("player_name", player.gameProfile.name)
        addProperty("world_uuid", WorldIdentity.uuid(level).toString()); addProperty("world_name", WorldIdentity.name(level))
        addProperty("dimension", WorldIdentity.dimension(level))
    }
    private fun pos(pos: BlockPos) = JsonObject().apply { addProperty("x", pos.x); addProperty("y", pos.y); addProperty("z", pos.z) }
}

private fun Stage.wireContext() = WireStageContext(
    UUID.fromString(id), origin.graphPosition(), GraphPosition(sizeX, sizeY, sizeZ), workspaceEpoch,
)
private fun BlockPos.graphPosition() = GraphPosition(x, y, z)
