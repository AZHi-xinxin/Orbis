package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HealthRecordsTest {
    private class Storage:HealthStorage{var text:String?=null;override fun read()=text;override fun write(value:String){text=value}}
    @Test fun humanEditedFieldsCannotBeOverwrittenOrUnlockedByRepeatingTheSameValue(){
        val s=HealthRepository(Storage()){7}
        s.write("2026-10-24",0,mapOf("sleepMinutes" to JsonPrimitive(420)),"ai","ai:owner","本人在对话提供")
        s.write("2026-10-24",1,mapOf("sleepMinutes" to JsonPrimitive(480)),"manual","human","本人校正")
        assertThrows(HealthHumanConflict::class.java){s.write("2026-10-24",2,mapOf("sleepMinutes" to JsonPrimitive(300)),"ai","ai:owner","旧对话")}
        val same=s.write("2026-10-24",2,mapOf("sleepMinutes" to JsonPrimitive(480)),"ai","ai:owner","重复内容")
        assertEquals("human",same.fields["sleepMinutes"]?.actor)
        val next=s.write("2026-10-24",3,mapOf("steps" to JsonPrimitive(100)),"mcp","ai:owner","设备返回")
        assertEquals(480,next.fields["sleepMinutes"]?.value?.int);assertEquals(100,next.fields["steps"]?.value?.int)
        assertThrows(IllegalStateException::class.java){s.write("2026-10-24",3,mapOf("steps" to JsonPrimitive(101)),"ai","ai:owner","过期写入")}
    }
    @Test fun screenshotProposalDoesNotWriteUntilNativeConfirmationAndOldProposalCannotOverwrite(){
        val storage=Storage();val s=HealthRepository(storage){9}
        val values=mapOf("steps" to JsonPrimitive(293))
        val p=s.propose("2026-10-24",0,values,"screenshot","ai:owner","截图测试")
        assertEquals(0,s.day(p.date).revision);assertTrue(s.snapshot().days.isEmpty())
        assertThrows(IllegalStateException::class.java){s.write(p.date,0,values,"screenshot","ai:owner","不能替代确认")}
        val updated=s.confirm(p.id,mapOf("steps" to JsonPrimitive(294)))
        assertEquals("human",updated.fields["steps"]?.actor);assertEquals("screenshot",updated.fields["steps"]?.source)
        assertTrue(s.snapshot().proposals.isEmpty());assertEquals(updated,HealthRepository(storage).day(p.date))
        val stale=s.propose(p.date,1,values,"ai","ai:owner","旧建议")
        s.write(p.date,1,mapOf("steps" to JsonPrimitive(300)),"manual","human","新值")
        assertThrows(IllegalStateException::class.java){s.confirm(stale.id,values)}
        assertEquals(300,s.day(p.date).fields["steps"]?.value?.int)
    }
    @Test fun summaryCountsOnlyRecordedValuesAndMissingMeasurementsStayMissing(){
        val s=HealthRepository(Storage()){10}
        s.write("2026-10-20",0,mapOf("sleepMinutes" to JsonPrimitive(300),"steps" to JsonPrimitive(100)),"manual","human","合成记录")
        s.write("2026-10-21",0,mapOf("sleepMinutes" to JsonPrimitive(480)),"manual","human","合成记录")
        s.write("2026-10-22",0,mapOf("steps" to JsonPrimitive(0)),"manual","human","明确为0")
        val summary=healthSummary(s.snapshot().days,"2026-10-19","2026-10-25")
        assertEquals(390.0,summary["average_sleep_minutes"]?.jsonPrimitive?.double?:0.0,0.0)
        assertEquals(2,summary["sleep_recorded_days"]?.jsonPrimitive?.int)
        assertEquals(1,summary["below_target_days"]?.jsonPrimitive?.int)
        assertEquals(2,summary["steps"]?.jsonArray?.size)
        assertFalse(s.day("2026-10-20").fields.containsKey("spo2"))
        assertEquals(JsonNull,healthSummary(emptyList(),"2026-10-19","2026-10-25")["average_sleep_minutes"])
    }
    @Test fun toolCannotImpersonateHumanConfirmProposalOrSetUnknownFields(){
        val s=HealthRepository(Storage()){10}
        fun args(action:String)=buildJsonObject{put("action",action);put("date","2026-10-24");put("expected_revision",0);put("source","manual");put("evidence","伪装");put("fields",buildJsonObject{put("steps",1)})}
        assertThrows(IllegalArgumentException::class.java){executeHealthTool(s,"owner",args("write"))}
        assertThrows(IllegalStateException::class.java){executeHealthTool(s,"owner",args("confirm"))}
        assertThrows(IllegalStateException::class.java){validateHealthFields(mapOf("diagnosis" to JsonPrimitive("伪造")))}
        assertTrue(s.snapshot().days.isEmpty())
    }
    @Test fun localOcrParserUsesLabelsAndLeavesAmbiguousUnlabelledNumbersAlone(){
        val values=parseHealthScreenshot("总睡眠\n9小时48分钟\n步数 293 步\n平均心率 80 bpm\n血氧 98%\n2026年10月24日")
        assertEquals(588,values["sleepMinutes"]?.int);assertEquals(293,values["steps"]?.int);assertEquals(80,values["averageHeart"]?.int)
        assertFalse(values.containsKey("periodDay"));assertFalse(values.containsKey("systolic"))
        assertFalse(parseHealthScreenshot("步数 293 步\n步数 600 步").containsKey("steps"))
        assertTrue(parseHealthScreenshot("今天 293 80 114").isEmpty())
    }
}
