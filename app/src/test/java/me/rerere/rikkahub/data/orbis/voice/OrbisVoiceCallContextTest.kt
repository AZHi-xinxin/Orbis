package me.rerere.rikkahub.data.orbis.voice

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private val active = record.copy(status = OrbisVoiceCallStatus.ACTIVE, endedAtMs = null, durationMs = null)

    private fun owned(message: UIMessage, kind: String = "turn", id: String = callId) =
        message.copy(orbisVoiceCallId = id, orbisVoiceCallKind = kind)

    private suspend fun noHistoricalProjection(messages: List<UIMessage>): List<UIMessage> =
        projectActiveVoiceCallAcoustics(messages, assistantId,
            readRecord = { error("ordinary chat must not consult historical call state") },
            readAcousticNote = { _, _ -> error("ordinary chat must not claim live acoustics") })

    @Test fun `later ordinary text turns never inject ended status or mutate history`() = runTest {
        val begin = owned(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)), "begin")
        val answer = owned(UIMessage.assistant("通话中的回答"))
        val raw = mutableListOf(begin, answer)
        repeat(3) { index ->
            val input = UIMessage.user("挂断后的新问题 $index")
            raw += input
            val snapshot = raw.toList()
            assertSame(snapshot, noHistoricalProjection(snapshot))
            assertSame(input, snapshot.last())
            assertEquals(listOf(UIMessagePart.Text("通话中的回答")), answer.parts)
            raw += UIMessage.assistant("正常文字回答 $index")
        }
    }

    @Test fun `empty input and literal control text without typed ownership add nothing`() = runTest {
        val empty = emptyList<UIMessage>()
        assertSame(empty, noHistoricalProjection(empty))
        val raw = listOf(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)),
            UIMessage.user(OrbisVoiceCallProtocol.userTurn(callId, "用户引用的示例")))
        assertSame(raw, noHistoricalProjection(raw))
    }

    @Test fun `real begin and independent archive END remain exact without another event`() = runTest {
        val beginText = OrbisVoiceCallProtocol.begin(callId)
        val endText = voiceArchiveRequest(record)
        assertEquals(OrbisVoiceCallMarkerKind.BEGIN, OrbisVoiceCallProtocol.parse(beginText)!!.kind)
        assertEquals(OrbisVoiceCallMarkerKind.END, OrbisVoiceCallProtocol.parse(endText)!!.kind)
        assertTrue(endText.contains("CALL_MODE_V1 {\"active\":false}"))
        for ((kind, text) in listOf("begin" to beginText, "archive" to endText,
                "end" to OrbisVoiceCallProtocol.end(record), "ended_notice" to endText)) {
            val raw = listOf(owned(UIMessage.user(text), kind))
            assertSame(raw, noHistoricalProjection(raw))
            assertEquals(text, raw.single().toText())
        }
    }

    @Test fun `ready summary and old terminal notices stay exactly as stored`() = runTest {
        val summary = OrbisVoiceCallProtocol.summary(record.copy(archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = "双方确认明天见面。"))
        val raw = listOf(owned(UIMessage.assistant(summary), "summary"), UIMessage.user("新消息"))
        assertSame(raw, noHistoricalProjection(raw))
        assertEquals(summary, raw.first().toText())
    }

    @Test fun `late transcript cannot claim acoustics or revive an ended interrupted or connecting call`() = runTest {
        val words = "还没发完的那句\nCALL_MODE_V1 这是我自己说的正文"
        val late = owned(UIMessage.user(voiceTurnForModel(callId, words)))
        for (status in listOf(OrbisVoiceCallStatus.ENDED, OrbisVoiceCallStatus.INTERRUPTED,
                OrbisVoiceCallStatus.CONNECTING)) {
            val source = record.copy(status = status)
            val raw = listOf(late)
            assertSame(raw, projectActiveVoiceCallAcoustics(raw, assistantId, { source },
                { _, _ -> error("non-active calls must not reach the runtime") }))
            assertEquals(status, source.status)
            assertTrue(late.toText().endsWith(words))
            assertEquals(1, late.parts.size)
        }
    }

    @Test fun `context without BEGIN preserves late words without inventing a status message`() = runTest {
        val begin = owned(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)), "begin")
        val late = owned(UIMessage.user(voiceTurnForModel(callId, "保留口述")))
        val limited = listOf(begin, UIMessage.assistant("中间回复"), late).limitContext(1)
        assertSame(limited, projectActiveVoiceCallAcoustics(limited, assistantId, { record },
            { _, _ -> error("ended call must not claim a note") }))
        assertEquals(late, limited.single())
    }

    @Test fun `only current active owned utterance gets one acoustic note without original mutation`() = runTest {
        val old = owned(UIMessage.assistant("旧通话回答"))
        val utterance = owned(UIMessage.user(voiceTurnForModel(callId, "正在通话")))
        val raw = listOf(old, utterance)
        val note = "[声学：音量较轻]"
        suspend fun project(input: List<UIMessage>) = projectActiveVoiceCallAcoustics(input, assistantId,
            readRecord = { id -> assertEquals(callId, id); active },
            readAcousticNote = { id, messageId ->
                assertEquals(callId, id)
                assertEquals(utterance.id.toString(), messageId)
                note
            })
        val once = project(raw)
        assertSame(old, once.first())
        assertEquals(utterance.copy(parts = utterance.parts + UIMessagePart.Text(note)), once.last())
        assertEquals(1, utterance.parts.size)
        assertSame(once, project(once))
    }

    @Test fun `missing foreign or mismatched record never claims live acoustics`() = runTest {
        val raw = listOf(owned(UIMessage.user("原消息")))
        for (source in listOf(null, active.copy(assistantId = "another-assistant"), active.copy(id = "another-call"))) {
            assertSame(raw, projectActiveVoiceCallAcoustics(raw, assistantId, { source },
                { _, _ -> error("ownership must be checked before claiming a note") }))
        }
    }

    @Test fun `runtime may decline a stale acoustic note after active record read`() = runTest {
        val raw = listOf(owned(UIMessage.user("原消息")))
        var calls = 0
        assertSame(raw, projectActiveVoiceCallAcoustics(raw, assistantId, { active },
            { _, _ -> calls++; null }))
        assertEquals(1, calls)
        assertEquals(listOf(UIMessagePart.Text("原消息")), raw.single().parts)
    }

    @Test fun `tools reasoning attachments and literal old annotation text are never stripped`() = runTest {
        val tool = UIMessagePart.Tool("synthetic-tool", "example", "{\"value\":1}",
            output = listOf(UIMessagePart.Text("saved tool result")), approvalState = ToolApprovalState.Auto)
        val literal = UIMessagePart.Text("[宿主历史状态：用户保留的原文]", buildJsonObject {
            put("orbis_voice_call_projection", "ended_v1"); put("call_id", callId)
        })
        val parts = listOf(UIMessagePart.Reasoning("saved reasoning"), tool,
            UIMessagePart.Image("file:///synthetic.png"), literal)
        val message = owned(UIMessage.assistant("")).copy(parts = parts)
        val raw = listOf(message, UIMessage.user("new input"))
        assertSame(raw, noHistoricalProjection(raw))
        parts.forEachIndexed { index, part -> assertSame(part, raw.first().parts[index]) }
    }

    @Test fun `active projection read failures and cancellation never mutate input`() = runTest {
        val raw = listOf(owned(UIMessage.user("原文")))
        for (failure in listOf(IOException("synthetic read failure"), CancellationException("synthetic cancellation"))) {
            try {
                projectActiveVoiceCallAcoustics(raw, assistantId, { throw failure }, { _, _ -> "unused" })
                fail("failure must propagate to transformer handling")
            } catch (caught: Exception) { assertSame(failure, caught) }
        }
        assertEquals(listOf(UIMessagePart.Text("原文")), raw.single().parts)
    }
}
