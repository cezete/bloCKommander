package me.buciqua.blockommander.runtime.graph

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.TransportContextParser
import me.buciqua.blockommander.runtime.TransportContextProtocolException
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import me.buciqua.blockommander.runtime.Diagnostics
import me.buciqua.blockommander.runtime.projectionKey
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

const val GRAPH_SECRET_ENV = "BLOCKCOMMANDER_GRAPH_SHARED_SECRET"
const val GRAPH_SECRET_HEADER = "X-BlockCommander-Graph-Secret"

sealed interface GraphHttpResult {
    data class Success(val response: GraphSuccessResponse) : GraphHttpResult
    data class ApiError(val response: GraphErrorResponse) : GraphHttpResult
    data class Unavailable(val reason: String) : GraphHttpResult
    data class ProtocolFailure(val reason: String) : GraphHttpResult
}

class GraphApiClient private constructor(
    baseUrl: String,
    private val secret: String?,
    private val http: HttpClient,
) {
    private val gson = GsonBuilder().serializeNulls().create()
    private val baseUrl = baseUrl.trimEnd('/')

    companion object {
        fun fromEnvironment(): GraphApiClient = GraphApiClient(
            System.getProperty("blockommander.api", "http://127.0.0.1:5001"),
            System.getenv(GRAPH_SECRET_ENV)?.takeIf { it.isNotBlank() },
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        )

        internal fun configured(baseUrl: String, secret: String?, http: HttpClient = HttpClient.newHttpClient()) =
            GraphApiClient(baseUrl, secret?.takeIf { it.isNotBlank() }, http)
    }

    val isConfigured: Boolean get() = secret != null

    fun getState(
        context: WorkingCopyTransportContext, stageId: UUID, player: ServerPlayerIdentity,
        diagnosticId:UUID=UUID.randomUUID(),
    ): CompletableFuture<GraphHttpResult> =
        request(context, stageId, "GET", "/api/v1/stages/$stageId/graph?player_uuid=${player.uuid}", null,diagnosticId=diagnosticId)

    fun send(context: WorkingCopyTransportContext, command: GraphCommand): CompletableFuture<GraphHttpResult> {
        val suffix = when (command) {
            is WireLayoutCommand -> "wire"
            is WirePlaceIntentCommand -> "wire/place"
            is BindEdgesCommand -> "bind"
        }
        return request(context, command.stageId, "POST", "/api/v1/stages/${command.stageId}/graph/$suffix", gson.toJson(command.toJson()),command)
    }

    private fun request(
        context: WorkingCopyTransportContext, stageId: UUID, method: String, path: String, body: String?,command:GraphCommand?=null,
        diagnosticId:UUID=UUID.randomUUID(),
    ): CompletableFuture<GraphHttpResult> {
        val trace=diagnosticId
        fun log(event:String,status:Int?,reason:String,epoch:UUID?=null,revision:Long?=null) {
            Diagnostics.event(event,context.projectionKey(stageId),"diagnostic_id" to trace,"method" to method,
                "route" to path.substringBefore('?'),"http_status" to status,"reason" to reason,
                "request_id" to command?.requestId,"request_epoch" to command?.workspaceEpoch,
                "request_revision" to command?.expectedWorkingRevision,"response_epoch" to epoch,"response_revision" to revision)
        }
        val configuredSecret = secret ?: return CompletableFuture.completedFuture<GraphHttpResult>(
            GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."),
        ).also { log("GRAPH_HTTP_NOT_SENT",null,"NOT_CONFIGURED") }
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header(GRAPH_SECRET_HEADER, configuredSecret)
            .header(Diagnostics.HEADER,trace.toString())
        context.addHeaders(builder)
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        log("GRAPH_HTTP_START",null,"SENDING")
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).handle { response, failure ->
            if (failure != null) {
                log("GRAPH_HTTP_RESULT",null,"TRANSPORT_UNAVAILABLE")
                return@handle GraphHttpResult.Unavailable("A graph szolgáltatás nem érhető el.")
            }
            val parsed = try {
                if (response.statusCode() in 200..299) {
                    TransportContextParser.validateResponse(
                        JsonParser.parseString(response.body()).asJsonObject, context, stageId,
                    )
                }
                GraphResponseParser.parse(response.statusCode(), response.body())
            } catch (failure: Exception) {
                log("GRAPH_HTTP_RESULT",response.statusCode(),"PROTOCOL_FAILURE")
                return@handle GraphHttpResult.ProtocolFailure(failure.message ?: "Hibás graph API-válasz.")
            }
            when(parsed) {
                is GraphSuccessResponse -> log("GRAPH_HTTP_RESULT",response.statusCode(),"OK",parsed.workspaceEpoch,parsed.workingRevision)
                is GraphErrorResponse -> log("GRAPH_HTTP_RESULT",response.statusCode(),parsed.errorCode,parsed.workspaceEpoch,parsed.workingRevision)
            }
            when {
                response.statusCode() in 200..299 && parsed is GraphSuccessResponse -> GraphHttpResult.Success(parsed)
                parsed is GraphErrorResponse -> GraphHttpResult.ApiError(parsed)
                else -> GraphHttpResult.ProtocolFailure("A graph HTTP státusz és válaszbody ellentmond egymásnak.")
            }
        }
    }

    override fun toString(): String = "GraphApiClient(configured=$isConfigured)"
}
