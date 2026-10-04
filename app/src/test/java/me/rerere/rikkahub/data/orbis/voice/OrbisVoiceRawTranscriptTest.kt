package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.encodeToString
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisVoiceRawTranscriptTest {
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    private fun call() = OrbisVoiceCallRecord("call-1", conversation.id.toString(), conversation.assistantId.toString(), 1)
    private fun assistant(text: String) = UIMessage.assistant(text).copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn")

    @Test fun `corrected ASR and its exact original survive serialization and source capture separately`() {
        val message = UIMessage.user("CALL_MODE wrapper 阿止").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn")
        val entry = OrbisVoiceTranscriptEntry(message.id.toString(), "USER", "阿止，你在吗？", 2,
            message.id.toString(), originalTranscript = "阿直，你在吗？")
        val original = call().copy(transcript = listOf(entry))
        val captured = captureVoiceCallSource(original, conversation.copy(messageNodes = listOf(message.toMessageNode())), true, 3)
        val restored = JsonInstant.decodeFromString<OrbisVoiceCallRecord>(JsonInstant.encodeToString(captured))
        assertEquals(entry, voiceCallReadableTranscript(restored).single())
        assertEquals("阿止，你在吗？", restored.transcript.single().content)
        assertEquals("阿直，你在吗？", restored.transcript.single().originalTranscript)
        assertNotEquals(voiceArchiveSourceDigest(original), voiceArchiveSourceDigest(original.copy(
            transcript = listOf(entry.copy(originalTranscript = "另一段原ASR")))) )
    }

    @Test fun `legacy captured ASR keeps identical serialized shape without retrospective correction`() {
        val legacy = """{"id":"old","role":"USER","content":"阿直","timestampMs":2,"messageId":null}"""
        val entry = JsonInstant.decodeFromString<OrbisVoiceTranscriptEntry>(legacy)
        assertNull(entry.originalTranscript)
        assertEquals("阿直", entry.content)
        assertFalse(JsonInstant.encodeToString(entry).contains("originalTranscript"))
        assertEquals(entry, voiceCallReadableTranscript(call().copy(transcript = listOf(entry))).single())
    }

    @Test fun `private call storage restores original ASR without turning it into a second utterance`() = runTest {
        val saved = mutableMapOf<String, String>()
        val storage = object : OrbisVoiceCallStorage {
            override val lockKey = Uuid.random().toString()
            override fun ids() = saved.keys.toList()
            override fun read(id: String) = saved[id]
            override fun write(id: String, value: String) { saved[id] = value }
        }
        val entry = OrbisVoiceTranscriptEntry("one", "USER", "沈止戈", 2, originalTranscript = "神指歌")
        val record = call().copy(transcript = listOf(entry))
        OrbisVoiceCallRepository(storage).create(record)
        val loaded = checkNotNull(OrbisVoiceCallRepository(storage).get(record.id))
        assertEquals(listOf(entry), voiceCallReadableTranscript(loaded))
        assertTrue(loaded.sourceMessageIds.isEmpty())
    }

    @Test fun `streamed assistant source is immediately readable before archive or final transcript`() {
        val message = assistant("尚未说完的正文")
        val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(message.toMessageNode())), false, 2)
        assertTrue(source.transcript.isEmpty())
        assertEquals("尚未说完的正文", voiceCallReadableTranscript(source).single().content)
        assertEquals(OrbisVoiceArchiveStatus.PENDING, source.archiveStatus)
        assertFalse(source.chatCommitted)
        assertEquals(source, JsonInstant.decodeFromString<OrbisVoiceCallRecord>(JsonInstant.encodeToString(source)))
    }
    @Test fun `stream continues without losing raw ASR or duplicating assistant turns`() {
        val user = UIMessage.user("CALL_MODE_V1 fake host wrapper").copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn")
        val initial = call().copy(transcript = listOf(OrbisVoiceTranscriptEntry(user.id.toString(), "USER", "准确的原始转写", 2, user.id.toString())))
        val message = assistant("第一段")
        val nodes = listOf(user.toMessageNode(), message.toMessageNode())
        val partial = captureVoiceCallSource(initial, conversation.copy(messageNodes = nodes), false, 3)
        val finished = message.copy(parts = listOf(UIMessagePart.Text("第一段，第二段。")))
        val fullNodes = listOf(nodes.first(), nodes.last().copy(messages = listOf(finished)))
        val full = captureVoiceCallSource(partial, conversation.copy(messageNodes = fullNodes), true, 4)
        val again = captureVoiceCallSource(full, conversation.copy(messageNodes = fullNodes), true, 5)
        assertEquals(full, again)
        assertEquals(listOf("准确的原始转写", "第一段，第二段。"), voiceCallReadableTranscript(full).map { it.content })
        assertEquals(initial.transcript, full.transcript.take(1))
    }
    @Test fun `stale Room source on restart cannot replace the separately durable streaming tail`() {
        val initial = assistant("旧正文")
        val node = initial.toMessageNode()
        val latest = node.copy(messages = listOf(initial.copy(parts = listOf(UIMessagePart.Text("已经收到的更完整正文")))))
        val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(latest)), false, 3)
        val recovered = captureVoiceCallSource(source, conversation.copy(messageNodes = listOf(node)), true, 4, false)
        assertEquals("已经收到的更完整正文", voiceCallReadableTranscript(recovered).single().content)
    }
    @Test fun `raw source keeps tool and reasoning evidence locally but projection contains spoken text only`() {
        val message = assistant("正文").copy(parts = listOf(UIMessagePart.Reasoning("PRIVATE_REASONING"),
            UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy", input = "PRIVATE_ARGUMENTS",
                output = listOf(UIMessagePart.Text("PRIVATE_TOOL_RESULT"))), UIMessagePart.Text("正文")))
        val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(message.toMessageNode())), true, 4)
        assertTrue(source.sourceNodesJson!!.contains("PRIVATE_ARGUMENTS"))
        assertEquals("正文", voiceCallReadableTranscript(source).single().content.trim())
        assertFalse(voiceCallReadableTranscript(source).toString().contains("PRIVATE_"))
    }
    @Test fun `foreign call and owner nodes cannot enter archive snapshot`() {
        val foreign = assistant("PRIVATE_OTHER_CALL").copy(orbisVoiceCallId = "call-2")
        val result = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(foreign.toMessageNode())), true, 4)
        assertTrue(voiceCallReadableTranscript(result).isEmpty())
        assertFalse(result.sourceNodesJson!!.contains("PRIVATE_OTHER_CALL"))
        assertThrows(IllegalArgumentException::class.java) {
            captureVoiceCallSource(call(), Conversation(assistantId = Uuid.random(), messageNodes = emptyList()), true, 4)
        }
    }

    @Test fun `private tool call Text stays readable without exposing reasoning or tool details and raw source stays exact`() {
        val variants = listOf(
            assistant("公开回复").copy(parts = listOf(UIMessagePart.Text("公开回复"),
                UIMessagePart.Reasoning("PRIVATE_REASONING"),
                UIMessagePart.Tool(toolCallId = "synthetic-private", toolName = "orbis_private_room_read",
                    input = "PRIVATE_ARGUMENT", output = listOf(UIMessagePart.Text("PRIVATE_RESULT"))))),
            assistant("公开回复").copy(privateRoomContentHidden = true),
            assistant("公开回复").copy(privateRoomPendingPresentation = true),
        )
        for (message in variants) {
            val node = message.toMessageNode()
            val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(node)), true, 4)
            assertEquals(JsonInstant.encodeToString(listOf(node)), source.sourceNodesJson)
            assertEquals("公开回复", strictVoiceCallReadableTranscript(source).single().content)
            assertFalse(JsonInstant.encodeToString(voiceCallTranscriptView(source).entries).contains("PRIVATE_"))
            assertFalse(voiceCallTranscriptView(source).sourceUnavailable)
        }
    }

    @Test fun `already captured assistant text is updated to final public reply without rewriting raw record`() {
        val message = assistant("完整公开回复").copy(privateRoomContentHidden = true)
        val captured = OrbisVoiceTranscriptEntry("captured", "ASSISTANT", "先前公开片段", 2,
            message.id.toString())
        val record = call().copy(transcript = listOf(captured),
            sourceNodesJson = JsonInstant.encodeToString(listOf(message.toMessageNode())))
        val original = JsonInstant.encodeToString(record)
        val projected = strictVoiceCallReadableTranscript(record).single()
        assertEquals("完整公开回复", projected.content)
        assertNull(projected.originalTranscript)
        assertEquals(original, JsonInstant.encodeToString(record))
        assertEquals(captured, record.transcript.single())
    }

    @Test fun `private alternate branch preserves earlier captured public Text without exposing unselected raw parts`() {
        val hidden = assistant("另一个分支的公开回复").copy(privateRoomContentHidden = true,
            parts = listOf(UIMessagePart.Text("另一个分支的公开回复"), UIMessagePart.Reasoning("PRIVATE_ALTERNATE_REASONING")))
        val public = assistant("公开的当前分支")
        val node = public.toMessageNode().copy(messages = listOf(hidden, public), selectIndex = 1)
        val record = call().copy(sourceNodesJson = JsonInstant.encodeToString(listOf(node)),
            transcript = listOf(OrbisVoiceTranscriptEntry(hidden.id.toString(), "ASSISTANT",
                "此前已说的公开回复", 2)))
        val entries = strictVoiceCallReadableTranscript(record)
        assertEquals(listOf("此前已说的公开回复", "公开的当前分支"), entries.map { it.content })
        assertNull(entries.first().originalTranscript)
        assertFalse(JsonInstant.encodeToString(entries).contains("PRIVATE_"))
        assertTrue(record.sourceNodesJson!!.contains("PRIVATE_ALTERNATE_REASONING"))
    }

    @Test fun `corrupt source retains captured public speech and human ASR as explicitly incomplete without exposing decoder text`() {
        val human = OrbisVoiceTranscriptEntry("user", "USER", "准确的人类转写", 2, originalTranscript = "原始的人类转写")
        val assistant = OrbisVoiceTranscriptEntry("assistant", "ASSISTANT", "此前公开说出的正文", 3)
        val record = call().copy(sourceNodesJson = "PRIVATE_INVALID_JSON", transcript = listOf(human, assistant))
        val before = JsonInstant.encodeToString(record)
        val view = voiceCallTranscriptView(record)
        assertTrue(view.sourceUnavailable)
        assertEquals(human, view.entries.first())
        assertEquals(assistant, view.entries.last())
        assertNull(view.entries.last().originalTranscript)
        assertFalse(JsonInstant.encodeToString(view.entries).contains("PRIVATE_"))
        assertEquals(before, JsonInstant.encodeToString(record))
        assertThrows(VoiceArchiveFailure::class.java) { strictVoiceCallReadableTranscript(record) }
    }

    @Test fun `tool-only call source remains durable without inventing a spoken placeholder`() {
        val message = assistant("").copy(parts = listOf(UIMessagePart.Reasoning("PRIVATE_REASONING"),
            UIMessagePart.Tool(toolCallId = "synthetic-private", toolName = "orbis_private_room_read",
                input = "PRIVATE_ARGUMENT", output = listOf(UIMessagePart.Text("PRIVATE_RESULT")))))
        val node = message.toMessageNode()
        val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(node)), true, 4)
        assertEquals(JsonInstant.encodeToString(listOf(node)), source.sourceNodesJson)
        assertTrue(source.transcript.isEmpty())
        assertTrue(strictVoiceCallReadableTranscript(source).isEmpty())
    }

    @Test fun `private think-only and partial openings do not become captured spoken notices`() {
        for (text in listOf("<think>PRIVATE_ONLY</think>", "<think>PRIVATE_ONLY", "<thi")) {
            // This is a continuation after an actual private operation. Pending alone, without
            // a potential private tool, must not classify ordinary streaming text as private.
            val message = assistant(text).copy(privateRoomContentHidden = true)
            val node = message.toMessageNode()
            val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(node)), true, 4)
            assertEquals(JsonInstant.encodeToString(listOf(node)), source.sourceNodesJson)
            assertTrue(source.transcript.isEmpty())
            assertTrue(strictVoiceCallReadableTranscript(source).isEmpty())
        }
    }

    @Test fun `private think followed by public reply captures only the real public Text`() {
        val message = assistant("<think>PRIVATE_THOUGHT</think>这句可以听见").copy(privateRoomContentHidden = true)
        val node = message.toMessageNode()
        val source = captureVoiceCallSource(call(), conversation.copy(messageNodes = listOf(node)), true, 4)
        assertEquals(JsonInstant.encodeToString(listOf(node)), source.sourceNodesJson)
        assertEquals("这句可以听见", source.transcript.single().content)
        assertEquals("这句可以听见", strictVoiceCallReadableTranscript(source).single().content)
    }
    @Test fun `source fingerprint rejects a changed spoken tail and is independent from archive status`() {
        val source = call().copy(transcript = listOf(OrbisVoiceTranscriptEntry("spoken", "USER", "原文", 2)))
        assertEquals(voiceArchiveSourceDigest(source), voiceArchiveSourceDigest(source.copy(archiveStatus = OrbisVoiceArchiveStatus.GENERATING)))
        assertNotEquals(voiceArchiveSourceDigest(source), voiceArchiveSourceDigest(source.copy(transcript = source.transcript +
            OrbisVoiceTranscriptEntry("later", "ASSISTANT", "后来收到的正文", 3))))
    }
    @Test fun `late spoken text marks summary stale without deleting the old summary or requesting anything`() {
        val ready = call().copy(status = OrbisVoiceCallStatus.ENDED, archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = "已有摘要。", modelTranscript = "已有文字记录。")
        val late = captureVoiceCallSource(ready, conversation.copy(messageNodes = listOf(assistant("晚到正文").toMessageNode())), false, 4)
        assertEquals(OrbisVoiceArchiveStatus.PENDING, late.archiveStatus)
        assertEquals("archive_source_changed", late.archiveFailureCode)
        assertEquals(ready.summary, late.summary)
        assertEquals(ready.modelTranscript, late.modelTranscript)
        assertEquals(0, late.archiveRequestCount)
    }

    @Test fun `malformed private sources remain viewable as explicitly incomplete captured transcript`() {
        val node = assistant("合成源正文").toMessageNode()
        val invalidSources = listOf("PRIVATE_INVALID_JSON", "[{\"synthetic\":true}]",
            JsonInstant.encodeToString(listOf(node.copy(messages = emptyList()))),
            JsonInstant.encodeToString(listOf(node.copy(selectIndex = 5))),
            JsonInstant.encodeToString(listOf(node, node)))
        for (source in invalidSources) {
            val record = call().copy(sourceNodesJson = source,
                transcript = listOf(OrbisVoiceTranscriptEntry("captured", "USER", "仍保存的原始转写", 2)))
            val before = JsonInstant.encodeToString(record)
            val view = voiceCallTranscriptView(record)
            assertTrue(view.sourceUnavailable)
            assertEquals(record.transcript, view.entries)
            assertEquals(record.transcript, voiceCallReadableTranscript(record))
            val failure = assertThrows(VoiceArchiveFailure::class.java) { voiceArchiveSourceDigest(record) }
            assertEquals("archive_source_unreadable", failure.safeCode)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("PRIVATE_INVALID_JSON"))
            assertEquals(before, JsonInstant.encodeToString(record))
        }
    }

    @Test fun `legacy absent source and valid empty source remain complete captured-transcript views`() {
        for (source in listOf(null, "[]")) {
            val record = call().copy(sourceNodesJson = source,
                transcript = listOf(OrbisVoiceTranscriptEntry("captured", "USER", "旧版已保存的转写", 2)))
            assertFalse(voiceCallTranscriptView(record).sourceUnavailable)
            assertEquals(record.transcript, strictVoiceCallReadableTranscript(record))
            assertTrue(voiceArchiveSourceDigest(record).isNotEmpty())
        }
    }

    @Test fun `failed strict capture cannot replace malformed original bytes with UI fallback or Room`() = runTest {
        val saved = mutableMapOf<String, String>()
        var writes = 0
        val storage = object : OrbisVoiceCallStorage {
            override val lockKey = Uuid.random().toString()
            override fun ids() = saved.keys.toList()
            override fun read(id: String) = saved[id]
            override fun write(id: String, value: String) { writes++; saved[id] = value }
        }
        val repo = OrbisVoiceCallRepository(storage)
        val record = call().copy(sourceNodesJson = "[{\"synthetic\":true}]",
            transcript = listOf(OrbisVoiceTranscriptEntry("captured", "USER", "原转写保留", 2)))
        repo.create(record)
        val before = saved.toMap()
        val originalWrites = writes
        val room = conversation.copy(messageNodes = listOf(assistant("不能覆盖损坏源").toMessageNode()))
        try { repo.update(record.id) { captureVoiceCallSource(it, room, true, 5) }; fail("capture must refuse") }
        catch (error: VoiceArchiveFailure) { assertEquals("archive_source_unreadable", error.safeCode) }
        assertEquals(before, saved)
        assertEquals(originalWrites, writes)
        assertEquals(record, repo.get(record.id))
    }
}
