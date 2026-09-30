package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.hostToolFailure
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointJournal
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.GenerationToolStatus
import me.rerere.rikkahub.data.ai.checkpoint.GenerationToolTransition
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/** Real queue/journal and synthetic commit ordering; no ChatService/Room/Android/audio/API instance.
 * These tests cover the handoff contract, not the private binding map's production wiring. */
class VoiceBargeInCancellationTest {
    @Test fun acknowledgementRequiresDurabilityHealthyQueueUnblockedRecoveryAndNoUnknownTools() {
        for (saved in listOf(false, true)) for (paused in listOf(false, true))
            for (blocked in listOf(false, true)) for (unknown in listOf(false, true)) {
                assertEquals("saved=$saved paused=$paused blocked=$blocked unknown=$unknown",
                    saved && !paused && !blocked && !unknown,
                    canAcknowledgeVoiceInterruption(saved, paused, blocked, if (unknown) setOf("external-write") else emptySet()))
            }
    }

    @Test fun savedInterruptionAdvancesIndependentTurnWithoutReenqueuingOldReply() {
        val writes = mutableListOf<Boolean>()
        val queue = MessageQueue(onPauseChanged = { writes += it; true })
        val oldId = Uuid.random()
        val nextId = Uuid.random()
        queue.enqueue(text("old utterance"), id = oldId, voiceCallId = "call", voiceCallKind = "turn")
        queue.enqueue(text("new utterance"), id = nextId, voiceCallId = "call", voiceCallKind = "turn")
        assertEquals(oldId, queue.takeNext()!!.id)
        val interruption = VoiceBargeInCancellation(oldId, "call").apply { partialSafelySaved = true }
        queue.afterGenerationFailure(interruption, partialSnapshotSaved = true)
        assertFalse(queue.state.value.paused)
        assertTrue(writes.isEmpty())
        assertEquals(nextId, queue.takeNext()!!.id)
        assertNull(queue.takeNext())
    }

    @Test fun savedInterruptionCannotResumePreviousHumanPauseIncludingEmptyQueue() {
        for (withPending in listOf(false, true)) {
            val writes = mutableListOf<Boolean>()
            val queue = MessageQueue(initiallyPaused = true, onPauseChanged = { writes += it; true })
            if (withPending) queue.enqueue(text("old independent pending input"))
            queue.afterGenerationFailure(VoiceBargeInCancellation(Uuid.random(), "call").apply {
                partialSafelySaved = true
            }, partialSnapshotSaved = true)
            assertTrue(queue.state.value.paused)
            assertTrue(writes.isEmpty()) // In particular no durable unpause request.
            assertNull(queue.takeNext())
        }
    }

