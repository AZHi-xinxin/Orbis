package me.rerere.rikkahub.data.ai

import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProviderRetryPolicyTest {
    @Test fun partialTextReasoningOrToolChunkMustNeverReplay() {
        assertFalse(canAutomaticallyRetryProvider(IOException("aborted"), true, 0, 3, receivedAnyChunk = true))
    }
    @Test fun retryBudgetRemainsBoundedBeforeTheFirstChunk() {
        (0..2).forEach { assertTrue(canAutomaticallyRetryProvider(ConnectException(), true, it, 3)) }
        assertFalse(canAutomaticallyRetryProvider(ConnectException(), true, 3, 3))
        assertFalse(canAutomaticallyRetryProvider(ConnectException(), true, -1, 3))
    }
    @Test fun disabledRetryNeverMakesAnotherRequest() {
        assertFalse(canAutomaticallyRetryProvider(IOException(), false, 0, 3))
    }
    @Test fun cancellationAndHttpStyleFailuresNeverRetry() {
        listOf(CancellationException(), IllegalStateException("400 context"), IllegalStateException("402 balance")).forEach {
            assertFalse(canAutomaticallyRetryProvider(it, true, 0, 3))
        }
    }
}
