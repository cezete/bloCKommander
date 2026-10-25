package me.buciqua.blockommander.runtime.graph

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GraphSyncStatus { NOT_REQUESTED, RECONCILING, SYNCED, STALE, CONFLICT, ERROR }
enum class GraphRequestKind { RECONCILIATION, COMMAND }

data class StageGraphView(
    val stageId: UUID,
    val stageEpoch: UUID,
    val stageRevision: Long,
    val status: GraphSyncStatus,
    val snapshot: GraphStateSnapshot?,
    val problem: String?,
)

class GraphRequestToken internal constructor(
    val stageId: UUID,
    internal val lifecycleGeneration: Long,
    internal val requestGeneration: Long,
    val kind: GraphRequestKind,
)

sealed interface GraphApplyResult {
    data class Applied(val view: StageGraphView) : GraphApplyResult
    data class IgnoredStaleRequest(val view: StageGraphView) : GraphApplyResult
    data class Rejected(val code: String, val view: StageGraphView) : GraphApplyResult
}

class GraphProjectionStore {
    private data class Slot(
        var stageEpoch: UUID,
        var stageRevision: Long,
        var lifecycleGeneration: Long,
        var requestGeneration: Long,
        var status: GraphSyncStatus,
        var snapshot: GraphStateSnapshot?,
        var problem: String?,
    )

    private val slots = ConcurrentHashMap<UUID, Slot>()

    /** A hiteles Stage reload az egyetlen belépési pont, amely epochváltást fogadhat el. */
    fun authoritativeStageReload(stageId: UUID, epoch: UUID, revision: Long): StageGraphView = synchronized(slots) {
        require(revision >= 0) { "A Stage revision nem lehet negatív." }
        val current = slots[stageId]
        if (current == null) {
            slots[stageId] = Slot(epoch, revision, 1, 0, GraphSyncStatus.NOT_REQUESTED, null, null)
        } else if (current.stageEpoch != epoch) {
            current.stageEpoch = epoch
            current.stageRevision = revision
            current.lifecycleGeneration++
            current.requestGeneration++
            current.status = GraphSyncStatus.NOT_REQUESTED
            current.snapshot = null
            current.problem = null
        } else {
            current.stageRevision = maxOf(current.stageRevision, revision)
            if (current.snapshot != null && current.snapshot!!.workingRevision < current.stageRevision) {
                current.status = GraphSyncStatus.STALE
                current.problem = "STAGE_AHEAD_OF_GRAPH"
            }
        }
        view(stageId, slots.getValue(stageId))
    }

    /** Legacy block CRUD után a korábbi graph vetítés többé nem jelölhető aktuálisnak. */
    fun legacyStageUpdated(stageId: UUID, epoch: UUID, revision: Long): StageGraphView = synchronized(slots) {
        val current = slots[stageId]
        if (current == null) {
            val created = Slot(epoch, revision, 1, 0, GraphSyncStatus.STALE, null, "LEGACY_STAGE_UPDATED")
            slots[stageId] = created
            return@synchronized view(stageId, created)
        }
        if (current.stageEpoch != epoch) {
            current.lifecycleGeneration++
            current.requestGeneration++
            current.status = GraphSyncStatus.CONFLICT
            current.problem = "LEGACY_EPOCH_CONFLICT"
            return@synchronized view(stageId, current)
        }
        if (revision < current.stageRevision) {
            return@synchronized view(stageId, current)
        }
        current.stageRevision = revision
        current.lifecycleGeneration++
        current.requestGeneration++
        current.status = GraphSyncStatus.STALE
        current.problem = "LEGACY_STAGE_UPDATED"
        view(stageId, current)
    }

    fun remove(stageId: UUID) {
        synchronized(slots) { slots.remove(stageId) }
    }

    fun begin(stageId: UUID, kind: GraphRequestKind): GraphRequestToken = synchronized(slots) {
        val slot = slots[stageId] ?: throw IllegalStateException("Ismeretlen Stage graph kéréshez: $stageId")
        slot.requestGeneration++
        if (kind == GraphRequestKind.RECONCILIATION) slot.status = GraphSyncStatus.RECONCILING
        slot.problem = null
        GraphRequestToken(stageId, slot.lifecycleGeneration, slot.requestGeneration, kind)
    }

    fun apply(token: GraphRequestToken, snapshot: GraphStateSnapshot): GraphApplyResult = synchronized(slots) {
        val slot = slots[token.stageId] ?: return@synchronized GraphApplyResult.Rejected(
            "UNKNOWN_STAGE", missingView(token.stageId),
        )
        if (token.lifecycleGeneration != slot.lifecycleGeneration || token.requestGeneration != slot.requestGeneration) {
            return@synchronized GraphApplyResult.IgnoredStaleRequest(view(token.stageId, slot))
        }
        fun reject(code: String): GraphApplyResult.Rejected {
            slot.status = GraphSyncStatus.CONFLICT
            slot.problem = code
            return GraphApplyResult.Rejected(code, view(token.stageId, slot))
        }
        if (snapshot.stageId != token.stageId) return@synchronized reject("STAGE_ID_MISMATCH")
        if (snapshot.workspaceEpoch != slot.stageEpoch) return@synchronized reject("EPOCH_CONFLICT")
        if (snapshot.workingRevision < slot.stageRevision) return@synchronized reject("STALE_REVISION")
        val previous = slot.snapshot
        if (previous != null && snapshot.workingRevision < previous.workingRevision) {
            return@synchronized reject("STALE_REVISION")
        }
        if (previous != null && snapshot.workingRevision == previous.workingRevision && snapshot != previous) {
            return@synchronized reject("SAME_REVISION_CONFLICT")
        }
        slot.snapshot = snapshot
        slot.stageRevision = snapshot.workingRevision
        slot.status = GraphSyncStatus.SYNCED
        slot.problem = null
        GraphApplyResult.Applied(view(token.stageId, slot))
    }

    fun fail(token: GraphRequestToken, code: String, uncertain: Boolean): StageGraphView = synchronized(slots) {
        val slot = slots[token.stageId] ?: return@synchronized missingView(token.stageId)
        if (token.lifecycleGeneration == slot.lifecycleGeneration && token.requestGeneration == slot.requestGeneration) {
            slot.status = if (uncertain) GraphSyncStatus.STALE else GraphSyncStatus.ERROR
            slot.problem = code
        }
        view(token.stageId, slot)
    }

    fun view(stageId: UUID): StageGraphView? = synchronized(slots) { slots[stageId]?.let { view(stageId, it) } }

    private fun view(stageId: UUID, slot: Slot) = StageGraphView(
        stageId, slot.stageEpoch, slot.stageRevision, slot.status, slot.snapshot, slot.problem,
    )

    private fun missingView(stageId: UUID) = StageGraphView(
        stageId, UUID(0, 0), 0, GraphSyncStatus.ERROR, null, "UNKNOWN_STAGE",
    )
}
