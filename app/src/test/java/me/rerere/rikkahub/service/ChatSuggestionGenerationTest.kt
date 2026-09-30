package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Synthetic responses only: no provider, settings store, chat database or network. */
class ChatSuggestionGenerationTest {
    @Test fun disabledPreferenceDoesNotStartAnyRequest() = runBlocking {
        var calls = 0
        val result = generateWhileChatSuggestionsEnabled(MutableStateFlow(false)) {
            calls++
            "synthetic suggestion"
        }
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test fun enabledPreferenceReturnsTheCompletedResponse() = runBlocking {
        assertEquals("synthetic suggestion", generateWhileChatSuggestionsEnabled(MutableStateFlow(true)) {
            "synthetic suggestion"
        })
    }

    @Test fun disableBetweenInitialCheckAndRequestPreventsTheRequest() = runBlocking {
        var subscriptions = 0
        var calls = 0
        val preference = flow { emit(++subscriptions == 1) }
        assertNull(generateWhileChatSuggestionsEnabled(preference) { calls++; "should not run" })
        assertEquals(0, calls)
    }

    @Test fun turningOffCancelsTheInFlightRequestAndDoesNotPublishItsResult() = runBlocking {
        withTimeout(5000) {
            val enabled = MutableStateFlow(true)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val generation = async {
                generateWhileChatSuggestionsEnabled(enabled) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            started.await()
            enabled.value = false
            assertNull(generation.await())
            cancelled.await()
        }
    }

    @Test fun reenablingDoesNotResurrectALateResponseFromTheCancelledRequest() = runBlocking {
        withTimeout(5000) {
            val enabled = MutableStateFlow(true)
            val started = CompletableDeferred<Unit>()
            val cancellationObserved = CompletableDeferred<Unit>()
            val releaseLateResponse = CompletableDeferred<Unit>()
            val generation = async {
                generateWhileChatSuggestionsEnabled(enabled) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        // Simulate a provider that returns despite request cancellation.
                        withContext(NonCancellable) {
                            cancellationObserved.complete(Unit)
                            releaseLateResponse.await()
                            "late synthetic response"
                        }
                    }
                }
            }
            started.await()
            enabled.value = false
            cancellationObserved.await()
            enabled.value = true
            releaseLateResponse.complete(Unit)
            assertNull(generation.await())
            assertEquals("new response", generateWhileChatSuggestionsEnabled(enabled) { "new response" })
        }
    }

    @Test fun cancellingCallerStillCancelsProviderWork() = runBlocking {
        withTimeout(5000) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val generation = async(start = CoroutineStart.UNDISPATCHED) {
                generateWhileChatSuggestionsEnabled(MutableStateFlow(true)) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
            started.await()
            generation.cancelAndJoin()
            cancelled.await()
            assertTrue(generation.isCancelled)
        }
    }

    @Test fun providerFailureIsNotTurnedIntoASuccessfulSuggestion() = runBlocking {
        val failure = IllegalStateException("synthetic provider failure")
        try {
            generateWhileChatSuggestionsEnabled(MutableStateFlow(true)) { throw failure }
            fail("Expected the provider failure")
        } catch (error: IllegalStateException) {
            // Coroutine stack-trace recovery can copy an exception while retaining
            // the original as its cause. Propagation must not depend on identity.
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
        }
    }
}
