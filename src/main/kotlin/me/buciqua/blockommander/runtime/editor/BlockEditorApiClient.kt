package me.buciqua.blockommander.runtime.editor

import me.buciqua.blockommander.runtime.Diagnostics
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_ENV
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal data class EditorHttpResponse(val status: Int, val body: String)
/** Test seam for the same JDK sendAsync transport used by Program/Graph clients. */
internal fun interface EditorHttpExchange {
    fun send(request: HttpRequest): CompletableFuture<EditorHttpResponse>
}
internal enum class EditorTransportFailureKind { NOT_CONFIGURED, TIMEOUT, UNAVAILABLE, LOCAL_REJECTED }
internal sealed interface EditorWireResult {
    data class Response(val response: EditorHttpResponse) : EditorWireResult
    data class Failure(val kind: EditorTransportFailureKind) : EditorWireResult
}

/** Internal, unconnected transport capability. It is not a session authorization boundary. */
internal class BlockEditorApiClient private constructor(
    baseUrl: String, private val secret: String?, private val exchange: EditorHttpExchange,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    companion object {
        fun fromEnvironment(): BlockEditorApiClient = configured(
            System.getProperty("blockommander.api", "http://127.0.0.1:5001"),
            System.getenv(GRAPH_SECRET_ENV),
        )
        internal fun configured(
            baseUrl: String, secret: String?,
            http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        ) = BlockEditorApiClient(baseUrl, secret?.takeIf(String::isNotBlank), EditorHttpExchange { request ->
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply { EditorHttpResponse(it.statusCode(), it.body()) }
        })
        internal fun withExchange(baseUrl: String, secret: String?, exchange: EditorHttpExchange) =
            BlockEditorApiClient(baseUrl, secret?.takeIf(String::isNotBlank), exchange)
    }

    internal fun get(context: BlockEditorRequestContext): CompletableFuture<EditorWireResult> =
        request(context, null)

    internal fun apply(request: EditorApplyRequest): CompletableFuture<EditorWireResult> =
        request(request.context, request)

    private fun request(context: BlockEditorRequestContext, apply: EditorApplyRequest?): CompletableFuture<EditorWireResult> {
        val configuredSecret = secret ?: return completed(EditorTransportFailureKind.NOT_CONFIGURED)
        return try {
            val base = "/api/v1/programs/${context.transport.programId}/stages/${context.stageId}/blocks/${context.instanceId}/editor"
            val path = if (apply == null) "$base?player_uuid=${context.playerUuid}" else "$base/apply"
            val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json").header(GRAPH_SECRET_HEADER, configuredSecret)
                .header(Diagnostics.HEADER, context.requestId.toString())
            context.transport.addHeaders(builder)
            if (apply == null) {
                require(context.applyBasis == null) { "GET requires a read context" }
                builder.GET()
            } else {
                val body = apply.toJson().toString()
                require(body.toByteArray(StandardCharsets.UTF_8).size <= 65536) { "Editor body exceeds B2 limit" }
                builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            }
            exchange.send(builder.build()).handle { response, failure ->
                if (failure == null) EditorWireResult.Response(response)
                else {
                    var cause = failure
                    while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
                    EditorWireResult.Failure(if (cause is HttpTimeoutException) EditorTransportFailureKind.TIMEOUT
                        else EditorTransportFailureKind.UNAVAILABLE)
                }
            }
        } catch (_: IllegalArgumentException) {
            completed(EditorTransportFailureKind.LOCAL_REJECTED)
        } catch (_: Exception) {
            completed(EditorTransportFailureKind.UNAVAILABLE)
        }
    }
    private fun completed(kind: EditorTransportFailureKind): CompletableFuture<EditorWireResult> =
        CompletableFuture.completedFuture(EditorWireResult.Failure(kind))

    override fun toString() = "BlockEditorApiClient(configured=${secret != null})"
}
