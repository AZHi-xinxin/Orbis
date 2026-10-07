package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SoupDraftPreparationTest {
    @Test fun successfulPreflightReturnsOnlyPreparedValue() {
        val ticket = Any()
        var checks = 0
        val result = prepareSoupDraft { checks++; ticket }
        assertTrue(result is SoupDraftPreparation.Ready)
        assertSame(ticket, (result as SoupDraftPreparation.Ready<*>).value)
        assertEquals(1, checks)
    }

    @Test fun invalidQuestionShowsActionableFeedbackWithoutSuggestingHostChange() {
        val result = prepareSoupDraft { error("soup_yes_no_required") } as SoupDraftPreparation.Blocked
        assertTrue(result.message.contains("没有调用模型"))
        assertTrue(result.message.contains("没有扣次数"))
        assertFalse(result.canConfigureHost)
    }

    @Test fun missingOrUnconfirmedHostOffersSettings() {
        listOf("soup_select_host", "soup_dm_confirmation_required", "soup_dm_invalid", "soup_dm_unavailable",
            "soup_model_missing", "soup_model_disabled", "soup_provider_key_missing", "soup_direct_provider_required").forEach { code ->
            val result = prepareSoupDraft { error(code) } as SoupDraftPreparation.Blocked
            assertTrue(code, result.canConfigureHost)
            assertTrue(code, result.message.isNotBlank())
        }
    }

    @Test fun changedSessionIsNotMistakenForMissingConfiguration() {
        val result = prepareSoupDraft { error("soup_stale_session") } as SoupDraftPreparation.Blocked
        assertTrue(result.message.contains("重新核对"))
        assertFalse(result.canConfigureHost)
    }

    @Test fun unexpectedErrorNeverLeaksRawDetails() {
        val result = prepareSoupDraft { error("test-private-url-and-secret") } as SoupDraftPreparation.Blocked
        assertFalse(result.message.contains("test-private"))
        assertFalse(result.canConfigureHost)
    }

    @Test fun cancellationIsNotSwallowedAsARejection() {
        val cancellation = CancellationException("fixture cancelled")
        try {
            prepareSoupDraft { throw cancellation }
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
    }
}
