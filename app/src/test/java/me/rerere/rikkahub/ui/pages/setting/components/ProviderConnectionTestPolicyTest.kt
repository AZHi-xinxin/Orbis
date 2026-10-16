package me.rerere.rikkahub.ui.pages.setting.components

import kotlinx.coroutines.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderConnectionTestPolicyTest {
    @Test fun currentAssistantModelWinsOnlyWithinThisConnection() {
        val first = Model(modelId = "first")
        val current = Model(modelId = "selected")
        assertSame(current, preferredConnectionTestModel(listOf(first, current), current.id))
        assertSame(first, preferredConnectionTestModel(listOf(first, current), Uuid.random()))
        assertSame(first, preferredConnectionTestModel(listOf(first, current), null))
    }
    @Test fun nonChatModelsCannotBecomeTheTestDefault() {
        val image = Model(modelId = "image", type = ModelType.IMAGE)
        val chat = Model(modelId = "chat")
        assertSame(chat, preferredConnectionTestModel(listOf(image, chat), image.id))
        assertNull(preferredConnectionTestModel(listOf(image), image.id))
    }
    @Test fun probesAreSequentialAndRunExactlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        val steps = (0..2).map { index -> suspend { events += "start$index"; yield(); events += "end$index" } }
        assertEquals(3, runConnectionTestSteps(steps) { _, _ -> fail() })
        assertEquals(listOf("start0", "end0", "start1", "end1", "start2", "end2"), events)
    }
    @Test fun aFailureStopsUnsentProbesWithoutRetry() = runBlocking {
        val events = mutableListOf<Int>()
        val completed = runConnectionTestSteps(listOf({ events += 0 }, { events += 1; error("synthetic") }, { events += 2 })) { index, _ -> assertEquals(1, index) }
        assertEquals(1, completed); assertEquals(listOf(0, 1), events)
    }
    @Test fun cancellationDoesNotBecomeFailureOrLaunchTheNextProbe() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var nextStarted = false
        var failureReported = false
        val job = launch {
            runConnectionTestSteps(listOf({ started.complete(Unit); awaitCancellation() }, { nextStarted = true })) { _, _ -> failureReported = true }
        }
        started.await(); job.cancelAndJoin()
        assertFalse(nextStarted); assertFalse(failureReported)
    }
}
