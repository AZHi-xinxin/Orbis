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
        put("fields",buildJsonObject{put("type","object")})
    },required=listOf("action"))},
    needsApproval={it.jsonObject["action"]?.jsonPrimitive?.content !in setOf("read","summary")},
    hostApproval=HostToolApproval("orbis:human-health","human-health-v1","人类记录"),isApprovalCurrent={enabled()},
    execute={raw->
        try{
            val result=withContext(Dispatchers.IO){check(enabled()){"health_disabled"};executeHealthTool(openHealth(context),owner,raw)}
            listOf(UIMessagePart.Text(result.toString()))
        }catch(e:CancellationException){throw e}
        catch(e:Exception){listOf(UIMessagePart.Text(buildJsonObject{
            put("ok",false);put("code",e.message?.takeIf{it.matches(Regex("health_[a-z_]+"))}?:"health_invalid_parameters_or_storage")
            if(e is HealthHumanConflict){put("protected_fields",JsonArray(e.fields.sorted().map(::JsonPrimitive)));put("next_step","这些字段由人类修正过，未覆盖；可提交propose供本人确认。")}
            put("automatic_retry",false)
        }.toString()))}
    },
)
internal fun executeHealthTool(store:HealthRepository,owner:String,raw:JsonElement):JsonObject {
    require(owner.matches(Regex("[A-Za-z0-9_-]{1,80}"))){"health_actor_invalid"}
    val a=raw.jsonObject
    fun text(k:String)=a[k]?.jsonPrimitive?.takeIf{it.isString}?.content?:error("health_invalid_parameters")
    fun integer(k:String)=a[k]?.jsonPrimitive?.takeUnless{it.isString}?.intOrNull?:error("health_invalid_parameters")
    val action=text("action")
    val keys=when(action){"read"->setOf("action","date");"write","propose"->setOf("action","date","expected_revision","fields","source","evidence");"summary"->setOf("action","start","end","sleep_target_minutes");else->error("health_invalid_parameters")}
    require(a.keys.all{it in keys}){"health_invalid_parameters"}
    return buildJsonObject{
        put("ok",true)
        when(action){
            "read"->put("day",healthJson.encodeToJsonElement(store.day(text("date"))))
            "summary"->put("summary",healthSummary(store.snapshot().days,text("start"),text("end"),if("sleep_target_minutes" in a)integer("sleep_target_minutes")else 360))
            else->{
                val fields=a["fields"]?.jsonObject?.mapValues{it.value as? JsonPrimitive?:error("health_fields_invalid")}?:error("health_fields_invalid")
                if(action=="write")put("day",healthJson.encodeToJsonElement(store.write(text("date"),integer("expected_revision"),fields,text("source"),"ai:$owner",text("evidence"))))
                else{val proposal=store.propose(text("date"),integer("expected_revision"),fields,text("source"),"ai:$owner",text("evidence"));put("proposal_id",proposal.id);put("status","awaiting_human_confirmation")}
            }
        }
    }
}
