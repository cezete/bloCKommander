package me.buciqua.blockommander.runtime.program

import com.google.gson.JsonObject
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_ENV
import me.buciqua.blockommander.runtime.graph.GRAPH_SECRET_HEADER
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface ProgramHttpResult<out T> {
    data class Success<T>(val value:T):ProgramHttpResult<T>
    data class ApiError(val error:ProgramApiError):ProgramHttpResult<Nothing>
    data class Unavailable(val reason:String):ProgramHttpResult<Nothing>
    data class ProtocolFailure(val reason:String):ProgramHttpResult<Nothing>
}

interface ProgramGateway {
    fun mountedProgram(hallId:UUID):CompletableFuture<ProgramHttpResult<MountedProgramResponse>>
    fun program(programId:UUID):CompletableFuture<ProgramHttpResult<ProgramViewResponse>>
    fun stage(programId:UUID,stageId:UUID):CompletableFuture<ProgramHttpResult<ProgramStageResponse>>
    fun ensureChild(
        programId:UUID,parentStageId:UUID,programBlockInstanceId:UUID,playerUuid:UUID,
    ):CompletableFuture<ProgramHttpResult<ChildEnsureResponse>>
}

class ProgramApiClient private constructor(
    baseUrl:String,
    private val secret:String?,
    private val http:HttpClient,
):ProgramGateway {
    private val baseUrl=baseUrl.trimEnd('/')

    companion object {
        fun fromEnvironment()=ProgramApiClient(
            System.getProperty("blockommander.api","http://127.0.0.1:5001"),
            System.getenv(GRAPH_SECRET_ENV)?.takeIf(String::isNotBlank),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        )
        internal fun configured(baseUrl:String,secret:String?,http:HttpClient=HttpClient.newHttpClient())=
            ProgramApiClient(baseUrl,secret?.takeIf(String::isNotBlank),http)
    }

    val isConfigured:Boolean get()=secret!=null

    override fun mountedProgram(hallId:UUID)=get("/api/v1/halls/$hallId/program",ProgramResponseParser::mounted)
    override fun program(programId:UUID)=get("/api/v1/programs/$programId",ProgramResponseParser::program)
    override fun stage(programId:UUID,stageId:UUID)=get(
        "/api/v1/programs/$programId/stages/$stageId",ProgramResponseParser::stage,
    )
    override fun ensureChild(
        programId:UUID,parentStageId:UUID,programBlockInstanceId:UUID,playerUuid:UUID,
    ):CompletableFuture<ProgramHttpResult<ChildEnsureResponse>> {
        val body=JsonObject().apply {
            addProperty("player_uuid",playerUuid.toString())
            addProperty("program_block_instance_id",programBlockInstanceId.toString())
        }.toString()
        return request(
            "POST","/api/v1/programs/$programId/stages/$parentStageId/children/ensure",body,
            ProgramResponseParser::child,
        )
    }

    private fun <T> get(path:String,parser:(String)->T)=request("GET",path,null,parser)
    private fun <T> request(
        method:String,path:String,body:String?,parser:(String)->T,
    ):CompletableFuture<ProgramHttpResult<T>> {
        val configuredSecret=secret ?: return CompletableFuture.completedFuture(
            ProgramHttpResult.Unavailable("A Program transport nincs konfigurálva."),
        )
        val builder=HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(10))
            .header("Accept","application/json").header(GRAPH_SECRET_HEADER,configuredSecret)
        if (body==null) builder.GET() else builder.header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofString(body))
        return http.sendAsync(builder.build(),HttpResponse.BodyHandlers.ofString()).handle { response,failure ->
            if (failure!=null) return@handle ProgramHttpResult.Unavailable("A Program szolgáltatás nem érhető el.")
            try {
                if (response.statusCode() in 200..299) ProgramHttpResult.Success(parser(response.body()))
                else ProgramHttpResult.ApiError(ProgramResponseParser.error(response.statusCode(),response.body()))
            } catch (failure:ProgramProtocolException) {
                ProgramHttpResult.ProtocolFailure(failure.message ?: "Hibás Program API-válasz.")
            }
        }
    }

    override fun toString()="ProgramApiClient(configured=$isConfigured)"
}
