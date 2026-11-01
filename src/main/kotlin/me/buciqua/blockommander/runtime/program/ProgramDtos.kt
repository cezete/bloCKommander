package me.buciqua.blockommander.runtime.program

import com.google.gson.*
import me.buciqua.blockommander.runtime.graph.GraphPosition
import java.math.BigDecimal
import java.util.UUID

const val PROGRAM_API_VERSION = "ck-program-v1"

enum class ProgramWorkspaceState { ACTIVE, RELOAD_REQUIRED }
enum class ProgramLinkState { ACTIVE, RETAINED }

data class ProgramMount(val hallId: UUID, val programId: UUID, val current: Boolean)
data class ProgramLink(
    val parentStageId: UUID,
    val programBlockInstanceId: UUID,
    val childStageId: UUID,
    val state: ProgramLinkState,
)
data class ProgramStageWorkspace(val epoch: UUID, val workingRevision: Long, val dirty: Boolean)
data class ProgramDomainLayout(val origin: GraphPosition, val size: GraphPosition)
data class ProgramStageSummary(
    val stageId: UUID,
    val programId: UUID,
    val kind: String,
    val name: String,
    val isRoot: Boolean,
    val savedVersion: Long,
    val workspace: ProgramStageWorkspace?,
    val domainLayout: ProgramDomainLayout,
    val parents: List<ProgramLink>,
    val children: List<ProgramLink>,
)
data class ProgramStageBlock(
    val instanceId: UUID,
    val typeKey: String,
    val position: GraphPosition,
    val orientation: String,
    val label: String,
    val description: String?,
)
data class ProgramStageProjection(
    val summary: ProgramStageSummary,
    val graphSnapshotVersion: Long,
    val portCatalogVersion: String,
    val blocks: List<ProgramStageBlock>,
)
data class ProgramSnapshot(
    val programId: UUID,
    val ownerUuid: UUID,
    val rootStageId: UUID,
    val savedVersion: Long,
    val aggregateDirty: Boolean,
    val hierarchyDirty: Boolean,
    val hierarchyRevision: Long,
    val workspaceEpoch: UUID,
    val workspaceState: ProgramWorkspaceState,
    val stages: List<ProgramStageSummary>,
    val links: List<ProgramLink>,
    val mounts: List<ProgramMount>,
)
data class MountedProgramResponse(val mount: ProgramMount, val program: ProgramSnapshot)
data class ProgramViewResponse(val program: ProgramSnapshot)
data class ProgramStageResponse(
    val programId: UUID,
    val programSavedVersion: Long,
    val programWorkspaceEpoch: UUID,
    val hierarchyRevision: Long,
    val stage: ProgramStageProjection,
)
data class ChildEnsureResponse(
    val programId: UUID,
    val programSavedVersion: Long,
    val programWorkspaceEpoch: UUID,
    val hierarchyRevision: Long,
    val created: Boolean,
    val link: ProgramLink,
    val child: ProgramStageSummary,
)
data class ProgramDiagnostic(val code: String, val severity: String, val message: String, val field: String?)
data class ProgramApiError(
    val httpStatus: Int,
    val errorCode: String,
    val message: String,
    val diagnostics: List<ProgramDiagnostic>,
)

class ProgramProtocolException(message: String) : RuntimeException(message)

object ProgramResponseParser {
    fun mounted(text: String): MountedProgramResponse = success(text).let { root ->
        val mount=parseMount(root.requiredObject("mount"))
        val program=parseProgram(root.requiredObject("program"))
        if (mount.programId!=program.programId) fail("A mount és Program azonosítója eltér.")
        MountedProgramResponse(mount,program)
    }

    fun program(text: String): ProgramViewResponse = success(text).let {
        ProgramViewResponse(parseProgram(it.requiredObject("program")))
    }

    fun stage(text: String): ProgramStageResponse = success(text).let { root ->
        val programId=root.requiredUuid("program_id")
        val stage=parseStageProjection(root.requiredObject("stage"))
        if (stage.summary.programId!=programId) fail("A Stage másik Programhoz tartozik.")
        ProgramStageResponse(
            programId,root.requiredLong("program_saved_version"),
            root.requiredUuid("program_workspace_epoch"),root.requiredLong("hierarchy_revision"),stage,
        )
    }

