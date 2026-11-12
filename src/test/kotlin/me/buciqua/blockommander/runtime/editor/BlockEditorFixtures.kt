package me.buciqua.blockommander.runtime.editor

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import java.nio.ByteBuffer
import java.net.http.HttpRequest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow

/** Captured from B2's Flask test client, not synthesized by the Kotlin parser. */
internal object BlockEditorFixtures {
    val hall = UUID.fromString("00000000-0000-0000-0000-000000009004")
    val program = UUID.fromString("00000000-0000-0000-0000-000000009002")
    val stage = UUID.fromString("00000000-0000-0000-0000-000000009003")
    val start = UUID.fromString("00000000-0000-0000-0000-000000005002")
    val stop = UUID.fromString("00000000-0000-0000-0000-000000005003")
    val player = UUID.fromString("00000000-0000-0000-0000-000000009001")
    val epoch = UUID(0, 700)
    val requestId = UUID(0, 8800)
    val workingCopy = UUID.fromString("ce4476d1-03a5-5610-9c74-3ad91ac25954")
    fun fixture(name: String): String = checkNotNull(javaClass.getResourceAsStream("/block-editor/$name.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    fun json(name: String = "start-get"): JsonObject = JsonParser.parseString(fixture(name)).asJsonObject
    fun context(apply: Boolean = false, type: EditorBlockType = EditorBlockType.START) = BlockEditorRequestContext(
        player, UUID(0, 77), WorkingCopyTransportContext(hall, program, workingCopy), stage,
        if (type == EditorBlockType.START) start else stop, type, requestId,
        if (apply) EditorApplyBasis(EditorSchemaRef("ck.block.$type.metadata", 1), epoch, 12) else null,
    )
    fun error(status: Int, request: UUID? = requestId): String = JsonObject().apply {
        addProperty("api_version", BLOCK_EDITOR_API_VERSION); addProperty("status", "error")
        addProperty("error_code", if (status == 422) "VALIDATION_FAILED" else "TEST_ERROR")
        addProperty("message", "Rejected")
        if (request != null) addProperty("request_id", request.toString())
        add("diagnostics", JsonParser.parseString("""[{"field":"label","code":"STRING_TOO_LONG","message":"Too long","details":{"max_length":32,"actual_length":33}}]"""))
    }.toString()
    fun body(request: HttpRequest): String {
        val output = StringBuilder()
        val done = CompletableFuture<String>()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) { output.append(Charsets.UTF_8.decode(item)) }
            override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
            override fun onComplete() { done.complete(output.toString()) }
        })
        return done.join()
    }
}
