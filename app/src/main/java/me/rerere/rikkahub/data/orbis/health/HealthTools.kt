package me.rerere.rikkahub.data.orbis.health

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun healthTool(context:Context,owner:String,enabled:()->Boolean)=Tool(
    name="orbis_health",
    description=me.rerere.rikkahub.data.ai.tools.orbisToolFullDescription("orbis_health"),
    parameters={InputSchema.Obj(properties=buildJsonObject{
        listOf("action","date","source","evidence","start","end").forEach{put(it,buildJsonObject{put("type","string")})}
        listOf("expected_revision","sleep_target_minutes").forEach{put(it,buildJsonObject{put("type","integer")})}
        put("fields",buildJsonObject{
            put("type","object");put("additionalProperties",false)
            put("properties",buildJsonObject { healthFields.forEach { field ->
                put(field.id,buildJsonObject {
                    put("anyOf",JsonArray(listOf(buildJsonObject { put("type",healthFieldType(field)) },buildJsonObject { put("type","null") })))
                    put("description",field.label + if(field.unit.isBlank()) "" else "（${field.unit}）")
                })
            } })
        })
    },required=listOf("action"))},
    needsApproval={it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("read","summary")},
    hostApproval=HostToolApproval("orbis:human-health","human-health-v1","人类记录"),isApprovalCurrent={enabled()},
    execute={raw->
        try{
            val result=withContext(Dispatchers.IO){check(enabled()){"health_disabled"};executeHealthTool(openHealth(context),owner,raw)}
            listOf(UIMessagePart.Text(result.toString()))
        }catch(e:CancellationException){throw e}
        catch(e:Exception){listOf(UIMessagePart.Text(healthToolError(e).toString()))}
    },
)
internal fun healthToolError(e:Exception):JsonObject = buildJsonObject {
    put("ok",false);put("code",e.message?.takeIf{it.matches(Regex("health_[a-z_]+"))}?:"health_invalid_parameters_or_storage")
    if(e is HealthParameterError) {
        put("field",e.field);put("expected_type",e.expected);put("actual_type",e.actual)
        if(e.hint.isNotBlank())put("next_step",e.hint)
    }
    if(e is HealthHumanConflict){put("protected_fields",JsonArray(e.fields.sorted().map(::JsonPrimitive)));put("next_step","这些字段由人类修正过，未覆盖；可提交propose供本人确认。")}
    if(e.message=="health_revision_conflict")put("next_step","先read读取该日最新revision，再决定是否提交；不要盲目重复写入。")
    put("automatic_retry",false)
}
internal fun executeHealthTool(store:HealthRepository,owner:String,raw:JsonElement):JsonObject {
    require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}"))){"health_actor_invalid"}
    val a=raw as? JsonObject ?: throw HealthParameterError("health_invalid_parameters", "arguments", "object", healthJsonType(raw))
    fun text(k:String)=(a[k] as? JsonPrimitive)?.takeIf{it.isString}?.content?:throw HealthParameterError("health_invalid_parameters",k,"string",healthJsonType(a[k]))
    fun integer(k:String)=(a[k] as? JsonPrimitive)?.takeUnless{it.isString}?.intOrNull?:throw HealthParameterError("health_invalid_parameters",k,"integer",healthJsonType(a[k]),
        if(k=="expected_revision") "write和propose均必填。先read取得该日revision；未记录的新日为0。" else "请传整数。")
    val action=text("action")
    val keys=when(action){"read"->setOf("action","date");"write","propose"->setOf("action","date","expected_revision","fields","source","evidence");"summary"->setOf("action","start","end","sleep_target_minutes");else->error("health_invalid_parameters")}
    require(a.keys.all{it in keys}){"health_invalid_parameters"}
    return buildJsonObject{
        put("ok",true)
        when(action){
            "read"->put("day",healthJson.encodeToJsonElement(store.day(text("date"))))
            "summary"->put("summary",healthSummary(store.snapshot().days,text("start"),text("end"),if("sleep_target_minutes" in a)integer("sleep_target_minutes")else 360))
            else->{
                val fields=(a["fields"] as? JsonObject)?.mapValues{it.value as? JsonPrimitive?:throw HealthParameterError("health_fields_invalid","fields","primitive values",healthJsonType(it.value))}
                    ?:throw HealthParameterError("health_fields_invalid","fields","object",healthJsonType(a["fields"]))
                if(action=="write")put("day",healthJson.encodeToJsonElement(store.write(text("date"),integer("expected_revision"),fields,text("source"),"ai:$owner",text("evidence"))))
                else{val proposal=store.propose(text("date"),integer("expected_revision"),fields,text("source"),"ai:$owner",text("evidence"));put("proposal_id",proposal.id);put("status","awaiting_human_confirmation")}
            }
        }
    }
}
