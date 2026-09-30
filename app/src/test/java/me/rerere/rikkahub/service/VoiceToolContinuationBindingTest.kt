package me.rerere.rikkahub.service

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class VoiceToolContinuationBindingTest {
    private fun pending(id: String) = UIMessagePart.Tool(id, "synthetic_mcp", "{}", approvalState = ToolApprovalState.Pending)
    private fun message(call: String?, kind: String? = "turn", id: String = "pending-A") = UIMessage.assistant("").copy(
        parts = listOf(pending(id), UIMessagePart.Tool("auto-end-$id", "end_voice_call", "{}")),
        orbisVoiceCallId = call, orbisVoiceCallKind = kind)

    @Test fun `resuming pending MCP plus auto end batch retains A even with newer B in same window`() {
        val binding = voiceToolContinuationBinding(listOf(message("call-A"), message("call-B", id = "pending-B")), "pending-A")!!
        assertEquals("call-A", binding.callId)
        assertEquals("turn", binding.kind)
        assertFalse(binding.allowAmbientCallBinding)
    }

    @Test fun `legacy unbound pending batch fails closed instead of adopting active or historical call`() {
        val binding = voiceToolContinuationBinding(listOf(message("call-old", id = "old"), message(null, null)), "pending-A")!!
        assertNull(binding.callId)
        assertNull(binding.kind)
        assertFalse(binding.allowAmbientCallBinding)
    }

    @Test fun `opening continuation keeps opening identity and stale completed approval is ignored`() {
        val original = message("call-A", "opening")
        assertEquals("opening", voiceToolContinuationBinding(listOf(original), "pending-A")!!.kind)
        val done = original.copy(parts = listOf(pending("pending-A").copy(output = listOf(UIMessagePart.Text("stored receipt")))))
        assertNull(voiceToolContinuationBinding(listOf(done), "pending-A"))
        assertNull(voiceToolContinuationBinding(listOf(original), "missing"))
    }
}
