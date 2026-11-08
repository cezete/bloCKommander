package me.buciqua.blockommander.runtime

import com.google.gson.Gson
import com.google.gson.JsonObject
import me.buciqua.blockommander.BlockCommanderMod
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

class ApiClient {
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val baseUrl = System.getProperty("blockommander.api", "http://127.0.0.1:5001").trimEnd('/')

    fun get(player: ServerPlayer?, path: String, onFailure: (String) -> Unit = {}, success: (JsonObject) -> Unit) =
        request(player, "GET", path, null, null, null, success, onFailure)
    fun get(player: ServerPlayer?, path: String, context: WorkingCopyTransportContext, stageId: UUID? = null,
            onFailure: (String) -> Unit = {}, success: (JsonObject) -> Unit) =
        request(player, "GET", path, null, context, stageId, success, onFailure)
    fun post(player: ServerPlayer, path: String, payload: JsonObject, onFailure: (String) -> Unit = {},
             success: (JsonObject) -> Unit) = request(player, "POST", path, payload, null, null, success, onFailure)
    fun post(player: ServerPlayer, path: String, payload: JsonObject, context: WorkingCopyTransportContext,
             stageId: UUID? = null, onFailure: (String) -> Unit = {}, success: (JsonObject) -> Unit) =
        request(player, "POST", path, payload, context, stageId, success, onFailure)

    private fun request(player: ServerPlayer?, method: String, path: String, payload: JsonObject?,
                        context: WorkingCopyTransportContext?, stageId: UUID?,
                        success: (JsonObject) -> Unit, onFailure: (String) -> Unit) {
        val trace=UUID.randomUUID()
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header(Diagnostics.HEADER,trace.toString())
        context?.addHeaders(builder)
        if (payload == null) builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
        Diagnostics.http("FABRIC_HTTP_START",trace,context,stageId,method,path)
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).whenComplete { response, failure ->
            Diagnostics.http("FABRIC_HTTP_RESULT",trace,context,stageId,method,path,response?.statusCode(),response?.body(),
                if(failure==null) "RECEIVED" else "TRANSPORT_UNAVAILABLE")
            val server = player?.level()?.server
            val task = Runnable {
                if (failure != null) {
                    if (player == null) BlockCommanderMod.LOGGER.warn("Python service unavailable during background refresh: {} {}", method, path)
                    else BlockCommanderMod.LOGGER.error("Python service request failed: {} {}", method, path, failure)
                    player?.sendSystemMessage(Component.literal("§c[CK] A Python szolgáltatás nem érhető el."))
                    onFailure("TRANSPORT_UNAVAILABLE")
                    return@Runnable
                }
                val body = runCatching { gson.fromJson(response.body(), JsonObject::class.java) }.getOrNull()
                if (response.statusCode() !in 200..299 || body == null) {
                    val message = body?.get("message")?.asString ?: "HTTP ${response.statusCode()}"
                    BlockCommanderMod.LOGGER.warn("Python service rejected request: {} {} status={} message={}", method, path, response.statusCode(), message)
                    player?.sendSystemMessage(Component.literal("§c[CK] $message"))
                    onFailure("HTTP_${response.statusCode()}: $message")
                    return@Runnable
                }
                if (context != null) {
                    try {
                        TransportContextParser.validateResponse(body, context, stageId)
                    } catch (failure: TransportContextProtocolException) {
                        BlockCommanderMod.LOGGER.warn(
                            "Python service returned invalid transport context: {} {} detail={}",
                            method, path, failure.message,
                        )
                        player?.sendSystemMessage(Component.literal("§c[CK] A Python válasz kontextusa hibás."))
                        onFailure("TRANSPORT_CONTEXT_INVALID")
                        return@Runnable
                    }
                }
                body.get("message")?.asString?.let { player?.sendSystemMessage(Component.literal("§a[CK] $it")) }
                success(body)
            }
            if (server != null) server.execute(task) else task.run()
        }
    }
}
