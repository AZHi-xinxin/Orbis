package me.rerere.rikkahub.data.orbis.screenshare

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScreenShareSummaryTest {
    private fun record(owner: String = "owner", conversation: String = "conversation", status: String = "ended",
        started: Long = 100, ended: Long? = 200, id: String = "00000000-0000-4000-8000-000000000001", summary: String = "plot: 合成画面\nprocess: 合成对话") = buildJsonObject {
        put("session_id", id); put("assistant_id", owner); put("conversation_id", conversation)
        put("started_at", started); put("ended_at", ended?.let(::JsonPrimitive) ?: JsonNull)
        put("status", status); put("reason", "stopped_by_human"); put("summary", summary); put("raw_frames_saved", false)
    }

    @Test fun `selects only exact owner and original conversation`() {
        val valid = record()
        val records = listOf(valid, record(owner = "another", started = 400), record(conversation = "another", started = 500))
        val result = selectCompletedScreenShareSummary(records, "owner", "conversation")!!
        assertEquals("plot: 合成画面\nprocess: 合成对话", result.summary)
        assertNull(selectCompletedScreenShareSummary(records, "unknown", "conversation"))
        assertNull(selectCompletedScreenShareSummary(records, "owner", "unknown"))
    }

    @Test fun `latest active record cannot be mistaken for previous completed share`() {
        val records = listOf(record(), record(status = "active", started = 300, ended = null))
        assertNull(selectCompletedScreenShareSummary(records, "owner", "conversation"))
    }

    @Test fun `malformed id missing terminal time and empty summary are never sendable`() {
        listOf(record(id = "../../other"), record(ended = null), record(summary = ""), record(status = "unknown")).forEach {
            assertNull(selectCompletedScreenShareSummary(listOf(it), "owner", "conversation"))
        }
        assertNull(selectCompletedScreenShareSummary(emptyList(), "owner", "conversation"))
    }

    @Test fun `explicit send contains saved text and stop reason without starting a share`() {
        val saved = selectCompletedScreenShareSummary(listOf(record()), "owner", "conversation")!!
        assertTrue(saved.message().contains(saved.summary))
        assertTrue(saved.message().contains("stopped_by_human"))
        assertTrue(saved.message().contains("不是重新开启共享"))
        assertFalse(saved.message().contains("orbis-screen-frame://"))
        assertFalse(saved.message().contains("data:image"))
    }
}
