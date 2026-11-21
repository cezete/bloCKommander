package me.buciqua.blockommander.runtime.value

import me.buciqua.blockommander.ModContent
import me.buciqua.blockommander.network.*
import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.declarations.*
import me.buciqua.blockommander.runtime.editor.EditorConnections
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.ServerPlayerIdentity
import me.buciqua.blockommander.runtime.program.*
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal fun matchesLetBasis(fence: StageUpdateFence, basis: LetBasis) =
    fence.workspaceEpoch == basis.stageEpoch && fence.workingRevision == basis.revision

internal fun acceptsLetIntent(state: LetScreenState, intent: LetIntentPayload): Boolean =
    intent.session == state.session && !state.closed && (intent.action == LetAction.CLOSE ||
        (intent.sequence == state.sequence && (intent.action == LetAction.REFRESH || (state.ready && when (intent.action) {
            LetAction.APPLY -> state.rows.any { it.id == intent.target }
            LetAction.NEXT -> state.hasNext
            LetAction.PREVIOUS -> state.page > 0
            LetAction.ENTER -> state.target != null
        }))))

internal class LetRuntime(
    private val registry: HallRegistry,
    private val navigation: StageNavigationCoordinator,
    private val api: LetApi,
    private val declarations: DeclarationGateway,
    private val main: Executor,
    private val nextSequence: (UUID) -> Long,
) {
    private data class Capture(val context: DeclarationContext, val fence: StageUpdateFence, val live: () -> Boolean)
    private class Session(val player: ServerPlayer, val key: EditorSessionKey, val block: StageBlock, var capture: Capture) {
        @Volatile var active = true
        var busy = false
        var view: LetView? = null
        var page: DeclarationPage? = null
        var ui: LetScreenState? = null
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private val editing = mutableSetOf<UUID>()
    private val connections = EditorConnections { sessions.remove(it)?.active = false }
    private var generation = 0L
    @Volatile private var running = true
    var onOpening: (UUID) -> Unit = {}

    fun joined(player: ServerPlayer) = connections.join(player.uuid, player.connection)
    fun disconnected(player: ServerPlayer) = connections.disconnect(player.uuid, player.connection)
    fun worldChanged(player: ServerPlayer) {
        invalidate(player)
        connections.disconnect(player.uuid, player.connection)
        connections.join(player.uuid, player.connection)
    }

    fun connectionGuard(player: ServerPlayer): (() -> Boolean)? {
        val token = player.connection
        val generation = connections.generation(player.uuid, token) ?: return null
        val level = player.level()
        return { running && connections.generation(player.uuid, token) == generation &&
            (!level.server.isSameThread || (level.server.playerList.getPlayer(player.uuid) === player && player.level() === level)) }
    }

    fun shutdown() {
        running = false
        sessions.values.forEach { it.active = false }
        sessions.clear()
    }

    fun tick() {
        for (session in sessions.values.toList()) {
            if (!session.busy && !current(session.capture))
                invalidate(session.player)
        }
    }

    fun invalidate(player: ServerPlayer) {
        val session = sessions.remove(player.uuid) ?: return
        session.active = false
        if (session.capture.live()) emit(player, session, "", closed = true)
    }

    private fun capture(player: ServerPlayer, hall: Hall): Capture? {
        if (hall.ownerUuid != player.uuid.toString() || hall.worldUuid != WorldIdentity.uuid(player.level()).toString()) return null
        val fence = registry.saveFenceForHall(hall.id) ?: return null
        if (!registry.isSaveFenceCurrent(fence) || hall.stage?.kind !in setOf("ROOT", "PROGRAM_BODY")) return null
        val live = connectionGuard(player) ?: return null
        return Capture(DeclarationContext(player.uuid, WorldIdentity.uuid(player.level()), hall.transportContext, fence.key.stageId),
            fence, live)
    }

    private fun current(capture: Capture) = capture.live() && registry.isSaveFenceCurrent(capture.fence)
    private fun basisCurrent(capture: Capture, basis: LetBasis): Boolean {
        if (!current(capture) || !matchesLetBasis(capture.fence, basis)) return false
        val stage = registry.hall(capture.fence.hallId)?.stage ?: return false
        return stage.savedVersion?.let { basis.stageSaved >= it } != false
    }

    fun open(player: ServerPlayer, hall: Hall, block: StageBlock) {
        if (!ServerPlayNetworking.canSend(player, LetStatePayload.TYPE)) {
            message(player, "A LET szerkesztőhöz azonos verziójú kliensmod szükséges.")
            return
        }
        val capture = capture(player, hall) ?: return
        if (block.type != "LET" || !physicalLet(player, block) || editing.contains(capture.context.transport.hallId)) return
        val connection = connections.generation(player.uuid, player.connection) ?: return
        invalidate(player)
        onOpening(player.uuid)
        val session = Session(player, EditorSessionKey(player.uuid, connection, ++generation, UUID.randomUUID()), block, capture)
        sessions[player.uuid] = session
        load(player, session, 0)
    }

    fun intent(player: ServerPlayer, intent: LetIntentPayload) {
        val session = sessions[player.uuid]?.takeIf { it.capture.live() && it.active } ?: return
        val ui = session.ui ?: return
        if (!acceptsLetIntent(ui, intent)) return
        if (intent.action == LetAction.CLOSE) { invalidate(player); return }
        if (session.busy || !current(session.capture) || !physicalLet(player, session.block)) {
            if (!session.busy) invalidate(player)
            return
        }
        when (intent.action) {
            LetAction.REFRESH -> load(player, session, 0)
            LetAction.NEXT -> load(player, session, ui.page + 1)
            LetAction.PREVIOUS -> load(player, session, ui.page - 1)
            LetAction.APPLY -> configure(player, session, requireNotNull(intent.target))
            LetAction.ENTER -> enter(player, session)
            LetAction.CLOSE -> Unit
        }
    }

    private fun load(player: ServerPlayer, session: Session, index: Int, notice: String? = null, canRepair: Boolean = true) {
        val capture = session.capture
        val block = UUID.fromString(session.block.instanceId)
        session.busy = true
        emit(player, session, notice ?: "Betöltés…")
        val page = declarations.read(capture.context, "symbols", index, session.page?.token?.takeIf { index > 0 })
        api.read(capture.context, block).thenCombine(page) { config, symbols -> config to symbols }
            .thenAcceptAsync({ (configuration, symbols) ->
                if (!session.active || !capture.live()) return@thenAcceptAsync
                session.busy = false
                if (!current(capture)) { invalidate(player); return@thenAcceptAsync }
                val view = (configuration as? ProgramHttpResult.Success)?.value
                val listed = (symbols as? DeclarationResult.Success)?.value
                if (canRepair && view != null && view.basis == listed?.let(LetBasis::from) &&
                    !basisCurrent(capture, view.basis)) {
                    session.busy = true
                    emit(player, session, "Stage egyeztetése…")
                    reconcile(player, capture, view.basis, { session.active }) { committed ->
                        if (!session.active || !capture.live()) return@reconcile
                        if (committed && recapture(player, session))
                            load(player, session, 0, notice, canRepair = false)
                        else invalidate(player)
                    }
                    return@thenAcceptAsync
                }
                if (view == null || listed == null || view.basis != LetBasis.from(listed) || !basisCurrent(capture, view.basis)) {
                    session.page = null
                    session.view = null
                    if (index > 0) load(player, session, 0, "A lista elavult. Válassz újra.")
                    else emit(player, session, "Nem egyező vagy elérhetetlen állapot. Frissíts.")
                    return@thenAcceptAsync
                }
                session.view = view; session.page = listed
                emit(player, session, notice ?: "", ready = true)
            }, main)
    }

    private fun configure(player: ServerPlayer, session: Session, target: UUID) {
        val capture = session.capture
        val view = session.view ?: return
        if (!basisCurrent(capture, view.basis)) { invalidate(player); return }
        val hall = capture.context.transport.hallId
        if (!editing.add(hall)) return
        session.busy = true
        emit(player, session, "Mentetlen kötés alkalmazása…")
        api.configure(capture.context, view.block, target, view.basis).thenAcceptAsync({ result ->
            if (!session.active || !capture.live()) { editing.remove(hall); return@thenAcceptAsync }
            val accepted = (result as? ProgramHttpResult.Success)?.value
            val unknown = isUncertainLetResult(result)
            if (session.active && capture.live() && unknown) emit(player, session, "UNKNOWN – szerverállapot egyeztetése…")
            if (accepted == null && !unknown) {
                editing.remove(hall)
                if (session.active && capture.live()) load(player, session, 0, describe(result))
                return@thenAcceptAsync
            }
            reconcile(player, capture, accepted?.basis, { session.active }) { committed ->
                editing.remove(hall)
                if (!session.active || !capture.live()) return@reconcile
                if (!committed || !recapture(player, session)) { invalidate(player); return@reconcile }
                load(player, session, 0, if (unknown) "Hiteles szerverállapot visszaolvasva." else "Kötés alkalmazva.")
            }
        }, main)
    }

    private fun enter(player: ServerPlayer, session: Session) {
        val capture = session.capture
        val view = session.view ?: return
        if (view.target == null || !basisCurrent(capture, view.basis) || !editing.add(capture.context.transport.hallId)) return
        session.busy = true
        emit(player, session, "VStage megnyitása…")
        navigation.enterLet(capture.context.transport.hallId, ServerPlayerIdentity.from(player), view.block,
            MinecraftStageSwitchCommitter(player.level(), registry), { session.active && capture.live() }) {
            api.ensureChild(capture.context, view.block, view.basis)
        }.thenAcceptAsync({ result ->
            editing.remove(capture.context.transport.hallId)
            if (!session.active || !capture.live()) return@thenAcceptAsync
            if (result.status == NavigationStatus.SUCCESS) invalidate(player)
            else if (recapture(player, session)) load(player, session, 0,
                if (result.status == NavigationStatus.UNAVAILABLE) "UNKNOWN – VStage kapcsolat visszaolvasva."
                else result.detail?.take(120) ?: "A VStage nem nyitható meg.")
            else invalidate(player)
        }, main)
    }

    fun editBlock(player: ServerPlayer, hall: Hall, position: BlockPos, block: StageBlock? = null) {
        val capture = capture(player, hall) ?: return
        val stage = hall.stage ?: return
        if (!stage.contains(position) || !editing.add(capture.context.transport.hallId)) return
        val level = player.level()
        val relative = position.subtract(stage.origin)
        val orientation = ModContent.placementFacing("LET", player.direction).name
        val blockId = block?.let { UUID.fromString(it.instanceId) }
        declarations.read(capture.context, "symbols").thenComposeAsync({ result ->
            val page = (result as? DeclarationResult.Success)?.value
            val basis = page?.let(LetBasis::from)
            val validPosition = if (block == null) level.getBlockState(position).isAir else
                registry.hall(hall.id)?.stage?.blocks?.any { it == block } == true && physicalLet(player, block)
            if (basis == null || !basisCurrent(capture, basis) || !validPosition) {
                CompletableFuture.completedFuture(ProgramHttpResult.ApiError(
                    ProgramApiError(409, "STALE_VALUE_BASIS", "", emptyList())))
            } else if (blockId == null) {
                api.place(capture.context, GraphPosition(relative.x, relative.y, relative.z), orientation, basis)
                    .thenApply { it.basisResult() }
            } else api.remove(capture.context, blockId, basis)
        }, main).thenAcceptAsync({ result ->
            val receipt = (result as? ProgramHttpResult.Success)?.value
            val unknown = isUncertainLetResult(result)
            if (receipt != null || unknown) {
                if (unknown && capture.live()) message(player, "UNKNOWN – szerverállapot egyeztetése…")
                reconcile(player, capture, receipt) { committed ->
                    editing.remove(capture.context.transport.hallId)
                    if (capture.live() && !committed) message(player, "A Stage projekció elavult vagy nem érhető el.")
                }
            } else {
                editing.remove(capture.context.transport.hallId)
                if (capture.live()) message(player, describe(result))
            }
        }, main)
    }

    private fun reconcile(player: ServerPlayer, capture: Capture, basis: LetBasis?,
        sessionLive: () -> Boolean = { true }, complete: (Boolean) -> Unit) {
        if (!current(capture) || !sessionLive()) { complete(false); return }
        navigation.reloadDisplayed(capture.fence, ServerPlayerIdentity.from(player),
            MinecraftStageSwitchCommitter(player.level(), registry), { capture.live() && sessionLive() }) { response ->
            basis?.matchesStage(response) != false
        }.thenAcceptAsync({ complete(it.status == NavigationStatus.SUCCESS) }, main)
    }

    private fun recapture(player: ServerPlayer, session: Session): Boolean {
        val hall = registry.hall(session.capture.fence.hallId) ?: return false
        val fresh = capture(player, hall) ?: return false
        if (fresh.context != session.capture.context || hall.stage?.blocks?.none { it == session.block } != false ||
            !physicalLet(player, session.block)) return false
        session.capture = fresh
        return true
    }

    private fun emit(player: ServerPlayer, session: Session, status: String, ready: Boolean = false, closed: Boolean = false) {
        val page = session.page
        val rows = page?.rows.orEmpty().map { row ->
            LetRow(row.id, row.fields.name, row.fields.type.name,
                if (row.fields.mutability == DeclarationMutability.MUTABLE) "VAR" else "VAL", row.ownerStage, row.group.name)
        }
        val state = LetScreenState(session.key, nextSequence(player.uuid), WorldIdentity.dimension(player.level()),
            UUID.fromString(session.block.instanceId), session.view?.target, page?.index ?: 0, page?.hasNext ?: false,
            rows, status.take(192), ready, closed)
        session.ui = state
        if (ServerPlayNetworking.canSend(player, LetStatePayload.TYPE)) ServerPlayNetworking.send(player, LetStatePayload(state))
    }

    private fun physicalLet(player: ServerPlayer, block: StageBlock) = player.level().getBlockState(block.pos).block === ModContent.LET

    private fun message(player: ServerPlayer, text: String) = player.sendSystemMessage(Component.literal("[CK] $text"))
    private fun describe(result: ProgramHttpResult<*>): String = when ((result as? ProgramHttpResult.ApiError)?.error?.errorCode) {
        "STALE_VALUE_BASIS" -> "A Stage vagy a scope megváltozott. Válassz újra."
        "DECLARATION_NOT_REACHABLE" -> "A deklaráció már nem elérhető."
        "FORBIDDEN" -> "A művelethez nincs jogosultságod."
        "OCCUPIED_POSITION" -> "A helyet blokk vagy CK Wire foglalja."
        "BLOCK_NOT_FOUND" -> "A LET már nem létezik."
        else -> (result as? ProgramHttpResult.ApiError)?.error?.errorCode ?: "A LET szolgáltatás nem érhető el."
    }
}

internal fun isUncertainLetResult(result: ProgramHttpResult<*>) =
    result is ProgramHttpResult.Unavailable || result is ProgramHttpResult.ProtocolFailure ||
        (result is ProgramHttpResult.ApiError && result.error.httpStatus >= 500)

private fun ProgramHttpResult<LetView>.basisResult(): ProgramHttpResult<LetBasis> = when (this) {
    is ProgramHttpResult.Success -> ProgramHttpResult.Success(value.basis)
    is ProgramHttpResult.ApiError -> this
    is ProgramHttpResult.Unavailable -> this
    is ProgramHttpResult.ProtocolFailure -> this
}
