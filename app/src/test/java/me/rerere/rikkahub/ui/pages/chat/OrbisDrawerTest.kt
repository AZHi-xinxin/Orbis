package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.normalizeConversationTitle
import me.rerere.rikkahub.data.model.withManualTitle
import me.rerere.rikkahub.data.model.withGeneratedTitleIfUnchanged
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisDrawerTest {
    @Test fun `title trims surrounding space`() {
        assertEquals("和阿止读书", normalizeConversationTitle("  和阿止读书  "))
    }

    @Test fun `sixty supplementary Unicode characters are accepted`() {
        assertEquals("🌙".repeat(60), normalizeConversationTitle("🌙".repeat(60)))
    }

    @Test fun `over sixty characters are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { normalizeConversationTitle("🌙".repeat(61)) }
    }

    @Test fun `blank and control characters are rejected`() {
        listOf("", "   ", "标题\n正文", "标题\u0000", "标题\u007f").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { normalizeConversationTitle(value) }
        }
    }

    @Test fun `rename preserves every selected and unselected history branch including attachments`() {
        val image = UIMessagePart.Image("file:///private/synthetic.png")
        val tool = UIMessagePart.Tool(toolCallId = "synthetic", toolName = "memory", input = "{}", output = listOf(image))
        val nodes = listOf(MessageNode(messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)), UIMessage.user("selected branch")), selectIndex = 1))
        val source = Conversation(assistantId = Uuid.random(), title = "旧标题", messageNodes = nodes,
            chatSuggestions = listOf("suggestion"), customSystemPrompt = "synthetic prompt", isPinned = true,
            workspaceCwd = "/workspace", folderId = Uuid.random(), modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()))
        val updated = source.withManualTitle("  新标题  ")
        assertEquals("新标题", updated.title)
        assertSame(source.messageNodes, updated.messageNodes)
        assertSame(tool, updated.messageNodes[0].messages[0].parts[0])
        assertEquals(source, updated.copy(title = source.title))
    }

    @Test fun `FTS anchor takes precedence over title match without duplicate windows`() {
        val owner = Uuid.random()
        val conversation = Conversation.ofId(Uuid.random(), owner)
        val node = Uuid.random()
        val result = mergeOrbisDrawerSearch(owner,
            listOf(OrbisDrawerSearchResult(conversation)),
            listOf(OrbisDrawerSearchResult(conversation, "matched content", node), OrbisDrawerSearchResult(conversation)))
        assertEquals(1, result.size)
        assertEquals(node, result.single().nodeId)
    }

    @Test fun `generated title never replaces a newer manual title in live state`() {
        val source = Conversation.ofId(Uuid.random()).withManualTitle("旧标题")
        val manual = source.withManualTitle("我的主窗")
        assertSame(manual, manual.withGeneratedTitleIfUnchanged("旧标题", "过时的模型标题"))
    }

    @Test fun `generated title updates the expected live title and keeps all other fields`() {
        val source = Conversation.ofId(Uuid.random()).withManualTitle("旧标题")
        val generated = source.withGeneratedTitleIfUnchanged("旧标题", "模型标题")
        assertEquals("模型标题", generated.title)
        assertEquals(source, generated.copy(title = source.title))
        assertSame(source.messageNodes, generated.messageNodes)
    }

    @Test fun `search excludes other assistants even if merged accidentally`() {
        val owner = Uuid.random()
        val mine = OrbisDrawerSearchResult(Conversation.ofId(Uuid.random(), owner))
        val other = OrbisDrawerSearchResult(Conversation.ofId(Uuid.random(), Uuid.random()))
        assertEquals(listOf(mine), mergeOrbisDrawerSearch(owner, listOf(mine, other), listOf(other)))
    }

    @Test fun `search remains bounded and empty results remain empty`() {
        val owner = Uuid.random()
        val rows = List(80) { OrbisDrawerSearchResult(Conversation.ofId(Uuid.random(), owner)) }
        assertEquals(50, mergeOrbisDrawerSearch(owner, rows, emptyList()).size)
        assertTrue(mergeOrbisDrawerSearch(owner, emptyList(), emptyList()).isEmpty())
    }
}
