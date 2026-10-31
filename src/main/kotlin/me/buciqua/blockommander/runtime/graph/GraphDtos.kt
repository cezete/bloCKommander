package me.buciqua.blockommander.runtime.graph

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigDecimal
import java.util.UUID

const val GRAPH_API_VERSION = "ck-graph-v1"
const val GRAPH_SCHEMA_VERSION = 1
const val GRAPH_DISCOVERY_SCHEMA_VERSION = 1

data class GraphPosition(val x: Int, val y: Int, val z: Int)
data class GraphPortRef(val instanceId: UUID, val portKey: String)
data class GraphNode(val instanceId: UUID, val typeKey: String, val position: GraphPosition, val orientation: String)
data class GraphWireChannel(val channel: String, val connections: List<String>)
data class GraphWireCell(val position: GraphPosition, val channels: List<GraphWireChannel>)
data class GraphEdge(
    val edgeId: UUID,
    val sourceInstanceId: UUID,
    val sourcePortKey: String,
    val targetInstanceId: UUID,
    val targetPortKey: String,
    val edgeKind: String,
)
data class GraphRouteStep(val position: GraphPosition, val channel: String)
data class GraphRoute(
    val routeId: UUID,
    val edgeId: UUID?,
    val sourcePort: GraphPortRef?,
    val targetPort: GraphPortRef?,
    val steps: List<GraphRouteStep>,
    val status: String,
)
data class GraphDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val nodeIds: List<UUID>,
    val portRefs: List<GraphPortRef>,
    val edgeIds: List<UUID>,
    val wirePositions: List<GraphPosition>,
    val suggestion: String?,
    val fieldName: String?,
)
data class GraphStatuses(
    val graph: String,
    val edgeIndicator: String,
    val save: String,
    val layout: String,
    val programValidation: String,
)
data class GraphValidation(val revision: Long?, val diagnostics: List<GraphDiagnostic>)
data class GraphStateSnapshot(
    val stageId: UUID,
    val hallId: UUID,
    val worldUuid: UUID,
    val origin: GraphPosition,
    val size: GraphPosition,
    val workspaceEpoch: UUID,
    val workingRevision: Long,
    val savedVersion: Long,
    val dirty: Boolean,
    val graphSnapshotVersion: Long,
    val portCatalogVersion: String?,
    val compatibleForGraphMutation: Boolean,
    val compatibilityIssues: List<String>,
    val statuses: GraphStatuses,
    val nodes: List<GraphNode>,
    val wireCells: List<GraphWireCell>,
    val edges: List<GraphEdge>,
    val routes: List<GraphRoute>,
    val validation: GraphValidation,
)
data class GraphWireDiff(val position: GraphPosition, val cell: GraphWireCell?)
data class GraphBinding(val createdEdgeIds: List<UUID>, val reusedEdgeIds: List<UUID>)
data class GraphPlacement(val intent: GraphPlacementIntent, val affectedCells: List<GraphWireCell>)
data class GraphDiscoveryVertex(val position: GraphPosition, val channel: String)
data class GraphDiscoveryOpenEnd(val position: GraphPosition, val channel: String, val direction: String)
data class GraphDiscoveryComponent(
    val componentId: UUID,
    val status: String,
    val vertices: List<GraphDiscoveryVertex>,
    val ports: List<GraphPortRef>,
    val openEnds: List<GraphDiscoveryOpenEnd>,
)
data class GraphDiscoveryCandidate(
    val candidateId: UUID,
    val componentId: UUID,
    val sourcePort: GraphPortRef,
    val targetPort: GraphPortRef,
    val steps: List<GraphRouteStep>,
)
data class GraphDiscoveryDiagnostic(
    val code: String,
    val severity: String,
    val message: String,
    val componentId: UUID,
    val portRefs: List<GraphPortRef>,
    val wirePositions: List<GraphPosition>,
)
data class GraphDiscovery(
    val schemaVersion: Int,
    val components: List<GraphDiscoveryComponent>,
    val candidates: List<GraphDiscoveryCandidate>,
    val diagnostics: List<GraphDiscoveryDiagnostic>,
)

sealed interface GraphApiResponse {
    val apiVersion: String
    val status: String
    val diagnostics: List<GraphDiagnostic>
}

