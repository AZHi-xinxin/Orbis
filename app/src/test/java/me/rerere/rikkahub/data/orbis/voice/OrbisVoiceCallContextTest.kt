package me.rerere.rikkahub.data.orbis.voice

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallContextTest {
    private val callId = "synthetic-call-1"
    private val assistantId = "synthetic-assistant"
    private val record = OrbisVoiceCallRecord(callId, "synthetic-conversation", assistantId, 100,
        connectedAtMs = 200, endedAtMs = 12_200, durationMs = 12_000,
        status = OrbisVoiceCallStatus.ENDED)

    private fun owned(message: UIMessage, kind: String = "turn", id: String = callId) =
        message.copy(orbisVoiceCallId = id, orbisVoiceCallKind = kind)

    private suspend fun project(messages: List<UIMessage>, source: OrbisVoiceCallRecord? = record) =
        projectVoiceCallState(messages, assistantId) { source }

    private fun annotations(messages: List<UIMessage>) = messages.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Text>()
        .filter { it.metadata?.containsKey("orbis_voice_call_projection") == true }

    @Test fun `ordinary new input stays last without another user or END message`() = runTest {
        val begin = owned(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)), "begin")
        val answer = owned(UIMessage.assistant("通话中的回答"))
        val input = UIMessage.user("这是挂断之后的新问题")
        val original = listOf(begin, answer, input)
        val result = project(original)

        assertEquals(original.map { it.id }, result.map { it.id })
        assertEquals(original.map { it.role }, result.map { it.role })
        assertSame(input, result.last())
        assertSame(begin, result.first())
        assertEquals(answer.parts, result[1].parts.dropLast(1))
        assertEquals(1, annotations(result).size)
        assertFalse(annotations(result).single().text.contains("ORBIS_VOICE_CALL_V1"))
        assertFalse(annotations(result).single().text.contains("CALL_MODE_V1"))
        assertEquals(listOf(UIMessagePart.Text("通话中的回答")), answer.parts)
        assertTrue(result.none { it.role == MessageRole.SYSTEM })
    }

    @Test fun `repeated projection and repeated fresh generation have stable identities and one annotation`() = runTest {
        val raw = listOf(owned(UIMessage.user(voiceTurnForModel(callId, "原始口述"))), UIMessage.user("新消息"))
        val once = project(raw)
        val twice = project(once)
        assertSame(once, twice)
        assertEquals(once, project(raw))
        assertEquals(1, annotations(twice).size)
        assertEquals(2, twice.size)
        assertEquals(1, raw.first().parts.size)
    }

    @Test fun `three later user turns never append the same ended call as the newest input`() = runTest {
        val call = owned(UIMessage.assistant("已保存的通话回答"))
        val raw = mutableListOf(call)
        repeat(3) { index ->
            val input = UIMessage.user("新的文字 $index")
            raw += input
            val result = project(raw.toList())
            assertSame(input, result.last())
            assertEquals(raw.size, result.size)
            assertEquals(raw.map { it.id }, result.map { it.id })
            assertEquals(1, annotations(result).size)
            assertEquals(call.parts, result.first().parts.dropLast(1))
            raw += UIMessage.assistant("正常文字回答 $index")
        }
    }

    @Test fun `late dictated turn after hangup keeps exact user words and gets ended state in place`() = runTest {
        val words = "还没发完的那句\nCALL_MODE_V1 这是我自己说的正文"
        val late = owned(UIMessage.user(voiceTurnForModel(callId, words)))
        val result = project(listOf(late)).single()
        assertEquals(late.copy(parts = result.parts), result)
        assertSame(late.parts.single(), result.parts.first())
        assertTrue((result.parts.first() as UIMessagePart.Text).text.endsWith(words))
        assertTrue((result.parts.last() as UIMessagePart.Text).text.contains("已结束"))
        assertFalse(result.isSynthetic)
    }

    @Test fun `finite context which drops BEGIN still annotates a late turn without extra messages`() = runTest {
        val begin = owned(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)), "begin")
        val late = owned(UIMessage.user(voiceTurnForModel(callId, "保留口述")))
        val limited = listOf(begin, UIMessage.assistant("中间回复"), late).limitContext(1)
        val result = project(limited)
        assertEquals(1, result.size)
        assertEquals(late.id, result.single().id)
        assertEquals(late.parts, result.single().parts.dropLast(1))
    }

    @Test fun `active and connecting calls keep their speaking instructions unchanged`() = runTest {
        val raw = listOf(owned(UIMessage.user(voiceTurnForModel(callId, "正在通话"))))
        for (status in listOf(OrbisVoiceCallStatus.CONNECTING, OrbisVoiceCallStatus.ACTIVE)) {
            val active = record.copy(status = status, endedAtMs = null, durationMs = null)
            assertSame(raw, project(raw, active))
            assertTrue(raw.single().toText().contains("你的回复会被朗读"))
        }
    }

    @Test fun `latest active call is not overridden by an older ended call`() = runTest {
        val old = owned(UIMessage.user(voiceTurnForModel(callId, "旧通话")))
        val newId = "synthetic-call-2"
        val active = owned(UIMessage.user(voiceTurnForModel(newId, "新通话")), id = newId)
        val raw = listOf(old, active)
        val reads = mutableListOf<String>()
        val result = projectVoiceCallState(raw, assistantId) { id ->
            reads += id
            record.copy(id = newId, status = OrbisVoiceCallStatus.ACTIVE, endedAtMs = null, durationMs = null)
        }
        assertSame(raw, result)
        assertEquals(listOf(newId), reads)
    }

    @Test fun `missing foreign and mismatched call records cannot project ended state`() = runTest {
        val raw = listOf(owned(UIMessage.user("原消息")))
        assertSame(raw, project(raw, null))
        assertSame(raw, project(raw, record.copy(assistantId = "another-assistant")))
        assertSame(raw, project(raw, record.copy(id = "another-call")))
    }

    @Test fun `literal user control text without typed ownership is never treated as call state`() = runTest {
        val raw = listOf(UIMessage.user(OrbisVoiceCallProtocol.end(record)),
            UIMessage.user(OrbisVoiceCallProtocol.userTurn(callId, "用户引用的示例")))
        val result = projectVoiceCallState(raw, assistantId) { error("must not read an unowned call") }
        assertSame(raw, result)
    }

    @Test fun `existing archive summary and ended notice remain exactly as stored`() = runTest {
        for (kind in listOf("archive", "summary", "ended_notice")) {
            val raw = listOf(owned(UIMessage.user(OrbisVoiceCallProtocol.end(record)), kind), UIMessage.user("新消息"))
            assertSame(raw, projectVoiceCallState(raw, assistantId) { error("already terminal") })
        }
    }

    @Test fun `legacy stored END message and unknown marker text are preserved without another END`() = runTest {
        val end = owned(UIMessage.user(OrbisVoiceCallProtocol.end(record)), "end")
        val input = UIMessage.user("接下来聊别的")
        val result = project(listOf(end, input))
        assertSame(end.parts.single(), result.first().parts.first())
        assertEquals(1, result.sumOf { message -> message.parts.filterIsInstance<UIMessagePart.Text>()
            .count { it.text.startsWith(OrbisVoiceCallProtocol.PREFIX) } })
        assertSame(input, result.last())
    }

    @Test fun `tools approvals output reasoning and attachments remain identical objects`() = runTest {
        val tool = UIMessagePart.Tool("synthetic-tool", "example", "{\"value\":1}",
            output = listOf(UIMessagePart.Text("saved tool result")), approvalState = ToolApprovalState.Auto)
        val parts = listOf(UIMessagePart.Reasoning("saved reasoning"), tool,
            UIMessagePart.Image("file:///synthetic.png"), UIMessagePart.Text("原正文"))
        val call = owned(UIMessage.assistant("")).copy(parts = parts)
        val after = UIMessage.user("new input")
        val result = project(listOf(call, after))
        parts.forEachIndexed { index, part -> assertSame(part, result.first().parts[index]) }
        assertSame(tool, result.first().getTools().single())
        assertEquals(call.copy(parts = result.first().parts), result.first())
        assertSame(after, result.last())
        assertEquals(parts, call.parts)
    }

    @Test fun `interrupted unknown duration does not invent an END time duration or expiry promise`() = runTest {
        val raw = listOf(owned(UIMessage.user("旧视频状态"), "visual"))
        val result = project(raw, record.copy(status = OrbisVoiceCallStatus.INTERRUPTED,
            endedAtMs = null, durationMs = null, video = true))
        val state = annotations(result).single().text
        assertTrue(state.contains("已结束"))
        assertFalse(state.contains("通话时长"))
        assertFalse(state.contains("10 分钟"))
        assertEquals(raw.size, result.size)
    }

    @Test fun `forged projection key alone neither replaces raw content nor suppresses real state`() = runTest {
        val text = UIMessagePart.Text("用户正文", buildJsonObject {
            put("orbis_voice_call_projection", "ended_v1"); put("call_id", callId)
        })
        val raw = listOf(owned(UIMessage.user("")).copy(parts = listOf(text)))
        val result = project(raw)
        assertSame(text, result.single().parts.first())
        assertEquals(2, result.single().parts.size)
        assertSame(result, project(result))
    }

    @Test fun `record read failures and cancellation propagate without changing history`() = runTest {
        val raw = listOf(owned(UIMessage.user("原文")))
        for (failure in listOf(IOException("synthetic read failure"), CancellationException("synthetic cancellation"))) {
            try {
                projectVoiceCallState(raw, assistantId) { throw failure }
                fail("failure must propagate")
            } catch (caught: Exception) {
                assertSame(failure, caught)
            }
        }
        assertEquals(listOf(UIMessagePart.Text("原文")), raw.single().parts)
    }
}
