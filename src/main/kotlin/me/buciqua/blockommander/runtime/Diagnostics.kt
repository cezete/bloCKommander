package me.buciqua.blockommander.runtime

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.util.UUID
import org.slf4j.LoggerFactory

/** Allowlisted scalar diagnostics only. Never pass bodies, credentials or domain snapshots. */
object Diagnostics {
    const val HEADER="X-BlockCommander-Diagnostic-Id"
    private val logger=LoggerFactory.getLogger("blockommander.diagnostics")
    private val json=GsonBuilder().serializeNulls().create()
    internal var sink:(Map<String,Any?>)->Unit={ logger.info("CK_DIAG {}",json.toJson(it)) }

    fun event(event:String,key:ProjectionKey?=null,vararg fields:Pair<String,Any?>) {
        // Observability failure must not affect domain, fences, futures or world application.
        runCatching {
            val record=linkedMapOf<String,Any?>("event" to event)
            key?.let { record.putAll(mapOf("hall_id" to it.hallId,"program_id" to it.programId,
                "working_copy_id" to it.workingCopyId,"stage_id" to it.stageId)) }
            record.putAll(fields)
            sink(record)
        }
    }

    fun http(event:String,trace:UUID,context:WorkingCopyTransportContext?,stage:UUID?,
        method:String,path:String,status:Int?=null,body:String?=null,reason:String?=null) {
        runCatching {
            val root=body?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
            fun obj(name:String)=root?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
            fun scalar(parent:com.google.gson.JsonObject?,name:String)=parent?.get(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            val state=obj("stage") ?: obj("state")
            val program=obj("program")
            this.event(event,if(context!=null && stage!=null) context.projectionKey(stage) else null,
                "diagnostic_id" to trace,"hall_id" to context?.hallId,"program_id" to context?.programId,
                "working_copy_id" to context?.workingCopyId,"requested_stage_id" to stage,
                "method" to method,"route" to path.substringBefore('?'),"http_status" to status,"reason" to reason,
                "error_code" to scalar(root,"error_code")?.asString,
                "response_stage_id" to (scalar(state,"stage_id") ?: scalar(root,"stage_id"))?.asString,
                "response_epoch" to (scalar(state,"workspace_epoch") ?: scalar(root,"workspace_epoch"))?.asString,
                "response_revision" to (scalar(state,"working_revision") ?: scalar(root,"working_revision"))?.asString,
                "program_dirty" to (scalar(state,"program_dirty") ?: scalar(program,"aggregate_dirty"))?.takeIf { it.isBoolean }?.asBoolean,
                "program_epoch" to scalar(program,"workspace_epoch")?.asString,
                "root_stage_id" to scalar(program,"root_stage_id")?.asString)
        }
    }
}
