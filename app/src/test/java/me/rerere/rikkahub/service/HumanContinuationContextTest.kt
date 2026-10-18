package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.ai.checkpoint.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class HumanContinuationContextTest {
    @Test fun `late durable four character chunk merges all continuation turns and permits compaction`() = runTest {
        val oldStore = Store(); val continuationStore = Store()
        val old = GenerationCheckpointJournal(oldStore)
        val room = base().let { it.copy(messageNodes = it.messageNodes.take(1)) }
        val handle = old.begin(room)
        val tools = List(3) { me.rerere.ai.ui.UIMessagePart.Tool("done-$it", "test_tool", "{}",
            output = listOf(me.rerere.ai.ui.UIMessagePart.Text("completed $it"))) }
        val reply = UIMessage.assistant("").copy(parts = tools +
            me.rerere.ai.ui.UIMessagePart.Reasoning("reasoning before last chunk", finishedAt = null))
        val visible = room.copy(messageNodes = room.messageNodes + reply.toMessageNode())
        val full = visible.copy(messageNodes = room.messageNodes + visible.messageNodes.last().copy(messages = listOf(
            reply.copy(parts = tools + (reply.parts.last() as me.rerere.ai.ui.UIMessagePart.Reasoning).copy(
                reasoning = "reasoning before last chunktail")))))
        old.checkpoint(handle, room.assistantId, room.compactionEpoch, full.messageNodes)
        assertFalse(old.provesInFlightBaseline(room, visible)) // Reproduces the former exact-byte dead end.
        val latest = visible.copy(messageNodes = visible.messageNodes.map { node -> node.copy(messages = node.messages.map {
            it.finishReasoning()
        }) } + List(135) { UIMessage.user("synthetic later $it").toMessageNode() })
        val journal = HumanContinuationJournal(continuationStore); val runId = Uuid.random()
        journal.checkpoint(runId, visible, latest)
        val recovery = journal.recover(room, old::provesInFlightBaseline)!!
        assertNull(recovery.durableBaseline)
        var disk = room
        lateinit var lane: HumanContinuationLane
        val storage = HumanContinuationContextStore(recovery.baseline, recovery.durableBaseline ?: recovery.baseline,
            runId, Mutex(), journal, read = { disk }, proveBaseline = old::provesInFlightBaseline,
            commit = { expected, next -> if (sameDurableGenerationSnapshot(expected, disk)) next.also { disk = it } else null },
            canWrite = { true }, project = { lane.projectContext(it) }, latest = { lane.state.value },
            onStored = {}, onRebased = { a,b -> lane.acceptCompaction(a,b) },
            retireOldJournal = { assertTrue(old.clearSettledInDurableContinuation(it, true)) },
            reconcileStream = old::streamedContinuationEnrichment, onEnriched = { lane.acceptStreamEnrichment(it) })
        lane = HumanContinuationLane(recovery.baseline, backgroundScope, persist = { false }, onFailure = { throw it },
            initial = recovery.snapshot, checkpointId = runId)
        assertTrue(storage.save(lane.state.value))
        assertEquals(137, disk.messageNodes.size)
        assertEquals("reasoning before last chunktail", (disk.currentMessages[1].parts.last() as me.rerere.ai.ui.UIMessagePart.Reasoning).reasoning)
        assertEquals(tools, disk.currentMessages[1].getTools())
        assertEquals(latest.messageNodes.drop(2), disk.messageNodes.drop(2))
        assertEquals(disk.messageNodes, lane.projectContext(latest)!!.messageNodes) // stale publishers keep the extra chunk
        journal.checkpoint(runId, recovery.baseline, disk)
        assertNull(journal.recover(disk)) // Saved continuation is now visible to Room/Web/restart.
        val after = storage.compact({ lane.state.value }) { source, durable ->
            assertEquals(source.messageNodes, durable.messageNodes)
            compacted(source).also { disk = it }
        }
        assertEquals(1L, after.compactionEpoch)
        assertFalse(old.hasCheckpoint(room.id))
        assertTrue(storage.save(latest))
        assertEquals(after.messageNodes, disk.messageNodes)
    }

    @Test fun `checkpoint selection proof retains every stored version and does not change browsing`() {
        val source = base().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) +
            c.messageNodes.last().copy(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two")), selectIndex = 1)) }
        val browsed = source.copy(messageNodes = source.messageNodes.map { it.copy(selectIndex = 0) })
        val proof = browsed.withCheckpointSelections(source)
        assertEquals(source.messageNodes, proof.messageNodes)
        assertEquals("one", browsed.currentMessages.last().toText())
        assertEquals(browsed.messageNodes.flatMap { it.messages }, proof.messageNodes.flatMap { it.messages })
        assertTrue(runCatching { browsed.copy(compactionEpoch = 1).withCheckpointSelections(source) }.isFailure)
        val changed = browsed.copy(messageNodes = browsed.messageNodes.dropLast(1) +
            browsed.messageNodes.last().copy(messages = listOf(UIMessage.assistant("unrelated")), selectIndex = 0))
        assertEquals(changed.messageNodes, changed.withCheckpointSelections(source).messageNodes)
    }

    @Test fun `browsing previous regeneration survives later publish and next human request`() = runTest {
        val source = base().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) +
            c.messageNodes.last().copy(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two"),
                UIMessage.assistant("three")), selectIndex = 2)) }
        val f = Fixture(backgroundScope, source)
        val keep = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        var publishLate: ((Conversation) -> Unit)? = null
        f.lane.send(null) { _, _, publish -> publishLate = publish; entered.complete(Unit); keep.await() }
        entered.await()
        f.lane.selectBranch(source.messageNodes.last().id, 0)
        publishLate!!(source)
        assertEquals("one", f.lane.state.value.currentMessages.last().toText())
        assertEquals(source.messageNodes.last().messages, f.lane.state.value.messageNodes.last().messages)
        val input = CompletableDeferred<Conversation>()
        f.lane.send(UIMessage.user("continue chosen reply")) { snapshot, _, _ -> input.complete(snapshot) }
        assertEquals("one", input.await().currentMessages[1].toText())
        keep.complete(Unit)
    }

    @Test fun `fresh regeneration after browsing selects new alternative while preserving old versions`() = runTest {
        val source = base().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) +
            c.messageNodes.last().copy(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two")), selectIndex = 1)) }
        val f = Fixture(backgroundScope, source)
        f.lane.selectBranch(source.messageNodes.last().id, 0)
        assertNull(f.lane.regenerate(f.lane.state.value.currentMessages.last()) { snapshot, _, publish ->
            publish(snapshot.copy(messageNodes = snapshot.messageNodes + UIMessage.assistant("three").toMessageNode()))
        })
        runCurrent()
        assertEquals("three", f.lane.state.value.currentMessages.last().toText())
        assertEquals(listOf("one", "two", "three"), f.lane.state.value.messageNodes.last().messages.map { it.toText() })
    }

    private class Store : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes.remove(conversationId) }
    }
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("old input").toMessageNode(), UIMessage.assistant("old answer").toMessageNode()))
    private fun Conversation.append(text: String) = copy(messageNodes = messageNodes + UIMessage.user(text).toMessageNode())
    private fun compacted(source: Conversation) = source.copy(compactionEpoch = source.compactionEpoch + 1,
        messageNodes = listOf(UIMessage.assistant("summary").toMessageNode()) + source.messageNodes.takeLast(1))

    private inner class Fixture(scope: CoroutineScope, val source: Conversation) {
        val bytes = Store()
        val journal = HumanContinuationJournal(bytes)
        val runId = Uuid.random()
        var disk = source
        val mutex = Mutex()
        lateinit var lane: HumanContinuationLane
        var retiring = 0
        val storage = HumanContinuationContextStore(source, source, runId, mutex, journal,
            read = { disk }, proveBaseline = { _, _ -> false },
            commit = { expected, next ->
                if (sameDurableGenerationSnapshot(expected, disk)) next.also { disk = it } else null
            }, canWrite = { true }, project = { lane.projectContext(it) }, latest = { lane.state.value },
            onStored = {}, onRebased = { before, after -> lane.acceptCompaction(before, after) },
            retireOldJournal = { retiring++ })
        init {
            lane = HumanContinuationLane(source, scope, persist = { storage.save(it) }, onFailure = { throw it },
                checkpointId = runId, checkpoint = { storage.checkpoint(it) }, contextStore = storage)
        }
        suspend fun compact(capture: () -> Conversation = { lane.state.value },
            gate: suspend () -> Unit = {}): Conversation = storage.compact(capture) { before, _ ->
            gate()
            compacted(before).also { disk = it }
        }
    }

    @Test fun `new human turn and streamed reply proceed during commit and survive late old context publication`() = runTest {
        val f = Fixture(backgroundScope, base())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val done = async { f.compact { entered.complete(Unit); release.await() } }
        entered.await()
        val replied = CompletableDeferred<Unit>(); val keepAlive = CompletableDeferred<Unit>()
        var laterPublish: ((Conversation) -> Unit)? = null
        lateinit var laterSnapshot: Conversation
        f.lane.send(UIMessage.user("next input")) { snapshot, _, publish ->
            laterSnapshot = snapshot.copy(messageNodes = snapshot.messageNodes + UIMessage.assistant("new answer").toMessageNode())
            laterPublish = publish
            publish(laterSnapshot); replied.complete(Unit); keepAlive.await()
        }
        replied.await() // No commit gate release: generation must already be usable.
        assertEquals("new answer", f.lane.state.value.currentMessages.last().toText())
        release.complete(Unit); done.await()
        assertEquals(1L, f.lane.state.value.compactionEpoch)
        assertEquals(listOf("summary", "old answer", "next input", "new answer"),
            f.lane.state.value.currentMessages.map { it.toText() })
        laterPublish!!(laterSnapshot.copy(messageNodes = laterSnapshot.messageNodes.dropLast(1) +
            laterSnapshot.messageNodes.last().let { n -> n.copy(messages = listOf(n.currentMessage.copy(
                parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("new answer finished"))))) }))
        keepAlive.complete(Unit); runCurrent()
        assertTrue(f.storage.save(f.lane.state.value))
        assertEquals(1L, f.disk.compactionEpoch)
        assertEquals("new answer finished", f.disk.currentMessages.last().toText())
        assertFalse(f.disk.currentMessages.any { it.toText() == "old input" })
        assertNull(f.lane.projectContext(f.source.copy(messageNodes = emptyList())))
    }

    @Test fun `failed rewrite keeps original and newer input in recovery copy`() = runTest {
        val f = Fixture(backgroundScope, base())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val attempt = async { runCatching { f.compact { entered.complete(Unit); release.await(); error("transaction rollback") } } }
        entered.await()
        f.lane.send(UIMessage.user("new during rollback"), answer = false) { _, _, _ -> }
        release.complete(Unit)
        assertTrue(attempt.await().isFailure)
        assertEquals(0L, f.disk.compactionEpoch)
        assertEquals("old input", f.disk.currentMessages.first().toText())
        val recovered = f.journal.recover(f.disk)
        // The independent observer may have already saved the newest input instead.
        assertEquals("new during rollback", (recovered?.snapshot ?: f.disk).currentMessages.last().toText())
    }

    @Test fun `committed summary plus unsaved new tail is recoverable after restart`() = runTest {
        val f = Fixture(backgroundScope, base())
        f.compact { f.lane.send(UIMessage.user("fresh while writing"), answer = false) { _, _, _ -> } }
        val recovered = f.journal.recover(f.disk)!!
        assertEquals(1L, recovered.snapshot.compactionEpoch)
        assertEquals("summary", recovered.snapshot.currentMessages.first().toText())
        assertEquals("fresh while writing", recovered.snapshot.currentMessages.last().toText())
        assertEquals(f.disk.messageNodes, recovered.durableBaseline!!.messageNodes)
    }

    @Test fun `compaction and checkpoint observers reject stale precompact snapshots without reverting epoch`() = runTest {
        val f = Fixture(backgroundScope, base())
        val after = f.compact()
        assertTrue(f.storage.save(f.source))
        assertEquals(after.messageNodes, f.disk.messageNodes)
        assertTrue(f.storage.checkpoint(f.source))
        assertNull(f.journal.recover(f.disk))
        assertEquals(1L, f.disk.compactionEpoch)
    }

    @Test fun `two compactions advance anchors and late observer cannot resurrect either prefix`() = runTest {
        val f = Fixture(backgroundScope, base())
        val first = f.compact()
        val second = f.compact()
        assertEquals(2L, second.compactionEpoch)
        assertTrue(f.storage.save(f.source))
        assertTrue(f.storage.checkpoint(first))
        assertEquals(second.messageNodes, f.disk.messageNodes)
        assertNull(f.journal.recover(f.disk))
    }

    @Test fun `busy writer only rejects this compact and never prevents human send`() = runTest {
        val f = Fixture(backgroundScope, base())
        f.mutex.lock()
        val attempt = async { runCatching { f.compact() } }
        val replied = CompletableDeferred<Unit>()
        f.lane.send(UIMessage.user("immediate")) { _, _, _ -> replied.complete(Unit) }
        replied.await()
        advanceTimeBy(1_501); runCurrent()
        assertTrue(attempt.await().exceptionOrNull() is TimeoutCancellationException)
        assertEquals("immediate", f.lane.state.value.currentMessages.last().toText())
        f.mutex.unlock()
    }

    @Test fun `owner replacement before capture does not commit stale compact`() = runTest {
        val f = Fixture(backgroundScope, base())
        var owner = -1L
        f.lane.send(UIMessage.user("first")) { _, turn, _ -> owner = turn }
        runCurrent()
        val before = f.lane.state.value.currentMessages
        f.lane.send(UIMessage.user("second"), answer = false) { _, _, _ -> }
        assertTrue(runCatching { f.compact(capture = { f.lane.captureCompaction(owner, before) }) }.isFailure)
        assertEquals(0L, f.disk.compactionEpoch)
    }

    @Test fun `rebase validates identity epoch and selected prefix`() {
        val before = base(); val after = compacted(before)
        val rebase = ContinuationContextRebase(before, after)
        assertNull(rebase.project(before.copy(assistantId = Uuid.random())))
        assertNull(rebase.project(before.copy(compactionEpoch = 2)))
        assertNull(rebase.project(before.copy(messageNodes = listOf(UIMessage.user("other").toMessageNode()))))
        val next = before.append("tail")
        assertEquals(after.messageNodes + next.messageNodes.takeLast(1), rebase.project(next)?.messageNodes)
    }
}
