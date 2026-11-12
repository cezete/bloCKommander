package me.buciqua.blockommander.runtime.graph

import java.util.UUID
import me.buciqua.blockommander.runtime.ProjectionKey
import me.buciqua.blockommander.runtime.Diagnostics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class GraphSyncStatus { NOT_REQUESTED, RECONCILING, SYNCED, STALE, CONFLICT, ERROR }
enum class GraphRequestKind { RECONCILIATION, COMMAND }

data class StageGraphView(
    val key: ProjectionKey,
    val stageEpoch: UUID,
    val stageRevision: Long,
    val status: GraphSyncStatus,
    val snapshot: GraphStateSnapshot?,
    val problem: String?,
    val lifecycleGeneration: Long,
) {
    val stageId get() = key.stageId
}

class GraphRequestToken internal constructor(
    val key: ProjectionKey,
    internal val lifecycleGeneration: Long,
    internal val requestGeneration: Long,
    val kind: GraphRequestKind,
)

sealed interface GraphApplyResult {
    data class Applied(val view: StageGraphView) : GraphApplyResult
    data class IgnoredStaleRequest(val view: StageGraphView) : GraphApplyResult
    data class Rejected(val code: String, val view: StageGraphView) : GraphApplyResult
}

/** Read-only fence: detects requests started after a metadata GET, even if their result is identical. */
internal data class GraphProjectionObservation(val view: StageGraphView, val requestGeneration: Long, val pending: Boolean)

class GraphProjectionStore {
    private data class Slot(
        var stageEpoch: UUID,
        var stageRevision: Long,
        var lifecycleGeneration: Long,
        var requestGeneration: Long,
        var status: GraphSyncStatus,
        var snapshot: GraphStateSnapshot?,
        var problem: String?,
        val pending: MutableSet<Long> = mutableSetOf(),
    )

    private val slots = ConcurrentHashMap<ProjectionKey, Slot>()
    private val lifecycles = AtomicLong()
    private fun nextLifecycle() = lifecycles.incrementAndGet()

