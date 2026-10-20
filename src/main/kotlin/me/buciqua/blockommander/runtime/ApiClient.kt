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

class ApiClient {
    private val gson = Gson()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val baseUrl = System.getProperty("blockommander.api", "http://127.0.0.1:5001").trimEnd('/')

    fun get(player: ServerPlayer?, path: String, success: (JsonObject) -> Unit) = request(player, "GET", path, null, success)
    fun post(player: ServerPlayer, path: String, payload: JsonObject, success: (JsonObject) -> Unit) =
        request(player, "POST", path, payload, success)

    private fun request(player: ServerPlayer?, method: String, path: String, payload: JsonObject?, success: (JsonObject) -> Unit) {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
        if (payload == null) builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).whenComplete { response, failure ->
            val server = player?.level()?.server
            val task = Runnable {
                if (failure != null) {
                    if (player == null) BlockCommanderMod.LOGGER.warn("Python service unavailable during background refresh: {} {}", method, path)
                    else BlockCommanderMod.LOGGER.error("Python service request failed: {} {}", method, path, failure)
                    player?.sendSystemMessage(Component.literal("§c[CK] A Python szolgáltatás nem érhető el."))
                    return@Runnable
                }
                val body = runCatching { gson.fromJson(response.body(), JsonObject::class.java) }.getOrNull()
                if (response.statusCode() !in 200..299 || body == null) {
                    val message = body?.get("message")?.asString ?: "HTTP ${response.statusCode()}"
                    player?.sendSystemMessage(Component.literal("§c[CK] $message"))
                    return@Runnable
                }
                body.get("message")?.asString?.let { player?.sendSystemMessage(Component.literal("§a[CK] $it")) }
                success(body)
            }
            if (server != null) server.execute(task) else task.run()
        }
    }
}
