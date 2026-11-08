package me.buciqua.blockommander.runtime

import me.buciqua.blockommander.runtime.program.HallSelection
import java.util.UUID

enum class HallSelectionPhase { DISPLAYED, SELECTION_REQUESTED, DESIRED_COMMITTED, PROJECTION_PREPARING, PROJECTION_FAILED }
data class HallSelectionAttempt(val token:StageSwitchToken,val worldUuid:UUID,
    val minimumRevision:Long,val sourceLifecycle:Long?,val knownSelection:HallSelection)
data class HallSelectionState(val attempt:HallSelectionAttempt,val phase:HallSelectionPhase,
    val desired:HallSelection?=null,val failure:String?=null)
