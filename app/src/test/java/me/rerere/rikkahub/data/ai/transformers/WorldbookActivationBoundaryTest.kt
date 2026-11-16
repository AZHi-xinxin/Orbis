package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test

class WorldbookActivationBoundaryTest {
    private fun run(entry: PromptInjection.RegexInjection, messages: List<UIMessage>): List<UIMessage> {
        val book=Lorebook(entries=listOf(entry))
        return transformMessages(messages,Assistant(lorebookIds=setOf(book.id)),emptyList(),listOf(book))
    }
    private val entry=PromptInjection.RegexInjection(content="RULE",keywords=listOf("trigger"))

    @Test fun `constant ignores retained keywords and does not evaluate even invalid old scan depth`() {
        val messages=listOf(UIMessage.system("base"),UIMessage.user("no keyword"))
        assertTrue(run(entry.copy(constantActive=true,scanDepth=Int.MIN_VALUE),messages).first().toText().contains("RULE"))
        assertFalse(run(entry.copy(constantActive=true,enabled=false),messages).any { it.toText().contains("RULE") })
    }
    @Test fun `human only excludes assistant and synthetic user but legacy still sees assistant`() {
        val messages=listOf(UIMessage.system("base"),UIMessage.assistant("trigger"),
            UIMessage.user("trigger").copy(isSynthetic=true),UIMessage.user("hello"))
        assertTrue(run(entry,messages).first().toText().contains("RULE"))
        assertFalse(run(entry.copy(scanUserOnly=true),messages).any { it.toText().contains("RULE") })
        assertTrue(run(entry.copy(scanUserOnly=true),messages+UIMessage.user("trigger")).first().toText().contains("RULE"))
    }
    @Test fun `scan depth counts selected human messages and zero depth matches nothing`() {
        val messages=listOf(UIMessage.user("trigger"),UIMessage.assistant("answer"),UIMessage.user("hello"))
        assertTrue(run(entry.copy(scanUserOnly=true,scanDepth=2),messages).any { it.toText().contains("RULE") })
        assertFalse(run(entry.copy(scanUserOnly=true,scanDepth=1),messages).any { it.toText().contains("RULE") })
        assertFalse(run(entry.copy(scanUserOnly=true,scanDepth=0),messages).any { it.toText().contains("RULE") })
    }
    @Test fun `explicit near human system insertion preserves base history and tool adjacency`() {
        val base=listOf(UIMessage.system("base"),UIMessage.user("old"),UIMessage.assistant("answer"))
        val human=UIMessage.user("trigger")
        val tool=UIMessage(role=MessageRole.ASSISTANT,parts=listOf(UIMessagePart.Tool("call","read","{}")))
        val result=run(entry.copy(position=InjectionPosition.BEFORE_LATEST_USER_SYSTEM),base+human+tool)
        assertEquals(base,result.take(base.size))
        assertEquals(MessageRole.SYSTEM,result[base.size].role)
        assertEquals("RULE",result[base.size].toText())
        assertEquals(listOf(human,tool),result.takeLast(2))
        val noHuman=run(entry.copy(constantActive=true,position=InjectionPosition.BEFORE_LATEST_USER_SYSTEM),base.take(1))
        assertTrue(noHuman.any { it.toText()=="RULE" && it.role==MessageRole.SYSTEM })
        assertFalse(noHuman.any { it.role==MessageRole.USER })
    }
    @Test fun `legacy serialization omits added false default and explicit settings round trip`() {
        val legacy=Json.encodeToString(entry)
        assertFalse(legacy.contains("scanUserOnly"))
        assertFalse(Json.decodeFromString<PromptInjection.RegexInjection>(legacy).scanUserOnly)
        val opted=entry.copy(scanUserOnly=true,position=InjectionPosition.BEFORE_LATEST_USER_SYSTEM)
        assertEquals(opted,Json.decodeFromString<PromptInjection.RegexInjection>(Json.encodeToString(opted)))
    }
}
