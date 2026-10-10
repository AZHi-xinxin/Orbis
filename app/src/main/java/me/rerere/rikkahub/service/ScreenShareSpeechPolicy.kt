package me.rerere.rikkahub.service

internal enum class ScreenShareSpeechRoute { SKIP, STANDALONE, OWNED_VOICE }

/** Small, non-persistent session fence. It cannot request recording or resume a model turn. */
internal class ScreenShareSpeechPolicy {
    var sessionId: String? = null; private set
    var owner: String? = null; private set
    var conversation: String? = null; private set
    var enabled = false; private set
    var voiceCallId: String? = null; private set
    var voiceStarting = false; private set
    private val seen = linkedSetOf<String>()

    fun begin(owner: String, conversation: String, session: String) {
        this.owner = owner; this.conversation = conversation; sessionId = session
        enabled = false; voiceCallId = null; voiceStarting = false; seen.clear()
    }

    fun matches(session: String) = sessionId == session
    fun setEnabled(session: String, value: Boolean): Boolean {
        if (!matches(session)) return false
        enabled = value
        return true
    }
    fun prepareVoice(session: String): Boolean {
        if (!matches(session)) return false
        voiceStarting = true
        return true
    }
    fun attachVoice(session: String, call: String, starting: Boolean = false): Boolean {
        if (!matches(session)) return false
        voiceStarting = starting; voiceCallId = call
        return true
    }
    fun detachVoice(session: String, call: String?) {
        if (matches(session) && call == voiceCallId) {
            voiceStarting = false; voiceCallId = null
        }
    }
    fun stop(session: String): Boolean {
        if (!matches(session)) return false
        sessionId = null; owner = null; conversation = null; enabled = false
        voiceCallId = null; voiceStarting = false; seen.clear()
        return true
    }

    fun claim(session: String, replyId: String, replyVoiceCallId: String?,
              liveVoiceCallId: String?, voiceBusy: Boolean): ScreenShareSpeechRoute {
        if (!matches(session) || !seen.add(replyId)) return ScreenShareSpeechRoute.SKIP
        // Disabled replies are consumed too: switching on must not read the conversation backlog.
        if (seen.size > 256) seen.remove(seen.first())
        if (!enabled || replyVoiceCallId != null || voiceStarting) return ScreenShareSpeechRoute.SKIP
        if (voiceCallId != null && voiceCallId == liveVoiceCallId) return ScreenShareSpeechRoute.OWNED_VOICE
        return if (voiceBusy) ScreenShareSpeechRoute.SKIP else ScreenShareSpeechRoute.STANDALONE
    }
}
