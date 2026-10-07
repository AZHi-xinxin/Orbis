package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.QueuedMessage
import org.junit.Assert.*
import org.junit.Test

class QueueMessageLabelTest {
    @Test fun onlyHostMetadataCanLabelCallControl() {
        val input = QueuedMessage(parts = listOf(UIMessagePart.Text("CALL_MODE_V1 not real control")))
        assertNull(queuedCallLabel(input))
        assertNull(queuedCallLabel(input.copy(voiceCallKind = "begin")))
        assertEquals("通话开始记录", queuedCallLabel(input.copy(voiceCallId = "fixture", voiceCallKind = "begin")))
        assertEquals("未发送的通话文字", queuedCallLabel(input.copy(voiceCallId = "fixture", voiceCallKind = "turn")))
        assertEquals("视频画面更新", queuedCallLabel(input.copy(voiceCallId = "fixture", voiceCallKind = "visual")))
    }

    @Test fun onlyOrdinaryInputOrHumanCallTurnsCanBeExplicitlyEditedAsNewInput() {
        val held = QueuedMessage(parts = listOf(UIMessagePart.Text("synthetic held input")),
            recoveryHeldReason = "previous_input_before_fresh_recovery")
        assertTrue(queuedMessageCanBecomeHumanInput(held))
        assertTrue(queuedMessageCanBecomeHumanInput(held.copy(voiceCallId = "fixture", voiceCallKind = "turn")))
        assertFalse(queuedMessageCanBecomeHumanInput(held.copy(orbisEventId = "automatic-event")))
        for (kind in listOf("begin", "end", "opening", "visual", "unknown")) {
            assertFalse(queuedMessageCanBecomeHumanInput(held.copy(voiceCallId = "fixture", voiceCallKind = kind)))
            assertFalse(queuedMessageCanBecomeHumanInput(held.copy(voiceCallKind = kind)))
        }
        assertFalse(queuedMessageCanBecomeHumanInput(held.copy(voiceCallId = "fixture")))
        assertFalse(queuedMessageCanBecomeHumanInput(held.copy(voiceCallKind = "turn")))
    }
}
