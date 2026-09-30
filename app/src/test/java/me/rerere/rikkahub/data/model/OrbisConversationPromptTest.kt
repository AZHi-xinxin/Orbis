package me.rerere.rikkahub.data.model

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.GenerationInputSnapshot
import me.rerere.rikkahub.data.repository.decodeConversationEntity
import me.rerere.rikkahub.data.repository.encodeConversationEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class OrbisConversationPromptTest {
    @Test fun `old prompt json retains prompt and defaults worldbook off`() {
        val restored = Json.decodeFromString<OrbisConversationPrompt>("""{"text":"legacy","enabled":true}""")
        assertEquals("legacy", restored.effectiveText())
        assertEquals("", restored.worldBookText)
        assertFalse(restored.worldBookEnabled)
        assertNull(restored.effectiveWorldBook())
    }

    @Test fun `worldbook is independently enabled and precedes supplemental prompt`() {
        val prompt = OrbisConversationPrompt("style", true, "world facts", true)
        assertEquals("base\n\n当前会话的世界书（背景资料与设定）：\nworld facts\n\n当前会话的补充提示：\nstyle",
            appendOrbisConversationPrompt("base", prompt))
        assertEquals("当前会话的世界书（背景资料与设定）：\nworld facts",
            appendOrbisConversationPrompt("", prompt.copy(enabled = false)))
        assertEquals("base", appendOrbisConversationPrompt("base", prompt.copy(enabled = false, worldBookEnabled = false)))
        assertTrue(prompt.copy(enabled = false).isActive())
    }

    @Test fun `worldbook survives database mapping without changing messages or other prompt fields`() {
        val prompt = OrbisConversationPrompt("style draft", false, "  world\n facts  ", false)
        val source = Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(UIMessage.user("history"))), orbisPrompt = prompt,
            createAt = Instant.ofEpochMilli(1000), updateAt = Instant.ofEpochMilli(2000))
        val restored = decodeConversationEntity(encodeConversationEntity(source), source.messageNodes)
        assertEquals(source, restored)
        assertEquals(prompt.worldBookText, restored.orbisPrompt.copy(worldBookEnabled = true).effectiveWorldBook())
        assertNull(restored.orbisPrompt.effectiveWorldBook())
    }

    @Test fun `blank worldbook never creates an empty context block`() {
        val prompt = OrbisConversationPrompt(worldBookText = " \n", worldBookEnabled = true)
        assertFalse(prompt.isActive())
        assertEquals("base", appendOrbisConversationPrompt("base", prompt))
    }

    @Test fun `legacy conversation json has empty disabled prompt`() {
        val id = Uuid.random()
        val legacy = """{"assistantId":"$id","messageNodes":[],"customSystemPrompt":"original"}"""
        val restored = JsonInstant.decodeFromString<Conversation>(legacy)
        assertEquals(OrbisConversationPrompt(), restored.orbisPrompt)
        assertEquals("original", restored.customSystemPrompt)
    }

    @Test fun `migration default json disables the feature`() {
        assertEquals(OrbisConversationPrompt(), Json.decodeFromString<OrbisConversationPrompt>("{}"))
    }

    @Test fun `blank enabled text has no effective instruction`() {
        assertNull(OrbisConversationPrompt(" \n\t", true).effectiveText())
        assertNull(OrbisConversationPrompt().effectiveText())
    }

    @Test fun `toggle keeps author text and preserves whitespace when enabled`() {
        val enabled = OrbisConversationPrompt("  第一行\n第二行  ", true)
        val disabled = enabled.copy(enabled = false)
        assertNull(disabled.effectiveText())
        assertEquals(enabled.text, disabled.text)
        assertEquals(enabled.text, disabled.copy(enabled = true).effectiveText())
    }

    @Test fun `conversation serialization retains disabled drafts independently`() {
        val a = Conversation(assistantId = Uuid.random(), messageNodes = emptyList(),
            orbisPrompt = OrbisConversationPrompt("conversation A", false))
        val b = Conversation(assistantId = a.assistantId, messageNodes = emptyList())
        val restored = JsonInstant.decodeFromString<List<Conversation>>(JsonInstant.encodeToString(listOf(a, b)))
        assertEquals(a.orbisPrompt, restored[0].orbisPrompt)
        assertFalse(restored[0].orbisPrompt.enabled)
        assertEquals(OrbisConversationPrompt(), restored[1].orbisPrompt)
    }

    @Test fun `database mapping keeps prompt and all existing metadata`() {
        val source = Conversation(
            assistantId = Uuid.random(), title = "synthetic conversation",
            messageNodes = listOf(MessageNode.of(UIMessage.user("synthetic message"))),
            chatSuggestions = listOf("synthetic suggestion"), isPinned = true,
            createAt = Instant.ofEpochMilli(1000), updateAt = Instant.ofEpochMilli(2000),
            customSystemPrompt = "existing conversation system prompt",
            orbisPrompt = OrbisConversationPrompt("raw\n supplemental text", true),
            modeInjectionIds = setOf(Uuid.random()), lorebookIds = setOf(Uuid.random()),
            workspaceCwd = "/workspace/synthetic", folderId = Uuid.random(),
        )
        val entity = encodeConversationEntity(source)
        val restored = decodeConversationEntity(entity, source.messageNodes)
        assertEquals(source, restored)
        assertEquals("[]", entity.nodes)
        assertEquals(source.orbisPrompt, JsonInstant.decodeFromString<OrbisConversationPrompt>(entity.orbisPrompt))
    }

    @Test fun `legacy database row preserves history and defaults new prompt`() {
        val source = Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode.of(UIMessage.user("existing history"))))
        val entity = encodeConversationEntity(source).copy(orbisPrompt = "{}")
        val restored = decodeConversationEntity(entity, source.messageNodes)
        assertEquals(OrbisConversationPrompt(), restored.orbisPrompt)
        assertEquals(source.messageNodes, restored.messageNodes)
    }

    @Test fun `supplement is appended after original identity memory and tools`() {
        val base = "original assistant\nmemory context\ntool contract"
        val prompt = OrbisConversationPrompt("supplemental instruction", true)
        val result = appendOrbisConversationPrompt(base, prompt)
        assertTrue(result.startsWith(base + "\n\n"))
        assertTrue(result.endsWith(prompt.text))
        assertEquals(base, appendOrbisConversationPrompt(base, prompt.copy(enabled = false)))
    }

    @Test fun `empty base receives supplement without an empty system prefix`() {
        assertEquals("当前会话的补充提示：\nsupplement",
            appendOrbisConversationPrompt("", OrbisConversationPrompt("supplement", true)))
    }

    @Test fun `same generation snapshot injects supplement once and new wake reads current setting`() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        var prompt = OrbisConversationPrompt("first instruction", true)
        var builds = 0
        suspend fun input() = snapshot.input {
            builds++
            listOf(UIMessage.system(appendOrbisConversationPrompt("base", prompt)))
        }
        val first = input()
        prompt = OrbisConversationPrompt("second instruction", true)
        assertEquals(first, input())
        assertEquals(1, builds)
        assertEquals(1, first.single().toText().split("当前会话的补充提示：").size - 1)
        val next = GenerationInputSnapshot().input {
            listOf(UIMessage.system(appendOrbisConversationPrompt("base", prompt)))
        }
        assertTrue(next.single().toText().endsWith("second instruction"))
    }
}
