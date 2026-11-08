package me.buciqua.blockommander.runtime.program

import me.buciqua.blockommander.runtime.*
import me.buciqua.blockommander.runtime.graph.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

enum class NavigationStatus { SUCCESS, NO_OP, FORBIDDEN, REJECTED, STALE, UNAVAILABLE, PROJECTION_FAILED }
data class NavigationResult(
    val status:NavigationStatus,
    val hallId:UUID?,
    val sourceStageId:UUID?,
    val targetStageId:UUID?,
    val detail:String?=null,
)
data class ProgramSwitchContext(
    val programId:UUID,
    val workspaceEpoch:UUID,
    val hierarchyRevision:Long,
)
data class PreparedStageSwitch(
    val target:Stage,
    val graph:GraphStateSnapshot,
    val program:ProgramSwitchContext,
    val programDirty:Boolean = true,
    val projectionLifecycle:Long,
)

interface StageSwitchCommitter {
    fun commit(token:StageSwitchToken,prepared:PreparedStageSwitch):Boolean
}

object StageProjectionAssembler {
    fun assemble(
        hall:Hall,response:ProgramStageResponse,graph:GraphStateSnapshot,projectionLifecycle:Long,
        physicalSelection:Boolean=false,
    ):PreparedStageSwitch {
        val summary=response.stage.summary
        val workspace=summary.workspace ?: throw IllegalArgumentException("A cél-Stage nincs betöltött workspace-ben.")
        require(response.programId==summary.programId) { "PROGRAM_ID_MISMATCH" }
        require(graph.stageId==summary.stageId) { "GRAPH_STAGE_ID_MISMATCH" }
        require(graph.workspaceEpoch==workspace.epoch) { "GRAPH_EPOCH_MISMATCH" }
        require(graph.workingRevision==workspace.workingRevision) { "GRAPH_REVISION_MISMATCH" }
        val layout=summary.domainLayout
        val graphOriginRelative=GraphPosition(
            graph.origin.x-hall.min.x,graph.origin.y-hall.min.y,graph.origin.z-hall.min.z,
        )
        require(graph.origin==layout.origin || graphOriginRelative==layout.origin) {
            "GRAPH_ORIGIN_MISMATCH"
        }
        require(graph.size==layout.size) { "GRAPH_SIZE_MISMATCH" }
        require(graph.graphSnapshotVersion==response.stage.graphSnapshotVersion) { "GRAPH_SNAPSHOT_VERSION_MISMATCH" }
        require(graph.portCatalogVersion==response.stage.portCatalogVersion) { "PORT_CATALOG_VERSION_MISMATCH" }
        require(layout.size.x>0 && layout.size.y>0 && layout.size.z>0) { "INVALID_STAGE_SIZE" }
        val logicalOrigin=hall.min.offset(layout.origin.x,layout.origin.y,layout.origin.z)
        if(physicalSelection) {
            val physical=response.stage.physical ?: error("PHYSICAL_STAGE_METADATA_MISSING")
            require(physical.hallId.toString()==hall.id && physical.worldUuid.toString()==hall.worldUuid &&
                physical.origin==GraphPosition(logicalOrigin.x,logicalOrigin.y,logicalOrigin.z) && physical.size==layout.size &&
                graph.origin==physical.origin && graph.hallId==physical.hallId && graph.worldUuid==physical.worldUuid) {
                "PHYSICAL_STAGE_IDENTITY_MISMATCH" }
        }
        val origin=if(physicalSelection) response.stage.physical!!.origin.let {
            net.minecraft.core.BlockPos(it.x,it.y,it.z)
        } else logicalOrigin
        val maxExclusive=origin.offset(layout.size.x,layout.size.y,layout.size.z)
        require(origin.x>hall.min.x && origin.y>hall.min.y && origin.z>hall.min.z &&
            maxExclusive.x<=hall.max.x && maxExclusive.y<=hall.max.y && maxExclusive.z<=hall.max.z) {
            "STAGE_OUTSIDE_HALL"
        }
        val blocks=response.stage.blocks.map { block ->
            require(block.position.x in 0 until layout.size.x &&
                block.position.y in 0 until layout.size.y && block.position.z in 0 until layout.size.z) {
                "BLOCK_OUTSIDE_STAGE"
            }
            StageBlock(
                block.instanceId.toString(),block.typeKey,
                origin.offset(block.position.x,block.position.y,block.position.z),
                block.orientation,block.label,block.description.orEmpty(),
            )
        }
        require(blocks.map(StageBlock::pos).toSet().size==blocks.size) { "DUPLICATE_BLOCK_POSITION" }
        val target=Stage(
            summary.stageId.toString(),origin,layout.size.x,layout.size.y,layout.size.z,
            workspace.dirty,workspace.epoch,workspace.workingRevision,blocks,
        )
        return PreparedStageSwitch(
            target,graph,ProgramSwitchContext(
                response.programId,response.programWorkspaceEpoch,response.hierarchyRevision,
            ),response.aggregateDirty,projectionLifecycle,
        )
    }
}

