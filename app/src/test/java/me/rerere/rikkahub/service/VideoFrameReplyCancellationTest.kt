package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoFrameReplyCancellationTest {
    @Test fun interruptedFrameReplyDoesNotStopTheFollowingSamplingCycle() = runTest {
        val interrupted = CompletableDeferred<String?>()
        val next = CompletableDeferred<String?>()
        val replies = mutableListOf<String?>()
        val sampler = launch {
            for (reply in listOf(interrupted, next)) replies.add(awaitVideoFrameReply(reply))
        }
        runCurrent()
        interrupted.cancel(CancellationException("human started speaking")); runCurrent()
        assertTrue(sampler.isActive)
        assertEquals(listOf<String?>(null), replies)
        next.complete("next camera tick completed"); sampler.join()
        assertEquals(listOf(null, "next camera tick completed"), replies)
    }

    @Test fun actualSamplerCancellationStillStopsImmediatelyAndCannotProduceAnotherFrame() = runTest {
        val pending = CompletableDeferred<String?>()
        var reachedNextFrame = false
        val sampler = launch { awaitVideoFrameReply(pending); reachedNextFrame = true }
        runCurrent()
        sampler.cancelAndJoin()
        assertTrue(sampler.isCancelled)
        assertFalse(reachedNextFrame)
        pending.complete("late reply must not reopen camera"); runCurrent()
        assertFalse(reachedNextFrame)
    }

    @Test fun ownerTimeoutIsNotSwallowedAsAnInterruptedModelReply() = runTest {
        val pending = CompletableDeferred<String?>()
        assertTrue(runCatching { withTimeout(100) { awaitVideoFrameReply(pending) } }.exceptionOrNull() is TimeoutCancellationException)
    }
}
