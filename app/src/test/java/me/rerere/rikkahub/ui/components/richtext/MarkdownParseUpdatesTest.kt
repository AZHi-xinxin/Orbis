package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarkdownParseUpdatesTest {
    @Test fun firstParseRunsAwayFromTheCollectorThread() = runBlocking {
        val collectingThread = Thread.currentThread()
        val result = markdownParseUpdates(flowOf("synthetic first message")) { source ->
            assertNotSame(collectingThread, Thread.currentThread())
            source.uppercase()
        }.single()
        assertEquals("SYNTHETIC FIRST MESSAGE", result.getOrThrow())
    }

    @Test fun identicalValuesDoNotReparseAndLaterStreamingContentStillArrives() = runBlocking {
        val firstCollected = CompletableDeferred<Unit>()
        val parsed = mutableListOf<String>()
        val source = flow {
            emit("first")
            firstCollected.await()
            emit("first")
            emit("first then second")
        }
        val received = mutableListOf<String>()
        markdownParseUpdates(source) { value -> parsed.add(value); value }.collect { result ->
            received.add(result.getOrThrow())
            firstCollected.complete(Unit)
        }
        assertEquals(listOf("first", "first then second"), parsed)
        assertEquals(parsed, received)
    }

    @Test fun failureIsRedactedAndDoesNotDisableLaterUpdates() = runBlocking {
        val failureCollected = CompletableDeferred<Unit>()
        val source = flow {
            emit("bad")
            failureCollected.await()
            emit("good")
        }
        val received = mutableListOf<Result<String>>()
        markdownParseUpdates(source) { value ->
            if (value == "bad") throw IllegalArgumentException("synthetic-private-message-payload")
            value
        }.collect { result ->
            received.add(result)
            failureCollected.complete(Unit)
        }
        val failure = received.first().exceptionOrNull()
        assertEquals("markdown_parse_failed", failure?.message)
        assertNull(failure?.cause)
        assertFalse(failure.toString().contains("synthetic-private-message-payload"))
        assertEquals("good", received.last().getOrThrow())
    }

    @Test fun cancellationIsNotConvertedIntoAContentError() = runBlocking {
        val result = runCatching {
            markdownParseUpdates(flowOf("synthetic")) { throw CancellationException("stop") }.toList()
        }
        // mapLatest may finish after its cancelled child without emitting a value. Neither
        // that case nor propagated cancellation may become a user-visible parse failure.
        if (result.isFailure) assertTrue(result.exceptionOrNull() is CancellationException)
        else assertTrue(result.getOrThrow().isEmpty())
    }
}