data class GraphSuccessResponse(
    override val apiVersion: String,
    override val status: String,
    val requestId: UUID?,
    val stageId: UUID,
    val workspaceEpoch: UUID,
    val workingRevision: Long,
    val modified: Boolean,
    override val diagnostics: List<GraphDiagnostic>,
    val wireDiff: List<GraphWireDiff>,
    val binding: GraphBinding?,
    val state: GraphStateSnapshot,
    val discovery: GraphDiscovery? = null,
    val placement: GraphPlacement? = null,
) : GraphApiResponse

data class GraphErrorResponse(
    override val apiVersion: String,
    override val status: String,
    val httpStatus: Int,
    val requestId: UUID?,
    val stageId: UUID?,
    val workspaceEpoch: UUID?,
    val workingRevision: Long?,
    val modified: Boolean?,
    val errorCode: String,
    val message: String,
    override val diagnostics: List<GraphDiagnostic>,
    val discovery: GraphDiscovery? = null,
) : GraphApiResponse

class GraphProtocolException(message: String) : RuntimeException(message)

object GraphResponseParser {
    fun parse(httpStatus: Int, text: String): GraphApiResponse {
        val root = try {
            JsonParser.parseString(text).asJsonObject
        } catch (_: Exception) {
            throw GraphProtocolException("A graph API válasza nem érvényes JSON objektum.")
        }
        val version = root.requiredString("api_version")
        if (version != GRAPH_API_VERSION) throw GraphProtocolException("Nem támogatott graph API-verzió: $version")
        return when (val status = root.requiredString("status")) {
            "ok" -> parseSuccess(root, version, status)
            "error" -> parseError(root, version, status, httpStatus)
            else -> throw GraphProtocolException("Ismeretlen graph válaszstátusz: $status")
        }
    }

    private fun parseSuccess(root: JsonObject, version: String, status: String): GraphSuccessResponse {
        val state = parseState(root.requiredObject("state"))
        val stageId = root.requiredUuid("stage_id")
        val epoch = root.requiredUuid("workspace_epoch")
        val revision = root.requiredLong("working_revision")
        if (state.stageId != stageId || state.workspaceEpoch != epoch || state.workingRevision != revision) {
            throw GraphProtocolException("A graph response envelope és state nem konzisztens.")
        }
        return GraphSuccessResponse(
            version, status, root.optionalUuid("request_id"), stageId, epoch, revision,
            root.requiredBoolean("modified"), parseDiagnostics(root.requiredArray("diagnostics")),
            root.optionalArray("wire_diff")?.mapObjects(::parseWireDiff) ?: emptyList(),
            root.optionalObject("binding")?.let(::parseBinding), state,
            root.optionalObject("discovery")?.let(::parseDiscovery),
            root.optionalObject("placement")?.let(::parsePlacement),
        )
    }

    private fun parseError(root: JsonObject, version: String, status: String, httpStatus: Int) = GraphErrorResponse(
        version, status, httpStatus, root.optionalUuid("request_id"), root.optionalUuid("stage_id"),
        root.optionalUuid("workspace_epoch"), root.optionalLong("working_revision"),
        root.optionalBoolean("modified"), root.requiredString("error_code"), root.requiredString("message"),
        parseDiagnostics(root.requiredArray("diagnostics")), root.optionalObject("discovery")?.let(::parseDiscovery),
    )

    private fun parseDiscovery(obj: JsonObject): GraphDiscovery {
        val schemaVersion = obj.requiredInt("schema_version")
        if (schemaVersion != GRAPH_DISCOVERY_SCHEMA_VERSION) {
            throw GraphProtocolException("Nem támogatott graph discovery-verzió: $schemaVersion")
        }
        return GraphDiscovery(
            schemaVersion,
            obj.requiredArray("components").mapObjects { component ->
                GraphDiscoveryComponent(
                    component.requiredUuid("component_id"), component.requiredString("status"),
                    component.requiredArray("vertices").mapObjects { vertex ->
                        GraphDiscoveryVertex(
                            parsePosition(vertex.requiredObject("position")), vertex.requiredString("channel"),
                        )
                    },
                    component.requiredArray("ports").mapObjects(::parsePortRef),
                    component.requiredArray("open_ends").mapObjects { end ->
                        GraphDiscoveryOpenEnd(
                            parsePosition(end.requiredObject("position")), end.requiredString("channel"),
                            end.requiredString("direction"),
                        )
                    },
                )
            },
            obj.requiredArray("candidates").mapObjects { candidate ->
                GraphDiscoveryCandidate(
                    candidate.requiredUuid("candidate_id"), candidate.requiredUuid("component_id"),
                    parsePortRef(candidate.requiredObject("source_port")),
                    parsePortRef(candidate.requiredObject("target_port")),
                    candidate.requiredArray("steps").mapObjects { step ->
                        GraphRouteStep(parsePosition(step.requiredObject("position")), step.requiredString("channel"))
                    },
                )
            },
            obj.requiredArray("diagnostics").mapObjects { diagnostic ->
                GraphDiscoveryDiagnostic(
                    diagnostic.requiredString("code"), diagnostic.requiredString("severity"),
                    diagnostic.requiredString("message"), diagnostic.requiredUuid("component_id"),
                    diagnostic.requiredArray("port_refs").mapObjects(::parsePortRef),
                    diagnostic.requiredArray("wire_positions").mapObjects(::parsePosition),
                )
            },
        )
    }

