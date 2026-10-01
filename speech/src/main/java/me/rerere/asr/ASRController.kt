package me.rerere.asr

import kotlinx.coroutines.flow.StateFlow

interface ASRController {
    val state: StateFlow<ASRState>
    /** Streaming capture can remain open; this is NOT proof that echo cancellation is active. */
    val supportsConcurrentPlayback: Boolean get() = false
    fun start(onTranscriptChange: (String) -> Unit)
    /** Opt-in recording of the SAME microphone stream; null disables retention. */
    fun setPcmObserver(observer: ((ByteArray, Int) -> Unit)?) {}
    fun stop()
    /** Stop microphone capture while keeping the connection open for the final transcript. */
    fun pauseCapture() {}
    fun dispose()
}
