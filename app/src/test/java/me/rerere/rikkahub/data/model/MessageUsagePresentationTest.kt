package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MessageUsagePresentationTest {
    private val usage = TokenUsage(promptTokens = 661, cachedTokens = 512, completionTokens = 78, totalTokens = 739)

    @Test fun `ordinary record is a short Chinese summary with exact recorded counts`() {
        val result = presentMessageUsage(usage, 3700)!!
        assertEquals("用量记录 · 用时3.7 秒", result.summary)
        assertEquals("661 token", result.details[0].value)
        assertEquals("78 token", result.details[1].value)
        assertEquals("512 token", result.details[2].value)
        assertFalse(result.summary.contains("tokens"))
        assertFalse(result.summary.contains("cached"))
        assertFalse(result.summary.contains("tok/s"))
    }

    @Test fun `no usage yields no invented zero counts`() {
        assertNull(presentMessageUsage(null, 3700))
    }

    @Test fun `missing zero or backwards duration never gives speed or invalid time`() {
        for (duration in listOf(null, 0L, -10L)) {
            val result = presentMessageUsage(usage, duration)!!
            assertEquals("用量记录", result.summary)
            assertEquals("尚无有效的完成用时记录", result.details.last().value)
            assertFalse(result.toString().contains("Infinity"))
            assertFalse(result.toString().contains("NaN"))
        }
    }

    @Test fun `very short duration is not misreported as zero`() {
        assertEquals("用量记录 · 用时不到 0.1 秒", presentMessageUsage(usage, 1)!!.summary)
        assertEquals("用量记录 · 用时0.1 秒", presentMessageUsage(usage, 100)!!.summary)
    }

    @Test fun `details use token not character counts and omit long explanations`() {
        val result = presentMessageUsage(usage, 3700)!!
        assertTrue(result.details.take(3).all { it.value.endsWith(" token") })
        assertTrue(result.details.take(3).all { it.label.contains("记录值") })
        assertFalse(result.details.any { it.value.contains("字") || it.value.contains("计量单位") })
        assertTrue(result.explanations.isEmpty())
    }

    @Test fun `zero cached count does not claim known zero cache hit`() {
        val result = presentMessageUsage(usage.copy(cachedTokens = 0), 3700)!!
        assertTrue(result.details[2].value.contains("尚不能确认"))
        assertTrue(result.details[2].value.contains("也可能未提供"))
        assertFalse(result.toString().contains("0%"))
    }

    @Test fun `cache larger than input is not divided into an impossible hit rate`() {
        val result = presentMessageUsage(usage.copy(cachedTokens = 999), 3700)!!
        assertEquals("999 token", result.details[2].value)
        assertEquals("661 token", result.details[0].value)
        assertTrue(result.explanations.isEmpty())
        assertFalse(result.toString().contains("%"))
    }

    @Test fun `negative invalid counts are not silently clamped to real usage`() {
        val result = presentMessageUsage(TokenUsage(-1, -2, -3), null)!!
        assertTrue(result.details.take(3).all { it.value == "记录异常，暂不能确认" })
    }

    @Test fun `large counts use full readable numbers without misleading decimal abbreviation`() {
        val result = presentMessageUsage(usage.copy(promptTokens = 153456, completionTokens = Int.MAX_VALUE), 3700)!!
        assertEquals("153,456 token", result.details[0].value)
        assertEquals("2,147,483,647 token", result.details[1].value)
    }

    @Test fun `formatting is stable across device locales and does not mutate usage`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val result = presentMessageUsage(usage, 3700)!!
            assertEquals("用量记录 · 用时3.7 秒", result.summary)
            assertEquals(TokenUsage(661, 78, 512, 739), usage)
        } finally { Locale.setDefault(original) }
    }

    @Test fun `presentation never invents price total round count or output speed`() {
        val result = presentMessageUsage(usage.copy(totalTokens = 999999), 3700)!!
        assertFalse(result.toString().contains("999999"))
        assertFalse(result.toString().contains("￥"))
        assertFalse(result.toString().contains("¥"))
        assertFalse(result.details.any { it.label.contains("速度") || it.label.contains("命中率") || it.label.contains("费用") })
    }
}
