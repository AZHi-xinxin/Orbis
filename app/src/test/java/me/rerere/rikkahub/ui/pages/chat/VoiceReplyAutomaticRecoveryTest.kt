package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpException
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn
import me.rerere.rikkahub.service.MessageQueue
import me.rerere.rikkahub.service.VoiceReplyResumeSnapshot
import me.rerere.rikkahub.service.canResumeOwnedVoiceReplies
import org.junit.Assert.*
import org.junit.Test

/** Virtual time and local fakes only: readiness must never dispatch model/tool work. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceReplyAutomaticRecoveryTest {
    private class Recorder : ASRController {
        override val state = MutableStateFlow(ASRState())
        var disposed = false
        override fun start(onTranscriptChange: (String) -> Unit) {
            state.value = ASRState(status = ASRStatus.Listening)
        }
        override fun pauseCapture() = Unit
        override fun stop() = Unit
        override fun dispose() { disposed = true }
        fun final(text: String) {
            state.value = state.value.copy(transcript = text, voiceTurn = ASRVoiceTurn("turn", true, text))
        }
    }

    private class Rig(scope: CoroutineScope) {
        val recorders = mutableListOf<Recorder>()
        val inputs = mutableListOf<String>()
        val replies = mutableListOf<CompletableDeferred<String?>>()
        val spoken = mutableListOf<String>()
        val muted = mutableListOf<Boolean>()
        val pauses = mutableListOf<Boolean>()
        val ended = mutableListOf<String?>()
        var openingFailure: Exception? = null
        var enqueueFailure: Exception? = null
        var openings = 0
        val voice = VoiceSessionController(scope, { it.toString() }) {
            inputs += it
            enqueueFailure?.let { error -> throw error }
            CompletableDeferred<String?>().also(replies::add)
        }

        fun start(check: suspend () -> Boolean) = voice.start(
            createAsr = { Recorder().also(recorders::add) },
            speak = { spoken += it }, stopSpeaking = {}, setOutputMuted = { muted += it },
            onEnded = { ended += it }, onReplyPauseChanged = { pauses += it },
            requestOpening = { openings++; openingFailure?.let { throw it }; null },
            checkReplyResume = check,
        )

        fun failReply() {
            replies.last().completeExceptionally(HttpException("private upstream details", httpStatus = 503))
        }
    }

    @Test fun safelySettledFailureResumesOnlyFutureSpeechAndFramesWithoutReplayingAnything() = runTest {
        val r = Rig(backgroundScope)
        var ready = false
        var checks = 0
        try {
            r.start { checks++; ready }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("accepted once"); runCurrent()
            val oldRecorder = r.recorders.last()
            r.failReply(); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking) // Manual continuation remains available.
            advanceTimeBy(251); runCurrent()
            assertEquals(1, checks)
            assertTrue(r.voice.state.value.replyBlocked)
            oldRecorder.final("stale recording"); runCurrent()
            ready = true
            advanceTimeBy(750); runCurrent()
            assertEquals(2, checks)
            assertFalse(r.voice.state.value.replyBlocked)
            assertNull(r.voice.state.value.replyNotice)
            assertNull(r.voice.state.value.recoveryNotice)
            assertEquals(listOf(true, false), r.pauses)
            assertEquals(3, r.recorders.size)
            assertEquals(listOf("accepted once"), r.inputs)
            assertEquals(1, r.openings)
            assertTrue(r.spoken.isEmpty())
            val frame = r.voice.enqueueSupplementaryReply({ true }) { CompletableDeferred<String?>(null) }
            assertNotNull(frame); runCurrent()
            r.recorders.last().final("new human utterance"); runCurrent()
            assertEquals(listOf("accepted once", "new human utterance"), r.inputs)
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun unresolvedReadinessIsBoundedAndLeavesManualContinueAvailable() = runTest {
        val r = Rig(backgroundScope)
        val times = mutableListOf<Long>()
        try {
            r.start { times += testScheduler.currentTime; false }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(listOf(451L, 1_201L, 2_701L, 5_701L), times)
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking)
            assertTrue(r.voice.state.value.replyNotice.orEmpty().contains("MODEL_RESUME_BLOCKED"))
            assertEquals(2, r.recorders.size)
            assertEquals(listOf("saved"), r.inputs)
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertEquals(4, times.size)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun readinessCannotClearPausedQueueQueuedInputOrUnknownToolHold() = runTest {
        val r = Rig(backgroundScope)
        val queue = MessageQueue(initiallyPaused = true)
        queue.enqueue(listOf(UIMessagePart.Text("unreviewed input")))
        val originalQueue = queue.state.value
        var unknownTool = true
        var checks = 0
        try {
            r.start {
                checks++
                VoiceReplyResumeSnapshot(true, true, queue.state.value.paused,
                    queue.state.value.messages.size, 0, false, false, unknownTool,
                    false, unknownTool, unknownTool).ready
            }
            advanceTimeBy(201); runCurrent()
            r.recorders.single().final("kept"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(1_001); runCurrent()
            assertEquals(2, checks)
            assertEquals(originalQueue, queue.state.value)
            queue.resume(); assertNotNull(queue.takeNext()) // Human review, not the controller.
            advanceTimeBy(1_500); runCurrent()
            assertEquals(3, checks)
            assertTrue(r.voice.state.value.replyBlocked) // Unknown tool still blocks despite idle queue.
            unknownTool = false
            advanceTimeBy(3_000); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertEquals(4, checks)
            assertEquals(listOf("kept"), r.inputs)
            assertTrue(r.spoken.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun throwingReadinessFailsClosedWithoutLeakingPrivateDetails() = runTest {
        val r = Rig(backgroundScope)
        var checks = 0
        try {
            r.start { checks++; error("secret storage and token details") }
            advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(4, checks)
            assertTrue(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.isActive)
            assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("secret"))
            assertFalse(r.voice.state.value.replyNotice.orEmpty().contains("private"))
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun timedOutChecksAreBoundedAndDoNotHangUpOrPreventManualContinuation() = runTest {
        val r = Rig(backgroundScope)
        var checks = 0
        try {
            r.start { checks++; awaitCancellation() }
            advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(30_000); runCurrent()
            assertEquals(4, checks)
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking)
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertTrue(r.ended.isEmpty())
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun manualCheckSupersedesLateAutomaticSuccessWithoutOverlappingProbes() = runTest {
        val r = Rig(backgroundScope)
        val gate = CompletableDeferred<Boolean>()
        var activeChecks = 0
        var maximumChecks = 0
        var manualChecks = 0
        try {
            r.start {
                activeChecks++; maximumChecks = maxOf(maximumChecks, activeChecks)
                try { withContext(NonCancellable) { gate.await() } } finally { activeChecks-- }
            }
            advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(251); runCurrent()
            assertEquals(1, activeChecks)
            assertTrue(r.voice.requestReplyResume {
                manualChecks++; activeChecks++; maximumChecks = maxOf(maximumChecks, activeChecks)
                try { false } finally { activeChecks-- }
            })
            runCurrent()
            assertEquals(0, manualChecks)
            gate.complete(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertEquals(1, maximumChecks)
            assertEquals(1, manualChecks)
            assertTrue(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.replyResumeChecking)
            assertEquals(listOf(true), r.pauses)
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
        } finally { gate.complete(false); r.voice.stopAndJoin() }
    }

    @Test fun manualContinueDuringBackoffCancelsRemainingAutomaticProbes() = runTest {
        val r = Rig(backgroundScope)
        var checks = 0
        try {
            r.start { checks++; false }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(251); runCurrent()
            assertTrue(r.voice.requestReplyResume { true }); runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, checks)
            assertFalse(r.voice.state.value.replyBlocked)
            assertEquals(listOf("saved"), r.inputs)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun hangupDoesNotAwaitNonCooperativeProbeAndLateResultCannotUnlockNewCall() = runTest {
        val r = Rig(backgroundScope)
        val gate = CompletableDeferred<Boolean>()
        try {
            r.start { withContext(NonCancellable) { gate.await() } }
            advanceTimeBy(201); runCurrent()
            r.recorders.single().final("first call"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(251); runCurrent()
            r.voice.stopAndJoin()
            assertEquals(VoicePhase.Off, r.voice.state.value.phase)
            assertEquals(1, r.ended.size)
            r.start { false }; advanceTimeBy(201); runCurrent()
            r.recorders.last().final("second call"); runCurrent(); r.failReply(); runCurrent()
            gate.complete(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
            assertTrue(r.voice.state.value.replyBlocked)
            assertEquals(4, r.recorders.size)
            assertEquals(listOf("first call", "second call"), r.inputs)
            assertEquals(listOf(true, true), r.pauses)
        } finally { gate.complete(false); r.voice.stopAndJoin() }
    }

    @Test fun callOrModelOwnershipChangesDuringProbeRejectOtherwiseReadyResult() = runTest {
        for (changeModel in listOf(false, true)) {
            val r = Rig(backgroundScope)
            val gate = CompletableDeferred<Boolean>()
            var callOwner = "call-A"
            var modelOwner = "model-A"
            try {
                r.start { canResumeOwnedVoiceReplies({ callOwner == "call-A" && modelOwner == "model-A" }) { gate.await() } }
                advanceTimeBy(201); runCurrent()
                r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
                advanceTimeBy(251); runCurrent()
                if (changeModel) modelOwner = "model-B" else callOwner = "call-B"
                gate.complete(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
                assertTrue(r.voice.state.value.replyBlocked)
                assertEquals(2, r.recorders.size)
                assertEquals(listOf(true), r.pauses)
                assertEquals(listOf("saved"), r.inputs)
            } finally { gate.complete(false); r.voice.stopAndJoin() }
        }
    }

    @Test fun automaticReadinessPreservesHumanMicrophoneAndSpeakerMute() = runTest {
        val r = Rig(backgroundScope)
        val gate = CompletableDeferred<Boolean>()
        try {
            r.start { gate.await() }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            advanceTimeBy(251); runCurrent()
            r.voice.setMicrophoneEnabled(false); r.voice.setSpeakerEnabled(false); runCurrent()
            gate.complete(true); runCurrent(); advanceTimeBy(301); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertFalse(r.voice.state.value.microphoneEnabled)
            assertFalse(r.voice.state.value.speakerEnabled)
            assertTrue(r.muted.last())
            assertEquals(2, r.recorders.size)
            r.voice.setMicrophoneEnabled(true); runCurrent()
            assertEquals(3, r.recorders.size)
            assertTrue(r.muted.last())
            assertEquals(listOf("saved"), r.inputs)
        } finally { gate.complete(false); r.voice.stopAndJoin() }
    }

    @Test fun automaticReadinessCannotReacquireSuspendedAudioFocus() = runTest {
        val r = Rig(backgroundScope)
        try {
            r.start { true }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("saved"); runCurrent(); r.failReply(); runCurrent()
            r.voice.setAudioFocusSuspended(true); runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertFalse(r.voice.state.value.replyBlocked)
            assertTrue(r.voice.state.value.audioFocusSuspended)
            assertTrue(r.muted.last())
            assertEquals(2, r.recorders.size)
            r.voice.setAudioFocusSuspended(false); runCurrent()
            assertEquals(3, r.recorders.size)
            assertEquals(listOf("saved"), r.inputs)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun pendingRepliesAndSuccessfulEmptyRepliesNeverStartRecoveryChecks() = runTest {
        val r = Rig(backgroundScope)
        var checks = 0
        try {
            r.start { checks++; true }; advanceTimeBy(201); runCurrent()
            r.recorders.single().final("wait for accepted work"); runCurrent()
            advanceTimeBy(6 * 60 * 1_000); runCurrent()
            assertEquals(0, checks)
            assertFalse(r.voice.state.value.replyBlocked)
            assertFalse(r.replies.single().isCancelled)
            r.replies.single().complete(null); runCurrent()
            r.recorders.last().final("empty is still completion"); runCurrent()
            r.replies.last().complete(" "); runCurrent()
            advanceTimeBy(10_000); runCurrent()
            assertEquals(0, checks)
            assertFalse(r.voice.state.value.replyBlocked)
            assertTrue(r.spoken.isEmpty())
            assertEquals(2, r.inputs.size)
        } finally { r.voice.stopAndJoin() }
    }

    @Test fun synchronousOpeningAndEnqueueFailureMayRecoverButAreNeverRetried() = runTest {
        for (opening in listOf(false, true)) {
            val r = Rig(backgroundScope)
            try {
                if (opening) r.openingFailure = IllegalStateException("private opening details")
                else r.enqueueFailure = IllegalStateException("private enqueue details")
                r.start { true }; advanceTimeBy(201); runCurrent()
                if (!opening) { r.recorders.single().final("accepted once"); runCurrent() }
                assertTrue(r.voice.state.value.replyBlocked)
                advanceTimeBy(1_000); runCurrent()
                assertFalse(r.voice.state.value.replyBlocked)
                assertEquals(1, r.openings)
                assertEquals(if (opening) emptyList<String>() else listOf("accepted once"), r.inputs)
                assertTrue(r.replies.isEmpty())
                assertTrue(r.spoken.isEmpty())
                assertTrue(r.ended.isEmpty())
            } finally { r.voice.stopAndJoin() }
        }
    }
}