    fun child(text: String): ChildEnsureResponse = success(text).let { root ->
        val programId=root.requiredUuid("program_id")
        val link=parseLink(root.requiredObject("link"))
        val child=parseStageSummary(root.requiredObject("child"))
        if (child.programId!=programId || child.stageId!=link.childStageId) {
            fail("A child ensure válasz identity mezői eltérnek.")
        }
        ChildEnsureResponse(
            programId,root.requiredLong("program_saved_version"),
            root.requiredUuid("program_workspace_epoch"),root.requiredLong("hierarchy_revision"),
            root.requiredBoolean("created"),link,child,
        )
    }

    fun error(httpStatus: Int,text: String): ProgramApiError {
        val root=parseRoot(text)
        version(root)
        if (root.requiredString("status")!="error") fail("A hibaválasz státusza nem error.")
        return ProgramApiError(
            httpStatus,root.requiredString("error_code"),root.requiredString("message"),
            root.requiredArray("diagnostics").mapObjects { diagnostic ->
                ProgramDiagnostic(
                    diagnostic.requiredString("code"),diagnostic.requiredString("severity"),
                    diagnostic.requiredString("message"),diagnostic.optionalString("field"),
                )
            },
        )
    }

    private fun success(text:String):JsonObject=parseRoot(text).also {
        version(it)
        if (it.requiredString("status")!="ok") fail("A sikeres Program-válasz státusza nem ok.")
    }
    private fun version(root:JsonObject) {
        val version=root.requiredString("api_version")
        if (version!=PROGRAM_API_VERSION) fail("Nem támogatott Program API-verzió: $version")
    }
    private fun parseRoot(text:String)=try { JsonParser.parseString(text).asJsonObject }
        catch (_:Exception) { fail("A Program API válasza nem érvényes JSON objektum.") }

    private fun parseProgram(obj:JsonObject):ProgramSnapshot {
        val id=obj.requiredUuid("program_id")
        val stages=obj.requiredArray("stages").mapObjects(::parseStageSummary)
        if (stages.any { it.programId!=id }) fail("A Program Stage-listája idegen Stage-et tartalmaz.")
        if (stages.map(ProgramStageSummary::stageId).toSet().size!=stages.size) fail("A Program Stage-listája ismétlődő azonosítót tartalmaz.")
        val root=obj.requiredUuid("root_stage_id")
        if (stages.count { it.isRoot }!=1 || stages.none { it.stageId==root && it.isRoot }) fail("A Program root Stage-e hiányzik vagy nem egyedi.")
        val links=obj.requiredArray("links").mapObjects(::parseLink)
        val stageIds=stages.map(ProgramStageSummary::stageId).toSet()
        if (links.any { it.parentStageId !in stageIds || it.childStageId !in stageIds }) fail("A Program linkje idegen Stage-re mutat.")
        val mounts=obj.requiredArray("mounts").mapObjects(::parseMount)
        if (mounts.any { it.programId!=id }) fail("A Program mount-listája idegen Programot tartalmaz.")
        return ProgramSnapshot(
            id,obj.requiredUuid("owner_uuid"),root,obj.requiredLong("saved_version"),
            obj.requiredBoolean("aggregate_dirty"),obj.requiredBoolean("hierarchy_dirty"),
            obj.requiredLong("hierarchy_revision"),obj.requiredUuid("workspace_epoch"),
            obj.requiredEnum("workspace_state"),stages,links,mounts,
        )
    }
    private fun parseStageProjection(obj:JsonObject)=ProgramStageProjection(
        parseStageSummary(obj),obj.requiredLong("graph_snapshot_version"),
        obj.requiredString("port_catalog_version"),
        obj.requiredArray("blocks").mapObjects { block ->
            ProgramStageBlock(
                block.requiredUuid("instance_id"),block.requiredString("type_key"),
                parsePosition(block.requiredObject("position")),block.requiredString("orientation"),
                block.requiredString("label"),block.optionalString("description"),
            )
        },
    )
    private fun parseStageSummary(obj:JsonObject):ProgramStageSummary {
        val stageId=obj.requiredUuid("stage_id")
        val parents=obj.requiredArray("parents").mapObjects(::parseLink)
        val children=obj.requiredArray("children").mapObjects(::parseLink)
        if (parents.any { it.childStageId!=stageId } || children.any { it.parentStageId!=stageId }) {
            fail("A Stage parent/child linkje nem ehhez a Stage-hez tartozik.")
        }
        return ProgramStageSummary(
            stageId,obj.requiredUuid("program_id"),obj.requiredString("kind"),
            obj.requiredString("name"),obj.requiredBoolean("is_root"),obj.requiredLong("saved_version"),
            obj.optionalObject("workspace")?.let {
                ProgramStageWorkspace(it.requiredUuid("epoch"),it.requiredLong("working_revision"),it.requiredBoolean("dirty"))
            },
            obj.requiredObject("domain_layout").let {
                ProgramDomainLayout(parsePosition(it.requiredObject("origin")),parsePosition(it.requiredObject("size")))
            },parents,children,
        )
    }
    private fun parseLink(obj:JsonObject)=ProgramLink(
        obj.requiredUuid("parent_stage_id"),obj.requiredUuid("program_block_instance_id"),
        obj.requiredUuid("child_stage_id"),obj.requiredEnum("state"),
    )
    private fun parseMount(obj:JsonObject)=ProgramMount(
        obj.requiredUuid("hall_id"),obj.requiredUuid("program_id"),obj.requiredBoolean("current"),
    )
    private fun parsePosition(obj:JsonObject)=GraphPosition(
        obj.requiredInt("x"),obj.requiredInt("y"),obj.requiredInt("z"),
    )

