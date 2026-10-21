package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.network.OpenFormPayload
import me.buciqua.blockommander.network.SubmitFormPayload
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.core.registries.BuiltInRegistries
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Interactions(private val api: ApiClient, private val registry: HallRegistry) {
    private data class Selection(val first: BlockPos, val material: String)
    private val selections = ConcurrentHashMap<UUID, Selection>()

    fun register() {
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
            if (level.isClientSide || hand != net.minecraft.world.InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val serverPlayer = player as ServerPlayer; val serverLevel = level as ServerLevel
            val worldUuid = WorldIdentity.uuid(serverLevel).toString()
            registry.controlAt(worldUuid, hit.blockPos)?.let { control ->
                handleControl(serverPlayer, serverLevel, control); return@register InteractionResult.SUCCESS
            }
            val held = player.getItemInHand(hand)
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
                response.getAsJsonArray("halls").forEach { WorldRenderer.buildHall(level, registry.install(it.asJsonObject)) }
            }
        }
    }

    private fun restoreFor(player: ServerPlayer) {
        val level = player.level()
        api.post(player, "/api/v1/halls/restore", playerPayload(player, level)) { response ->
            response.getAsJsonArray("halls").forEach { WorldRenderer.buildHall(level, registry.install(it.asJsonObject)) }
        }
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