    @Test fun unsavedSnapshotPausesEvenIfAnEarlierRecoveryAcknowledgementWasSet() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("later"), reply = reply)
        queue.afterGenerationFailure(VoiceBargeInCancellation(Uuid.random(), "call").apply {
            partialSafelySaved = true
        }, partialSnapshotSaved = false)
        assertTrue(queue.state.value.paused)
        assertEquals(1, queue.state.value.messages.size)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is MessageQueuePausedException)
    }

    @Test fun ordinaryCancellationAndUnacknowledgedVoiceCancellationBothFailClosed() {
        listOf(CancellationException("ordinary stop"), VoiceBargeInCancellation(Uuid.random(), "call")).forEach {
            val queue = MessageQueue()
            queue.enqueue(text("later"))
            assertFalse(it.isSavedVoiceInterruption())
            queue.afterGenerationFailure(it, partialSnapshotSaved = true)
            assertTrue(queue.state.value.paused)
            assertNull(queue.takeNext())
        }
    }

    @Test fun removingOnePendingVoiceTurnCompletesOnlyItsWaiterAndKeepsOtherKinds() = runBlocking {
        val queue = MessageQueue()
        val turn = Uuid.random()
        val oldReply = CompletableDeferred<String?>()
        val ordinaryReply = CompletableDeferred<String?>()
        val archiveReply = CompletableDeferred<String?>()
        queue.enqueue(text("voice turn"), id = turn, reply = oldReply, voiceCallId = "call", voiceCallKind = "turn")
        queue.enqueue(text("ordinary"), reply = ordinaryReply)
        queue.enqueue(text("archive"), reply = archiveReply, voiceCallId = "call", voiceCallKind = "archive")
        queue.remove(turn)
        assertNull(oldReply.await())
        assertFalse(ordinaryReply.isCompleted)
        assertFalse(archiveReply.isCompleted)
        assertFalse(queue.state.value.paused)
        assertEquals(listOf(null, "archive"), queue.state.value.messages.map { it.voiceCallKind })
    }

    @Test fun startedUnknownToolRemainsStoppedAfterBargeInRecoveryAndCannotResumeExecution() {
        val run = Recovery()
        run.checkpoint(GenerationToolStatus.STARTED)
        run.recover()
        val restored = run.stored.currentMessages.last().getTools().single()
        assertEquals(HostToolFailure.INTERRUPTED, restored.hostToolFailure())
        assertFalse(restored.canResumeExecution)
        assertFalse(run.interruption.partialSafelySaved)
        assertTrue(run.queue.state.value.paused)
        assertNull(run.queue.takeNext())
        assertEquals(1, run.queue.state.value.messages.size)
        assertFalse(run.journal.hasCheckpoint(run.stored.id)) // Unknown receipt itself is now durable.
    }

    @Test fun completedToolReceiptIsPreservedBeforeHealthyQueueAdvancesAndNeverReexecuted() {
        val run = Recovery()
        run.checkpoint(GenerationToolStatus.STARTED)
        run.checkpoint(GenerationToolStatus.COMPLETED)
        run.recover()
        assertEquals(listOf("room.commit", "journal.clear"), run.events)
        assertTrue(run.interruption.isSavedVoiceInterruption())
        assertFalse(run.queue.state.value.paused)
        assertEquals(run.followingId, run.queue.takeNext()!!.id)
        assertEquals("exact synthetic receipt", (run.stored.currentMessages.last().getTools().single().output.single() as UIMessagePart.Text).text)
        assertEquals(1, run.syntheticToolExecutions) // recover() has no execution callback.
        assertFalse(run.journal.hasCheckpoint(run.stored.id))
    }

    @Test fun failedRoomCommitDoesNotAcknowledgeOrClearToolReceiptAndKeepsQueuePaused() {
        val run = Recovery()
        run.checkpoint(GenerationToolStatus.STARTED)
        run.checkpoint(GenerationToolStatus.COMPLETED)
        val before = run.store.bytes.getValue(run.stored.id).copyOf()
        run.failCommit = true
        run.recover()
        assertFalse(run.interruption.partialSafelySaved)
        assertTrue(run.queue.state.value.paused)
        assertTrue(run.journal.hasCheckpoint(run.stored.id))
        assertArrayEquals(before, run.store.bytes.getValue(run.stored.id))
        assertEquals(listOf("room.failed"), run.events)
        assertEquals(1, run.syntheticToolExecutions)
    }

    @Test fun corruptedCheckpointPreservesOriginalChatAndNeverAcknowledgesSafeInterruption() {
        val run = Recovery()
        val original = run.stored
        run.store.bytes[original.id] = "corrupt synthetic fixture".toByteArray()
        run.recover()
        assertSame(original, run.stored)
        assertTrue(run.queue.state.value.paused)
        assertFalse(run.interruption.partialSafelySaved)
        assertTrue(run.store.bytes.containsKey(original.id))
        assertTrue(run.events.isEmpty())
        assertEquals(0, run.syntheticToolExecutions)
    }

    @Test fun cancellationCauseRetainsDurabilityAcknowledgementThroughJobCompletion() = runBlocking {
        val interruption = VoiceBargeInCancellation(Uuid.random(), "call")
        var completionCause: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            catch (cancelled: VoiceBargeInCancellation) {
                withContext(NonCancellable) { cancelled.partialSafelySaved = true }
                throw cancelled
            }
        }
        job.invokeOnCompletion { completionCause = it }
        job.cancel(interruption)
        job.join()
        assertSame(interruption, completionCause)
        assertTrue(completionCause!!.isSavedVoiceInterruption())
    }

    @Test fun cancelledBeforeDispatchNeverEntersRecoveryBodyAndNeedsCompletionSideClassification() = runBlocking {
        val queue = MessageQueue()
        queue.enqueue(text("next turn"), voiceCallId = "call", voiceCallKind = "turn")
        val interruption = VoiceBargeInCancellation(Uuid.random(), "call")
        var entered = false
        val job = launch {
            entered = true
            try { awaitCancellation() }
            catch (_: VoiceBargeInCancellation) { fail("The body never entered") }
        }
        job.invokeOnCompletion { cause ->
            if (!entered && cause === interruption && canAcknowledgeVoiceInterruption(
                    partialSaved = true, queuePaused = queue.state.value.paused,
                    recoveryBlocked = false, unknownToolIds = emptySet())) interruption.partialSafelySaved = true
            queue.afterGenerationFailure(checkNotNull(cause), partialSnapshotSaved = true)
        }
        job.cancel(interruption)
        job.join()
        assertFalse(entered)
        assertTrue(interruption.isSavedVoiceInterruption())
        assertFalse(queue.state.value.paused)
        assertNotNull(queue.takeNext())
    }

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private class Store : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes.remove(conversationId) }
    }

    /** Fake repository boundary around the actual journal+queue, mirroring required commit order. */
    private class Recovery {
        val store = Store()
        val journal = GenerationCheckpointJournal(store)
        val utterance = UIMessage.user("synthetic voice utterance")
        var stored = Conversation(assistantId = Uuid.random(), messageNodes = listOf(utterance.toMessageNode()))
        val handle = journal.begin(stored, prefixCount = 0)
        val interruption = VoiceBargeInCancellation(utterance.id, "call")
        val queue = MessageQueue()
        val followingId = Uuid.random()
        val events = mutableListOf<String>()
        var failCommit = false
        var syntheticToolExecutions = 0
        private val toolMessage = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(tool(completed = false))).toMessageNode()

        init { queue.enqueue(listOf(UIMessagePart.Text("following voice input")), id = followingId,
            voiceCallId = "call", voiceCallKind = "turn") }

        fun checkpoint(status: GenerationToolStatus) {
            if (status == GenerationToolStatus.COMPLETED) syntheticToolExecutions++
            val response = toolMessage.copy(messages = listOf(toolMessage.currentMessage.copy(
                parts = listOf(tool(completed = status == GenerationToolStatus.COMPLETED)))))
            journal.checkpoint(handle, stored.assistantId, stored.compactionEpoch,
                stored.messageNodes + response, GenerationToolTransition("write-once", "fake_write", status))
        }

        fun recover() {
            var saved = false
            try {
                val recovery = checkNotNull(journal.recover(stored))
                if (recovery.unknownToolIds.isNotEmpty()) queue.pause()
                if (failCommit) { events += "room.failed"; throw IOException("synthetic commit failure") }
                stored = recovery.conversation
                events += "room.commit"
                journal.clearAfterDurableCommit(recovery.handle, stored)
                events += "journal.clear"
                if (canAcknowledgeVoiceInterruption(true, queue.state.value.paused, false, recovery.unknownToolIds))
                    interruption.partialSafelySaved = true
                saved = true
            } catch (_: Exception) { queue.pause() }
            queue.afterGenerationFailure(interruption, saved)
        }

        private fun tool(completed: Boolean) = UIMessagePart.Tool("write-once", "fake_write", "{}",
            if (completed) listOf(UIMessagePart.Text("exact synthetic receipt")) else emptyList())
    }
}
