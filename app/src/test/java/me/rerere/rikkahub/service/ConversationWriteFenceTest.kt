package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWriteFenceTest {
    @Test fun `late stopped cleanup cannot write even after a new turn completes`() = runTest {
        val fence = ConversationWriteFence()
        var visible = "1234"
        val release = CompletableDeferred<Unit>()
        val old = launch(fence.context()) {
            withContext(NonCancellable + Dispatchers.Default) {
                release.await()
                fence.requireCurrent()
                visible = "1234.1"
            }
        }
        runCurrent()
        fence.revoke(); old.cancel()
        withContext(fence.context()) { fence.requireCurrent(); visible = "1234 + new input" }
        release.complete(Unit); old.join()
        assertEquals("1234 + new input", visible)
        // Manual editing remains possible after the old producer is gone.
        fence.requireCurrent()
    }

    @Test fun `queued stale generation cannot acquire a new owner merely by starting late`() = runTest {
        val fence = ConversationWriteFence()
        var writes = 0
        val old = launch(fence.context(), start = CoroutineStart.LAZY) { fence.requireCurrent(); writes++ }
        fence.revoke(); old.start(); old.join()
        assertEquals(0, writes)
        withContext(fence.context()) { fence.requireCurrent(); writes++ }
        assertEquals(1, writes)
    }
}