    /** A hiteles Stage reload az egyetlen belépési pont, amely epochváltást fogadhat el. */
    fun authoritativeStageReload(key: ProjectionKey, epoch: UUID, revision: Long): StageGraphView = synchronized(slots) {
        require(revision >= 0) { "A Stage revision nem lehet negatív." }
        val current = slots[key]
        if (current == null) {
            slots[key] = Slot(epoch, revision, nextLifecycle(), 0, GraphSyncStatus.NOT_REQUESTED, null, null)
        } else if (current.stageEpoch != epoch) {
            current.stageEpoch = epoch
            current.stageRevision = revision
            current.lifecycleGeneration = nextLifecycle()
            current.requestGeneration++
            current.pending.clear()
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
        view(key, slots.getValue(key))
    }

    /**
     * A reconnect authoritative Stage GET-je után a következő Graph GET új vetítést épít.
     * Azonos epochnál is leválasztja a reconnect előtt indult kéréseket és snapshotot.
     */
    fun authoritativeStageReconciliation(key: ProjectionKey, epoch: UUID, revision: Long): StageGraphView = synchronized(slots) {
        require(revision >= 0) { "A Stage revision nem lehet negatív." }
        val current = slots[key]
        if (current == null) {
            slots[key] = Slot(epoch, revision, nextLifecycle(), 0, GraphSyncStatus.NOT_REQUESTED, null, null)
        } else {
            current.stageEpoch = epoch
            current.stageRevision = revision
            current.lifecycleGeneration = nextLifecycle()
            current.requestGeneration++
            current.pending.clear()
            current.status = GraphSyncStatus.NOT_REQUESTED
            current.snapshot = null
            current.problem = null
        }
        view(key, slots.getValue(key))
    }

    /** Legacy block CRUD után a korábbi graph vetítés többé nem jelölhető aktuálisnak. */
    fun legacyStageUpdated(key: ProjectionKey, epoch: UUID, revision: Long): StageGraphView = synchronized(slots) {
        val current = slots[key]
        if (current == null) {
            val created = Slot(epoch, revision, nextLifecycle(), 0, GraphSyncStatus.STALE, null, "LEGACY_STAGE_UPDATED")
            slots[key] = created
            return@synchronized view(key, created)
        }
        if (current.stageEpoch != epoch) {
            current.lifecycleGeneration = nextLifecycle()
            current.requestGeneration++
            current.pending.clear()
            current.status = GraphSyncStatus.CONFLICT
            current.problem = "LEGACY_EPOCH_CONFLICT"
            return@synchronized view(key, current)
        }
        if (revision < current.stageRevision) {
            return@synchronized view(key, current)
        }
        current.stageRevision = revision
        current.lifecycleGeneration = nextLifecycle()
        current.requestGeneration++
        current.pending.clear()
        current.status = GraphSyncStatus.STALE
        current.problem = "LEGACY_STAGE_UPDATED"
        view(key, current)
    }

    fun remove(key: ProjectionKey) {
        synchronized(slots) { slots.remove(key) }
    }

    fun begin(key: ProjectionKey, kind: GraphRequestKind): GraphRequestToken = synchronized(slots) {
        val slot = slots[key] ?: throw IllegalStateException("Ismeretlen Stage graph kéréshez: $key")
        slot.requestGeneration++
        slot.pending.add(slot.requestGeneration)
        if (kind == GraphRequestKind.RECONCILIATION) slot.status = GraphSyncStatus.RECONCILING
        slot.problem = null
        GraphRequestToken(key, slot.lifecycleGeneration, slot.requestGeneration, kind)
    }

    /** The returned token belongs to the checked incarnation; callers must not reserve again. */
    fun beginIfLifecycleCurrent(key: ProjectionKey, expected: Long, kind: GraphRequestKind): GraphRequestToken? =
        synchronized(slots) {
            if (slots[key]?.lifecycleGeneration!=expected) null else begin(key,kind)
        }

    fun removeIfLifecycleCurrent(key: ProjectionKey, expected: Long): Boolean = synchronized(slots) {
        if (slots[key]?.lifecycleGeneration!=expected) false
        else { slots.remove(key); true }
    }

    fun apply(token: GraphRequestToken, snapshot: GraphStateSnapshot): GraphApplyResult = synchronized(slots) {
        val prior=slots[token.key]
        val priorEpoch=prior?.stageEpoch;val priorRevision=prior?.stageRevision
        fun result(value:GraphApplyResult,reason:String):GraphApplyResult {
            Diagnostics.event("GRAPH_STORE_APPLY",token.key,"reason" to reason,
                "prior_epoch" to priorEpoch,"prior_revision" to priorRevision,
                "response_epoch" to snapshot.workspaceEpoch,"response_revision" to snapshot.workingRevision,
                "response_stage_id" to snapshot.stageId,"request_kind" to token.kind,
                "requested_lifecycle" to token.lifecycleGeneration,"slot_lifecycle" to prior?.lifecycleGeneration,
                "requested_generation" to token.requestGeneration,"slot_generation" to prior?.requestGeneration)
            return value
        }
        val slot = slots[token.key] ?: return@synchronized GraphApplyResult.Rejected(
            "UNKNOWN_STAGE", missingView(token.key),
        ).let { result(it,"UNKNOWN_STAGE") }
        if (token.lifecycleGeneration == slot.lifecycleGeneration) slot.pending.remove(token.requestGeneration)
        if (token.lifecycleGeneration != slot.lifecycleGeneration || token.requestGeneration != slot.requestGeneration) {
            return@synchronized result(GraphApplyResult.IgnoredStaleRequest(view(token.key, slot)),"STALE_REQUEST_TOKEN")
        }
        fun reject(code: String): GraphApplyResult {
            slot.status = GraphSyncStatus.CONFLICT
            slot.problem = code
            return result(GraphApplyResult.Rejected(code, view(token.key, slot)),code)
        }
        if (snapshot.stageId != token.key.stageId) return@synchronized reject("STAGE_ID_MISMATCH")
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
        result(GraphApplyResult.Applied(view(token.key, slot)),"ACCEPTED")
    }

    fun fail(token: GraphRequestToken, code: String, uncertain: Boolean): StageGraphView = synchronized(slots) {
        val slot = slots[token.key] ?: return@synchronized missingView(token.key)
        if (token.lifecycleGeneration == slot.lifecycleGeneration) slot.pending.remove(token.requestGeneration)
        if (token.lifecycleGeneration == slot.lifecycleGeneration && token.requestGeneration == slot.requestGeneration) {
            slot.status = if (uncertain) GraphSyncStatus.STALE else GraphSyncStatus.ERROR
            slot.problem = code
        }
        view(token.key, slot)
    }

    fun view(key: ProjectionKey): StageGraphView? = synchronized(slots) { slots[key]?.let { view(key, it) } }

    internal fun observe(key: ProjectionKey): GraphProjectionObservation? = synchronized(slots) {
        slots[key]?.let { GraphProjectionObservation(view(key, it), it.requestGeneration, it.pending.isNotEmpty()) }
    }

    internal fun isObservationCurrent(expected: GraphProjectionObservation): Boolean = synchronized(slots) {
        observe(expected.view.key) == expected
    }

    /** Reuses ordinary apply validation and supersedes older callbacks without a lifecycle/navigation change. */
    internal fun applyObserved(expected: GraphProjectionObservation, snapshot: GraphStateSnapshot): GraphApplyResult? = synchronized(slots) {
        if (expected.pending || !isObservationCurrent(expected)) return@synchronized null
        apply(begin(expected.view.key, GraphRequestKind.RECONCILIATION), snapshot)
    }

    fun removeHall(hallId: UUID) = synchronized(slots) {
        slots.keys.removeIf { it.hallId == hallId }
    }

    fun isLifecycleCurrent(key: ProjectionKey, generation: Long): Boolean = synchronized(slots) {
        slots[key]?.lifecycleGeneration == generation
    }

    private fun view(key: ProjectionKey, slot: Slot) = StageGraphView(
        key, slot.stageEpoch, slot.stageRevision, slot.status, slot.snapshot, slot.problem, slot.lifecycleGeneration,
    )

    private fun missingView(key: ProjectionKey) = StageGraphView(
        key, UUID(0, 0), 0, GraphSyncStatus.ERROR, null, "UNKNOWN_STAGE", -1,
    )
}
