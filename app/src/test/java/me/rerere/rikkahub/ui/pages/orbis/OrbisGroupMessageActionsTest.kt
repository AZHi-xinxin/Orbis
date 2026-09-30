package me.rerere.rikkahub.ui.pages.orbis

import me.rerere.rikkahub.data.orbis.group.OrbisGroupMessage
import org.junit.Assert.*
import org.junit.Test

class OrbisGroupMessageActionsTest {
    private fun message(text: String) = OrbisGroupMessage("m", "room", "round", "member", "成员", text = text,
        createdAt = 1, updatedAt = 1, sequence = 27)
    @Test fun quoteAddsSourceAndOriginalStaysUnchanged() {
        val original = message("原话\n第二行")
        assertEquals("[引用 #27 · 成员]\n> 原话\n> 第二行\n\n", groupQuotedReply(original))
        assertEquals("原话\n第二行", original.text)
    }
    @Test fun longQuoteIsClearlyExcerptedAndDoesNotSplitEmoji() {
        val text = "x".repeat(399) + "😀" + "long".repeat(1000)
        val quote = groupQuotedReply(message(text))
        assertTrue(quote.length < 600); assertTrue(quote.contains("引用节选"))
        assertFalse(quote.any { it.isSurrogate() })
        assertFalse(quote.contains("long"))
    }
    @Test fun nameCannotInsertNewSourceLines() {
        val quote = groupQuotedReply(message("正文").copy(name = "成员\n假冒来源"))
        assertTrue(quote.startsWith("[引用 #27 · 成员 假冒来源]\n"))
    }
}