    private fun parsePlacement(obj: JsonObject): GraphPlacement {
        val intent = try {
            GraphPlacementIntent.valueOf(obj.requiredString("intent"))
        } catch (_: IllegalArgumentException) {
            throw GraphProtocolException("Nem támogatott placement intent a graph válaszban.")
        }
        return GraphPlacement(
            intent,
            obj.requiredArray("affected_cells").mapObjects(::parseWireCell),
        )
    }

    private fun parseState(obj: JsonObject) = GraphStateSnapshot(
        obj.requiredUuid("stage_id"), obj.requiredUuid("hall_id"), obj.requiredUuid("world_uuid"),
        parsePosition(obj.requiredObject("origin")), parsePosition(obj.requiredObject("size")),
        obj.requiredUuid("workspace_epoch"), obj.requiredLong("working_revision"), obj.requiredLong("saved_version"),
        obj.requiredBoolean("dirty"), obj.requiredLong("graph_snapshot_version"),
        obj.optionalString("port_catalog_version"), obj.requiredBoolean("compatible_for_graph_mutation"),
        obj.requiredArray("compatibility_issues").mapStrings(), parseStatuses(obj.requiredObject("status")),
        obj.requiredArray("nodes").mapObjects(::parseNode), obj.requiredArray("wire_cells").mapObjects(::parseWireCell),
        obj.requiredArray("edges").mapObjects(::parseEdge), obj.requiredArray("routes").mapObjects(::parseRoute),
        parseValidation(obj.requiredObject("validation")),
    )

    private fun parseStatuses(obj: JsonObject) = GraphStatuses(
        obj.requiredString("graph"), obj.requiredString("edge_indicator"), obj.requiredString("save"),
        obj.requiredString("layout"), obj.requiredString("program_validation"),
    )

    private fun parseNode(obj: JsonObject) = GraphNode(
        obj.requiredUuid("instance_id"), obj.requiredString("type_key"), parsePosition(obj.requiredObject("position")),
        obj.requiredString("orientation"),
    )

    private fun parseWireCell(obj: JsonObject) = GraphWireCell(
        parsePosition(obj.requiredObject("position")), obj.requiredArray("channels").mapObjects {
            GraphWireChannel(it.requiredString("channel"), it.requiredArray("connections").mapStrings())
        },
    )

    private fun parseEdge(obj: JsonObject) = GraphEdge(
        obj.requiredUuid("edge_id"), obj.requiredUuid("source_instance_id"), obj.requiredString("source_port_key"),
        obj.requiredUuid("target_instance_id"), obj.requiredString("target_port_key"), obj.requiredString("edge_kind"),
    )

    private fun parseRoute(obj: JsonObject) = GraphRoute(
        obj.requiredUuid("route_id"), obj.optionalUuid("edge_id"), obj.optionalObject("source_port")?.let(::parsePortRef),
        obj.optionalObject("target_port")?.let(::parsePortRef), obj.requiredArray("steps").mapObjects {
            GraphRouteStep(parsePosition(it.requiredObject("position")), it.requiredString("channel"))
        }, obj.requiredString("status"),
    )

    private fun parseValidation(obj: JsonObject) = GraphValidation(
        obj.optionalLong("revision"), parseDiagnostics(obj.requiredArray("diagnostics")),
    )

    private fun parseDiagnostics(array: JsonArray) = array.mapObjects { obj ->
        GraphDiagnostic(
            obj.requiredString("code"), obj.requiredString("severity"), obj.requiredString("message"),
            obj.optionalArray("node_ids")?.mapUuids() ?: emptyList(),
            obj.optionalArray("port_refs")?.mapObjects(::parsePortRef) ?: emptyList(),
            obj.optionalArray("edge_ids")?.mapUuids() ?: emptyList(),
            obj.optionalArray("wire_positions")?.mapObjects(::parsePosition) ?: emptyList(),
            obj.optionalString("suggestion"), obj.optionalString("field_name"),
        )
    }

