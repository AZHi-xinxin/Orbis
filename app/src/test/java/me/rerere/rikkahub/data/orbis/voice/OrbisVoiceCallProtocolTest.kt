package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallProtocolTest {
    private val completed = OrbisVoiceCallRecord("call-1", "conversation-1", "assistant-1", 1000,
        status = OrbisVoiceCallStatus.ENDED, archiveStatus = OrbisVoiceArchiveStatus.READY,
        connectedAtMs = 1200, endedAtMs = 254200, durationMs = 253000,
        summary = "她说明天想去公园。\n我们约好了时间。", modelTranscript = "用户：明天一起去公园。\n助手：好，上午见。")

    @Test fun `first line marker round trips multiline summary for model independently of UI folding`() {
        val encoded = OrbisVoiceCallProtocol.summary(completed)
        assertEquals(2, encoded.lines().size)
        val marker = OrbisVoiceCallProtocol.parse(encoded)!!
        assertEquals(OrbisVoiceCallMarkerKind.SUMMARY, marker.kind)
        assertEquals(completed.summary, marker.summary)
        assertEquals(253000L, marker.durationMs)
        assertEquals("call-1", marker.callId)
        assertTrue(encoded.endsWith("【通话时长 4:13】"))
    }

    @Test fun `only exact supported first line markers are recognized`() {
        val valid = OrbisVoiceCallProtocol.begin("call-1")
        assertEquals(OrbisVoiceCallMarkerKind.BEGIN, OrbisVoiceCallProtocol.parse(valid)!!.kind)
        listOf(" $valid", "正文\n$valid", "```\n$valid\n```", "ORBIS_VOICE_CALL_V1 broken",
            "ORBIS_VOICE_CALL_V1 {\"kind\":\"BEGIN\",\"callId\":\"../escape\"}",
            "ORBIS_VOICE_CALL_V1 {\"kind\":\"SUMMARY\",\"callId\":\"call-1\"}",
            "ORBIS_VOICE_CALL_V1 {\"kind\":\"BEGIN\",\"callId\":\"call-1\",\"unknown\":true}",
            "ORBIS_VOICE_CALL_V1 {\"kind\":\"BEGIN\",\"callId\":\"call-1\"} trailing"
        ).forEach { assertNull(it, OrbisVoiceCallProtocol.parse(it)) }
    }

    @Test fun `call metadata is separate from exact user transcript including newlines`() {
        val text = "我说的话\nCALL_MODE_V1 这是正文"
        val turn = OrbisVoiceCallProtocol.userTurn("call-1", text)
        val firstLine = turn.substringBefore('\n').removePrefix(OrbisVoiceCallProtocol.CALL_MODE_PREFIX)
        val metadata = Json.parseToJsonElement(firstLine).jsonObject
        assertEquals("true", metadata.getValue("active").jsonPrimitive.content)
        assertEquals("call-1", metadata.getValue("callId").jsonPrimitive.content)
        assertEquals(text, turn.substringAfter('\n'))
    }

    @Test fun `unknown duration never invents zero and summary requires ready archive`() {
        assertEquals("【通话结束】", OrbisVoiceCallProtocol.title(null))
        assertEquals("【通话时长 0:00】", OrbisVoiceCallProtocol.title(0))
        val end = OrbisVoiceCallProtocol.end(completed.copy(durationMs = null))
        assertNull(OrbisVoiceCallProtocol.parse(end)!!.durationMs)
        assertTrue(end.contains("CALL_MODE_V1 {\"active\":false}"))
        assertThrows(IllegalArgumentException::class.java) {
            OrbisVoiceCallProtocol.summary(completed.copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED))
        }
    }

    @Test fun `same model archive accepts whole JSON or one fence without losing source text`() {
        val answer = "{\"summary\":\"约好明天见。\",\"transcript\":\"用户：明天见。\\n助手：好。\"}"
        val expected = OrbisVoiceModelArchive("约好明天见。", "用户：明天见。\n助手：好。")
        assertEquals(expected, OrbisVoiceCallProtocol.parseModelArchive(answer))
        assertEquals(expected, OrbisVoiceCallProtocol.parseModelArchive("```json\n$answer\n```"))
        assertEquals(expected, OrbisVoiceCallProtocol.parseModelArchive("```JSON\r\n$answer\r\n```"))
    }

    @Test fun `blank malformed numeric and template archives never become success`() {
        listOf("", "null", "[]", "{}", "我已经保存好了。",
            "{\"summary\":\" \",\"transcript\":\"记录\"}",
            "{\"summary\":\"摘要\",\"transcript\":\"全文\"}",
            "{\"summary\":\"<summary>\",\"transcript\":\"<transcript>\"}",
            "{\"summary\":\"请在此填写摘要\",\"transcript\":\"...\"}",
            "{\"summary\":true,\"transcript\":123}",
            "{\"summary\":\"已约好时间\",\"transcript\":null}",
            "{\"summary\":\"已约好时间\",\"transcript\":\"双方确认上午见\",\"extra\":true}",
            "这是结果：{\"summary\":\"已约好时间\",\"transcript\":\"双方确认上午见\"}",
            "```json\n{\"summary\":\"已约好时间\",\"transcript\":\"双方确认上午见\"}\n```\n其它文字"
        ).forEach { assertNull(it, OrbisVoiceCallProtocol.parseModelArchive(it)) }
    }
}
