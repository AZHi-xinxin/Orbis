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
    description="独立的人类日记录，与AI身体模拟不同。仅人类开启后可用，不自动注入；读取结果会交给当前聊天模型，记录本身保存在本机、不进入云备份。action=read/write/propose/summary。read按date读取，未记录返回空。write传date(YYYY-MM-DD)、expected_revision(新建0)、fields对象、source(ai或mcp)、evidence(事实来源，不编造)。仅改指定字段，null表示明确清除；人类改过的字段拒绝覆盖，可改用propose交人类确认。截图来源必须propose，source=screenshot，不直接入库。propose由工具娱乐“人类记录”内原生预览确认，AI没有confirm动作。fields可用sleepMinutes整数分钟/sleepQuality/bedtime或wakeTime(HH:mm)/periodStatus(none|active|pre|post)/periodDay/steps/activeMinutes/calories/restingHeart/averageHeart/systolic/diastolic/spo2/note。缺失留空，血压血氧需要真实设备来源。summary传start/end(一年以内)及可选sleep_target_minutes(仅统计阈值)，返回有记录日的平均睡眠、步数与经期原始时间序列；缺失不记0，不作诊断预测。",
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
