package me.buciqua.blockommander.runtime.graph

import com.google.gson.Gson
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
    private val gson = Gson()
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

    fun getState(stageId: UUID, player: ServerPlayerIdentity): CompletableFuture<GraphHttpResult> =
        request("GET", "/api/v1/stages/$stageId/graph?player_uuid=${player.uuid}", null)

    fun send(command: GraphCommand): CompletableFuture<GraphHttpResult> {
        val suffix = when (command) {
            is WireLayoutCommand -> "wire"
            is BindEdgesCommand -> "bind"
        }
        return request("POST", "/api/v1/stages/${command.stageId}/graph/$suffix", gson.toJson(command.toJson()))
    }

    private fun request(method: String, path: String, body: String?): CompletableFuture<GraphHttpResult> {
        val configuredSecret = secret ?: return CompletableFuture.completedFuture(
            GraphHttpResult.Unavailable("A graph transport nincs konfigurálva."),
        )
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header(GRAPH_SECRET_HEADER, configuredSecret)
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).handle { response, failure ->
            if (failure != null) return@handle GraphHttpResult.Unavailable("A graph szolgáltatás nem érhető el.")
            val parsed = try {
                GraphResponseParser.parse(response.statusCode(), response.body())
            } catch (failure: GraphProtocolException) {
                return@handle GraphHttpResult.ProtocolFailure(failure.message ?: "Hibás graph API-válasz.")
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
