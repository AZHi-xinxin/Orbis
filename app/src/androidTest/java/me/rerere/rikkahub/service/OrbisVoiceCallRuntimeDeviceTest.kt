package me.rerere.rikkahub.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.testutil.ShellComposeActivityRule
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/**
 * Real Android foreground lifetime and notification, but synthetic audio and model callbacks only.
 * Requires -PorbisIsolatedTests=true and this explicit test class. The runner uses plain Application;
 * no RikkaHubApp/Koin/ChatService, provider, recorder, real chat, or archive is ever initialized.
 * Existing permissions are checked, never granted/revoked. No screen/lock/system settings change.
 */
@RunWith(AndroidJUnit4::class)
class OrbisVoiceCallRuntimeDeviceTest {
    @get:Rule val shell = ShellComposeActivityRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val runtime get() = OrbisVoiceCallRuntime.get(context)
    private var owned: Fixture? = null

    @Before fun requireIsolatedVisibleProcessAndExistingPermission() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(context.javaClass == Application::class.java)
        check(context.packageName == "org.orbis.agent.dev")
        onMain {
            check(shell.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            check(!runtime.callState.value.isActive && !runtime.callState.value.ending) {
                "Another runtime session exists; this fixture will not stop a call it does not own"
            }
        }
        assumeTrue(
            "Microphone FGS requires an already-granted RECORD_AUDIO permission; test does not change permissions",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }

    @After fun stopOnlyTheOwnedSyntheticCall() {
        val fixture = owned ?: return
        onMain {
            if (runtime.callState.value.callId == fixture.callId && runtime.callState.value.isActive) runtime.hangUp()
        }
        if (fixture.accepted) {
            assertTrue("Owned runtime did not deliver its cleanup callback", fixture.ended.await(10, TimeUnit.SECONDS))
            awaitCondition("Owned runtime cleanup remained pending") {
                !runtime.callState.value.ending || runtime.callState.value.callId != fixture.callId
            }
            assertTrue("A synthetic ASR was not disposed", fixture.recorders.all { it.disposeCount.get() == 1 })
        }
    }

    @Test fun connectionMarkerCompletesBeforeFirstUtteranceAndHangupCleansOnce() {
        val fixture = startFixture(blockBegin = true)
        assertTrue("ASR did not connect", fixture.beginEntered.await(10, TimeUnit.SECONDS))
        val first = awaitRecorder(fixture)
        onMain { first.finish("synthetic first utterance") }
        instrumentation.waitForIdleSync()
        assertTrue("A blocked begin marker must prevent submission", fixture.submitted.isEmpty())

        fixture.allowBegin.complete(Unit)
        assertTrue("First synthetic utterance was not submitted", fixture.utteranceQueued.await(10, TimeUnit.SECONDS))
        val next = awaitRecorder(fixture, first)
        assertEquals(listOf("begin", "utterance:synthetic first utterance"), fixture.events.toList())
        val stopBefore = fixture.stopSpeakingCount.get()

        onMain {
            runtime.dismissReturnScreen(fixture.callId)
            assertFalse("A stale UI callback must not dismiss an active call", runtime.callState.value.returnScreenDismissed)
            runtime.hangUp()
            assertTrue("Hangup must pause capture before it returns", next.pauseCount.get() > 0)
            assertEquals("Hangup must stop playback before it returns", stopBefore + 1, fixture.stopSpeakingCount.get())
            assertFalse(runtime.callState.value.isActive)
            assertFalse(runtime.callState.value.returnScreenDismissed)
            runtime.dismissReturnScreen("different-call")
            assertFalse(runtime.callState.value.returnScreenDismissed)
            runtime.dismissReturnScreen(fixture.callId)
            assertTrue(runtime.callState.value.returnScreenDismissed)
            runtime.hangUp()
        }
        assertTrue(fixture.ended.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(1, fixture.endCount.get())
        assertEquals(OrbisVoiceCallEndReason.USER, fixture.endValue.get()?.reason)
        assertTrue(fixture.recorders.all { it.disposeCount.get() == 1 })
        assertEquals(VoicePhase.Off, runtime.voiceSession.state.value.phase)
    }

    @Test fun actualForegroundNotificationHasReturnAndWorkingHangupAction() {
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Notification permission/channel state is not changed by this fixture", manager.areNotificationsEnabled())
        manager.getNotificationChannel("orbis_voice_call")?.let {
            assumeTrue("The existing call channel is disabled", it.importance != NotificationManager.IMPORTANCE_NONE)
        }
        val fixture = startFixture()
        awaitRecorder(fixture)
        awaitCondition("Call FGS did not publish its own notification") { findNotification(fixture) != null }
        val notification = checkNotNull(findNotification(fixture))
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.contentIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("Return action must target an Activity", notification.contentIntent.isActivity)
        }
        assertEquals(context.packageName, notification.contentIntent.creatorPackage)
        val hangup = checkNotNull(notification.actions?.singleOrNull { it.title.toString() == "挂断" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("Hangup must target the service", hangup.actionIntent.isService)
        }

        // Never send contentIntent: it opens the real RouteActivity and is outside this fixture.
        hangup.actionIntent.send()
        assertTrue("Notification hangup did not reach the runtime", fixture.ended.await(10, TimeUnit.SECONDS))
        assertEquals(OrbisVoiceCallEndReason.USER, fixture.endValue.get()?.reason)
        assertEquals(1, fixture.endCount.get())
        awaitCondition("Ended call notification remained posted") { findNotification(fixture) == null }
    }

    @Test fun destroyingTheVisibleSyntheticActivityDoesNotEndTheCallOrPlayback() {
        val fixture = startFixture(withPlayback = true)
        val recorder = awaitRecorder(fixture)
        val activity = shell.activity
        onMain { activity.finish() }
        awaitCondition("Owned synthetic activity was not destroyed") { onMain { activity.isDestroyed } }
        assertTrue("Activity destruction must not hang up the process-owned call", runtime.callState.value.isActive)
        assertEquals(0, fixture.endCount.get())
        assertEquals(0, recorder.disposeCount.get())

        onMain { recorder.finish("synthetic background utterance") }
        assertTrue("Background ASR did not reach the fake model", fixture.utteranceQueued.await(10, TimeUnit.SECONDS))
        assertTrue("Background response did not reach fake TTS", fixture.playbackEntered.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("synthetic reply"), fixture.spoken.toList())
        assertEquals(VoicePhase.Speaking, runtime.voiceSession.state.value.phase)
        val recorderCountBeforeHangup = fixture.recorders.size
        val stopBefore = fixture.stopSpeakingCount.get()
        onMain {
            runtime.hangUp()
            assertEquals(stopBefore + 1, fixture.stopSpeakingCount.get())
        }
        assertTrue(fixture.ended.await(10, TimeUnit.SECONDS))
        fixture.allowPlayback.complete(Unit)
        instrumentation.waitForIdleSync()
        assertFalse(runtime.callState.value.isActive)
        assertEquals(1, fixture.endCount.get())
        assertEquals("Late playback completion must not reopen capture", recorderCountBeforeHangup, fixture.recorders.size)
        assertTrue(fixture.recorders.all { it.disposeCount.get() == 1 })
    }

    @Test fun assistantEndRejectsDifferentCallAndConversationThenEndsOnce() {
        val fixture = startFixture()
        awaitRecorder(fixture)
        awaitCondition("Synthetic connection did not finish") { runtime.voiceSession.state.value.phase == VoicePhase.Listening }
        onMain {
            val conversation = checkNotNull(runtime.callState.value.conversationId).toString()
            assertFalse(runtime.endFromAssistant("old-call", conversation, null))
            assertFalse(runtime.endFromAssistant(fixture.callId, Uuid.random().toString(), null))
            assertTrue(runtime.callState.value.isActive)
            assertTrue(runtime.endFromAssistant(fixture.callId, conversation, "synthetic end reason"))
            assertFalse(runtime.endFromAssistant(fixture.callId, conversation, "duplicate"))
        }
        assertTrue(fixture.ended.await(10, TimeUnit.SECONDS))
        assertEquals(1, fixture.endCount.get())
        assertEquals(OrbisVoiceCallEndReason.AI_END, fixture.endValue.get()?.reason)
        assertEquals("synthetic end reason", fixture.endValue.get()?.endReasonText)
        assertNull(fixture.endValue.get()?.error)
    }

    @Test fun modelOpeningWaitsForConnectionAndMuteOnlyChangesOwnedOutput() {
        val fixture = startFixture(blockBegin = true, withPlayback = true, withOpening = true)
        assertTrue(fixture.beginEntered.await(10, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        assertEquals(0, fixture.openingCount.get())
        assertTrue(fixture.spoken.isEmpty())
        fixture.allowBegin.complete(Unit)
        assertTrue(fixture.playbackEntered.await(10, TimeUnit.SECONDS))
        val stops = fixture.stopSpeakingCount.get()
        onMain {
            runtime.setSpeakerEnabled(false)
            runtime.setMicrophoneEnabled(false)
            runtime.setSpeakerEnabled(true)
            assertEquals(stops, fixture.stopSpeakingCount.get())
            assertTrue(runtime.callState.value.isActive)
        }
        instrumentation.waitForIdleSync()
        assertEquals(1, fixture.openingCount.get())
        assertEquals(listOf(false, true, false), fixture.outputMuted.toList())
        assertEquals(listOf("free synthetic opening"), fixture.spoken.toList())
        assertEquals(stops, fixture.stopSpeakingCount.get())
    }

    private fun startFixture(blockBegin: Boolean = false, withPlayback: Boolean = false,
        withOpening: Boolean = false): Fixture {
        check(owned == null)
        val fixture = Fixture()
        owned = fixture
        if (!blockBegin) fixture.allowBegin.complete(Unit)
        onMain {
            fixture.accepted = runtime.start(
                callId = fixture.callId,
                conversationId = Uuid.random(),
                title = fixture.title,
                createAsr = { FakeAsr().also { fixture.recorders.add(it) } },
                enqueueMessage = { text ->
                    check(fixture.events.firstOrNull() == "begin") { "Utterance preceded begin completion" }
                    fixture.events.add("utterance:$text")
                    fixture.submitted.add(text)
                    fixture.utteranceQueued.countDown()
                    // The synthetic model is a completed value, not GenerationLoop or a provider.
                    CompletableDeferred(if (withPlayback) "synthetic reply" else null)
                },
                speak = if (withPlayback) ({ text: String ->
                    fixture.spoken.add(text)
                    fixture.playbackEntered.countDown()
                    fixture.allowPlayback.await()
                }) else null,
                stopSpeaking = { fixture.stopSpeakingCount.incrementAndGet(); Unit },
                setOutputMuted = { fixture.outputMuted.add(it); Unit },
                requestOpening = if (withOpening) ({
                    check(fixture.events.firstOrNull() == "begin")
                    fixture.openingCount.incrementAndGet()
                    CompletableDeferred("free synthetic opening")
                }) else null,
                onConnected = {
                    fixture.beginEntered.countDown()
                    fixture.allowBegin.await()
                    fixture.events.add("begin")
                },
                onEnded = {
                    fixture.endValue.set(it)
                    fixture.endCount.incrementAndGet()
                    fixture.ended.countDown()
                },
            )
            assertTrue("Synthetic runtime session was not accepted", fixture.accepted)
        }
        return fixture
    }

    private fun awaitRecorder(fixture: Fixture, previous: FakeAsr? = null): FakeAsr {
        awaitCondition("Synthetic ASR never entered listening; runtime error=${runtime.callState.value.error}") {
            fixture.recorders.lastOrNull()?.let {
                it !== previous && it.state.value.status == ASRStatus.Listening && it.disposeCount.get() == 0
            } == true
        }
        return fixture.recorders.last()
    }

    private fun findNotification(fixture: Fixture): Notification? = context
        .getSystemService(NotificationManager::class.java).activeNotifications
        .map { it.notification }
        .firstOrNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == fixture.title }

    private fun awaitCondition(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(message, predicate())
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }

    private class Fixture {
        val callId = "synthetic-runtime-${Uuid.random()}"
        val title = "Synthetic voice ${Uuid.random()}"
        var accepted = false
        val recorders = CopyOnWriteArrayList<FakeAsr>()
        val events = CopyOnWriteArrayList<String>()
        val submitted = CopyOnWriteArrayList<String>()
        val spoken = CopyOnWriteArrayList<String>()
        val allowBegin = CompletableDeferred<Unit>()
        val allowPlayback = CompletableDeferred<Unit>()
        val beginEntered = CountDownLatch(1)
        val utteranceQueued = CountDownLatch(1)
        val playbackEntered = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val stopSpeakingCount = AtomicInteger()
        val openingCount = AtomicInteger()
        val outputMuted = CopyOnWriteArrayList<Boolean>()
        val endCount = AtomicInteger()
        val endValue = AtomicReference<OrbisVoiceCallEnd?>()
    }

    /** Pure StateFlow: no AudioRecord, permissions API, sockets, files or audio focus. */
    private class FakeAsr : ASRController {
        override val state = MutableStateFlow(ASRState())
        val pauseCount = AtomicInteger()
        val disposeCount = AtomicInteger()
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun pauseCapture() { pauseCount.incrementAndGet() }
        override fun stop() { pauseCapture() }
        override fun dispose() { disposeCount.incrementAndGet() }
        fun finish(text: String) {
            state.value = state.value.copy(
                transcript = text,
                voiceTurn = ASRVoiceTurn("synthetic-turn", speechEnded = true, finalText = text),
            )
        }
    }
}
