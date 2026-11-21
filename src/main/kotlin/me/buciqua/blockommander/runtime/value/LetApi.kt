package me.buciqua.blockommander.runtime.value

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.declarations.*
import me.buciqua.blockommander.runtime.declarations.DeclarationJson.flag
import me.buciqua.blockommander.runtime.declarations.DeclarationJson.number
import me.buciqua.blockommander.runtime.declarations.DeclarationJson.obj
import me.buciqua.blockommander.runtime.declarations.DeclarationJson.text
import me.buciqua.blockommander.runtime.declarations.DeclarationJson.uuid
import me.buciqua.blockommander.runtime.graph.GraphPosition
import me.buciqua.blockommander.runtime.program.*
import java.util.UUID

internal const val VALUE_API = "ck-value-v1"

internal data class LetBasis(val stageEpoch: UUID, val revision: Long, val digest: String,
    val programEpoch: UUID, val hierarchy: Long, val programSaved: Long, val stageSaved: Long) {
    companion object {
        fun from(page: DeclarationPage): LetBasis? = page.basisDigest?.let {
            LetBasis(page.version.epoch, page.version.revision, it, page.programEpoch, page.hierarchy,
                page.version.programSaved, page.version.stageSaved)
        }
    }

    fun matchesStage(response: ProgramStageResponse): Boolean {
        val stage = response.stage.summary
        val workspace = stage.workspace ?: return false
        return response.programWorkspaceEpoch == programEpoch && response.hierarchyRevision == hierarchy &&
            response.programSavedVersion == programSaved && stage.savedVersion == stageSaved &&
            workspace.epoch == stageEpoch && workspace.workingRevision == revision
    }
}

internal data class LetView(val basis: LetBasis, val block: UUID, val target: UUID?)
internal data class LetChild(val basis: LetBasis, val child: ChildEnsureResponse)

internal class LetApi(private val client: ProgramApiClient) {
    fun read(c: DeclarationContext, block: UUID) =
        send(c, "GET", resource(c, block) + "?player_uuid=${c.player}", null) { root, basis ->
            parseLet(root, basis).also { require(it.block == block) }
        }

    fun configure(c: DeclarationContext, block: UUID, target: UUID, basis: LetBasis) =
        write(c, "PATCH", resource(c, block), basis, { addProperty("target_id", target.toString()) }) { root, next ->
            require(next.revision - basis.revision in 0L..1L && next.hierarchy == basis.hierarchy)
            parseLet(root, next).also { require(it.block == block && it.target == target) }
        }

    fun ensureChild(c: DeclarationContext, block: UUID, basis: LetBasis) =
        write(c, "POST", stage(c) + "/children/ensure", basis, {
            addProperty("program_block_instance_id", block.toString())
            addProperty("owner_role", "LET_RHS")
        }) { root, next ->
            val result = ProgramResponseParser.child(root)
            require(next.revision == basis.revision && next.hierarchy - basis.hierarchy in 0L..1L)
            require(result.link.ownerRole == ProgramOwnerRole.LET_RHS &&
                result.link.parentStageId == c.stage && result.link.programBlockInstanceId == block &&
                result.link.state == ProgramLinkState.ACTIVE && result.child.kind == "VALUE_BODY" &&
                result.child.valueSchemaVersion == 1L && result.child.parents.count { it.state == ProgramLinkState.ACTIVE } == 1 &&
                result.link in result.child.parents)
            LetChild(next, result)
        }

    fun place(c: DeclarationContext, position: GraphPosition, orientation: String, basis: LetBasis) =
        write(c, "POST", "/api/v1/stages/${c.stage}/blocks", basis, {
            addProperty("type_key", "LET")
            addProperty("coordinate_space", "STAGE_RELATIVE")
            addProperty("x", position.x); addProperty("y", position.y); addProperty("z", position.z)
            addProperty("orientation", orientation)
        }) { root, next ->
            require(next.revision - basis.revision == 1L && next.hierarchy == basis.hierarchy)
            parseLet(root, next).also { view ->
                require(view.target == null)
                val block = root.obj("stage").getAsJsonArray("blocks").map { it.asJsonObject }
                    .single { it.uuid("instance_id") == view.block }
                require(block.text("type_key") == "LET" && block.text("orientation") == orientation &&
                    block.number("rel_x") == position.x.toLong() && block.number("rel_y") == position.y.toLong() &&
                    block.number("rel_z") == position.z.toLong())
            }
        }

