package me.buciqua.blockommander.runtime.program

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.buciqua.blockommander.runtime.WorkingCopyTransportContext
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_ENV
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class HallSelection(val context:WorkingCopyTransportContext,val worldUuid:UUID,
    val stageId:UUID,val revision:Long)
data class SelectionResponse(val selection:HallSelection,val changed:Boolean?=null)

object HallSelectionParser {
    fun parse(text:String,hall:UUID,world:UUID,put:Boolean=false):SelectionResponse {
        val root=JsonParser.parseString(text).asJsonObject
        fun string(obj:JsonObject,key:String):String {
            val value=obj[key] ?: error("MISSING_$key")
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "INVALID_$key" }
            return value.asString
        }
        fun id(obj:JsonObject,key:String):UUID {
            val value=string(obj,key)
            require(value.matches(Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))) { "INVALID_$key" }
            return UUID.fromString(value)
        }
        require(string(root,"api_version")==PROGRAM_API_VERSION) { "API_VERSION" }
        require(string(root,"selection_api_version")=="ck-hall-selection-v1") { "SELECTION_API_VERSION" }
        require(string(root,"status")=="ok") { "SELECTION_STATUS" }
        val value=root.getAsJsonObject("selection") ?: error("MISSING_SELECTION")
        require(id(value,"hall_id")==hall) { "HALL_ID_MISMATCH" }
        require(id(value,"world_uuid")==world) { "WORLD_UUID_MISMATCH" }
        require(string(value,"content_scope")=="SHARED_PROGRAM") { "CONTENT_SCOPE" }
        require(string(value,"projection_state")=="DESIRED_ONLY") { "PROJECTION_STATE" }
        val revision=value["selection_revision"] ?: error("MISSING_REVISION")
        require(revision.isJsonPrimitive && revision.asJsonPrimitive.isNumber) { "INVALID_REVISION" }
        val number=revision.asBigDecimal.longValueExact()
        require(number>=0) { "INVALID_REVISION" }
        val changed=if (put) {
            val field=root["changed"] ?: error("MISSING_CHANGED")
            require(field.isJsonPrimitive && field.asJsonPrimitive.isBoolean) { "INVALID_CHANGED" }
            field.asBoolean
        } else null
        return SelectionResponse(HallSelection(WorkingCopyTransportContext(hall,
            id(value,"program_id"),id(value,"working_copy_id")),world,id(value,"active_stage_id"),number),changed)
    }
}

interface HallSelectionGateway {
    fun get(hall:UUID,world:UUID,player:UUID):CompletableFuture<ProgramHttpResult<SelectionResponse>>
    fun put(base:HallSelection,program:UUID,stage:UUID,player:UUID):CompletableFuture<ProgramHttpResult<SelectionResponse>>
}

class HallSelectionApiClient internal constructor(baseUrl:String,private val secret:String?,
    private val http:HttpClient=HttpClient.newHttpClient()):HallSelectionGateway {
    private val baseUrl=baseUrl.trimEnd('/')
    companion object {
        fun fromEnvironment()=HallSelectionApiClient(
            System.getProperty("blockommander.api","http://127.0.0.1:5001"),System.getenv(GRAPH_SECRET_ENV))
    }
    override fun get(hall:UUID,world:UUID,player:UUID)=request(hall,world,
        "/api/v1/halls/$hall/selection?player_uuid=$player",null)
    override fun put(base:HallSelection,program:UUID,stage:UUID,player:UUID)=request(
        base.context.hallId,base.worldUuid,"/api/v1/halls/${base.context.hallId}/selection",JsonObject().apply {
            addProperty("player_uuid",player.toString());addProperty("program_id",program.toString())
            addProperty("stage_id",stage.toString());addProperty("expected_selection_revision",base.revision)
        })
    private fun request(hall:UUID,world:UUID,path:String,body:JsonObject?):CompletableFuture<ProgramHttpResult<SelectionResponse>> {
        if (secret.isNullOrBlank()) return CompletableFuture.completedFuture(ProgramHttpResult.Unavailable("SELECTION_NOT_CONFIGURED"))
        val builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(10))
            .header(GRAPH_SECRET_HEADER,secret).header("Accept","application/json")
        if(body==null) builder.GET() else builder.header("Content-Type","application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
        // Dedicated handoff; never validate the new identity as an echo of the old G1 triple.
        return http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofString()).handle { response,failure ->
            if(failure!=null) ProgramHttpResult.Unavailable("SELECTION_UNAVAILABLE") else try {
                if(response.statusCode() in 200..299) ProgramHttpResult.Success(
                    HallSelectionParser.parse(response.body(),hall,world,body!=null))
                else ProgramHttpResult.ApiError(ProgramResponseParser.error(response.statusCode(),response.body()))
            } catch(exc:Exception) { ProgramHttpResult.ProtocolFailure(exc.message ?: "SELECTION_PROTOCOL_ERROR") }
        }
    }
}
