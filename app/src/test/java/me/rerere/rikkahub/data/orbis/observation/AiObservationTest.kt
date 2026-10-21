package me.rerere.rikkahub.data.orbis.observation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationInputSnapshot
import me.rerere.rikkahub.data.ai.tools.observationWindowsTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.dao.RescueWindowMetadata
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.*
import org.junit.Test

class AiObservationTest {
    private fun facts(regeneration: Long = 0, deleted: Long = 0) = ObservationFacts(
        "当前窗", "当前助手", "actual-configured-model", regeneration = regeneration, deleted = deleted,
        compactions = 3, longestWindow = "旧窗", windowCount = 12, assistantCount = 5,
        lastActive = "2026-10-20T10:00:00Z", tts = "tts-model", toolModels = mapOf("快速" to "fast-model"),
    )

    @Test fun newAndOldSettingsDefaultOff() {
        assertFalse(Settings().aiGlobalObservation)
        assertFalse(Json { ignoreUnknownKeys = true }.decodeFromString<Settings>("{}").aiGlobalObservation)
        val settings = Settings(aiGlobalObservation = true)
        assertTrue(Json.decodeFromString<Settings>(Json.encodeToString(Settings.serializer(), settings)).aiGlobalObservation)
    }

    @Test fun intervalIsConsumedBeforeFinallyFlushAndClockNeverGoesBackwards() {
        val interval = ObservationInterval(1_000)
        assertEquals(10_000L, interval.take(11_000))
        assertEquals(0L, interval.take(11_000))
        assertEquals(1L, interval.take(11_001))
        assertEquals(0L, interval.take(10_999))
        assertEquals(9L, interval.take(11_010))
    }

    @Test fun missingAutoModelDoesNotInventAWorkingFallback() {
        assertEquals("未配置", observationToolModels(Settings())["快速"])
        assertEquals("未配置", observationToolModels(Settings())["翻译"])
    }

    @Test fun conciseFactsExcludeZeroEventsAndNoInstructions() {
        val text = observationReport(facts())
        val value = Json.parseToJsonElement(text).jsonObject
        assertFalse("本条重刷" in value); assertFalse("已删消息" in value)
        assertEquals(3L, value["本窗自压缩"]!!.jsonPrimitive.long)
        assertEquals(5, value["助手总数"]!!.jsonPrimitive.int)
        assertEquals("actual-configured-model", value["模型"]!!.jsonPrimitive.content)
        assertFalse(text.contains("不是")); assertFalse(text.contains("不要"))
        assertTrue(text.length < 450)
        val nonZero = Json.parseToJsonElement(observationReport(facts(2, 7))).jsonObject
        assertEquals(2L, nonZero["本条重刷"]!!.jsonPrimitive.long)
        assertEquals(7L, nonZero["已删消息"]!!.jsonPrimitive.long)
    }

    @Test fun neverIncludeKeysEndpointsOrEntireProviderConfiguration() {
        val provider = TTSProviderSetting.OpenAI(model = "voice-1", apiKey = "secret-value", baseUrl = "https://private.invalid/")
        val settings = Settings(ttsProviders = listOf(provider), selectedTTSProviderId = provider.id)
        assertEquals("voice-1", observationTts(settings))
        val report = observationReport(facts().copy(tts = observationTts(settings)))
        assertFalse(report.contains("secret-value")); assertFalse(report.contains("private.invalid"))
        val volc = TTSProviderSetting.Volcengine(resourceId = "seed-tts-model")
        assertEquals("seed-tts-model", observationTts(settings.copy(ttsProviders = listOf(volc),
            selectedTTSProviderId = volc.id)))
    }

    @Test fun frozenOnceDoesNotPersistChangeHumanOrMultiplyOnToolContinuation() = runTest {
        val human = UIMessage.user("hello")
        val original = listOf(human)
        assertSame(original, projectObservation(original, null))
        val snapshot = GenerationInputSnapshot(observation = observationReport(facts(1, 0)))
        val first = snapshot.input { original }
        val second = snapshot.input { error("must not re-render") }
        assertEquals(MessageRole.USER, first.single().role)
        assertEquals(human.id, first.single().id)
        assertEquals(human.parts, first.single().parts.dropLast(1))
        assertTrue(first.single().parts.last().isObservationProjection())
        assertEquals(first, second)
        assertEquals("hello", human.toText())
        assertEquals(1, first.count { it.toText().contains("[Orbis全局观测]") })
    }

    @Test fun namesOnlyToolIsPagedAndRechecksHumanToggle() = runTest {
        var enabled = true
        var reads = 0
        val tool = observationWindowsTool({ enabled }) { limit, offset ->
            reads++; assertEquals(3, limit); assertEquals(4, offset)
            listOf(RescueWindowMetadata("1", "一"), RescueWindowMetadata("2", "二"), RescueWindowMetadata("3", "三"))
        }
        val args = buildJsonObject { put("limit", 2); put("offset", 4) }
        val text = tool.execute(args).filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>().single().text
        val result = Json.parseToJsonElement(text).jsonObject
        assertEquals(2, result["windows"]!!.jsonArray.size)
        assertTrue(result["has_more"]!!.jsonPrimitive.boolean)
        assertEquals(6, result["next_offset"]!!.jsonPrimitive.int)
        enabled = false
        assertTrue(tool.execute(args).toString().contains("observation_disabled"))
        assertEquals(1, reads)
    }
}
