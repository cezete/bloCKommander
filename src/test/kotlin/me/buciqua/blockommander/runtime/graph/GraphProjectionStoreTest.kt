package me.buciqua.blockommander.runtime.graph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GraphProjectionStoreTest {
    @Test fun `fetched empty graph differs from never fetched`() {
        val store = GraphProjectionStore()
        assertEquals(GraphSyncStatus.NOT_REQUESTED, store.authoritativeStageReload(STAGE_ID, EPOCH_A, 0).status)
        val token = store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION)
        val applied = store.apply(token, snapshot(revision = 0)) as GraphApplyResult.Applied
        assertEquals(GraphSyncStatus.SYNCED, applied.view.status)
        assertNotNull(applied.view.snapshot)
        assertTrue(applied.view.snapshot!!.nodes.isEmpty())
    }

    @Test fun `lower revision cannot overwrite higher revision`() {
        val store = seeded(5)
        val result = store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(revision = 4))
        assertEquals("STALE_REVISION", (result as GraphApplyResult.Rejected).code)
        assertEquals(5, result.view.snapshot!!.workingRevision)
    }

    @Test fun `same revision same snapshot is idempotent`() {
        val store = seeded(3)
        assertTrue(store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot()) is GraphApplyResult.Applied)
    }

    @Test fun `same revision different snapshot conflicts and preserves prior`() {
        val store = seeded(3)
        val result = store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(dirty = false))
        assertEquals("SAME_REVISION_CONFLICT", (result as GraphApplyResult.Rejected).code)
        assertTrue(result.view.snapshot!!.dirty)
    }

    @Test fun `different epoch is not automatically accepted`() {
        val store = seeded(3)
        val result = store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_B, revision = 4))
        assertEquals("EPOCH_CONFLICT", (result as GraphApplyResult.Rejected).code)
        assertEquals(EPOCH_A, result.view.snapshot!!.workspaceEpoch)
    }

    @Test fun `authoritative lifecycle reload accepts new epoch and clears old snapshot`() {
        val store = seeded(3)
        val reloaded = store.authoritativeStageReload(STAGE_ID, EPOCH_B, 0)
        assertNull(reloaded.snapshot)
        val result = store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(epoch = EPOCH_B, revision = 0))
        assertTrue(result is GraphApplyResult.Applied)
    }

    @Test fun `authoritative reconnect clears same epoch dirty snapshot and fences pending response`() {
        val store = seeded(3)
        val pending = store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION)

        val reset = store.authoritativeStageReconciliation(STAGE_ID, EPOCH_A, 3)
        val late = store.apply(pending, snapshot(revision = 4))
        val clean = store.apply(
            store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION),
            snapshot(revision = 3, dirty = false),
        )

        assertEquals(GraphSyncStatus.NOT_REQUESTED, reset.status)
        assertNull(reset.snapshot)
        assertTrue(late is GraphApplyResult.IgnoredStaleRequest)
        assertTrue(clean is GraphApplyResult.Applied)
        assertFalse(store.view(STAGE_ID)!!.snapshot!!.dirty)
    }

    @Test fun `old asynchronous response cannot overwrite newer request`() {
        val store = GraphProjectionStore()
        store.authoritativeStageReload(STAGE_ID, EPOCH_A, 0)
        val old = store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION)
        val fresh = store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION)
        store.apply(fresh, snapshot(revision = 2))
        val result = store.apply(old, snapshot(revision = 1))
        assertTrue(result is GraphApplyResult.IgnoredStaleRequest)
        assertEquals(2, store.view(STAGE_ID)!!.snapshot!!.workingRevision)
    }

    @Test fun `stages remain isolated`() {
        val store = seeded(1)
        store.authoritativeStageReload(OTHER_STAGE_ID, EPOCH_B, 0)
        store.apply(store.begin(OTHER_STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(OTHER_STAGE_ID, EPOCH_B, 2))
        assertEquals(1, store.view(STAGE_ID)!!.snapshot!!.workingRevision)
        assertEquals(2, store.view(OTHER_STAGE_ID)!!.snapshot!!.workingRevision)
    }

    @Test fun `legacy CRUD invalidates projection and pending response`() {
        val store = seeded(3)
        val pending = store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION)
        assertEquals(GraphSyncStatus.STALE, store.legacyStageUpdated(STAGE_ID, EPOCH_A, 4).status)
        assertTrue(store.apply(pending, snapshot(revision = 5)) is GraphApplyResult.IgnoredStaleRequest)
    }

    @Test fun `late legacy response cannot switch epoch or lower revision`() {
        val store = seeded(5)
        val epochConflict = store.legacyStageUpdated(STAGE_ID, EPOCH_B, 8)
        assertEquals(EPOCH_A, epochConflict.stageEpoch)
        assertEquals(5, epochConflict.stageRevision)
        assertEquals(GraphSyncStatus.CONFLICT, epochConflict.status)
        val lower = store.legacyStageUpdated(STAGE_ID, EPOCH_A, 4)
        assertEquals(5, lower.stageRevision)
    }

    private fun seeded(revision: Long): GraphProjectionStore = GraphProjectionStore().also { store ->
        store.authoritativeStageReload(STAGE_ID, EPOCH_A, 0)
        store.apply(store.begin(STAGE_ID, GraphRequestKind.RECONCILIATION), snapshot(revision = revision))
    }
}
