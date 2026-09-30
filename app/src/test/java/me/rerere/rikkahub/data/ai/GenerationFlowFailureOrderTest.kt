package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationFlowFailureOrderTest {
    private fun assertFailureOrigin(expected: Throwable, actual: Throwable?) {
        // Coroutine stack-trace recovery may copy an exception while retaining the original cause.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertEquals(expected.message, actual?.message)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    @Test fun `completed tool updates arrive before a fast upstream failure`() = runBlocking {
        val failure = IllegalStateException("synthetic empty completion")
        val collected = mutableListOf<String>()
        val actual = runCatching {
            flow {
                emit("tool request")
                emit("completed tool receipt")
                emit("partial reasoning")
                throw failure
            }.orderedGenerationFlow(Dispatchers.Default).collect {
                delay(20) // The producer fails while these updates are still buffered.
                collected += it
            }
        }.exceptionOrNull()
        assertEquals(listOf("tool request", "completed tool receipt", "partial reasoning"), collected)
        assertFailureOrigin(failure, actual)
    }

    @Test fun `downstream storage failure is not hidden or replayed`() = runBlocking {
        val failure = IllegalStateException("synthetic disk full")
        val collected = mutableListOf<Int>()
        val actual = runCatching {
            flow { repeat(3) { emit(it) } }.orderedGenerationFlow(Dispatchers.Default).collect {
                collected += it
                throw failure
            }
        }.exceptionOrNull()
        assertEquals(listOf(0), collected)
        assertFailureOrigin(failure, actual)
    }

    @Test fun `explicit cancellation stays cancellation`() = runBlocking {
        val stop = CancellationException("human stop")
        val actual = runCatching {
            flow<Int> { throw stop }.orderedGenerationFlow(Dispatchers.Default).collect { }
        }.exceptionOrNull()
        assertFailureOrigin(stop, actual)
    }
}
