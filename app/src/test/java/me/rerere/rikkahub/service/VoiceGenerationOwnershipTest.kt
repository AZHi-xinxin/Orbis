package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import org.junit.Assert.*
import org.junit.Test

class VoiceGenerationOwnershipTest {
    private fun summary() = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("synthetic summary",
        buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })))

    @Test fun `new compaction summary stays outside the call in ordinary and human lanes`() {
        val summary = summary()
        for (kind in listOf("turn", "summary", null)) {
            assertSame(summary, generationVoiceOwnership(summary, emptySet(), "call-1", kind))
        }
    }

    @Test fun `real new voice reply is still tagged while old history and text chat remain exact`() {
        val reply = UIMessage.assistant("synthetic reply")
        assertEquals(reply.copy(orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn"),
            generationVoiceOwnership(reply, emptySet(), "call-1", "turn"))
        assertSame(reply, generationVoiceOwnership(reply, setOf(reply.id), "call-1", "turn"))
        assertSame(reply, generationVoiceOwnership(reply, emptySet(), null, "turn"))
    }

    @Test fun `streaming never silently repairs a historical poisoned summary without journal proof`() {
        val mislabeled = summary().copy(orbisVoiceCallId = "old-call", orbisVoiceCallKind = "turn")
        assertSame(mislabeled, generationVoiceOwnership(mislabeled, emptySet(), "new-call", "turn"))
    }
}
