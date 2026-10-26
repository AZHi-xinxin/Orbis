package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.*
import java.time.*

internal data class HealthSyncSlot(val key:String,val through:LocalDate)
/** Two reads per day: a late-evening snapshot and a final catch-up after midnight. */
internal fun healthSyncSlot(now:ZonedDateTime):HealthSyncSlot {
    val evening=now.toLocalTime()>=LocalTime.of(23,50)
    val day=if(evening)now.toLocalDate() else now.toLocalDate().minusDays(1)
    return HealthSyncSlot("$day:${if(evening)"evening" else "final"}",day)
}
internal fun nextHealthSyncTime(now:ZonedDateTime,completedSlot:String):ZonedDateTime =
    (0L..2L).flatMap { days -> listOf(LocalTime.of(0,10),LocalTime.of(23,50)).map { time ->
        now.toLocalDate().plusDays(days).atTime(time).atZone(now.zone)
    } }.first { it>now && healthSyncSlot(it).key!=completedSlot }
private fun healthInstant(value:JsonElement?):Instant?=runCatching { Instant.parse(value?.jsonPrimitive?.content) }.getOrNull()

/** hcMCP read_health_data returns dated observations. Never parse its undated prose summary.
 * Repeated uploads are deduplicated by observation interval, newest file first.
 */
internal fun parseHealthCloudData(text:String,zone:ZoneId,from:LocalDate,through:LocalDate):Map<String,Map<String,JsonPrimitive>> {
    require(text.length<=2*1024*1024 && !through.isBefore(from) && through.toEpochDay()-from.toEpochDay()<=7){"health_cloud_range"}
    val root=healthJson.parseToJsonElement(text).jsonObject
    val uploads=root["data"] as? JsonArray ?: error("health_cloud_format")
    require(uploads.size<=100){"health_cloud_limit"}
    val result=mutableMapOf<String,MutableMap<String,JsonPrimitive>>()
    fun add(date:LocalDate,key:String,value:JsonPrimitive){if(date in from..through) {
        validateHealthFields(mapOf(key to value));result.getOrPut(date.toString()){mutableMapOf()}[key]=value
    }}
    val sorted=uploads.map{it.jsonObject}.sortedByDescending{it["file"]?.jsonPrimitive?.content.orEmpty()}
    fun rows(kind:String):List<JsonObject> = sorted.flatMap { upload ->
        val rows=(upload[kind] as? JsonArray).orEmpty();require(rows.size<=10000){"health_cloud_limit"};rows.map{it.jsonObject}
    }.also{require(it.size<=100000){"health_cloud_limit"}}
    data class Step(val start:Instant,val end:Instant,val count:Int)
    val steps=rows("steps").mapNotNull { r ->
        val start=healthInstant(r["start_time"])?:return@mapNotNull null
        val end=healthInstant(r["end_time"])?:return@mapNotNull null
        val count=r["count"]?.jsonPrimitive?.intOrNull?:return@mapNotNull null
        if(!end.isAfter(start)||count !in 0..200000) null else Step(start,end,count)
    }.distinctBy{it.start to it.end}
    for(day in from..through) {
        val start=day.atStartOfDay(zone).toInstant();val end=day.plusDays(1).atStartOfDay(zone).toInstant()
        // A cumulative whole-day interval supersedes its contained partial uploads.
        val items=steps.filter{it.start>=start && it.end<=end}.filter { a ->
            steps.none { b -> b!==a && b.start>=start && b.end<=end && b.start<=a.start && b.end>=a.end && (b.start<a.start||b.end>a.end) }
        }.sortedBy{it.start}
        if(items.isNotEmpty() && items.zipWithNext().all{(a,b)->a.end<=b.start}) {
            val total=items.sumOf{it.count.toLong()};if(total<=200000)add(day,"steps",JsonPrimitive(total.toInt()))
        }
    }
    val sleeps=rows("sleep").mapNotNull{r->
        val end=healthInstant(r["session_end_time"])?:return@mapNotNull null
        val seconds=r["duration_seconds"]?.jsonPrimitive?.longOrNull?:return@mapNotNull null
        if(seconds !in 1..86400) null else Triple(end,seconds,r)
    }.distinctBy{it.first}.groupBy{it.first.atZone(zone).toLocalDate()}
    sleeps.forEach{(day,sessions)->
        val seconds=sessions.sumOf{it.second}
        val spans=sessions.map { session ->
            val start=(session.third["stages"] as? JsonArray).orEmpty().mapNotNull{healthInstant(it.jsonObject["start_time"])}.minOrNull()
                ?:session.first.minusSeconds(session.second)
            start to session.first
        }.sortedBy{it.first}
        if(seconds in 1..86400 && spans.all{it.first<it.second} && spans.zipWithNext().all{(a,b)->a.second<=b.first}) {
            add(day,"sleepMinutes",JsonPrimitive((seconds/60).toInt()))
            val main=sessions.maxBy{it.second}
            add(day,"wakeTime",JsonPrimitive(main.first.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()))
            val start=(main.third["stages"] as? JsonArray).orEmpty().mapNotNull{healthInstant(it.jsonObject["start_time"])}.minOrNull()
            if(start!=null && start<main.first && Duration.between(start,main.first).toHours()<=24)
                add(day,"bedtime",JsonPrimitive(start.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()))
        }
    }
    rows("heart_rate").mapNotNull{r->
        val time=healthInstant(r["time"])?:return@mapNotNull null
        val bpm=r["bpm"]?.jsonPrimitive?.intOrNull?:return@mapNotNull null
        if(bpm !in 1..300)null else time to bpm
    }.distinctBy{it.first}.groupBy{it.first.atZone(zone).toLocalDate()}.forEach{(day,readings)->
        add(day,"heartRate",JsonPrimitive(readings.maxBy{it.first}.second))
    }
    return result
}
private operator fun ClosedRange<LocalDate>.iterator():Iterator<LocalDate> = generateSequence(start){it.plusDays(1)}.takeWhile{it<=endInclusive}.iterator()

/** Human corrections (including explicit blanks) are never overwritten by automatic sync. */
internal fun healthSyncPatch(day:HealthDay,incoming:Map<String,JsonPrimitive>):Map<String,JsonPrimitive> =
    incoming.filter { (key,value)->day.fields[key]?.actor!="human" && day.fields[key]?.value!=value }
