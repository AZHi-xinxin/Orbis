package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompactionThresholdCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `old assistant settings do not silently enable reminders`() {
        val assistant = json.decodeFromString<Assistant>("""{"name":"synthetic"}""")
        assertEquals(0, assistant.compactionThresholdTokens)
    }

    @Test fun `threshold round trips independently from message count limit`() {
        val original = Assistant(name = "synthetic", compactionThresholdTokens = 350_000, contextMessageLimit = 64)
        val restored = json.decodeFromString<Assistant>(json.encodeToString(Assistant.serializer(), original))
        assertEquals(350_000, restored.compactionThresholdTokens)
        assertEquals(64, restored.contextMessageLimit)
        assertFalse(restored.enableMemory)
    }

    @Test fun `copied assistant changes do not reset threshold`() {
        assertEquals(600_000, Assistant(compactionThresholdTokens = 600_000).copy(name = "renamed").compactionThresholdTokens)
    }
}
