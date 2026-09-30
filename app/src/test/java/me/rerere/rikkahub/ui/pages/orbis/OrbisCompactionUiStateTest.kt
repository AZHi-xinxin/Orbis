package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisCompactionUiStateTest {
    @Test fun `zero only disables threshold-based rollback restriction`() {
        assertNull(orbisRollbackLimitMessage(2_000_000, 0))
        assertNull(orbisRollbackLimitMessage(null, 350000))
    }

    @Test fun `rollback admits exact threshold plus 50k and explains first token over`() {
        assertNull(orbisRollbackLimitMessage(400000, 350000))
        val message = orbisRollbackLimitMessage(400001, 350000)!!
        assertTrue(message.contains("400K"))
        assertTrue(message.contains("50K"))
        assertTrue(message.contains("调高阈值"))
    }

    @Test fun `changing threshold leaves a way to perform intentional rollback`() {
        assertNotNull(orbisRollbackLimitMessage(550000, 350000))
        assertNull(orbisRollbackLimitMessage(550000, 500000))
        assertNull(orbisRollbackLimitMessage(550000, 0))
    }

    @Test fun `threshold parser supports exact entry and rejects malformed input`() {
        assertEquals(0, parseOrbisCompactionThreshold("0"))
        assertEquals(315001, parseOrbisCompactionThreshold("315001"))
        assertEquals(1000000, parseOrbisCompactionThreshold(" 1000000 "))
        listOf("", "-1", "350K", "1.5", "1000001", "999999999999999999999999").forEach {
            assertNull(parseOrbisCompactionThreshold(it))
        }
    }

    @Test fun `default state contains no archive text and offers no rollback`() {
        val state = OrbisCompactionUiState()
        assertTrue(state.history.isEmpty())
        assertNull(state.latestRollback)
        assertNull(state.projectedRollbackTokens)
        assertFalse(state.loading)
        assertFalse(state.busy)
        assertNull(state.error)
    }
    @Test fun `protocol usage basis is shown as an honest human readable estimate`() {
        assertTrue(orbisCompactionBasisLabel("previous_provider_prompt_usage_plus_reply_and_new_messages_estimate; not exact next request").contains("不是下一次请求的精确"))
        assertTrue(orbisCompactionBasisLabel("estimated_text_and_tools_not_exact_provider_tokens").contains("本地估算"))
        assertEquals("合成估算", orbisCompactionBasisLabel("合成估算"))
        assertEquals("用量来源未记录", orbisCompactionBasisLabel(""))
    }
}