class StageNavigationCoordinator(
    private val registry:HallRegistry,
    private val programs:ProgramGateway,
    private val graph:GraphGateway,
    private val mainThread:Executor,
) {
    fun projectSelection(token:StageSwitchToken,selection:HallSelection,player:ServerPlayerIdentity,
        committer:StageSwitchCommitter):CompletableFuture<NavigationResult> = protect(token) {
        prepareAndCommit(token,null,selection.context,selection.stageId,player,committer)
    }
    fun enter(
        hallId:UUID,programBlockInstanceId:UUID,player:ServerPlayerIdentity,
        committer:StageSwitchCommitter,
    ):CompletableFuture<NavigationResult> {
        val token=registry.beginStageSwitch(hallId.toString(),player.uuid)
            ?: return completed(NavigationStatus.FORBIDDEN,hallId,null,null,"OWNER_OR_HALL_MISMATCH")
        val transport=registry.transportContext(token.hallId)
            ?: return reject(token,"TRANSPORT_CONTEXT_MISSING")
        return protect(token) { programs.mountedProgram(transport).thenCompose { mounted ->
            val value=mounted.valueOrFail(token) ?: return@thenCompose completedFrom(token,mounted.failureStatus(),mounted.failureDetail())
            val program=value.program
            if (value.mount.hallId!=hallId || value.mount.programId!=program.programId ||
                value.mount.workingCopyId!=transport.workingCopyId ||
                program.ownerUuid!=player.uuid || program.workspaceState!=ProgramWorkspaceState.ACTIVE ||
                program.stages.none { it.stageId==token.sourceStageId }) {
                return@thenCompose reject(token,"PROGRAM_CONTEXT_MISMATCH")
            }
            programs.ensureChild(
                transport,token.sourceStageId,programBlockInstanceId,player.uuid,
            ).thenCompose { ensured ->
                val child=ensured.valueOrFail(token)
                    ?: return@thenCompose completedFrom(token,ensured.failureStatus(),ensured.failureDetail())
                if (child.programId!=program.programId ||
                    child.programWorkspaceEpoch!=program.workspaceEpoch ||
                    child.link.parentStageId!=token.sourceStageId ||
                    child.link.programBlockInstanceId!=programBlockInstanceId ||
                    child.link.state!=ProgramLinkState.ACTIVE ||
                    child.child.stageId!=child.link.childStageId || child.child.kind!="PROGRAM_BODY") {
                    return@thenCompose reject(token,"CHILD_ENSURE_IDENTITY_MISMATCH")
                }
                prepareAndCommit(
                    token,ProgramSwitchContext(
                        child.programId,child.programWorkspaceEpoch,child.hierarchyRevision,
                    ),transport,child.child.stageId,player,committer,
                )
            }
        } }
    }

    fun back(
        hallId:UUID,player:ServerPlayerIdentity,committer:StageSwitchCommitter,
    ):CompletableFuture<NavigationResult> {
        val token=registry.beginStageSwitch(hallId.toString(),player.uuid)
            ?: return completed(NavigationStatus.FORBIDDEN,hallId,null,null,"OWNER_OR_HALL_MISMATCH")
        val transport=registry.transportContext(token.hallId)
            ?: return reject(token,"TRANSPORT_CONTEXT_MISSING")
        return protect(token) { programs.mountedProgram(transport).thenCompose { mounted ->
            val value=mounted.valueOrFail(token) ?: return@thenCompose completedFrom(token,mounted.failureStatus(),mounted.failureDetail())
            val program=value.program
            if (value.mount.hallId!=hallId || value.mount.workingCopyId!=transport.workingCopyId ||
                program.ownerUuid!=player.uuid ||
                program.workspaceState!=ProgramWorkspaceState.ACTIVE ||
                value.mount.programId!=program.programId ||
                program.stages.none { it.stageId==token.sourceStageId }) {
                return@thenCompose reject(token,"PROGRAM_CONTEXT_MISMATCH")
            }
            if (token.sourceStageId==program.rootStageId) {
                registry.failStageSwitch(token)
                return@thenCompose completedFrom(token,NavigationStatus.NO_OP,"ALREADY_AT_ROOT")
            }
            val parents=program.links.filter {
                it.childStageId==token.sourceStageId && it.state==ProgramLinkState.ACTIVE
            }
            if (parents.size!=1) return@thenCompose reject(token,"ACTIVE_PARENT_NOT_FOUND")
            prepareAndCommit(
                token,ProgramSwitchContext(
                    program.programId,program.workspaceEpoch,program.hierarchyRevision,
                ),transport,parents.single().parentStageId,player,committer,
            )
        } }
    }

    private fun prepareAndCommit(
        token:StageSwitchToken,expectedContext:ProgramSwitchContext?,transport:WorkingCopyTransportContext,targetId:UUID,
        player:ServerPlayerIdentity,committer:StageSwitchCommitter,
    ):CompletableFuture<NavigationResult> = programs.stage(transport,targetId).thenCompose { stageResult ->
        val stageResponse=stageResult.valueOrFail(token)
            ?: return@thenCompose completedFrom(token,stageResult.failureStatus(),stageResult.failureDetail())
        val context=expectedContext ?: ProgramSwitchContext(
            transport.programId,stageResponse.programWorkspaceEpoch,stageResponse.hierarchyRevision)
        val workspace=stageResponse.stage.summary.workspace
            ?: return@thenCompose reject(token,"TARGET_STAGE_NOT_LOADED")
        if (stageResponse.programId!=context.programId ||
            stageResponse.programWorkspaceEpoch!=context.workspaceEpoch ||
            stageResponse.hierarchyRevision!=context.hierarchyRevision ||
            stageResponse.stage.summary.stageId!=targetId) {
            return@thenCompose reject(token,"TARGET_STAGE_IDENTITY_MISMATCH")
        }
        if (!registry.prepareStageSwitchProjection(
                token,context.programId,context.workspaceEpoch,context.hierarchyRevision,targetId,
                workspace.epoch,workspace.workingRevision,
            )) return@thenCompose stale(token,targetId,"SWITCH_GENERATION_CHANGED")
        graph.reconcile(transport,targetId,player).thenCompose { outcome ->
            val view=registry.graphProjections.view(transport.projectionKey(targetId))
            if (!registry.isStageSwitchCurrent(
                    token,context.programId,targetId,context.workspaceEpoch,context.hierarchyRevision,
                )) return@thenCompose stale(token,targetId,"SWITCH_GENERATION_CHANGED")
            if (outcome.application !is GraphApplyResult.Applied || view?.status!=GraphSyncStatus.SYNCED ||
                view.snapshot==null || view.stageEpoch!=workspace.epoch ||
                view.stageRevision!=workspace.workingRevision) {
                return@thenCompose reject(token,"GRAPH_NOT_AUTHORITATIVE")
            }
            val hall=registry.hall(token.hallId) ?: return@thenCompose reject(token,"HALL_NOT_FOUND")
            val prepared=try { StageProjectionAssembler.assemble(hall.copy(transportContext=transport),
                stageResponse,view.snapshot,view.lifecycleGeneration,token.selection) }
                catch (failure:Exception) { return@thenCompose reject(token,failure.message ?: "PROJECTION_INVALID") }
            CompletableFuture.supplyAsync({
                if (!registry.isStageSwitchCurrent(
                        token,context.programId,targetId,context.workspaceEpoch,context.hierarchyRevision,
                    ) || !registry.graphProjections.isLifecycleCurrent(
                        transport.projectionKey(targetId),prepared.projectionLifecycle,
                    )) terminalFailure(token,NavigationStatus.STALE,targetId,
                    "SWITCH_GENERATION_CHANGED",
                ) else if (committer.commit(token,prepared)) NavigationResult(
                    NavigationStatus.SUCCESS,UUID.fromString(token.hallId),token.sourceStageId,targetId,
                ) else terminalFailure(token,NavigationStatus.PROJECTION_FAILED,targetId,
                    "WORLD_PROJECTION_FAILED",
                )
            },mainThread)
        }
    }

    private fun terminalFailure(token:StageSwitchToken,status:NavigationStatus,target:UUID?,detail:String):NavigationResult {
        registry.failStageSwitch(token)
        return NavigationResult(status,UUID.fromString(token.hallId),token.sourceStageId,target,detail)
    }

    private fun protect(token:StageSwitchToken, action:()->CompletableFuture<NavigationResult>):CompletableFuture<NavigationResult> =
        try { action().exceptionally { terminalFailure(token,NavigationStatus.UNAVAILABLE,null,"NAVIGATION_FAILED") } }
        catch (_:Exception) { CompletableFuture.completedFuture(
            terminalFailure(token,NavigationStatus.UNAVAILABLE,null,"NAVIGATION_FAILED")) }

    private fun reject(token:StageSwitchToken,detail:String):CompletableFuture<NavigationResult> {
        registry.failStageSwitch(token)
        return completedFrom(token,NavigationStatus.REJECTED,detail)
    }
    private fun stale(token:StageSwitchToken,target:UUID?,detail:String):CompletableFuture<NavigationResult> {
        registry.failStageSwitch(token)
        return completed(NavigationStatus.STALE,UUID.fromString(token.hallId),token.sourceStageId,target,detail)
    }
    private fun completedFrom(token:StageSwitchToken,status:NavigationStatus,detail:String?)=completed(
        status,UUID.fromString(token.hallId),token.sourceStageId,null,detail,
    )
    private fun completed(
        status:NavigationStatus,hall:UUID?,source:UUID?,target:UUID?,detail:String?,
    )=CompletableFuture.completedFuture(NavigationResult(status,hall,source,target,detail))

    private fun <T> ProgramHttpResult<T>.valueOrFail(token:StageSwitchToken):T?=when(this) {
        is ProgramHttpResult.Success -> value
        else -> { registry.failStageSwitch(token);null }
    }
    private fun ProgramHttpResult<*>.failureStatus()=when(this) {
        is ProgramHttpResult.Unavailable -> NavigationStatus.UNAVAILABLE
        is ProgramHttpResult.ProtocolFailure,is ProgramHttpResult.ApiError -> NavigationStatus.REJECTED
        is ProgramHttpResult.Success -> NavigationStatus.REJECTED
    }
    private fun ProgramHttpResult<*>.failureDetail()=when(this) {
        is ProgramHttpResult.Unavailable -> reason
        is ProgramHttpResult.ProtocolFailure -> reason
        is ProgramHttpResult.ApiError -> error.errorCode
        is ProgramHttpResult.Success -> null
    }
}
