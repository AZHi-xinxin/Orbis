package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallChatTest {
    private val callId = "call-1"
    private val record = OrbisVoiceCallRecord(callId, "conversation-1", "assistant-1", 1000,
        connectedAtMs = 1200, endedAtMs = 13200, durationMs = 12000,
        status = OrbisVoiceCallStatus.ENDED, archiveStatus = OrbisVoiceArchiveStatus.READY,
        summary = "约好了周末去公园，出门前再确认天气。", modelTranscript = "用户：周末去公园吧。\n助手：好，出门前再确认天气。")

    private fun owned(message: UIMessage, kind: String = "turn") =
        message.copy(orbisVoiceCallId = callId, orbisVoiceCallKind = kind).toMessageNode()

    private fun summary() = UIMessage.assistant(OrbisVoiceCallProtocol.summary(record))
        .copy(orbisVoiceCallId = callId, orbisVoiceCallKind = "summary")

    @Test fun `begin and archive request explicitly enter and leave text only voice mode`() {
        val begin = OrbisVoiceCallProtocol.begin(callId)
        assertEquals(OrbisVoiceCallMarkerKind.BEGIN, OrbisVoiceCallProtocol.parse(begin)!!.kind)
        assertTrue(begin.contains("【已进入语音通话】"))
        assertTrue(begin.contains("不含音频"))
        val end = voiceArchiveRequest(record)
        assertEquals(OrbisVoiceCallMarkerKind.END, OrbisVoiceCallProtocol.parse(end)!!.kind)
        assertTrue(end.contains("CALL_MODE_V1 {\"active\":false}"))
        assertTrue(end.contains("【已结束语音通话】"))
        assertTrue(end.contains("收音和播放已经停止"))
        assertTrue(end.contains("后续回复不会自动朗读"))
        assertTrue(end.contains("不调用其他模型代写"))
        assertTrue(end.contains("summary") && end.contains("transcript"))
        assertTrue(end.contains("只写本次通话"))
        assertTrue(end.contains("工具回执、系统提示不是任何一方亲口说的话"))
        assertTrue(end.contains(callId))
    }

    @Test fun `each latest turn retains metadata and speaking instructions after finite context drops begin`() {
        val history = mutableListOf(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)))
        repeat(12) { index ->
            val original = "这是第 $index 句。\n我还没说完。"
            history += UIMessage.user(voiceTurnForModel(callId, original))
            val limited = history.limitContext(1)
            assertEquals(1, limited.size)
            val text = limited.single().toText()
            val metadata = Json.parseToJsonElement(text.substringBefore('\n')
                .removePrefix(OrbisVoiceCallProtocol.CALL_MODE_PREFIX)).jsonObject
            assertTrue(metadata.getValue("active").jsonPrimitive.boolean)
            assertEquals(callId, metadata.getValue("callId").jsonPrimitive.content)
            assertTrue(text.contains("并非原始音频"))
            assertTrue(text.contains("你的回复会被朗读"))
            assertTrue(text.contains("避免代码块"))
            assertTrue(text.endsWith(original))
            assertFalse(text.contains("【已进入语音通话】"))
            history += UIMessage.assistant("我明白了。")
        }
    }

    @Test fun `collapse replaces only owned nodes preserving interleaved sentinel text and unrelated alternatives`() {
        val before = UIMessage.user("通话之前的普通聊天").toMessageNode()
        val begin = owned(UIMessage.user(OrbisVoiceCallProtocol.begin(callId)), "begin")
        val user = owned(UIMessage.user(voiceTurnForModel(callId, "周末去公园吧。")))
        val sentinel = UIMessage.user("提醒：明天下雨。")
            .copy(orbisEvent = OrbisEventMetadata("event-record", "sentinel", "event-1", 5000)).toMessageNode()
        val otherBranches = MessageNode(messages = listOf(UIMessage.assistant("第一种答复"), UIMessage.assistant("第二种答复")), selectIndex = 1)
        val answer = owned(UIMessage.assistant("好，出门前再确认天气。"))
        val after = UIMessage.user("通话之后的普通聊天").toMessageNode()
        val nodes = listOf(before, begin, user, sentinel, otherBranches, answer, after)
        val archived = listOf(begin, user, answer)
        val summary = summary()
        val collapsed = collapseVoiceCallNodes(nodes, callId, archived, summary)
        assertEquals(5, collapsed.size)
        assertSame(before, collapsed[0])
        assertSame(sentinel, collapsed[1])
        assertSame(otherBranches, collapsed[2])
        assertSame(after, collapsed[4])
        assertEquals(summary, collapsed[3].currentMessage)
        val marker = OrbisVoiceCallProtocol.parse(collapsed[3].currentMessage.toText())!!
        assertEquals(record.summary, marker.summary)
        assertEquals(12000L, marker.durationMs)
        assertEquals(archived, listOf(begin, user, answer))
        assertFalse(collapsed.any { it.id in archived.map(MessageNode::id) })
    }

    @Test fun `folded human presentation does not remove model readable summary or require UI expansion`() {
        val user = owned(UIMessage.user("周末去公园吧。"))
        val answer = owned(UIMessage.assistant("好，出门前确认天气。"))
        val collapsed = collapseVoiceCallNodes(listOf(user, answer), callId, listOf(user, answer), summary())
        val modelMessages = collapsed.map { it.currentMessage }
        assertEquals(1, modelMessages.size)
        assertEquals(record.summary, OrbisVoiceCallProtocol.parse(modelMessages.single().toText())!!.summary)
        assertTrue(modelMessages.single().toText().contains("通话时长"))
        assertFalse(modelMessages.single().toText().contains(record.modelTranscript!!))
    }

    @Test fun `edited source or switched branch refuses replacement without changing nodes`() {
        val original = owned(UIMessage.user("原来的说法"))
        val edited = original.copy(messages = listOf(original.currentMessage.copy(parts = listOf(UIMessagePart.Text("用户改过的说法")))))
        val nodes = listOf(edited)
        assertThrows(IllegalStateException::class.java) { collapseVoiceCallNodes(nodes, callId, listOf(original), summary()) }
        assertEquals("用户改过的说法", nodes.single().currentMessage.toText())
        val branches = original.copy(messages = original.messages + original.currentMessage.copy(parts = listOf(UIMessagePart.Text("另一个分支"))))
        val switched = branches.copy(selectIndex = 1)
        assertThrows(IllegalStateException::class.java) { collapseVoiceCallNodes(listOf(switched), callId, listOf(branches), summary()) }
        assertEquals(1, switched.selectIndex)
    }

    @Test fun `mixed ownership branch and unrelated branch refuse replacement without clearing chat`() {
        val original = owned(UIMessage.user("通话内容"))
        val mixed = original.copy(messages = original.messages + UIMessage.user("不是通话的备选分支"))
        assertThrows(IllegalStateException::class.java) { collapseVoiceCallNodes(listOf(mixed), callId, listOf(mixed), summary()) }
        assertEquals(2, mixed.messages.size)
        val ordinary = UIMessage.user("当前窗口只有普通聊天").toMessageNode()
        assertThrows(IllegalStateException::class.java) { collapseVoiceCallNodes(listOf(ordinary), callId, listOf(original), summary()) }
        assertEquals("当前窗口只有普通聊天", ordinary.currentMessage.toText())
    }

    @Test fun `previously removed or compacted archived turns are not resurrected and other nodes stay unchanged`() {
        val first = owned(UIMessage.user("已经移出当前页的那句话"))
        val second = owned(UIMessage.assistant("仍保留的回答"))
        val compaction = UIMessage.assistant("先前聊天压缩摘要，含更早的话题。").toMessageNode()
        val ordinary = UIMessage.user("通话外新发的消息").toMessageNode()
        val remaining = listOf(compaction, second, ordinary)
        val result = collapseVoiceCallNodes(remaining, callId, listOf(first, second), summary())
        assertEquals(3, result.size)
        assertSame(compaction, result[0])
        assertSame(ordinary, result[2])
        assertEquals("summary", result[1].currentMessage.orbisVoiceCallKind)
        assertFalse(result.any { it.id == first.id || it.id == second.id })
        assertEquals(listOf(compaction, second, ordinary), remaining)
    }

    @Test fun `existing summary makes repeated collapse idempotent`() {
        val original = owned(UIMessage.user("周末去公园吧。"))
        val once = collapseVoiceCallNodes(listOf(original), callId, listOf(original), summary())
        val twice = collapseVoiceCallNodes(once, callId, listOf(original), summary())
        assertSame(once, twice)
        assertEquals(1, twice.count { it.currentMessage.orbisVoiceCallKind == "summary" })
    }
}
