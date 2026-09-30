package me.rerere.rikkahub.data.orbis.contact

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** The exact production driver; only its focus/player/synthesis ports are fake. No Android audio. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSpeechPlaybackSessionTest {
    private class Port : NotificationSpeechPlaybackPort<String> {
        val events = mutableListOf<String>()
        val synthesis = CompletableDeferred<String>()
        val completion = CompletableDeferred<Unit>()
        var blocked: String? = null
        var result = NotificationSpeechFocusResult.GRANTED
        var onRequest: (() -> Unit)? = null
        var callback: ((NotificationSpeechFocusChange) -> Unit)? = null
        var started: (() -> Unit)? = null
        var markStarted = true
        var abandonThrows = false
        override fun blockedReason() = blocked
        override suspend fun synthesize(text: String): String {
            events += "synthesize:$text"
            return synthesis.await()
        }
        override fun requestFocus(onChange: (NotificationSpeechFocusChange) -> Unit): NotificationSpeechFocusResult {
            events += "focus"
            callback = onChange
            onRequest?.invoke()
            return result
        }
        override fun abandonFocus() { events += "abandon"; if (abandonThrows) error("private platform error") }
        override suspend fun play(audio: String, onStarted: () -> Unit) {
            events += "play:$audio"
            started = onStarted
            if (markStarted) onStarted()
            completion.await()
        }
        override fun pause() { events += "pause" }
        override fun resume() { events += "resume" }
        override fun disposePlayer() { events += "dispose" }
        fun focus(change: NotificationSpeechFocusChange) = checkNotNull(callback).invoke(change)
        fun ready() { synthesis.complete("synthetic_audio") }
    }

    private fun TestScope.session(port: Port, budget: Long = 1_000) = NotificationSpeechPlaybackSession(
        port, { testScheduler.currentTime }, waitBudgetMillis = budget, guardIntervalMillis = 10,
    )

    private suspend fun outcome(session: NotificationSpeechPlaybackSession<String>): String = try {
        session.play("only_current_notice")
        "completed"
    } catch (failure: NotificationSpeechInterrupted) { failure.code }

    private fun reader(session: NotificationSpeechPlaybackSession<String>, timeout: Long = 45_000) =
        OrbisNotificationSpeech(object : OrbisNotificationSpeechPort {
            override fun skipReason(scope: OrbisNotificationSpeechScope): String? = null
            override suspend fun acquire(scope: OrbisNotificationSpeechScope) = object : OrbisNotificationSpeechLease {
                override val playbackStarted: Boolean get() = session.playbackStarted
                override suspend fun play(text: String) = session.play(text)
                override suspend fun close() = session.close()
            }
        }, Mutex(), timeout)

    private suspend fun OrbisNotificationSpeech.read() = readNotification(
        "only_current_notice", "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222",
    )

    @Test fun synthesisFinishesBeforeTheOnlyFocusRequest() = runTest {
        val port = Port()
        val session = session(port)
        val task = async { outcome(session) }
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(listOf("synthesize:only_current_notice"), port.events)
        port.ready()
        runCurrent()
        assertEquals(listOf("synthesize:only_current_notice", "focus", "play:synthetic_audio"), port.events)
        port.completion.complete(Unit)
        assertEquals("completed", task.await())
        assertTrue(session.playbackStarted)
        assertEquals(listOf("dispose", "abandon"), port.events.takeLast(2))
        assertEquals(1, port.events.count { it == "focus" })
    }

    @Test fun systemNotificationDuckPausesAndGainResumesTheSameAudioExactlyOnce() = runTest {
        val port = Port().also { it.ready() }
        val session = session(port)
        val task = async { outcome(session) }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        port.focus(NotificationSpeechFocusChange.TRANSIENT_LOSS)
        assertEquals(1, port.events.count { it == "pause" })
        advanceTimeBy(500)
        port.focus(NotificationSpeechFocusChange.GAIN)
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertEquals(1, port.events.count { it == "resume" })
        assertEquals(1, port.events.count { it.startsWith("play:") })
        assertEquals(1, port.events.count { it == "focus" })
        port.completion.complete(Unit)
        assertEquals("completed", task.await())
    }

    @Test fun transientLossDuringRequestDoesNotStartUntilGain() = runTest {
        val port = Port().also {
            it.ready()
            it.onRequest = { it.focus(NotificationSpeechFocusChange.DUCK) }
        }
        val session = session(port)
        val task = async { outcome(session) }
        runCurrent()
        assertFalse(port.events.any { it.startsWith("play:") })
        assertFalse(session.playbackStarted)
        port.focus(NotificationSpeechFocusChange.GAIN)
        runCurrent()
        assertEquals(1, port.events.count { it.startsWith("play:") })
        port.completion.complete(Unit)
        assertEquals("completed", task.await())
    }

    @Test fun delayedFocusCanStartOnceWithinBudget() = runTest {
        val port = Port().also { it.result = NotificationSpeechFocusResult.DELAYED; it.ready() }
        val task = async { outcome(session(port)) }
        runCurrent()
        assertFalse(port.events.any { it.startsWith("play:") })
        advanceTimeBy(500)
        port.focus(NotificationSpeechFocusChange.GAIN)
        runCurrent()
        port.completion.complete(Unit)
        assertEquals("completed", task.await())
        assertEquals(1, port.events.count { it == "focus" })
    }

    @Test fun delayedFocusTimeoutAbandonsAndLateGainCannotPlay() = runTest {
        val port = Port().also { it.result = NotificationSpeechFocusResult.DELAYED; it.ready() }
        val session = session(port)
        val task = async { outcome(session) }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("audio_focus_timeout", task.await())
        val sealed = port.events.toList()
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertEquals(sealed, port.events)
        assertFalse(session.playbackStarted)
    }

    @Test fun repeatedLossAndGainShareOneCumulativeBudget() = runTest {
        val port = Port().also { it.ready() }
        val task = async { outcome(session(port)) }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        advanceTimeBy(600)
        port.focus(NotificationSpeechFocusChange.GAIN)
        advanceTimeBy(2_000)
        port.focus(NotificationSpeechFocusChange.TRANSIENT_LOSS)
        advanceTimeBy(399)
        assertFalse(task.isCompleted)
        advanceTimeBy(1)
        port.focus(NotificationSpeechFocusChange.GAIN) // exact deadline: no second resume
        runCurrent()
        assertEquals("audio_focus_timeout", task.await())
        assertEquals(1, port.events.count { it == "resume" })
        assertEquals(1, port.events.count { it == "focus" })
    }

    @Test fun permanentLossStopsWithoutReRequestOrLateResume() = runTest {
        val port = Port().also { it.ready() }
        val task = async { outcome(session(port)) }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.LOSS)
        runCurrent()
        assertEquals("audio_focus_lost", task.await())
        val sealed = port.events.toList()
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertEquals(sealed, port.events)
        assertEquals(1, port.events.count { it == "abandon" })
        assertFalse(port.events.contains("resume"))
    }

    @Test fun deniedFocusNeverPlaysAndDoesNotRetry() = runTest {
        val port = Port().also { it.ready(); it.result = NotificationSpeechFocusResult.DENIED }
        assertEquals("audio_focus_denied", outcome(session(port)))
        assertFalse(port.events.any { it.startsWith("play:") })
        assertEquals(1, port.events.count { it == "focus" })
        assertEquals(1, port.events.count { it == "abandon" })
    }

    @Test fun settingsLockCallsAndOtherAudioAreRecheckedBeforeAnyResume() = runTest {
        for (reason in listOf("disabled", "device_locked", "call_active", "alarm_active", "silent_mode",
            "do_not_disturb", "voice_setting_changed", "other_audio_active", "assistant_unavailable")) {
            val port = Port().also { it.ready() }
            val task = async { outcome(session(port)) }
            runCurrent()
            port.focus(NotificationSpeechFocusChange.DUCK)
            port.blocked = reason
            port.focus(NotificationSpeechFocusChange.GAIN)
            runCurrent()
            assertEquals(reason, task.await())
            assertFalse(port.events.contains("resume"))
            port.blocked = null
            port.focus(NotificationSpeechFocusChange.GAIN)
            assertFalse(port.events.contains("resume"))
        }
    }

    @Test fun disabledWhileSynthesizingCancelsWithoutTakingFocus() = runTest {
        val port = Port()
        val task = async { outcome(session(port)) }
        runCurrent()
        port.blocked = "disabled"
        advanceTimeBy(10)
        runCurrent()
        assertEquals("disabled", task.await())
        port.ready()
        runCurrent()
        assertFalse(port.events.contains("focus"))
        assertFalse(port.events.contains("abandon"))
        assertEquals(1, port.events.count { it == "dispose" })
    }

    @Test fun blockedAfterSynthesisBeforeRequestNeverTakesFocus() = runTest {
        val port = Port()
        val task = async { outcome(session(port)) }
        runCurrent()
        port.blocked = "device_locked"
        port.ready()
        runCurrent()
        assertEquals("device_locked", task.await())
        assertFalse(port.events.contains("focus"))
    }

    @Test fun callerCancellationWhilePausedCannotResumeOrReportCompleted() = runTest {
        val port = Port().also { it.ready() }
        val session = session(port)
        var completed = false
        val task = launch { session.play("notice"); completed = true }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        task.cancelAndJoin()
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertFalse(completed)
        assertFalse(port.events.contains("resume"))
        assertEquals(1, port.events.count { it == "dispose" })
        assertEquals(1, port.events.count { it == "abandon" })
        session.close()
        assertEquals(1, port.events.count { it == "abandon" })
    }

    @Test fun gainQueuedBeforeCancellationCleanupCannotRestartAudio() = runTest {
        val port = Port().also { it.ready() }
        val task = launch { session(port).play("notice") }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        task.cancel() // Cancellation is visible, but finally has not run on the serial dispatcher.
        assertFalse(port.events.contains("dispose"))
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertFalse(port.events.contains("resume"))
        task.join()
        assertEquals(1, port.events.count { it == "abandon" })
    }

    @Test fun lateStartedCallbackAfterCancelCannotFabricatePlaybackReceipt() = runTest {
        val port = Port().also { it.ready(); it.markStarted = false }
        val session = session(port)
        val task = launch { session.play("notice") }
        runCurrent()
        task.cancelAndJoin()
        checkNotNull(port.started).invoke()
        assertFalse(session.playbackStarted)
    }

    @Test fun wholeReaderTimeoutIsNotRenewedByTemporaryGains() = runTest {
        val port = Port().also { it.ready() }
        val session = session(port, budget = 10_000)
        val task = async { reader(session, timeout = 100).read() }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        advanceTimeBy(40)
        port.focus(NotificationSpeechFocusChange.GAIN)
        advanceTimeBy(40)
        port.focus(NotificationSpeechFocusChange.DUCK)
        advanceTimeBy(20)
        runCurrent()
        val receipt = task.await()
        assertEquals("speech_timeout", receipt.reasonCode)
        assertTrue(receipt.playbackStarted)
        assertFalse(receipt.userHeardConfirmed)
        val sealed = port.events.toList()
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertEquals(sealed, port.events)
    }

    @Test fun receiptsDistinguishNeverStartedPartialAndFullyCompleted() = runTest {
        val denied = Port().also { it.ready(); it.result = NotificationSpeechFocusResult.DENIED }
        val never = reader(session(denied)).read()
        assertEquals("skipped", never.status)
        assertFalse(never.playbackStarted)

        val partial = Port().also { it.ready() }
        val read = async { reader(session(partial)).read() }
        runCurrent()
        partial.focus(NotificationSpeechFocusChange.LOSS)
        val stopped = read.await()
        assertEquals("skipped", stopped.status)
        assertTrue(stopped.playbackStarted)
        assertFalse(stopped.userHeardConfirmed)

        val complete = Port().also { it.ready(); it.completion.complete(Unit) }
        val done = reader(session(complete)).read()
        assertEquals("played", done.status)
        assertTrue(done.playbackStarted)
        assertFalse(done.userHeardConfirmed)
    }

    @Test fun oldLeaseGainCannotTouchANewerNotification() = runTest {
        val first = Port().also { it.ready(); it.completion.complete(Unit) }
        assertEquals("completed", outcome(session(first)))
        val next = Port().also { it.ready() }
        val task = async { outcome(session(next)) }
        runCurrent()
        next.focus(NotificationSpeechFocusChange.DUCK)
        val oldSealed = first.events.toList()
        val nextSealed = next.events.toList()
        first.focus(NotificationSpeechFocusChange.GAIN)
        assertEquals(oldSealed, first.events)
        assertEquals(nextSealed, next.events)
        next.focus(NotificationSpeechFocusChange.GAIN)
        next.completion.complete(Unit)
        assertEquals("completed", task.await())
    }

    @Test fun cleanupErrorCannotEraseACompletedReceiptOrAllowLatePlayback() = runTest {
        val port = Port().also { it.ready(); it.completion.complete(Unit); it.abandonThrows = true }
        val session = session(port)
        val receipt = reader(session).read()
        assertEquals("played", receipt.status)
        assertTrue(receipt.playbackStarted)
        val sealed = port.events.toList()
        port.focus(NotificationSpeechFocusChange.GAIN)
        session.close()
        assertEquals(sealed, port.events)
    }

    @Test fun duckDuringBufferingRecoversWithoutInventingAnEarlierStart() = runTest {
        val port = Port().also { it.ready(); it.markStarted = false }
        val session = session(port)
        val task = async { reader(session).read() }
        runCurrent()
        port.focus(NotificationSpeechFocusChange.DUCK)
        assertFalse(session.playbackStarted)
        advanceTimeBy(200)
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertFalse(session.playbackStarted)
        checkNotNull(port.started).invoke()
        port.completion.complete(Unit)
        val receipt = task.await()
        assertEquals("played", receipt.status)
        assertTrue(receipt.playbackStarted)
        assertEquals(1, port.events.count { it.startsWith("play:") })
    }

    @Test fun lockWhileWaitingIsCancelledWithoutWaitingForAFocusCallback() = runTest {
        val port = Port().also { it.ready(); it.result = NotificationSpeechFocusResult.DELAYED }
        val task = async { outcome(session(port)) }
        runCurrent()
        port.blocked = "device_locked"
        advanceTimeBy(10)
        runCurrent()
        assertEquals("device_locked", task.await())
        port.blocked = null
        port.focus(NotificationSpeechFocusChange.GAIN)
        assertFalse(port.events.any { it.startsWith("play:") })
    }

    @Test fun callerCancellationDuringSynthesisNeverRequestsOrAbandonsFocus() = runTest {
        val port = Port()
        val session = session(port)
        val task = launch { session.play("notice") }
        runCurrent()
        task.cancelAndJoin()
        port.ready()
        runCurrent()
        assertFalse(port.events.contains("focus"))
        assertFalse(port.events.contains("abandon"))
        assertFalse(session.playbackStarted)
    }

    @Test fun failedSynthesisReturnsSanitizedReceiptWithoutAudioFocus() = runTest {
        val port = Port()
        port.synthesis.completeExceptionally(IllegalStateException("SECRET source text and key"))
        val receipt = reader(session(port)).read()
        assertEquals("speech_failed", receipt.reasonCode)
        assertFalse(receipt.playbackStarted)
        assertFalse(receipt.toString().contains("SECRET"))
        assertFalse(port.events.contains("focus"))
        assertFalse(port.events.contains("abandon"))
    }

    @Test fun completionDoesNotInventAMissingPlaybackStartedObservation() = runTest {
        val port = Port().also { it.ready(); it.markStarted = false; it.completion.complete(Unit) }
        val receipt = reader(session(port)).read()
        assertEquals("played", receipt.status)
        assertFalse(receipt.playbackStarted)
        assertFalse(receipt.userHeardConfirmed)
    }
}
