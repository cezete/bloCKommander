package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.ServerPlayerIdentity
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Selection transport/handoff only; all world planning/commit stays in StageNavigation. */
class HallSelectionCoordinator(private val registry:HallRegistry,private val api:HallSelectionGateway,
    private val navigation:StageNavigationCoordinator,private val main:Executor) {
    fun select(hall:UUID,world:UUID,player:ServerPlayerIdentity,program:UUID,stage:UUID,
        committer:StageSwitchCommitter,currentWorld:()->UUID={world})=
        run(hall,world,player,program to stage,committer,currentWorld)
    fun retry(hall:UUID,world:UUID,player:ServerPlayerIdentity,committer:StageSwitchCommitter,
        currentWorld:()->UUID={world})=run(hall,world,player,null,committer,currentWorld)

    private fun run(hall:UUID,world:UUID,player:ServerPlayerIdentity,target:Pair<UUID,UUID>?,
        committer:StageSwitchCommitter,currentWorld:()->UUID):CompletableFuture<NavigationResult> {
        val attempt=registry.beginHallSelection(hall.toString(),player.uuid,world)
            ?: return CompletableFuture.completedFuture(NavigationResult(NavigationStatus.FORBIDDEN,hall,null,null,"OWNER_OR_WORLD_MISMATCH"))
        fun current()=registry.selectionCurrent(attempt) && currentWorld()==world
        fun failed(detail:String,status:NavigationStatus=NavigationStatus.PROJECTION_FAILED):NavigationResult {
            registry.failSelection(attempt,detail)
            return NavigationResult(status,hall,attempt.token.sourceStageId,target?.second,detail)
        }
        fun value(result:ProgramHttpResult<SelectionResponse>):SelectionResponse = when(result) {
            is ProgramHttpResult.Success -> result.value
            is ProgramHttpResult.ApiError -> error(result.error.errorCode)
            is ProgramHttpResult.ProtocolFailure -> error(result.reason)
            is ProgramHttpResult.Unavailable -> error(result.reason)
        }
        val guardedCommitter=object:StageSwitchCommitter {
            override fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch)=
                current() && committer.commit(token,prepared)
        }
        return try { api.get(hall,world,player.uuid).thenComposeAsync({ read ->
            if(!current()) return@thenComposeAsync CompletableFuture.completedFuture(failed("SELECTION_STALE",NavigationStatus.STALE))
            val base=value(read).selection
            require(base.context.hallId==hall && base.worldUuid==world && base.revision>=attempt.minimumRevision) { "SELECTION_REVISION_OR_IDENTITY_MISMATCH" }
            require(base.revision!=attempt.minimumRevision || base==attempt.knownSelection) { "SAME_SELECTION_REVISION_CONFLICT" }
            val desired=if(target==null) CompletableFuture.completedFuture(SelectionResponse(base))
                else api.put(base,target.first,target.second,player.uuid).thenApply { result ->
                    val response=value(result);val selected=response.selection
                    require(selected.context.hallId==hall && selected.worldUuid==world &&
                        selected.context.programId==target.first && selected.stageId==target.second) { "PUT_TARGET_MISMATCH" }
                    val changed=response.changed ?: error("MISSING_CHANGED")
                    val same=base.context.programId==target.first && base.stageId==target.second
                    require(changed!=same && selected.revision==Math.addExact(base.revision,if(changed) 1L else 0L)) { "PUT_REVISION_MISMATCH" }
                    require(if(base.context.programId==target.first) selected.context.workingCopyId==base.context.workingCopyId
                        else selected.context.workingCopyId!=base.context.workingCopyId) { "WORKING_COPY_HANDOFF_MISMATCH" }
                    response
                }
            desired.thenComposeAsync({ response ->
                if(!current()) return@thenComposeAsync CompletableFuture.completedFuture(failed("SELECTION_STALE",NavigationStatus.STALE))
                if(!registry.acceptSelection(attempt,response.selection) || !registry.selectionPreparing(attempt))
                    return@thenComposeAsync CompletableFuture.completedFuture(failed("SELECTION_STALE",NavigationStatus.STALE))
                navigation.projectSelection(attempt.token,response.selection,player,guardedCommitter)
                    .thenApplyAsync({ result ->
                        if(result.status!=NavigationStatus.SUCCESS) registry.failSelection(attempt,result.detail ?: "PROJECTION_FAILED")
                        result
                    },main)
            },main)
        },main).exceptionallyAsync({ failure -> failed(
            failure.cause?.message ?: failure.message ?: "SELECTION_FAILED") },main) }
        catch(failure:Exception) { CompletableFuture.completedFuture(failed(failure.message ?: "SELECTION_FAILED")) }
    }
}
