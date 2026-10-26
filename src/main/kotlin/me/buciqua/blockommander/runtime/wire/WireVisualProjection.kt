package me.buciqua.blockommander.runtime.wire

import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.graph.GraphStateSnapshot

enum class WireVisualState { UNBOUND, BOUND_VALID, BOUND_NEEDS_REPAIR, UNKNOWN }

/** Pure display-only mapping. It deliberately performs no route discovery or validation. */
object WireVisualProjection {
    private val knownNonValidStatuses = setOf("INCOMPLETE", "BROKEN", "AMBIGUOUS", "ILLEGAL")

    fun states(snapshot: GraphStateSnapshot): Map<GraphPosition, WireVisualState> {
        val edgeIds = snapshot.edges.mapTo(hashSetOf()) { it.edgeId }
        return snapshot.wireCells.associate { cell ->
            val routeStates = snapshot.routes.asSequence()
                .filter { route -> route.steps.any { it.position == cell.position } }
                .map { route ->
                    when {
                        route.edgeId == null -> WireVisualState.UNBOUND
                        route.edgeId !in edgeIds -> WireVisualState.UNKNOWN
                        route.status == "VALID" -> WireVisualState.BOUND_VALID
                        route.status in knownNonValidStatuses -> WireVisualState.BOUND_NEEDS_REPAIR
                        else -> WireVisualState.UNKNOWN
                    }
                }.toList()
            cell.position to aggregate(routeStates)
        }
    }

    private fun aggregate(states: List<WireVisualState>): WireVisualState = when {
        WireVisualState.UNKNOWN in states -> WireVisualState.UNKNOWN
        WireVisualState.BOUND_NEEDS_REPAIR in states -> WireVisualState.BOUND_NEEDS_REPAIR
        WireVisualState.BOUND_VALID in states -> WireVisualState.BOUND_VALID
        else -> WireVisualState.UNBOUND
    }
}
