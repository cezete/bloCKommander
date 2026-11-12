package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import com.mojang.brigadier.arguments.StringArgumentType
import me.buciqua.blockommander.runtime.program.HallSelectionCoordinator
import me.buciqua.blockommander.BlockCommanderMod
import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.network.OpenFormPayload
import me.buciqua.blockommander.network.SubmitFormPayload
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.GraphPlacementIntent
import me.buciqua.blockommander.runtime.graph.ServerPlayerIdentity
import me.buciqua.blockommander.runtime.program.MinecraftStageSwitchCommitter
import me.buciqua.blockommander.runtime.program.NavigationResult
import me.buciqua.blockommander.runtime.program.NavigationStatus
import me.buciqua.blockommander.runtime.program.StageNavigationCoordinator
import me.buciqua.blockommander.runtime.program.ProgramApiClient
import me.buciqua.blockommander.runtime.program.ProgramGateway
import me.buciqua.blockommander.runtime.program.ProgramHttpResult
import me.buciqua.blockommander.runtime.wire.*
import me.buciqua.blockommander.runtime.editor.resolveBlockEditorTarget
import me.buciqua.blockommander.runtime.editor.permitsLegacyBlockMetadata
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

class Interactions(
    private val api: ApiClient,
    private val registry: HallRegistry,
    private val wireCoordinator: () -> WireInteractionCoordinator? = { null },
    private val stageNavigation: () -> StageNavigationCoordinator? = { null },
    private val programs: ProgramGateway = ProgramApiClient.fromEnvironment(),
    private val hallSelection: () -> HallSelectionCoordinator? = { null },
) {
    private data class Selection(val first: BlockPos, val material: String)
    private val selections = ConcurrentHashMap<UUID, Selection>()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(Commands.literal("ck").then(Commands.literal("hall")
                .then(Commands.literal("select").then(Commands.argument("hall-code",StringArgumentType.word())
                    .then(Commands.argument("program-uuid",StringArgumentType.word())
                        .then(Commands.argument("stage-uuid",StringArgumentType.word()).executes { context ->
                            selectHall(context.source.playerOrException,StringArgumentType.getString(context,"hall-code"),
                                StringArgumentType.getString(context,"program-uuid"),StringArgumentType.getString(context,"stage-uuid"))
                            1
                        }))))
                .then(Commands.literal("retry").then(Commands.argument("hall-code",StringArgumentType.word()).executes { context ->
                    selectHall(context.source.playerOrException,StringArgumentType.getString(context,"hall-code"),null,null)
                    1
                }))))
            dispatcher.register(Commands.literal("ckbind")
                .requires { source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN) }
                .executes { context ->
                    bindCurrentStage(context.source.playerOrException)
                    1
                })
            dispatcher.register(Commands.literal("ckback")
                .executes { context ->
                    navigateBack(context.source.playerOrException)
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
            if(registry.hallAt(worldUuid,hit.blockPos)?.let { !registry.editingAllowed(it.id) }==true)
                return@register InteractionResult.SUCCESS
            registry.controlAt(worldUuid, hit.blockPos)?.let { control ->
                handleControl(serverPlayer, serverLevel, control); return@register InteractionResult.SUCCESS
            }
            val held = player.getItemInHand(hand)
            if (held.item === ModContent.CK_WIRE_ITEM) {
                val target = hit.blockPos.relative(hit.direction)
                val intent = if (player.isShiftKeyDown) GraphPlacementIntent.CROSS else GraphPlacementIntent.AUTO
                placeWire(serverPlayer, serverLevel, worldUuid, target, held, intent)
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
            registry.hallAt(worldUuid, hit.blockPos)?.let { hall ->
                val stage = hall.stage ?: return@let
                val block = stage.blocks.firstOrNull { it.pos == hit.blockPos } ?: return@let
                if (block.type == "PROGRAM") {
                    enterProgramBlock(serverPlayer, serverLevel, hall, block)
                    return@register InteractionResult.SUCCESS
                }
                if (block.type in setOf("START", "STOP")) {
                    val target = resolveBlockEditorTarget(hall, serverPlayer.uuid, WorldIdentity.uuid(serverLevel), hit.blockPos,
                        ModContent.typeOf(serverLevel.getBlockState(hit.blockPos).block), registry.navigationState(hall.id)?.switchInProgress == true,
                        registry.editingAllowed(hall.id)) ?: return@register InteractionResult.SUCCESS
                    if (!ServerPlayNetworking.canSend(serverPlayer, me.buciqua.blockommander.network.EditorStatePayload.TYPE)) {
                        serverPlayer.sendSystemMessage(Component.literal("§c[CK] A Block Editorhoz azonos verziójú kliensmod szükséges."))
                    } else if (BlockCommanderMod.editorSessions?.open(serverPlayer, UUID.fromString(hall.id), UUID.fromString(target.instanceId)) == null) {
                        serverPlayer.sendSystemMessage(Component.literal("§c[CK] Az editor most nem nyitható meg ebben a csarnokban."))
                    }
                    return@register InteractionResult.SUCCESS
                }
                ServerPlayNetworking.send(serverPlayer, OpenFormPayload(
                    "block", "${hall.id}|${stage.id}|${block.instanceId}", block.label, block.description,
                ))
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
            val breakHall=registry.hallAt(worldUuid,pos)
            if(breakHall?.let { !registry.editingAllowed(it.id) }==true) {
                Diagnostics.event("BLOCK_BREAK_LOCAL_REJECT",breakHall.stage?.let { breakHall.projectionKey() },
                    "reason" to "SELECTION_UNRESOLVED","http_sent" to false,"x" to pos.x,"y" to pos.y,"z" to pos.z)
                return@register false
            }
            if (level.getBlockState(pos).block === ModContent.CK_WIRE) {
                val hall=registry.hallAt(worldUuid,pos)
                Diagnostics.event("CKW_BREAK_INTERCEPT",hall?.stage?.let { hall.projectionKey() },
                    "world_uuid" to worldUuid,"x" to pos.x,"y" to pos.y,"z" to pos.z,
                    "editing_allowed" to hall?.let { registry.editingAllowed(it.id) })
                removeWire(serverPlayer, serverLevel, worldUuid, pos)
                return@register false
            }
            registry.hallAt(worldUuid, pos)?.let { hall ->
                val stage = hall.stage ?: return@let
                val block = stage.blocks.firstOrNull { it.pos == pos } ?: return@let
                val fence = registry.stageUpdateFenceForHall(hall.id) ?: return@let
                val payload = playerPayload(serverPlayer, serverLevel)
                api.post(serverPlayer, "/api/v1/stages/${stage.id}/blocks/${block.instanceId}/delete", payload,
                    hall.transportContext, UUID.fromString(stage.id)) { response ->
                    registry.updateStageFromDomainResponse(response.getAsJsonObject("stage"), fence)
                        ?.let { WorldRenderer.renderStage(serverLevel, it) }
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
                val ids = payload.contextId.split('|', limit = 3); if (ids.size != 3) return
                if (payload.value1.isBlank() || payload.value1.length > 32 || payload.value2.length > 256) {
                    player.sendSystemMessage(Component.literal("§c[CK] A felirat 1–32, a leírás legfeljebb 256 karakteres lehet.")); return
                }
                val body = playerPayload(player, level).apply { addProperty("label", payload.value1); addProperty("description", payload.value2) }
                val hall = registry.hall(ids[0]) ?: return
                val target = hall.stage?.blocks?.singleOrNull { it.instanceId == ids[2] } ?: return
                // START/STOP have one writer path: the authoritative editor session, including forged old form packets.
                if (!permitsLegacyBlockMetadata(target)) return
                val fence = registry.stageUpdateFenceForHall(hall.id) ?: return
                if (fence.stageId != ids[1]) return
                api.post(player, "/api/v1/stages/${ids[1]}/blocks/${ids[2]}", body,
                    hall.transportContext, UUID.fromString(ids[1])) { response ->
                    registry.updateStageFromDomainResponse(response.getAsJsonObject("stage"), fence)
                        ?.let { WorldRenderer.renderStage(level, it) }
                }
            }
        }
    }

    private fun refresh(level: ServerLevel) {
        val worldUuid = WorldIdentity.uuid(level).toString()
        val projection=StartupHallProjection(registry,java.util.concurrent.Executor(level.server::execute)) { hall ->
            WorldRenderer.buildHall(level,hall)
            val owner=level.players().firstOrNull { it.uuid.toString()==hall.ownerUuid }
            if (owner!=null && hall.stage!=null) reconcileWire(owner,level,hall)
        }
        val fence=projection.begin(worldUuid)
        api.get(null, "/api/v1/worlds/$worldUuid/halls") { response ->
            response.getAsJsonArray("halls").forEach { projection.apply(fence,it.asJsonObject) }
        }
    }

    private fun restoreFor(player: ServerPlayer) {
        val level = player.level()
        val worldUuid = WorldIdentity.uuid(level).toString()
        api.post(player, "/api/v1/halls/restore", playerPayload(player, level), onFailure = { detail ->
            BlockCommanderMod.LOGGER.warn("Reconnect restore failed for player {}: {}", player.uuid, detail)
        }) { response ->
            val restoredHalls = response.getAsJsonArray("halls")
            if (restoredHalls == null) {
                BlockCommanderMod.LOGGER.warn("Reconnect restore response has no halls array for player {}", player.uuid)
                return@post
            }
            val restoredHallIds = runCatching {
                restoredHalls.map { it.asJsonObject["hall_id"].asString }.toSet()
            }.getOrElse {
                BlockCommanderMod.LOGGER.warn("Reconnect restore response is invalid for player {}", player.uuid, it)
                return@post
            }
            refreshOwnerStages(player, level, worldUuid, restoredHallIds)
        }
    }

    private fun refreshOwnerStages(player: ServerPlayer, level: ServerLevel, worldUuid: String,
                                   restoredHallIds: Set<String>) {
        val ownerUuid = player.uuid.toString()
        val fences = registry.stageUpdateFencesForOwner(ownerUuid)
        api.get(player, "/api/v1/worlds/$worldUuid/halls", onFailure = { detail ->
            BlockCommanderMod.LOGGER.warn("Authoritative reconnect Stage GET failed for player {}: {}", player.uuid, detail)
        }) { response ->
            val reconciler = StageReconnectReconciler(
                registry,
                projectStage = { hall ->
                    if (hall.id in restoredHallIds) WorldRenderer.buildHallStructure(level, hall)
                    WorldRenderer.renderStageBlocks(level, hall)
                },
                reconcileGraphAndWire = { hall -> reconcileGraphAndWire(player, level, hall) },
                projectSaveState = { hall, dirty -> WorldRenderer.renderSaveState(level, hall, dirty) },
                diagnose = { detail ->
                    BlockCommanderMod.LOGGER.warn("Reconnect reconciliation issue for player {}: {}", player.uuid, detail)
                },
            )
            val halls = response.getAsJsonArray("halls")
            if (halls == null) {
                BlockCommanderMod.LOGGER.warn("Authoritative reconnect Stage GET has no halls array for player {}", player.uuid)
                return@get
            }
            halls.forEach { element ->
                val hallJson = runCatching { element.asJsonObject }.getOrElse {
                    BlockCommanderMod.LOGGER.warn("Authoritative reconnect Stage entry is invalid for player {}", player.uuid, it)
                    return@forEach
                }
                val identity = runCatching { hallJson["owner_uuid"].asString to hallJson["hall_id"].asString }.getOrElse {
                    BlockCommanderMod.LOGGER.warn("Authoritative reconnect Stage identity is invalid for player {}", player.uuid, it)
                    return@forEach
                }
                if (identity.first == ownerUuid) reconciler.reconcile(hallJson, fences[identity.second])
            }
        }
    }

    private fun placeWire(player: ServerPlayer, level: ServerLevel, worldUuid: String, target: BlockPos,
                          held: net.minecraft.world.item.ItemStack, intent: GraphPlacementIntent) {
        val hall = registry.hallAt(worldUuid, target)
        val stage = hall?.stage
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            Diagnostics.event("CKW_PLACE_LOCAL_REJECT",null,"hall_id" to hall?.id,
                "reason" to if(stage==null) "STAGE_MISSING" else "COORDINATOR_UNAVAILABLE","http_sent" to false)
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
        val completionFence=registry.wireCompletionFence(hall.id,worldUuid) ?: run {
            Diagnostics.event("CKW_PLACE_LOCAL_REJECT",hall.projectionKey(),"reason" to "NO_WIRE_COMPLETION_FENCE","http_sent" to false)
            return
        }
        coordinator.place(hall.wireContext(registry), ServerPlayerIdentity.from(player), target.graphPosition(),
            MinecraftWireWorldAccess(level), intent).thenAccept { result ->
            wireCompletion(level).complete(completionFence,result) {
                if ((result.status == WireActionStatus.SUCCESS || result.status == WireActionStatus.RECOVERED) && !alreadyWire &&
                    !player.abilities.instabuild) held.shrink(1)
            }
            wireFeedback(player, result)
        }
    }

    private fun removeWire(player: ServerPlayer, level: ServerLevel, worldUuid: String, target: BlockPos) {
        val hall = registry.hallAt(worldUuid, target)
        val stage = hall?.stage
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            Diagnostics.event("CKW_BREAK_LOCAL_REJECT",null,"hall_id" to hall?.id,
                "reason" to if(stage==null) "STAGE_MISSING" else "COORDINATOR_UNAVAILABLE","http_sent" to false)
            correct(level, target)
            player.sendSystemMessage(Component.literal("§c[CK] A CK Wire eltávolításához szinkronizált Stage szükséges."))
            return
        }
        val completionFence=registry.wireCompletionFence(hall.id,worldUuid) ?: run {
            Diagnostics.event("CKW_BREAK_LOCAL_REJECT",hall.projectionKey(),"reason" to "NO_WIRE_COMPLETION_FENCE","http_sent" to false)
            return
        }
        coordinator.remove(hall.wireContext(registry), ServerPlayerIdentity.from(player), target.graphPosition(),
            MinecraftWireWorldAccess(level)).thenAccept {
            wireCompletion(level).complete(completionFence,it)
            wireFeedback(player, it)
        }
    }

    private fun reconcileWire(player: ServerPlayer, level: ServerLevel, hall: Hall) {
        val fence=registry.wireCompletionFence(hall.id,WorldIdentity.uuid(level).toString()) ?: return
        reconcileGraphAndWire(player, level, hall).thenAccept {
            level.server.execute {
                registry.completeWire(fence,WorldIdentity.uuid(level).toString()) { refreshSaveSign(level,hall.id) }
            }
        }
    }

    private fun reconcileGraphAndWire(player: ServerPlayer, level: ServerLevel,
                                      hall: Hall, projectionAllowed: () -> Boolean = { true }): CompletableFuture<ReconnectGraphResult> {
        val stage = hall.stage ?: return CompletableFuture.completedFuture(
            ReconnectGraphResult(false, "STAGE_MISSING"),
        )
        val coordinator = wireCoordinator() ?: return CompletableFuture.completedFuture(
            ReconnectGraphResult(false, "GRAPH_COORDINATOR_UNAVAILABLE"),
        )
        return coordinator.reconcileAndRender(
            hall.wireContext(registry), ServerPlayerIdentity.from(player), MinecraftWireWorldAccess(level),
            projectionAllowed,
        ).thenApply { result ->
            val synchronized = result.status == WireActionStatus.SUCCESS || result.status == WireActionStatus.RECOVERED
            if (!synchronized) {
                BlockCommanderMod.LOGGER.warn(
                    "Stage graph reconciliation failed: stage={} status={} detail={}",
                    stage.id, result.status, result.detail,
                )
            }
            if (result.status == WireActionStatus.NEEDS_REPAIR) wireFeedback(player, result)
            ReconnectGraphResult(synchronized, result.detail ?: result.status.name)
        }
    }

    private fun bindCurrentStage(player: ServerPlayer) {
        val level = player.level()
        val worldUuid = WorldIdentity.uuid(level).toString()
        val hall = registry.hallAt(worldUuid, player.blockPosition())
        val stage = hall?.stage
        val coordinator = wireCoordinator()
        if (stage == null || coordinator == null) {
            player.sendSystemMessage(Component.literal("§c[CK] Az Élbekötéshez állj az aktuális Stage területére."))
            return
        }
        player.sendSystemMessage(Component.literal("§e[CK] Élbekötés indítása a Python graph szolgáltatáson keresztül…"))
        val completionFence=registry.wireCompletionFence(hall.id,worldUuid) ?: return
        coordinator.bind(
            hall.wireContext(registry), ServerPlayerIdentity.from(player), MinecraftWireWorldAccess(level),
        ).thenAccept {
            wireCompletion(level).complete(completionFence,it)
            wireFeedback(player, it)
        }
    }

    private fun enterProgramBlock(
        player: ServerPlayer, level: ServerLevel, hall: Hall, block: StageBlock,
    ) {
        val navigation = stageNavigation()
        if (navigation == null) {
            player.sendSystemMessage(Component.literal("§c[CK] A Program-navigáció jelenleg nem érhető el."))
            return
        }
        val blockId = runCatching { UUID.fromString(block.instanceId) }.getOrNull()
        if (blockId == null) {
            player.sendSystemMessage(Component.literal("§c[CK] A PROGRAM blokk azonosítója hibás."))
            return
        }
        player.sendSystemMessage(Component.literal("§e[CK] Belépés a gyermek Stage-be…"))
        navigation.enter(
            UUID.fromString(hall.id), blockId, ServerPlayerIdentity.from(player),
            MinecraftStageSwitchCommitter(level, registry),
        ).thenAccept { result -> level.server.execute { navigationFeedback(player, result) } }
    }

    private fun navigateBack(player: ServerPlayer) {
        val level = player.level()
        val worldUuid = WorldIdentity.uuid(level).toString()
        val hall = registry.hallAt(worldUuid, player.blockPosition())
        val navigation = stageNavigation()
        if (hall == null || navigation == null) {
            player.sendSystemMessage(Component.literal("§c[CK] A visszalépéshez állj az aktív Stage területére."))
            return
        }
        navigation.back(
            UUID.fromString(hall.id), ServerPlayerIdentity.from(player),
            MinecraftStageSwitchCommitter(level, registry),
        ).thenAccept { result -> level.server.execute { navigationFeedback(player, result) } }
    }

    private fun selectHall(player:ServerPlayer,code:String,programText:String?,stageText:String?) {
        val level=player.level();val world=WorldIdentity.uuid(level)
        val hall=registry.inWorld(world.toString()).singleOrNull {
            it.ownerUuid==player.uuid.toString() && it.code.equals(code,ignoreCase=true)
        }
        val coordinator=hallSelection()
        if(hall==null || coordinator==null) {
            player.sendSystemMessage(Component.literal("§c[CK] A saját Hall nem található az aktuális világban, vagy a selection nem érhető el."));return
        }
        val committer=MinecraftStageSwitchCommitter(level,registry)
        val future=if(programText==null) coordinator.retry(UUID.fromString(hall.id),world,
            ServerPlayerIdentity.from(player),committer) { WorldIdentity.uuid(player.level()) }
        else {
            val program=runCatching { UUID.fromString(programText) }.getOrNull()
            val stage=runCatching { UUID.fromString(stageText) }.getOrNull()
            if(program==null || stage==null) {
                player.sendSystemMessage(Component.literal("§c[CK] Hibás Program vagy Stage UUID."));return
            }
            coordinator.select(UUID.fromString(hall.id),world,ServerPlayerIdentity.from(player),program,stage,committer) {
                WorldIdentity.uuid(player.level())
            }
        }
        future.thenAccept { result -> level.server.execute {
            player.sendSystemMessage(Component.literal(if(result.status==NavigationStatus.SUCCESS)
                "§a[CK] Hall selection és fizikai projekció kész."
                else "§c[CK] Selection/projekció: ${result.detail ?: result.status}. A sikertelen Hall zárolt; /ck hall retry ${hall.code}"))
        } }
    }

    private fun navigationFeedback(player: ServerPlayer, result: NavigationResult) {
        val message = when (result.status) {
            NavigationStatus.SUCCESS -> "§a[CK] Stage-váltás kész."
            NavigationStatus.NO_OP -> "§e[CK] Már a Program root Stage-ében vagy."
            NavigationStatus.FORBIDDEN -> "§c[CK] Csak a csarnok tulajdonosa navigálhat."
            NavigationStatus.STALE -> "§e[CK] Egy újabb Stage-váltás megelőzte ezt a kérést."
            NavigationStatus.UNAVAILABLE -> "§c[CK] A Program szolgáltatás nem érhető el."
            NavigationStatus.PROJECTION_FAILED -> "§c[CK] A cél-Stage világvetítése nem sikerült; a korábbi Stage maradt aktív."
            NavigationStatus.REJECTED -> "§c[CK] A Stage-váltás elutasítva: ${result.detail ?: "ismeretlen hiba"}."
        }
        player.sendSystemMessage(Component.literal(message))
    }

    private fun refreshSaveSign(level: ServerLevel, hallId: String) {
        // Callers have already crossed the server-thread completion fence; do not enqueue again.
        val hall = registry.hall(hallId) ?: return
        val fence = registry.beginProgramStatus(hall.id)
        val current = registry.hall(hall.id) ?: return
        WorldRenderer.renderSaveState(level, current, current.programDirty)
        if (fence == null) return
        Diagnostics.event("SAVE_STATUS_REQUEST",fence.stage.key,"status_generation" to fence.generation,
            "epoch" to fence.stage.workspaceEpoch,"revision" to fence.stage.workingRevision)
        programs.mountedProgram(hall.transportContext).thenAccept { result ->
            Diagnostics.event("SAVE_STATUS_RESPONSE",fence.stage.key,"status_generation" to fence.generation,
                "outcome" to result.javaClass.simpleName,
                "response_dirty" to if(result is ProgramHttpResult.Success) result.value.program.aggregateDirty else null)
            level.server.execute {
                if (result is ProgramHttpResult.Success) {
                    registry.applyProgramStatus(fence, result.value)?.let { updated ->
                        WorldRenderer.renderSaveState(level, updated, updated.programDirty)
                    }
                }
            }
        }
    }

    private fun wireCompletion(level: ServerLevel)=WireProjectionCompletion(
        registry,java.util.concurrent.Executor(level.server::execute),
        { WorldIdentity.uuid(level).toString() },{ refreshSaveSign(level,it) },
    )

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
            WireActionStatus.FORBIDDEN -> "§c[CK] Ezt a Stage-et csak a csarnok tulajdonosa szerkesztheti vagy mentheti."
            WireActionStatus.REJECTED -> "§c[CK] A Python elutasította a CK Wire műveletet: ${result.detail ?: "ismeretlen hiba"}."
        }
        player.sendSystemMessage(Component.literal(message))
        result.discoveryDiagnostics.forEach { diagnostic ->
            player.sendSystemMessage(Component.literal("§e[CK] ${diagnostic.code}: ${diagnostic.message}"))
        }
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
        val hall = registry.hallAt(worldUuid, target)
        val stage = hall?.stage
        if (stage == null) { player.sendSystemMessage(Component.literal("§c[CK] CK-blokk csak a saját stage területére helyezhető.")); return }
        val payload = playerPayload(player, level).apply {
            addProperty("type_key",type);addProperty("coordinate_space","STAGE_RELATIVE")
            addProperty("x",target.x-stage.origin.x);addProperty("y",target.y-stage.origin.y)
            addProperty("z",target.z-stage.origin.z)
            addProperty("orientation", ModContent.placementFacing(type, player.direction).name)
        }
        val fence = registry.stageUpdateFenceForHall(hall.id) ?: return
        api.post(player, "/api/v1/stages/${stage.id}/blocks", payload,
            hall.transportContext, UUID.fromString(stage.id)) { response ->
            registry.updateStageFromDomainResponse(response.getAsJsonObject("stage"), fence)
                ?.let { WorldRenderer.renderStage(level, it) }
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
            "SAVE_BUTTON" -> registry.saveFenceForHall(control.hallId)?.let { fence ->
                Diagnostics.event("SAVE_BUTTON_REQUEST",fence.key,"epoch" to fence.workspaceEpoch,"revision" to fence.workingRevision,
                    "hall_generation" to fence.hallGeneration,"navigation_generation" to fence.navigationGeneration,
                    "program_dirty" to registry.hall(control.hallId)?.programDirty)
                if (!registry.isSaveFenceCurrent(fence)) return@let
                payload.addProperty("active_stage_id", fence.stageId)
                payload.addProperty("workspace_epoch", fence.workspaceEpoch.toString())
                payload.addProperty("expected_working_revision", fence.workingRevision)
                val hall = registry.hall(control.hallId) ?: return@let
                api.post(player, "/api/v1/halls/save-button", payload,
                    hall.transportContext, UUID.fromString(fence.stageId)) { response ->
                    registry.updateStageAfterSave(response.getAsJsonObject("stage"), fence)?.let { hall ->
                        WorldRenderer.renderStage(level, hall)
                        hall.stage?.let { savedStage ->
                            val savedFence = registry.saveFenceForHall(hall.id) ?: return@let
                            reconcileGraphAndWire(player, level, hall) {
                                registry.isSaveFenceCurrent(savedFence)
                            }.thenAccept {
                                level.server.execute {
                                    if (registry.isSaveFenceCurrent(savedFence)) {
                                        registry.hall(hall.id)?.programDirty?.let { dirty ->
                                            WorldRenderer.renderSaveState(level, hall, dirty)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
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

private fun Hall.wireContext(registry: HallRegistry): WireStageContext {
    val stage = requireNotNull(stage)
    return WireStageContext(
        transportContext, UUID.fromString(stage.id), stage.origin.graphPosition(),
        GraphPosition(stage.sizeX, stage.sizeY, stage.sizeZ), stage.workspaceEpoch,
        requireNotNull(registry.graphProjections.view(projectionKey())).lifecycleGeneration,
    )
}
private fun BlockPos.graphPosition() = GraphPosition(x, y, z)
