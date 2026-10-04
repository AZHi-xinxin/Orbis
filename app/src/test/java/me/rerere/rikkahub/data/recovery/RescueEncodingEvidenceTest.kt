package me.rerere.rikkahub.data.recovery

import org.junit.Assert.*
import org.junit.Test

class RescueEncodingEvidenceTest {
    private val left = "[{\"id\":\"synthetic-message-123456789\",\"text\":\""
    private val right = ",\"rest\":\"same suffix\"}]"
    @Test fun `raw diagnostic JSON keeps lone UTF16 units distinct and reversible`() {
        val first = exactRescueJsonBytes("{\"raw\":\"\uD800\"}")
        val second = exactRescueJsonBytes("{\"raw\":\"\uD801\"}")
        assertEquals("{\"raw\":\"\\ud800\"}", first.toString(Charsets.UTF_8))
        assertFalse(first.contentEquals(second))
        assertEquals("{\"raw\":\"\\ud83d\\udc22\"}", exactRescueJsonBytes("{\"raw\":\"🐢\"}").toString(Charsets.UTF_8))
    }
    @Test fun `known delimiter consumption qualifies`() {
        assertTrue(isKnownQuoteEncodingDamage(left + "🐢" + right, left + "?\"" + right))
    }
    @Test fun `valid identical content never qualifies`() {
        assertFalse(isKnownQuoteEncodingDamage(left + "hello\"" + right, left + "hello\"" + right))
    }
    @Test fun `truncated or different reply does not qualify`() {
        assertFalse(isKnownQuoteEncodingDamage(left + "a different answer", left + "?\"" + right))
        assertFalse(isKnownQuoteEncodingDamage(left + "old content" + right, left + "?\"" + right))
    }
    @Test fun `second changed field rejects the candidate`() {
        assertFalse(isKnownQuoteEncodingDamage(left + "🐢" + right, left + "?\"" + right.replace("same", "new")))
    }
    @Test fun `ascii typo cannot be silently repaired`() {
        assertFalse(isKnownQuoteEncodingDamage(left + "x" + right, left + "?\"" + right))
    }
}
