package me.rerere.rikkahub.data.orbis.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

internal class NotificationSpeechInterrupted(val code: String) : IllegalStateException(code)

/** All methods/callbacks are confined to the caller's serial dispatcher (Android: Main). */
internal interface NotificationSpeechPlaybackPort<Audio> {
    fun blockedReason(): String?
    suspend fun synthesize(text: String): Audio
    fun requestFocus(onChange: (NotificationSpeechFocusChange) -> Unit): NotificationSpeechFocusResult
    fun abandonFocus()
    suspend fun play(audio: Audio, onStarted: () -> Unit)
    fun pause()
    fun resume()
    fun disposePlayer()
}

/** Production lifecycle with fakeable I/O: no history, retries, second focus request or replay. */
internal class NotificationSpeechPlaybackSession<Audio>(
    private val port: NotificationSpeechPlaybackPort<Audio>,
    private val nowMillis: () -> Long,
    waitBudgetMillis: Long = 10_000,
    private val guardIntervalMillis: Long = 100,
) {
    private val policy = NotificationSpeechFocusPolicy(waitBudgetMillis)
    private val focusRevision = MutableStateFlow(0L)
    private val interruption = CompletableDeferred<String>()
    private var used = false
    private var closed = false
    private var focusRequested = false
    private var playbackInvoked = false
    private var paused = false
    private var sessionJob: Job? = null
    var playbackStarted = false
        private set

    init { require(guardIntervalMillis > 0) }

    suspend fun play(text: String) = coroutineScope {
        check(!used && !closed) { "Notification playback is one shot" }
        used = true
        sessionJob = currentCoroutineContext()[Job]
        val work = async {
            checkLive()
            val response = port.synthesize(text)
            currentCoroutineContext().ensureActive()
            checkLive()
            // Synthesis can take seconds. Do not reserve/interfere with system audio during it.
            policy.beginRequest()
            focusRequested = true
            policy.requestReturned(port.requestFocus(::focusChanged), nowMillis())
            applyFocusState()
            focusRevision.first { policy.canPlay || policy.finished }
            currentCoroutineContext().ensureActive()
            checkLive()
            playbackInvoked = true
            port.play(response) {
                if (!closed && !policy.finished && sessionJob?.isActive == true) playbackStarted = true
            }
            currentCoroutineContext().ensureActive()
            checkLive()
            policy.finish()
        }
        val guard = launch {
            while (isActive) {
                checkLive()
                delay(guardIntervalMillis)
            }
        }
        try {
            select<Unit> {
                interruption.onAwait { throw NotificationSpeechInterrupted(it) }
                work.onAwait { }
            }
        } finally {
            // Seal callbacks before suspending for cancellation/cleanup. A queued GAIN must not
            // revive this notification after a timeout, caller cancellation or successful end.
            policy.finish()
            withContext(NonCancellable) {
                guard.cancelAndJoin()
                work.cancelAndJoin()
                close()
            }
        }
    }

    private fun focusChanged(change: NotificationSpeechFocusChange) {
        if (closed || policy.finished) return
        // Cancellation can be published before Main gets to execute finally/close. Do not let
        // a focus callback queued ahead of that cleanup restart even a fraction of old audio.
        if (sessionJob?.isActive != true) { policy.finish(); return }
        try {
            port.blockedReason()?.let(policy::cancel)
            policy.change(change, nowMillis())
            applyFocusState()
        } catch (_: Exception) {
            // No text, provider URL or platform error is copied into the receipt.
            policy.cancel("speech_failed")
            interruption.complete("speech_failed")
        }
    }

    private fun checkLive() {
        port.blockedReason()?.let(policy::cancel)
        policy.tick(nowMillis())
        applyFocusState()
        policy.reasonCode?.let { throw NotificationSpeechInterrupted(it) }
        check(!closed) { "Notification playback closed" }
    }

    private fun applyFocusState() {
        if (playbackInvoked && !policy.canPlay && !paused && (!policy.finished || policy.reasonCode != null)) {
            port.pause()
            paused = true
        } else if (playbackInvoked && policy.canPlay && paused) {
            port.resume()
            paused = false
        }
        policy.reasonCode?.let(interruption::complete)
        focusRevision.value += 1
    }

    fun close() {
        if (closed) return
        closed = true
        sessionJob = null
        policy.finish()
        interruption.complete("speech_cancelled")
        focusRevision.value += 1
        try { port.disposePlayer() } catch (_: Exception) { /* Best effort, still abandon focus. */ } finally {
            if (focusRequested) {
                focusRequested = false
                try { port.abandonFocus() } catch (_: Exception) { /* Never replace the playback outcome. */ }
            }
        }
    }
}
