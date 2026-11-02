package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthDiagnosticsTest {
    private class Storage:HealthStorage { var text:String?=null;override fun read()=text;override fun write(value:String){text=value} }
    private fun args(action:String,revision:Boolean=true,quality:JsonPrimitive=JsonPrimitive("睡得不错"))=buildJsonObject {
        put("action",action);put("date","2026-11-02");if(revision)put("expected_revision",0)
        put("source","ai");put("evidence","用户提供的合成记录")
        put("fields",buildJsonObject{put("sleepQuality",quality)})
    }
    @Test fun missingRevisionAndNumericSleepFeelingIdentifyFieldWithoutWriting() {
        for(action in listOf("write","propose")) {
            val storage=Storage();val repo=HealthRepository(storage){10}
            val e=assertThrows(HealthParameterError::class.java){executeHealthTool(repo,"owner",args(action,false))}
            val error=healthToolError(e)
            assertEquals(JsonPrimitive("expected_revision"),error["field"])
            assertEquals(JsonPrimitive("integer"),error["expected_type"])
            assertEquals(JsonPrimitive("missing"),error["actual_type"])
            assertNull(storage.text)
            val typeError=assertThrows(HealthParameterError::class.java){executeHealthTool(repo,"owner",args(action,quality=JsonPrimitive(77)))}
            val result=healthToolError(typeError)
            assertEquals(JsonPrimitive("fields.sleepQuality"),result["field"])
            assertEquals(JsonPrimitive("string"),result["expected_type"])
            assertEquals(JsonPrimitive("number"),result["actual_type"])
            assertFalse(result.toString().contains("77"));assertNull(storage.text)
            executeHealthTool(repo,"owner",args(action))
            assertNotNull(storage.text)
            if(action=="propose")assertTrue(repo.snapshot().days.isEmpty())
        }
    }
    @Test fun incompatibleCloudPayloadIsFormatFailureAndNotRetriedAsNetwork() {
        val day=LocalDate.parse("2026-11-02")
        for(payload in listOf("# 设备记录\n步数: 700", """{"data":{"2026-11-02":{"steps":700}}}""", """{"data":[{"steps":{"count":700}}]}""")) {
            val e=assertThrows(IllegalStateException::class.java){parseHealthCloudData(payload,ZoneId.of("Asia/Shanghai"),day,day)}
            assertEquals("health_cloud_format",e.message)
            assertTrue(healthSyncFailureMessage(e.message!!).contains("格式"))
            assertFalse(healthSyncRetryable(e.message!!))
        }
        assertEquals(emptyMap<String,Map<String,JsonPrimitive>>(),parseHealthCloudData("{\"data\":[]}",ZoneId.of("UTC"),day,day))
        assertTrue(healthSyncRetryable("health_sync_timeout"));assertFalse(healthSyncRetryable("health_sync_authorization"))
    }
}
