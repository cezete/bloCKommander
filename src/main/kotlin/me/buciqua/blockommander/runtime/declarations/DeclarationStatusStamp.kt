package me.buciqua.blockommander.runtime.declarations

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.GraphSyncStatus
import java.util.UUID

/** Local observation, not Python's resolution basis. Retains dirty-state checks
 * as well as identity, versions and lifecycle for every mounted Hall. */
internal data class DeclarationHallStamp(
    val hall: UUID, val transport: WorkingCopyTransportContext, val world: String,
    val stage: String?, val epoch: UUID?, val revision: Long?, val programDirty: Boolean,
    val graphEpoch: UUID?, val graphRevision: Long?, val graphStatus: GraphSyncStatus?, val projection: Long?,
    val hallGeneration: Long?, val navigation: Long?, val navigating: Boolean?,
    val programEpoch: UUID?, val hierarchy: Long?,
)
internal data class DeclarationProgramStamp(val halls: List<DeclarationHallStamp>)

internal class DeclarationOwnStatusChange private constructor(val before: DeclarationProgramStamp, val after: DeclarationProgramStamp) {
    companion object {
        /** Only the initiating Hall's false→true (or true→true) status is owned.
         * Any other Hall/status/content/lifecycle change invalidates this proof. */
        fun verified(before: DeclarationProgramStamp, after: DeclarationProgramStamp, target: UUID): DeclarationOwnStatusChange? {
            if (before.halls.count { it.hall == target } != 1 || after.halls != before.halls.map {
                    if (it.hall == target) it.copy(programDirty = true) else it
                }) return null
            return DeclarationOwnStatusChange(before, after)
        }
    }
}

internal fun declarationProgramStamp(registry: HallRegistry, halls: List<Hall>): DeclarationProgramStamp =
    DeclarationProgramStamp(halls.sortedBy { it.id }.map { hall ->
        val graph = hall.stage?.let { registry.graphProjections.view(hall.projectionKey()) }
        val navigation = registry.navigationState(hall.id)
        val fence = registry.stageUpdateFenceForHall(hall.id)
        DeclarationHallStamp(UUID.fromString(hall.id), hall.transportContext, hall.worldUuid, hall.stage?.id,
            hall.stage?.workspaceEpoch, hall.stage?.workingRevision, hall.programDirty,
            graph?.stageEpoch, graph?.stageRevision, graph?.status, graph?.lifecycleGeneration,
            fence?.hallGeneration, navigation?.switchGeneration, navigation?.switchInProgress,
            navigation?.programWorkspaceEpoch, navigation?.hierarchyRevision)
    })
