package me.buciqua.blockommander.runtime.declarations

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_ENV
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import me.buciqua.blockommander.runtime.editor.EditorHttpExchange
import me.buciqua.blockommander.runtime.editor.EditorHttpResponse
import java.net.URI
import java.net.http.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.Flow
import java.io.ByteArrayOutputStream

/** Cancels on overflow before accumulating an unbounded HTTP body. Request timeout
 * stays active through the subscriber's completion, including a stalled body. */
internal class DeclarationBodySubscriber(private val limit: Int = 16384) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) { subscription = value; value.request(1) }
    override fun onNext(items: List<ByteBuffer>) {
        if (result.isDone) return
        if (items.sumOf { it.remaining().toLong() } > limit - bytes.size()) {
            subscription?.cancel(); result.completeExceptionally(IllegalArgumentException("RESPONSE_TOO_LARGE")); return
        }
        items.forEach { b -> val chunk = ByteArray(b.remaining()); b.get(chunk); bytes.write(chunk) }
        subscription?.request(1)
    }
    override fun onError(error: Throwable) { result.completeExceptionally(error) }
    override fun onComplete() { result.complete(bytes.toByteArray()) }
}

internal interface DeclarationGateway {
    fun read(c: DeclarationContext, endpoint: String = "declaration-editor", page: Int = 0, token: String? = null): CompletableFuture<DeclarationResult<DeclarationPage>>
    fun mutate(c: DeclarationContext, opened: DeclarationPage, operation: String, target: UUID?, fields: DeclarationFields?, request: UUID): CompletableFuture<DeclarationResult<DeclarationReceipt>>
}

internal class DeclarationApiClient private constructor(private val base: String, private val secret: String?, private val exchange: EditorHttpExchange) : DeclarationGateway {
    companion object {
        fun fromEnvironment() = configured(System.getProperty("blockommander.api", "http://127.0.0.1:5001"), System.getenv(GRAPH_SECRET_ENV))
        fun configured(base: String, secret: String?, http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) =
            withExchange(base, secret, EditorHttpExchange { request ->
                http.sendAsync(request, HttpResponse.BodyHandler { DeclarationBodySubscriber() }).thenApply { response ->
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(response.body())).toString()
                    EditorHttpResponse(response.statusCode(), text)
                }
            })
        fun withExchange(base: String, secret: String?, exchange: EditorHttpExchange) = DeclarationApiClient(base.trimEnd('/'), secret?.takeIf { it.isNotBlank() }, exchange)
    }
    override fun read(c: DeclarationContext, endpoint: String, page: Int, token: String?): CompletableFuture<DeclarationResult<DeclarationPage>> {
        require(endpoint in setOf("declarations", "symbols", "declaration-editor") && page >= 0)
        require(token == null || Regex("$DECLARATION_BASIS:[0-9a-f]{64}").matches(token))
        val query = "?player_uuid=${c.player}&page=$page" + (token?.let { "&page_token=$it" } ?: "")
        return send(c, "GET", "/$endpoint$query", null, null) { DeclarationJson.page(it, c, endpoint, page, token) }
    }
    override fun mutate(c: DeclarationContext, opened: DeclarationPage, operation: String, target: UUID?, fields: DeclarationFields?, request: UUID): CompletableFuture<DeclarationResult<DeclarationReceipt>> {
        require(operation in setOf("CREATE", "UPDATE", "DELETE"))
        require((target == null) == (operation == "CREATE") && (fields == null) == (operation == "DELETE"))
        val body = JsonObject().apply {
            addProperty("api_version", DECLARATION_API); addProperty("request_id", request.toString()); addProperty("player_uuid", c.player.toString())
            addProperty("expected_stage_workspace_epoch", opened.version.epoch.toString()); addProperty("expected_working_revision", opened.version.revision)
            addProperty("expected_resolution_basis", requireNotNull(opened.basisDigest))
            if (fields != null) add(if (operation == "CREATE") "declaration" else "changes", JsonObject().apply {
                addProperty("name", fields.name); addProperty("type_key", fields.type.name); addProperty("mutability", fields.mutability.name)
            })
        }.toString()
        return send(c, when (operation) { "CREATE" -> "POST"; "UPDATE" -> "PATCH"; else -> "DELETE" },
            "/declarations" + (target?.let { "/$it" } ?: ""), body, request) {
            DeclarationJson.receipt(it, c, request, operation, opened.version, target)
        }
    }
    private fun <T> send(c: DeclarationContext, method: String, suffix: String, body: String?, id: UUID?, parse: (String) -> T): CompletableFuture<DeclarationResult<T>> {
        val key = secret ?: return CompletableFuture.completedFuture(DeclarationResult.Failure("NOT_CONFIGURED"))
        val mutation = method != "GET"
        var dispatchStarted = false
        return try {
            require(body == null || body.toByteArray(Charsets.UTF_8).size <= 65536)
            val builder = HttpRequest.newBuilder(URI.create("$base/api/v1/programs/${c.transport.programId}/stages/${c.stage}$suffix"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json").header(GRAPH_SECRET_HEADER, key)
            c.transport.addHeaders(builder)
            if (body == null) builder.GET() else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body))
            val request = builder.build()
            dispatchStarted = true
            exchange.send(request).handle { response, failure ->
                if (failure != null) {
                    var cause = failure
                    while (cause is CompletionException && cause.cause != null) cause = cause.cause!!
                    DeclarationResult.Failure(if (cause is HttpTimeoutException) "TIMEOUT" else "TRANSPORT_FAILURE", mutation)
                } else try {
                    require(response != null && response.body.toByteArray(Charsets.UTF_8).size <= 16384)
                    if (response.status == if (method == "POST") 201 else 200) DeclarationResult.Success(parse(response.body))
                    else DeclarationJson.error(response.body, response.status, id)
                } catch (_: Exception) { DeclarationResult.Failure("INVALID_RESPONSE", mutation) }
            }
        } catch (_: Exception) { CompletableFuture.completedFuture(DeclarationResult.Failure(if (dispatchStarted) "TRANSPORT_FAILURE" else "LOCAL_REQUEST_REJECTED", dispatchStarted && mutation)) }
    }
    override fun toString() = "DeclarationApiClient(configured=${secret != null})"
}
