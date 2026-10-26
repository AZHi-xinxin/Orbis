package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.LocalDate

/** Human daily observations, deliberately separate from the AI body simulator. */
@Serializable internal data class HealthCell(val value: JsonPrimitive, val source: String,
    val actor: String, val updatedAt: Long, val evidence: String, val confidence: Int? = null)
@Serializable internal data class HealthDay(val date: String, val revision: Int = 0,
    val fields: Map<String,HealthCell> = emptyMap(), val updatedAt: Long = 0)
@Serializable internal data class HealthProposal(val id: String, val date: String, val expectedRevision: Int,
    val fields: Map<String,JsonPrimitive>, val source: String, val author: String,
    val evidence: String, val createdAt: Long)
@Serializable internal data class HealthState(val version: Int = 1, val days: List<HealthDay> = emptyList(),
    val history: List<HealthDay> = emptyList(), val proposals: List<HealthProposal> = emptyList())
internal interface HealthStorage { fun read(): String?; fun write(value: String) }
internal val healthJson = Json { encodeDefaults=true;ignoreUnknownKeys=false }
internal data class HealthField(val id:String,val label:String,val unit:String="",val min:Double?=null,val max:Double?=null,val whole:Boolean=true)
internal val healthFields=listOf(
    HealthField("sleepMinutes","睡眠时长","分钟",0.0,1440.0),HealthField("sleepQuality","睡眠感受"),
    HealthField("bedtime","入睡时间","HH:mm"),HealthField("wakeTime","起床时间","HH:mm"),
    HealthField("periodStatus","经期阶段","none / active / pre / post"),HealthField("periodDay","经期第几天","天",1.0,100.0),
    HealthField("steps","步数","步",0.0,200000.0),HealthField("activeMinutes","活动时长","分钟",0.0,1440.0),
    HealthField("calories","活动热量","千卡",0.0,20000.0,false),
    HealthField("restingHeart","静息心率","次/分钟",1.0,300.0),HealthField("averageHeart","平均心率","次/分钟",1.0,300.0),
    HealthField("heartRate","当次心率","次/分钟",1.0,300.0),
    HealthField("systolic","收缩压","mmHg",1.0,400.0),HealthField("diastolic","舒张压","mmHg",1.0,300.0),
    HealthField("spo2","血氧","%",0.0,100.0,false),HealthField("note","当天备注"),
)
internal fun validHealthDate(date:String): Boolean = runCatching {
    LocalDate.parse(date).let { it.toString()==date && it.year in 1900..2200 }
}.getOrDefault(false)
internal fun healthValue(field: HealthField, text:String):JsonPrimitive = if(text.isBlank()) JsonNull else {
    val value = if(field.min!=null) {
        val n=text.trim().toDoubleOrNull() ?: error("health_number_invalid")
        require(n.isFinite() && n>=field.min && n<=field.max!! && (!field.whole || n%1==0.0)) { "health_number_invalid" }
        if(field.whole) JsonPrimitive(n.toInt()) else JsonPrimitive(n)
    } else JsonPrimitive(text.trim())
    validateHealthFields(mapOf(field.id to value));value
}
internal fun validateHealthFields(fields:Map<String,JsonPrimitive>) {
    require(fields.isNotEmpty() && fields.size<=healthFields.size) { "health_fields_invalid" }
    fields.forEach { (key,value) ->
        val field=healthFields.firstOrNull { it.id==key } ?: error("health_field_unknown")
        if(value==JsonNull) return@forEach
        if(field.min!=null) {
            val number=value.takeUnless { it.isString }?.doubleOrNull
            require(number!=null && number.isFinite() && number>=field.min && number<=field.max!! && (!field.whole || number%1==0.0)) { "health_number_invalid" }
        } else {
            require(value.isString && value.content.length<=if(key=="note") 2000 else 200) { "health_text_invalid" }
            if(key=="periodStatus") require(value.content in setOf("none","active","pre","post")) { "health_period_invalid" }
            if(key in setOf("bedtime","wakeTime")) require(value.content.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))) { "health_time_invalid" }
        }
    }
}
internal class HealthHumanConflict(val fields:Set<String>):IllegalStateException("health_human_edited")
internal class HealthRepository(private val storage:HealthStorage, private val now:()->Long=System::currentTimeMillis) {
    fun snapshot():HealthState = storage.read()?.let { healthJson.decodeFromString<HealthState>(it).also(::validate) } ?: HealthState()
    fun day(date:String):HealthDay {require(validHealthDate(date)){"health_date_invalid"};return snapshot().days.firstOrNull{it.date==date}?:HealthDay(date)}
    @Synchronized fun write(date:String,expected:Int,fields:Map<String,JsonPrimitive>,source:String,actor:String,evidence:String,confidence:Int?=null):HealthDay {
        validateWrite(date,fields,source,actor,evidence);require(confidence==null||confidence in 0..100){"health_confidence_invalid"}
        check(actor=="human" || source in setOf("ai","mcp")) { "health_confirmation_required" }
        val state=snapshot();val old=state.days.firstOrNull{it.date==date}?:HealthDay(date)
        check(old.revision==expected){"health_revision_conflict"}
        if(actor!="human") {
            val locked=fields.keys.filter { old.fields[it]?.actor=="human" && old.fields[it]?.value!=fields[it] }.toSet()
            if(locked.isNotEmpty()) throw HealthHumanConflict(locked)
        }
        val time=now();val updated=old.copy(revision=Math.addExact(old.revision,1),updatedAt=time,
            fields=old.fields+fields.mapValues { (key,value) ->
                // An AI repeating a human value cannot quietly remove its protection/provenance.
                old.fields[key]?.takeIf { actor!="human" && it.actor=="human" && it.value==value }
                    ?:HealthCell(value,source,actor,time,evidence,confidence)
            })
        save(state.copy(days=state.days.filterNot{it.date==date}+updated,history=(state.history+updated).takeLast(256)))
        return updated
    }
    @Synchronized fun propose(date:String,expected:Int,fields:Map<String,JsonPrimitive>,source:String,actor:String,evidence:String):HealthProposal {
        validateWrite(date,fields,source,actor,evidence)
        val state=snapshot();check((state.days.firstOrNull{it.date==date}?.revision?:0)==expected){"health_revision_conflict"}
        check(state.proposals.size<50){"health_proposals_full"}
        return HealthProposal(java.util.UUID.randomUUID().toString(),date,expected,fields,source,actor,evidence,now()).also {
            save(state.copy(proposals=state.proposals+it))
        }
    }
    /** Only the native confirmation UI can call this host-side method. No AI action exposes it. */
    @Synchronized fun confirm(id:String,fields:Map<String,JsonPrimitive>):HealthDay {
        val state=snapshot();val proposal=state.proposals.firstOrNull{it.id==id}?:error("health_proposal_missing")
        validateHealthFields(fields)
        // One storage transaction: no duplicate commit if the process exits between accept/remove.
        val old=state.days.firstOrNull{it.date==proposal.date}?:HealthDay(proposal.date)
        check(old.revision==proposal.expectedRevision){"health_revision_conflict"}
        val time=now();val next=old.copy(revision=Math.addExact(old.revision,1),updatedAt=time,
            fields=old.fields+fields.mapValues { HealthCell(it.value,proposal.source,"human",time,proposal.evidence) })
        save(state.copy(days=state.days.filterNot{it.date==next.date}+next,history=(state.history+next).takeLast(256),proposals=state.proposals.filterNot{it.id==id}))
        return next
    }
    @Synchronized fun dismiss(id:String){val state=snapshot();save(state.copy(proposals=state.proposals.filterNot{it.id==id}))}
    private fun save(state:HealthState){validate(state);storage.write(healthJson.encodeToString(state))}
    private fun validateWrite(date:String,fields:Map<String,JsonPrimitive>,source:String,actor:String,evidence:String) {
        require(validHealthDate(date)){"health_date_invalid"};validateHealthFields(fields)
        require(source in setOf("manual","ai","mcp","screenshot") && (actor=="human"||actor=="device:hcmcp"&&source=="mcp"||actor.matches(Regex("ai:[A-Za-z0-9_-]{1,80}"))) && evidence.isNotBlank() && evidence.length<=500){"health_provenance_required"}
        require(source!="manual"||actor=="human"){"health_actor_invalid"}
    }
    companion object {
        fun validate(state:HealthState) {
            require(state.version==1 && state.days.size<=3650 && state.history.size<=256 && state.proposals.size<=50 && state.days.map{it.date}.distinct().size==state.days.size){"health_storage_invalid"}
            (state.days+state.history).forEach { day ->
                require(validHealthDate(day.date) && day.revision>0 && day.updatedAt>=0){"health_storage_invalid"}
                validateHealthFields(day.fields.mapValues{it.value.value})
                day.fields.values.forEach {
                    require((it.actor=="human"||it.actor=="device:hcmcp"&&it.source=="mcp"||it.actor.matches(Regex("ai:[A-Za-z0-9_-]{1,80}"))) &&
                        it.source in setOf("manual","ai","mcp","screenshot") && (it.source!="manual"||it.actor=="human") &&
                        it.updatedAt>=0 && it.evidence.isNotBlank() && it.evidence.length<=500 &&
                        (it.confidence==null||it.confidence in 0..100)){"health_storage_invalid"}
                }
            }
            require(state.proposals.map{it.id}.distinct().size==state.proposals.size){"health_storage_invalid"}
            state.proposals.forEach {
                require(validHealthDate(it.date) && it.expectedRevision>=0 && it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) &&
                    (it.author=="human"||it.author.matches(Regex("ai:[A-Za-z0-9_-]{1,80}"))) &&
                    it.source in setOf("manual","ai","mcp","screenshot") && (it.source!="manual"||it.author=="human") &&
                    it.createdAt>=0 && it.evidence.isNotBlank() && it.evidence.length<=500){"health_storage_invalid"}
                validateHealthFields(it.fields)
            }
        }
    }
}
internal fun healthSummary(days:List<HealthDay>,start:String,end:String,sleepTargetMinutes:Int=360):JsonObject {
    require(validHealthDate(start)&&validHealthDate(end)&&sleepTargetMinutes in 0..1440){"health_date_invalid"}
    val from=LocalDate.parse(start);val to=LocalDate.parse(end)
    require(!to.isBefore(from)&&java.time.temporal.ChronoUnit.DAYS.between(from,to)<366){"health_range_invalid"}
    val selected=days.filter{it.date>=start&&it.date<=end}.sortedBy{it.date}
    fun values(key:String)=selected.mapNotNull{it.fields[key]?.value?.doubleOrNull}
    val sleep=values("sleepMinutes")
    return buildJsonObject {
        put("start",start);put("end",end);put("recorded_days",selected.size);put("calendar_days",java.time.temporal.ChronoUnit.DAYS.between(from,to)+1)
        put("sleep_recorded_days",sleep.size);put("average_sleep_minutes",if(sleep.isEmpty()) JsonNull else JsonPrimitive(sleep.average()))
        put("sleep_target_minutes",sleepTargetMinutes);put("below_target_days",sleep.count{it<sleepTargetMinutes})
        put("steps",JsonArray(selected.mapNotNull{day->day.fields["steps"]?.value?.takeUnless{it==JsonNull}?.let { value->buildJsonObject{put("date",day.date);put("value",value)} }}))
        put("period",JsonArray(selected.mapNotNull{day->day.fields["periodStatus"]?.value?.takeUnless{it==JsonNull}?.let{value->buildJsonObject{put("date",day.date);put("status",value)} }}))
        put("note","缺失数据不计为0；阈值仅用于这次统计，不作诊断或经期预测。")
    }
}
