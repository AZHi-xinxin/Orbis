package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.checkpoint.GenerationCheckpointStore
import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.prepareOrbisManualContext
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ManualContinuationContextTest {
    private class Store : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { this.bytes[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { bytes.remove(conversationId) }
    }
    private fun base() = Conversation(assistantId = Uuid.random(), title = "synthetic",
        messageNodes = List(6) { UIMessage.user("synthetic $it").toMessageNode() })

    private class Fixture(scope: CoroutineScope, val baseline: Conversation, initial: Conversation = baseline) {
        val bytes = Store()
        val journal = HumanContinuationJournal(bytes)
        var disk = baseline
        var rejectRetirement = false
        lateinit var lane: HumanContinuationLane
        val storage = HumanContinuationContextStore(baseline, baseline, Uuid.random(), Mutex(), journal,
            read = { disk }, proveBaseline = { _, _ -> false },
            commit = { expected, next ->
                if (sameDurableGenerationSnapshot(expected, disk)) disk.copy(messageNodes = next.messageNodes,
                    compactionEpoch = next.compactionEpoch,
                    updateAt = disk.updateAt.plusSeconds(1), chatSuggestions = emptyList()).also { disk = it } else null
            }, canWrite = { true }, project = { lane.projectContext(it) }, latest = { lane.state.value },
            onStored = {}, onRebased = { a, b -> lane.acceptCompaction(a, b) },
            retireOldJournal = { check(!rejectRetirement) { "synthetic unverified old tools" } })
        init {
            lane = HumanContinuationLane(baseline, scope, persist = { storage.save(it) }, onFailure = { throw it },
                initial = initial, checkpoint = { storage.checkpoint(it) }, contextStore = storage)
        }
    }

    @Test fun `manual commit archives displayed continuation and rebases fresh input arriving during commit`() = runTest {
        val original = base()
        val displayed = original.copy(messageNodes = original.messageNodes + UIMessage.assistant("new visible reply").toMessageNode())
        val f = Fixture(backgroundScope, original, displayed)
        val preview = prepareOrbisManualContext(displayed, 4)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val archive = Uuid.random()
        var archived: Conversation? = null
        val result = async {
            commitManualContinuation(f.lane, preview, requireReady = {}) { durable ->
                archived = durable
                entered.complete(Unit); release.await()
                val after = durable.copy(messageNodes = preview.replacementNodes, compactionEpoch = durable.compactionEpoch + 1)
                f.disk = after
                after to archive
            }
        }
        entered.await()
        f.lane.send(UIMessage.user("fresh input during local transaction"), answer = false) { _, _, _ -> error("no model") }
        runCurrent()
        release.complete(Unit)
        assertEquals(archive, result.await())
        runCurrent()
        assertEquals(displayed.messageNodes, archived!!.messageNodes)
        assertEquals(1L, f.lane.state.value.compactionEpoch)
        assertEquals(preview.replacementNodes, f.lane.state.value.messageNodes.dropLast(1))
        assertEquals("fresh input during local transaction", f.lane.state.value.currentMessages.last().toText())
        assertTrue(f.storage.save(f.lane.state.value))
        f.storage.checkpoint(f.lane.state.value)
        assertEquals(f.lane.state.value.messageNodes, f.disk.messageNodes)
        assertNull(HumanContinuationJournal(f.bytes).recover(f.disk))
    }

    @Test fun `stale preview cannot archive a changed continuation`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        f.lane.send(UIMessage.user("later input"), answer = false) { _, _, _ -> error("no model") }
        runCurrent()
        var rewrites = 0
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, {}) { rewrites++; it to Uuid.random() } }.isFailure)
        assertEquals(0, rewrites)
        assertEquals(0L, f.disk.compactionEpoch)
        assertEquals("later input", f.lane.state.value.currentMessages.last().toText())
    }

    @Test fun `manual transaction refuses same node regeneration and branch selection but allows appended input`() = runTest {
        val reply = MessageNode(messages = listOf(UIMessage.assistant("original reply"), UIMessage.assistant("other reply")))
        val original = base().let { it.copy(messageNodes = it.messageNodes + reply) }
        val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val result = async {
            commitManualContinuation(f.lane, preview, {}) { durable ->
                entered.complete(Unit); release.await()
                val after = durable.copy(messageNodes = preview.replacementNodes, compactionEpoch = durable.compactionEpoch + 1)
                f.disk = after
                after to Uuid.random()
            }
        }
        entered.await()
        var generated = false
        assertNotNull(f.lane.regenerate(reply.currentMessage) { _, _, _ -> generated = true })
        assertTrue(runCatching { f.lane.selectBranch(reply.id, 1) }.isFailure)
        assertEquals(0, f.lane.state.value.messageNodes.last().selectIndex)
        f.lane.send(UIMessage.user("fresh input is allowed"), answer = false) { _, _, _ -> error("no model") }
        runCurrent()
        assertFalse(generated)
        release.complete(Unit); result.await(); runCurrent()
        assertEquals("fresh input is allowed", f.lane.state.value.currentMessages.last().toText())
        assertEquals(reply.messages.map { it.id }, f.lane.state.value.messageNodes.first { it.id == reply.id }.messages.map { it.id })
        f.lane.selectBranch(reply.id, 1) // The reservation was released after successful commit.
        assertEquals(1, f.lane.state.value.messageNodes.first { it.id == reply.id }.selectIndex)
        assertNull(f.lane.regenerate(f.lane.state.value.currentMessages.last()) { _, _, _ -> generated = true })
        runCurrent()
        assertTrue(generated)
    }

    @Test fun `failed archive transaction retains original epoch and continuation`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, {}) { error("synthetic transaction rollback") } }.isFailure)
        assertEquals(original.messageNodes, f.disk.messageNodes)
        assertEquals(original.messageNodes, f.lane.state.value.messageNodes)
        assertEquals(0L, f.disk.compactionEpoch)
        assertTrue(f.storage.save(f.lane.state.value))
        // Rollback must release the same-node guard too, not just leave sends usable.
        assertNull(f.lane.regenerate(original.currentMessages.last()) { _, _, _ -> })
        runCurrent()
    }

    @Test fun `unverified old receipts prevent manual commit without changing epoch`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        f.rejectRetirement = true
        var rewrites = 0
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, {}) { rewrites++; it to Uuid.random() } }.isFailure)
        assertEquals(0, rewrites)
        assertEquals(original.messageNodes, f.disk.messageNodes)
        assertEquals(0L, f.lane.state.value.compactionEpoch)
        assertNull(f.lane.regenerate(original.currentMessages.last()) { _, _, _ -> })
        runCurrent()
    }

    @Test fun `durable title change rejects old preview then a refreshed preview can commit`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        f.disk = original.copy(title = "new durable title")
        var rewrites = 0
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, {}) { rewrites++; it to Uuid.random() } }.isFailure)
        assertEquals(0, rewrites)
        assertEquals("new durable title", f.disk.title)
        assertEquals(0L, f.disk.compactionEpoch)
        assertEquals("new durable title", f.lane.state.value.title)
        runCurrent()
        val refreshed = prepareOrbisManualContext(f.lane.state.value, 3)
        commitManualContinuation(f.lane, refreshed, {}) { durable ->
            val after = durable.copy(messageNodes = refreshed.replacementNodes, compactionEpoch = durable.compactionEpoch + 1)
            f.disk = after
            after to Uuid.random()
        }
        assertEquals(1L, f.disk.compactionEpoch)
        assertEquals("new durable title", f.lane.state.value.title)
    }

    @Test fun `verified metadata refresh retains concurrent input and late producer updates`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val preview = prepareOrbisManualContext(original, 3)
        val captured = f.lane.captureManualContext(preview)
        try {
            val entered = CompletableDeferred<Conversation>()
            val release = CompletableDeferred<Unit>()
            f.lane.send(UIMessage.user("concurrent input")) { request, _, publish ->
                entered.complete(request); release.await()
                publish(request.copy(messageNodes = request.messageNodes + UIMessage.assistant("late reply").toMessageNode()))
            }
            val oldRequest = entered.await()
            f.disk = f.disk.copy(title = "verified durable title")
            f.lane.refreshManualContextMetadata(captured, original.copy(title = "verified durable title"))
            assertEquals("verified durable title", f.lane.state.value.title)
            assertEquals(oldRequest.messageNodes, f.lane.state.value.messageNodes)
            release.complete(Unit); runCurrent()
            assertEquals("verified durable title", f.lane.state.value.title)
            assertEquals("concurrent input", f.lane.state.value.currentMessages[f.lane.state.value.currentMessages.lastIndex - 1].toText())
            assertEquals("late reply", f.lane.state.value.currentMessages.last().toText())
        } finally { f.lane.releaseManualContext() }
    }

    @Test fun `metadata refresh cannot replace changed messages owner or a newer header`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val captured = f.lane.captureManualContext(prepareOrbisManualContext(original, 3))
        try {
            f.lane.refreshManualContextMetadata(captured, original.copy(assistantId = Uuid.random(), title = "other owner"))
            assertEquals(original, f.lane.state.value)
            f.lane.refreshManualContextMetadata(captured, original.copy(title = "changed history", messageNodes = original.messageNodes.dropLast(1)))
            assertEquals(original, f.lane.state.value)
            f.lane.state.value = original.copy(title = "newer local header")
            f.lane.refreshManualContextMetadata(captured, original.copy(title = "older durable header"))
            assertEquals("newer local header", f.lane.state.value.title)
        } finally { f.lane.releaseManualContext() }
    }

    @Test fun `active producer or changed host cannot start manual rewrite`() = runTest {
        val original = base(); val f = Fixture(backgroundScope, original)
        val waiting = CompletableDeferred<Unit>()
        f.lane.send(null) { _, _, _ -> waiting.await() }; runCurrent()
        val preview = prepareOrbisManualContext(f.lane.state.value, 3)
        var rewrites = 0
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, {}) { rewrites++; it to Uuid.random() } }.isFailure)
        waiting.complete(Unit); runCurrent()
        assertTrue(runCatching { commitManualContinuation(f.lane, preview, { error("changed host") }) {
            rewrites++; it to Uuid.random()
        } }.isFailure)
        assertEquals(0, rewrites)
    }

    @Test fun `fork targets displayed continuation without truncating or retiring the original`() {
        val main = base()
        val latest = UIMessage.assistant("only in current continuation").toMessageNode()
        val continuation = main.copy(messageNodes = main.messageNodes + latest)
        val (source, nodes) = forkDisplayedPrefix(main, continuation, latest.currentMessage.id)
        assertSame(continuation, source)
        assertEquals(continuation.messageNodes, nodes)
        assertEquals(6, main.messageNodes.size)
        assertEquals(7, continuation.messageNodes.size)
        assertEquals(main to main.messageNodes.take(2), forkDisplayedPrefix(main, null, main.currentMessages[1].id))
    }

    @Test fun `fork rejects absent target or mismatched owner instead of silently falling back to old main`() {
        val main = base()
        val shorter = main.copy(messageNodes = main.messageNodes.dropLast(1))
        assertTrue(runCatching { forkDisplayedPrefix(main, shorter, main.currentMessages.last().id) }.isFailure)
        assertTrue(runCatching { forkDisplayedPrefix(main, main.copy(assistantId = Uuid.random()), main.currentMessages.first().id) }.isFailure)
        assertTrue(runCatching { forkDisplayedPrefix(main, null, Uuid.random()) }.isFailure)
    }
}
