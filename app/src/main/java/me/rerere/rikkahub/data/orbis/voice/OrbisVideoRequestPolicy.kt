package me.rerere.rikkahub.data.orbis.voice

/** Evaluated again at dispatch, not a permission cached when a frame entered the queue. */
data class OrbisVideoRequestPermission(
    val owner: String, val conversationId: String, val callId: String,
    val videoOwner: String?, val videoConversationId: String?, val videoCallId: String?,
    val voiceConversationId: String?, val voiceCallId: String?,
    val voiceActive: Boolean, val voiceEnding: Boolean, val connected: Boolean,
    val foreground: Boolean, val cameraEnabled: Boolean,
    val cameraGranted: Boolean, val unlocked: Boolean,
) {
    fun permitsRequest(): Boolean = callId.isNotBlank() && owner.isNotBlank() && conversationId.isNotBlank() &&
        videoOwner == owner && videoConversationId == conversationId &&
        voiceConversationId == conversationId && videoCallId == callId && voiceCallId == callId &&
        voiceActive && !voiceEnding && connected && foreground && cameraEnabled && cameraGranted && unlocked
}

internal fun mayDispatchVideoFrame(liveAuthorized: Boolean, record: OrbisVoiceCallRecord?,
    owner: String, conversationId: String, callId: String, modelSupportsImages: Boolean): Boolean =
    liveAuthorized && modelSupportsImages && record?.let {
        it.video && it.id == callId && it.assistantId == owner && it.conversationId == conversationId &&
            it.status == OrbisVoiceCallStatus.ACTIVE && it.connectedAtMs != null
    } == true