    private fun fail(message:String):Nothing=throw ProgramProtocolException(message)
}

private fun JsonObject.element(name:String):JsonElement=get(name)
    ?: throw ProgramProtocolException("Hiányzó kötelező Program mező: $name")
private fun JsonObject.requiredString(name:String):String=element(name).let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw ProgramProtocolException("Hibás Program szövegmező: $name")
    it.asString
}
private fun JsonObject.requiredBoolean(name:String):Boolean=element(name).let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw ProgramProtocolException("Hibás Program logikai mező: $name")
    it.asBoolean
}
private fun JsonObject.requiredLong(name:String):Long {
    val value=element(name)
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) throw ProgramProtocolException("Hibás Program egész mező: $name")
    return try { BigDecimal(value.asString).toBigIntegerExact().longValueExact() }
    catch (_:Exception) { throw ProgramProtocolException("Hibás Program egész mező: $name") }
}
private fun JsonObject.requiredInt(name:String):Int=try { Math.toIntExact(requiredLong(name)) }
catch (_:ArithmeticException) { throw ProgramProtocolException("Túl nagy Program egész mező: $name") }
private fun JsonObject.requiredUuid(name:String):UUID=try { UUID.fromString(requiredString(name)) }
catch (_:Exception) { throw ProgramProtocolException("Hibás Program UUID mező: $name") }
private fun JsonObject.requiredObject(name:String):JsonObject=try { element(name).asJsonObject }
catch (_:Exception) { throw ProgramProtocolException("Hibás Program objektummező: $name") }
private fun JsonObject.requiredArray(name:String):JsonArray=try { element(name).asJsonArray }
catch (_:Exception) { throw ProgramProtocolException("Hibás Program tömbmező: $name") }
private fun JsonObject.optionalString(name:String):String?=get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw ProgramProtocolException("Hibás Program szövegmező: $name")
    it.asString
}
private fun JsonObject.optionalObject(name:String):JsonObject?=get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    try { it.asJsonObject } catch (_:Exception) { throw ProgramProtocolException("Hibás Program objektummező: $name") }
}
private inline fun <reified T:Enum<T>> JsonObject.requiredEnum(name:String):T=try { enumValueOf(requiredString(name)) }
catch (_:Exception) { throw ProgramProtocolException("Ismeretlen Program enumérték: $name") }
private fun <T> JsonArray.mapObjects(transform:(JsonObject)->T):List<T> = mapIndexed { index,element ->
    val obj=try { element.asJsonObject } catch (_:Exception) {
        throw ProgramProtocolException("Hibás Program objektum a tömb $index. eleménél.")
    }
    transform(obj)
}
