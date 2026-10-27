package me.buciqua.blockommander.runtime

import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

data class ReconnectGraphResult(val synchronized: Boolean, val detail: String? = null)

/** Orders an authoritative reconnect projection without issuing domain mutations. */
class StageReconnectReconciler(
    private val registry: HallRegistry,
    private val projectStage: (Hall) -> Unit,
    private val reconcileGraphAndWire: (Stage) -> CompletableFuture<ReconnectGraphResult>,
    private val projectSaveState: (Hall, Boolean) -> Unit,
    private val diagnose: (String) -> Unit = {},
) {
    fun reconcile(hallJson: JsonObject, fence: StageUpdateFence?): Boolean {
        val hall = runCatching { registry.authoritativeReload(hallJson, fence) }.getOrElse {
            diagnose("STAGE_RELOAD_INVALID: ${it.message}")
            return false
        }
        if (hall == null) {
            diagnose("STAGE_RELOAD_REJECTED")
            return false
        }
        val stage = hall.stage ?: run {
            diagnose("STAGE_RELOAD_MISSING_STAGE")
            return false
        }
        try {
            projectStage(hall)
        } catch (failure: Exception) {
            diagnose("STAGE_PROJECTION_FAILED: ${failure.message}")
            return false
        }

        val graphFuture = runCatching { reconcileGraphAndWire(stage) }.getOrElse {
            completeAfterGraph(stage, ReconnectGraphResult(false, "GRAPH_RECONCILIATION_FAILED: ${it.message}"))
            return true
        }
        graphFuture.whenComplete { result, failure ->
            completeAfterGraph(
                stage,
                if (failure == null) result else ReconnectGraphResult(false, "GRAPH_RECONCILIATION_FAILED: ${failure.message}"),
            )
        }
        return true
    }

    private fun completeAfterGraph(stage: Stage, result: ReconnectGraphResult) {
        if (!result.synchronized) diagnose(result.detail ?: "GRAPH_RECONCILIATION_NOT_SYNCED")
        val currentHall = registry.hallForStage(stage.id) ?: run {
            diagnose("STAGE_LIFECYCLE_CHANGED")
            return
        }
        val currentStage = currentHall.stage ?: return
        if (currentStage.workspaceEpoch != stage.workspaceEpoch ||
            currentStage.workingRevision != stage.workingRevision) {
            diagnose("STAGE_LIFECYCLE_CHANGED")
            return
        }
        val dirty = registry.saveDirty(stage.id) ?: return
        projectSaveState(currentHall, dirty)
    }
}
