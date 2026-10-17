package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class AiCompactionBranchTest {
    @Test fun `old settings default on and explicit human opt out round trips independently`() {
        assertTrue(Assistant().preserveCompactionBranch)
        assertTrue(JsonInstant.decodeFromString<Assistant>("{}").preserveCompactionBranch)
        assertFalse(JsonInstant.decodeFromString<Assistant>("{\"preserveCompactionBranch\":false}").preserveCompactionBranch)
        val settings = listOf(Assistant(preserveCompactionBranch = true),
            Assistant(retainCompactionOriginals = true, preserveCompactionBranch = false))
        val decoded = JsonInstant.decodeFromString<List<Assistant>>(JsonInstant.encodeToString(settings))
        assertEquals(settings, decoded)
        assertTrue(decoded[0].preserveCompactionBranch)
        assertFalse(decoded[0].retainCompactionOriginals)
        assertFalse(decoded[1].preserveCompactionBranch)
        assertTrue(decoded[1].retainCompactionOriginals)
    }

    @Test fun `branch reuses manual full copy but leaves active messages and model content untouched`() {
        val message = UIMessage.assistant("original").copy(usage = TokenUsage(promptTokens = 42),
            orbisEvent = OrbisEventMetadata("record", "test", "event", 123L),
            orbisVoiceCallId = "call", orbisVoiceCallKind = "turn",
            parts = listOf(UIMessagePart.Text("original"), UIMessagePart.Image("file:///synthetic/image.png"),
                UIMessagePart.Tool("completed", "noop", "{}", listOf(UIMessagePart.Text("ok")))))
        val source = Conversation(assistantId = Uuid.random(), title = "test chat",
            messageNodes = listOf(MessageNode(messages = listOf(message, message.copy(id = Uuid.random())), selectIndex = 1)),
            folderId = Uuid.random(), customSystemPrompt = "original prompt", workspaceCwd = "/synthetic",
            chatSuggestions = listOf("old suggestion"), compactionEpoch = 5, isPinned = true)
        val fingerprint = manualContextFingerprint(source)
        val branch = createAiCompactionBranch(source, Instant.ofEpochMilli(1234))
        assertEquals(fingerprint, manualContextFingerprint(source))
        assertNotEquals(source.id, branch.id)
        assertEquals("test chat · AI 压缩前原文", branch.title)
        assertEquals(source.assistantId, branch.assistantId)
        assertEquals(source.folderId, branch.folderId)
        assertEquals(source.customSystemPrompt, branch.customSystemPrompt)
        assertEquals(source.workspaceCwd, branch.workspaceCwd)
        assertEquals(0L, branch.compactionEpoch)
        assertEquals(Instant.ofEpochMilli(1234), branch.createAt)
        assertFalse(branch.isPinned)
        assertTrue(branch.chatSuggestions.isEmpty())
        source.messageNodes.zip(branch.messageNodes).forEach { (old, fresh) ->
            assertNotEquals(old.id, fresh.id)
            assertEquals(old.selectIndex, fresh.selectIndex)
            old.messages.zip(fresh.messages).forEach { (a, b) ->
                assertNotEquals(a.id, b.id)
                assertEquals(a.copy(id = b.id, orbisEvent = null, orbisVoiceCallId = null, orbisVoiceCallKind = null), b)
                assertTrue(b.getTools().all { it.isExecuted })
            }
        }
    }

    @Test fun `consultation routing cannot leak into an ordinary archived branch`() {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("test").toMessageNode()),
            consultation = ConsultationConversationBinding("session", "test"))
        assertTrue(runCatching { createAiCompactionBranch(source, Instant.now()) }.isFailure)
    }

    @Test fun `branch title handles unnamed conversations`() {
        val source = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("test").toMessageNode()))
        assertEquals("未命名窗口 · AI 压缩前原文", createAiCompactionBranch(source, Instant.now()).title)
    }
}