    fun remove(c: DeclarationContext, block: UUID, basis: LetBasis) =
        write(c, "POST", "/api/v1/stages/${c.stage}/blocks/$block/delete", basis, {}) { root, next ->
            require(root.uuid("deleted_instance_id") == block)
            require(next.revision - basis.revision == 1L && next.hierarchy - basis.hierarchy in 0L..1L)
            root.get("link")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { json ->
                val link = ProgramResponseParser.parseLink(json)
                require(link.ownerRole == ProgramOwnerRole.LET_RHS && link.state == ProgramLinkState.RETAINED &&
                    link.parentStageId == c.stage && link.programBlockInstanceId == block)
            }
            next
        }

    private fun stage(c: DeclarationContext) = "/api/v1/programs/${c.transport.programId}/stages/${c.stage}"
    private fun resource(c: DeclarationContext, block: UUID) = stage(c) + "/blocks/$block/value"

    private fun <T> write(c: DeclarationContext, method: String, path: String, basis: LetBasis,
        fields: JsonObject.() -> Unit, parse: (JsonObject, LetBasis) -> T): java.util.concurrent.CompletableFuture<ProgramHttpResult<T>> {
        val request = UUID.randomUUID()
        val body = JsonObject().apply {
            addProperty("api_version", VALUE_API)
            addProperty("player_uuid", c.player.toString())
            addProperty("request_id", request.toString())
            addProperty("expected_stage_workspace_epoch", basis.stageEpoch.toString())
            addProperty("expected_working_revision", basis.revision)
            addProperty("expected_resolution_basis", basis.digest)
            fields()
        }
        return send(c, method, path, body, request) { root, next ->
            require(next.stageEpoch == basis.stageEpoch && next.programEpoch == basis.programEpoch &&
                next.revision >= basis.revision && next.hierarchy >= basis.hierarchy &&
                next.programSaved >= basis.programSaved && next.stageSaved >= basis.stageSaved)
            parse(root, next)
        }
    }

    private fun <T> send(c: DeclarationContext, method: String, path: String, body: JsonObject?,
        request: UUID? = null, parse: (JsonObject, LetBasis) -> T) =
        client.request(c.transport, c.stage, method, path, body?.toString(), { text ->
            val root = DeclarationJson.parse(text, 4 * 1024 * 1024)
            require(root.text("api_version") == VALUE_API && root.text("status") == "ok")
            require(root.uuid("program_id") == c.transport.programId && root.uuid("stage_id") == c.stage)
            if (request != null) require(root.uuid("request_id") == request)
            require(root.text("stage_kind") in setOf("ROOT", "PROGRAM_BODY"))
            val basis = parseBasis(root, c)
            parse(root, basis)
        }, { status, text ->
            val root = DeclarationJson.parse(text)
            require(root.text("api_version") == VALUE_API && root.text("status") == "error")
            if (request != null && root.has("request_id")) require(root.uuid("request_id") == request)
            ProgramApiError(status, root.text("error_code"), root.text("message", 1024), emptyList())
        })

    private fun parseBasis(root: JsonObject, c: DeclarationContext): LetBasis {
        val stamp = LetBasis(root.uuid("stage_workspace_epoch"), root.number("working_revision"),
            root.text("resolution_basis_digest"), root.uuid("program_workspace_epoch"),
            root.number("hierarchy_revision"), root.number("program_saved_version"), root.number("stage_saved_version"))
        require(Regex("$DECLARATION_BASIS:[0-9a-f]{64}").matches(stamp.digest))
        val scope = root.obj("resolution_basis")
        require(scope.text("basis_version") == DECLARATION_BASIS && scope.uuid("program_id") == c.transport.programId &&
            scope.uuid("program_workspace_epoch") == stamp.programEpoch && scope.number("hierarchy_revision") == stamp.hierarchy &&
            scope.obj("query_scope").uuid("owner_id") == c.stage)
        val local = scope.getAsJsonArray("scopes").first().asJsonObject
        require(local.obj("scope").uuid("owner_id") == c.stage && local.uuid("workspace_epoch") == stamp.stageEpoch &&
            local.number("working_revision") == stamp.revision && (!root.flag("stage_dirty") || root.flag("aggregate_dirty")))
        return stamp
    }

    private fun parseLet(root: JsonObject, basis: LetBasis): LetView {
        val let = root.obj("let")
        require(let.text("type_key") == "LET" && let.has("target_id"))
        val target = if (let.get("target_id").isJsonNull) null else let.uuid("target_id")
        val config = let.obj("semantic_config")
        require(if (target == null) config.size() == 0 else
            config.size() == 2 && config.number("version") == 1L && config.uuid("target_id") == target)
        return LetView(basis, let.uuid("instance_id"), target)
    }
}