    private fun parsePortRef(obj: JsonObject) = GraphPortRef(obj.requiredUuid("instance_id"), obj.requiredString("port_key"))
    private fun parsePosition(obj: JsonObject) = GraphPosition(obj.requiredInt("x"), obj.requiredInt("y"), obj.requiredInt("z"))
    private fun parseWireDiff(obj: JsonObject) = GraphWireDiff(
        parsePosition(obj.requiredObject("position")), obj.optionalObject("cell")?.let(::parseWireCell),
    )
    private fun parseBinding(obj: JsonObject) = GraphBinding(
        obj.requiredArray("created_edge_ids").mapUuids(), obj.requiredArray("reused_edge_ids").mapUuids(),
    )
}

private fun JsonObject.element(name: String): JsonElement = get(name)
    ?: throw GraphProtocolException("Hiányzó kötelező graph mező: $name")
private fun JsonObject.requiredString(name: String): String = element(name).let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw GraphProtocolException("Hibás graph szövegmező: $name")
    it.asString
}
private fun JsonObject.requiredBoolean(name: String): Boolean = element(name).let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw GraphProtocolException("Hibás graph logikai mező: $name")
    it.asBoolean
}
private fun JsonObject.requiredLong(name: String): Long = strictLong(element(name), name)
private fun JsonObject.requiredInt(name: String): Int = try { Math.toIntExact(strictLong(element(name), name)) }
catch (_: ArithmeticException) { throw GraphProtocolException("Túl nagy graph egész mező: $name") }
private fun JsonObject.requiredUuid(name: String): UUID = try { UUID.fromString(requiredString(name)) }
catch (_: Exception) { throw GraphProtocolException("Hibás graph UUID mező: $name") }
private fun JsonObject.requiredObject(name: String): JsonObject = try { element(name).asJsonObject }
catch (_: Exception) { throw GraphProtocolException("Hibás graph objektummező: $name") }
private fun JsonObject.requiredArray(name: String): JsonArray = try { element(name).asJsonArray }
catch (_: Exception) { throw GraphProtocolException("Hibás graph tömbmező: $name") }
private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw GraphProtocolException("Hibás graph szövegmező: $name")
    it.asString
}
private fun JsonObject.optionalBoolean(name: String): Boolean? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) throw GraphProtocolException("Hibás graph logikai mező: $name")
    it.asBoolean
}
private fun JsonObject.optionalLong(name: String): Long? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let { strictLong(it, name) }
private fun JsonObject.optionalUuid(name: String): UUID? = optionalString(name)?.let {
    try { UUID.fromString(it) } catch (_: Exception) { throw GraphProtocolException("Hibás graph UUID mező: $name") }
}
private fun JsonObject.optionalObject(name: String): JsonObject? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    try { it.asJsonObject } catch (_: Exception) { throw GraphProtocolException("Hibás graph objektummező: $name") }
}
private fun JsonObject.optionalArray(name: String): JsonArray? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
    try { it.asJsonArray } catch (_: Exception) { throw GraphProtocolException("Hibás graph tömbmező: $name") }
}
private fun <T> JsonArray.mapObjects(transform: (JsonObject) -> T): List<T> = mapIndexed { index, element ->
    val obj = try { element.asJsonObject } catch (_: Exception) { throw GraphProtocolException("Hibás graph objektum a tömb $index. eleménél.") }
    transform(obj)
}
private fun JsonArray.mapStrings(): List<String> = mapIndexed { index, element ->
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
        throw GraphProtocolException("Hibás graph szöveg a tömb $index. eleménél.")
    }
    element.asString
}
private fun JsonArray.mapUuids(): List<UUID> = mapStrings().map {
    try { UUID.fromString(it) } catch (_: Exception) { throw GraphProtocolException("Hibás graph UUID a tömbben.") }
}
private fun strictLong(element: JsonElement, name: String): Long {
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
        throw GraphProtocolException("Hibás graph egész mező: $name")
    }
    return try { BigDecimal(element.asString).toBigIntegerExact().longValueExact() }
    catch (_: ArithmeticException) { throw GraphProtocolException("Hibás graph egész mező: $name") }
    catch (_: NumberFormatException) { throw GraphProtocolException("Hibás graph egész mező: $name") }
}
