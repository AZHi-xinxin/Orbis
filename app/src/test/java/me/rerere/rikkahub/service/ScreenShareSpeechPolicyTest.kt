package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class ScreenShareSpeechPolicyTest {
    private fun gate() = ScreenShareSpeechPolicy().apply { begin("assistant", "conversation", "session") }
    private fun ScreenShareSpeechPolicy.claim(id: String, fromVoice: String? = null, activeVoice: String? = null, busy: Boolean = false) =
        claim("session", id, fromVoice, activeVoice, busy)

    @Test fun disabledByDefaultAndEnableNeverReplaysOldReply() {
        val gate = gate()
        assertFalse(gate.enabled)
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("old"))
        assertTrue(gate.setEnabled("session", true))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("old"))
        assertEquals(ScreenShareSpeechRoute.STANDALONE, gate.claim("new"))
        assertNull(gate.voiceCallId)
        assertFalse(gate.voiceStarting)
    }

    @Test fun repeatedFinalAndVoiceOwnedRepliesAreNotSpokenTwice() {
        val gate = gate().apply { setEnabled("session", true) }
        assertEquals(ScreenShareSpeechRoute.STANDALONE, gate.claim("reply"))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("reply"))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("asr", fromVoice = "ended-call"))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("asr"))
    }

    @Test fun microphoneHandoffHasNoStandalonePlaybackWindow() {
        val gate = gate().apply { setEnabled("session", true); prepareVoice("session") }
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("during-start"))
        assertTrue(gate.attachVoice("session", "call"))
        assertEquals(ScreenShareSpeechRoute.OWNED_VOICE, gate.claim("typed", activeVoice = "call", busy = true))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("voice", fromVoice = "call", activeVoice = "call", busy = true))
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("other-call", activeVoice = "another", busy = true))
    }

    @Test fun switchingOutputDoesNotChangeOwnedMicrophoneCall() {
        val gate = gate().apply { attachVoice("session", "call"); setEnabled("session", true) }
        gate.setEnabled("session", false)
        assertEquals("call", gate.voiceCallId)
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("muted", activeVoice = "call", busy = true))
        gate.setEnabled("session", true)
        assertEquals("call", gate.voiceCallId)
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("muted", activeVoice = "call", busy = true))
        assertEquals(ScreenShareSpeechRoute.OWNED_VOICE, gate.claim("future", activeVoice = "call", busy = true))
    }

    @Test fun oldSessionCannotEnableStopAttachOrDeliverIntoNewSession() {
        val gate = gate().apply { setEnabled("session", true); begin("next-owner", "next-conversation", "next") }
        assertFalse(gate.setEnabled("session", true))
        assertFalse(gate.prepareVoice("session"))
        assertFalse(gate.attachVoice("session", "late-call"))
        assertFalse(gate.stop("session"))
        gate.detachVoice("session", null)
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("late"))
        assertEquals("next-owner", gate.owner)
        assertFalse(gate.enabled)
        assertNull(gate.voiceCallId)
    }

    @Test fun detachDoesNotReopenMicOrReplayReplyAndEndClearsOwnership() {
        val gate = gate().apply { setEnabled("session", true); attachVoice("session", "call") }
        gate.detachVoice("session", "other")
        assertEquals("call", gate.voiceCallId)
        gate.detachVoice("session", "call")
        assertNull(gate.voiceCallId)
        assertEquals(ScreenShareSpeechRoute.STANDALONE, gate.claim("future-text"))
        assertTrue(gate.stop("session"))
        assertFalse(gate.enabled)
        assertNull(gate.sessionId)
        assertNull(gate.owner)
        assertNull(gate.conversation)
        assertEquals(ScreenShareSpeechRoute.SKIP, gate.claim("late"))
    }

    @Test fun failedPreparedVoiceReleasesHandoffButLateOldEndCannotDetachNewCall() {
        val gate = gate().apply {
            setEnabled("session", true); prepareVoice("session"); attachVoice("session", "failed", starting = true)
        }
        gate.detachVoice("session", "failed")
        assertFalse(gate.voiceStarting)
        assertEquals(ScreenShareSpeechRoute.STANDALONE, gate.claim("text-after-failure"))
        gate.prepareVoice("session")
        gate.attachVoice("session", "new", starting = true)
        gate.detachVoice("session", "failed")
        assertEquals("new", gate.voiceCallId)
        assertTrue(gate.voiceStarting)
    }
}
