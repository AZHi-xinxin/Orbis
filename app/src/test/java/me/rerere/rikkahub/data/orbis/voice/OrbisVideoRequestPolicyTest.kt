package me.rerere.rikkahub.data.orbis.voice

import org.junit.Assert.*
import org.junit.Test

class OrbisVideoRequestPolicyTest {
    private val authorized = OrbisVideoRequestPermission("owner", "chat", "call", "owner", "chat", "call",
        "chat", "call", true, false, true, true, true, true, true)
    private val record = OrbisVoiceCallRecord("call", "chat", "owner", 1, video = true,
        status = OrbisVoiceCallStatus.ACTIVE, connectedAtMs = 2)

    @Test fun lifecycleAndPermissionChangesWithdrawQueuedFrames() {
        assertTrue(authorized.permitsRequest())
        listOf(authorized.copy(foreground = false), authorized.copy(cameraEnabled = false),
            authorized.copy(cameraGranted = false), authorized.copy(unlocked = false),
            authorized.copy(voiceActive = false), authorized.copy(voiceEnding = true),
            authorized.copy(connected = false), authorized.copy(videoCallId = null),
            authorized.copy(voiceCallId = null), authorized.copy(voiceCallId = "new-call"),
            authorized.copy(videoConversationId = "other-chat"), authorized.copy(voiceConversationId = "other-chat"),
            authorized.copy(videoOwner = "another-assistant"), authorized.copy(owner = "another-assistant"),
        ).forEach { assertFalse(it.toString(), it.permitsRequest()) }
    }

    @Test fun durableRecordAloneNeverReplaysFrameAfterRestartOrToTextModel() {
        fun dispatch(live: Boolean = true, saved: OrbisVoiceCallRecord? = record, vision: Boolean = true) =
            mayDispatchVideoFrame(live, saved, "owner", "chat", "call", vision)
        assertTrue(dispatch())
        assertFalse(dispatch(live = false))
        assertFalse(dispatch(vision = false))
        assertFalse(dispatch(saved = null))
        assertFalse(dispatch(saved = record.copy(video = false)))
        assertFalse(dispatch(saved = record.copy(status = OrbisVoiceCallStatus.ENDED)))
        assertFalse(dispatch(saved = record.copy(status = OrbisVoiceCallStatus.INTERRUPTED)))
        assertFalse(dispatch(saved = record.copy(connectedAtMs = null)))
        assertFalse(dispatch(saved = record.copy(assistantId = "another-assistant")))
        assertFalse(dispatch(saved = record.copy(conversationId = "another-chat")))
        assertFalse(dispatch(saved = record.copy(id = "another-call")))
    }
}
