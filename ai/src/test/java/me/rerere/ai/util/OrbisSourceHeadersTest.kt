package me.rerere.ai.util

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import org.junit.Assert.*
import org.junit.Test

class OrbisSourceHeadersTest {
    @Test fun interactiveWindowHasStableLabels() {
        val p = TextGenerationParams(Model(modelId = "test"), orbisConversationId = "same-window")
        assertEquals("orbis:same-window", p.orbisSourceHeaders()["X-ST-Thread-ID"])
        assertEquals("orbis-dev", p.orbisSourceHeaders()["X-ST-Client-ID"])
        assertNotEquals(p.orbisSourceHeaders()["X-ST-Thread-ID"], p.copy(orbisConversationId = "new").orbisSourceHeaders()["X-ST-Thread-ID"])
    }
    @Test fun conflictingStaticHeadersCannotSpoofWindow() {
        val p = TextGenerationParams(Model(), orbisConversationId = "actual", customHeaders = listOf(
            CustomHeader("x-st-thread-id", "wrong"), CustomHeader("X-Session-ID", "also-wrong"), CustomHeader("custom", "kept")))
        assertEquals(listOf("orbis:actual"), p.orbisSourceHeaders().values("X-ST-Thread-ID"))
        assertNull(p.orbisSourceHeaders()["X-Session-ID"])
        assertEquals("kept", p.orbisSourceHeaders()["custom"])
    }
    @Test fun auxiliaryAndMissingOrInvalidSourceHaveNoWindow() {
        val p = TextGenerationParams(Model(), sessionId = "vendor-session", customHeaders = listOf(CustomHeader("X-ST-Client-ID", "bad")))
        assertNull(p.orbisSourceHeaders()["X-ST-Thread-ID"])
        assertNull(p.orbisSourceHeaders()["X-ST-Client-ID"])
        assertNull(p.copy(orbisConversationId = "a\nb").orbisSourceHeaders()["X-ST-Thread-ID"])
        assertNull(p.copy(model = Model(modelId = "m--auxiliary-no-memory"), orbisConversationId = "valid").orbisSourceHeaders()["X-ST-Thread-ID"])
    }
}
