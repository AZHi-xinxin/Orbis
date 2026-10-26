package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HealthCloudSyncTest {
    private val zone=ZoneId.of("Asia/Shanghai")
    private val day=LocalDate.parse("2026-10-20")
    private fun upload(file:String,steps:JsonArray=JsonArray(emptyList()),sleep:JsonArray=JsonArray(emptyList()),heart:JsonArray=JsonArray(emptyList()))=buildJsonObject {
        put("file",file);put("steps",steps);put("sleep",sleep);put("heart_rate",heart)
    }
    private fun step(start:String,end:String,count:Int)=buildJsonObject{put("start_time",start);put("end_time",end);put("count",count)}
    private fun parse(vararg uploads:JsonObject)=parseHealthCloudData(buildJsonObject{put("data",JsonArray(uploads.toList()))}.toString(),zone,day,day)
    @Test fun disablingRebindingOrReenablingRejectsInFlightResults() {
        val grant=HealthSyncSettings(enabled=true,serverId="source",binding="endpoint",generation="one")
        assertTrue(healthSyncGrantCurrent(grant,grant))
        assertFalse(healthSyncGrantCurrent(grant.copy(enabled=false),grant))
        assertFalse(healthSyncGrantCurrent(grant.copy(binding="changed"),grant))
        assertFalse(healthSyncGrantCurrent(grant.copy(generation="two"),grant))
    }
    @Test fun cumulativeAndRepeatedUploadsDoNotMultiplySteps() {
        val full=step("2026-10-19T16:00:00Z","2026-10-20T16:00:00Z",800)
        val partial=step("2026-10-19T16:00:00Z","2026-10-20T10:00:00Z",500)
        val newest=upload("health_data_20261021.json",JsonArray(listOf(full,partial)))
        val old=upload("health_data_20261020.json",JsonArray(listOf(full,partial)))
        assertEquals(800,parse(old,newest)[day.toString()]?.get("steps")?.int)
        assertEquals(parse(newest),parse(old,newest))
    }
    @Test fun separateIntervalsSumButAmbiguousOverlapDoesNot() {
        val a=step("2026-10-20T00:00:00Z","2026-10-20T01:00:00Z",120)
        val b=step("2026-10-20T01:00:00Z","2026-10-20T02:00:00Z",200)
        val overlap=step("2026-10-20T00:30:00Z","2026-10-20T01:30:00Z",180)
        assertEquals(320,parse(upload("a",JsonArray(listOf(a,b))))[day.toString()]?.get("steps")?.int)
        assertTrue(parse(upload("a",JsonArray(listOf(a,b,overlap)))).isEmpty())
    }
    @Test fun latestCorrectionWinsAndMissingIsNotZero() {
        val a=step("2026-10-20T00:00:00Z","2026-10-20T01:00:00Z",120)
        val b=step("2026-10-20T00:00:00Z","2026-10-20T01:00:00Z",130)
        assertEquals(130,parse(upload("20261020",JsonArray(listOf(a))),upload("20261021",JsonArray(listOf(b))))[day.toString()]?.get("steps")?.int)
        assertTrue(parse(upload("empty")).isEmpty())
    }
    @Test fun sleepBelongsToWakeDateAndHeartIsAnInstantMeasurement() {
        val sleep=buildJsonObject{put("session_end_time","2026-10-19T23:00:00Z");put("duration_seconds",25200);put("stages",buildJsonArray{add(buildJsonObject{put("start_time","2026-10-19T16:00:00Z")})})}
        val heart=buildJsonObject{put("time","2026-10-20T03:00:00Z");put("bpm",75)}
        val row=upload("a",sleep=JsonArray(listOf(sleep)),heart=JsonArray(listOf(heart)))
        val actual=parse(row,row).getValue(day.toString())
        assertEquals(420,actual["sleepMinutes"]?.int);assertEquals("07:00",actual["wakeTime"]?.content)
        assertEquals(75,actual["heartRate"]?.int);assertFalse(actual.containsKey("averageHeart"))
    }
    @Test fun nextWakeSkipsCompletedSlotAndDoesNotPollDuringTheDay() {
        val noon=day.atTime(12,0).atZone(zone)
        assertEquals(day.atTime(23,50).atZone(zone),nextHealthSyncTime(noon,"2026-10-19:final"))
        val evening=day.atTime(23,55).atZone(zone)
        assertEquals(day.plusDays(1).atTime(0,10).atZone(zone),nextHealthSyncTime(evening,"2026-10-20:evening"))
        assertEquals(day.plusDays(1).atTime(23,50).atZone(zone),nextHealthSyncTime(day.plusDays(1).atTime(0,5).atZone(zone),"2026-10-20:final"))
    }
    @Test fun lateEveningAndMidnightUseDifferentSlotsAndLocalDates() {
        assertEquals(HealthSyncSlot("2026-10-19:final",day.minusDays(1)),healthSyncSlot(day.atTime(23,49).atZone(zone)))
        assertEquals(HealthSyncSlot("2026-10-20:evening",day),healthSyncSlot(day.atTime(23,50).atZone(zone)))
        assertEquals(HealthSyncSlot("2026-10-20:final",day),healthSyncSlot(day.plusDays(1).atStartOfDay(zone)))
    }
    @Test fun manualCorrectionsAndExplicitBlanksSurviveWithoutDuplicateWrites() {
        val cell=HealthCell(JsonPrimitive(40),"manual","human",1,"manual")
        val day=HealthDay("2026-10-20",1,mapOf("steps" to cell,"sleepMinutes" to cell.copy(value=JsonNull)))
        assertTrue(healthSyncPatch(day,mapOf("steps" to JsonPrimitive(800),"sleepMinutes" to JsonPrimitive(420))).isEmpty())
        val automatic=day.copy(fields=mapOf("steps" to cell.copy(actor="device:hcmcp",source="mcp")))
        assertTrue(healthSyncPatch(automatic,mapOf("steps" to JsonPrimitive(40))).isEmpty())
        assertEquals(1,healthSyncPatch(automatic,mapOf("steps" to JsonPrimitive(50))).size)
    }
    @Test fun deviceProvenancePersistsWithoutImpersonatingHumanOrAi() {
        var value:String?=null
        val storage=object:HealthStorage{override fun read()=value;override fun write(v:String){value=v}}
        val repo=HealthRepository(storage)
        repo.write(day.toString(),0,mapOf("steps" to JsonPrimitive(12)),"mcp","device:hcmcp","device records")
        assertEquals("device:hcmcp",HealthRepository(storage).day(day.toString()).fields["steps"]?.actor)
        assertThrows(IllegalArgumentException::class.java){repo.write(day.toString(),1,mapOf("steps" to JsonPrimitive(13)),"ai","device:hcmcp","invalid")}
    }
}
