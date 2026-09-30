package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.hostToolFailure
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.applyEventPresentation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.persistOrbisEventPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * Cross-lifecycle contract tests: real journal, synthetic repository and atomic memory store.
 * The driver makes ChatService's intended call ordering explicit; this is NOT a Room/ChatService
 * instrumentation test and does not prove Android lifecycle wiring. No disk, API or real tools.
 */
class GenerationRecoveryWorkflowTest {
    /** Current-behavior reproduction, not a fix: use real card helper + journal, synthetic DB. */
    @Test fun `stage46 event card change after baseline makes completed reply conflict and blocks later sends`() = runBlocking {
        val run = Workflow()
        val seed = run.repository.stored
        val event = seed.currentMessages.first().copy(orbisEvent = OrbisEventMetadata(
            "synthetic-receipt", "synthetic-source", "synthetic-event", 1000L, occurredAt = 900L))
        val original = seed.copy(messageNodes = listOf(seed.messageNodes.first().copy(messages = listOf(event))) +
            seed.messageNodes.drop(1))
        run.repository.save(original)
        run.session.visible = original
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        val live = MutableStateFlow(final)
        val edit = OrbisEventPresentationEdit(original.messageNodes.first().id, event.id,
            checkNotNull(event.orbisEvent), event.toText(), read = true, collapsed = false)
        persistOrbisEventPresentation(live, original.assistantId, edit) {
            run.repository.save(run.repository.read().applyEventPresentation(original.assistantId, edit))
        }
        run.session.visible = live.value
        val error = runCatching { run.finish(live.value) }.exceptionOrNull()
        assertTrue(error is GenerationCheckpointException)
        assertEquals("database_prefix_changed", (error as GenerationCheckpointException).code)
        // The full reply and completed receipt did reach the synthetic DB; the journal remains.
        assertEquals(final.messageNodes.drop(1), run.repository.stored.messageNodes.drop(1))
        assertEquals(original.currentMessages.first().parts, run.repository.stored.currentMessages.first().parts)
        assertTrue(run.repository.stored.currentMessages.first().orbisEvent!!.read)
        val journalBefore = run.store.payload(original.id)
        val databaseBefore = run.repository.stored
        assertFalse(run.settlePreviousGeneration())
        assertTrue(run.session.queuePaused)
        assertTrue(run.session.recoveryBlocked)
        assertNotNull(runCatching { run.beginExplicitRequest() }.exceptionOrNull())
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
        assertEquals(databaseBefore, run.repository.stored)
        assertArrayEquals(journalBefore, run.store.payload(original.id))
        assertFalse(run.events.contains("journal.delete"))
    }

    @Test fun `stage46 editing history with leftover completed receipt blocks recovery without replay or erasure`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        // No successful final DB save/clear yet: an independent history edit changes the prefix.
        val changedFirst = original.messageNodes.first().let { node -> node.copy(messages =
            node.messages.map { it.copy(translation = "synthetic translated history") }) }
        val edited = original.copy(messageNodes = listOf(changedFirst) + original.messageNodes.drop(1))
        run.repository.save(edited)
        val before = run.store.payload(original.id)
        assertFalse(run.settlePreviousGeneration())
        assertEquals(edited, run.repository.stored)
        assertArrayEquals(before, run.store.payload(original.id))
        assertTrue(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertNotNull(runCatching { run.beginExplicitRequest() }.exceptionOrNull())
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
        assertFalse(run.events.contains("journal.delete"))
        // The completed result remains in the protected tail, not re-executed or removed.
        val inspected = run.journal.recover(original)!!
        assertEquals(final.messageNodes.last(), inspected.conversation.messageNodes.last())
        assertTrue(inspected.unknownToolIds.isEmpty())
        assertArrayEquals(before, run.store.payload(original.id))
    }

    @Test fun `stage46 transient favorite edit with leftover receipt recovers and does not replay tools`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        val edited = original.copy(messageNodes = listOf(original.messageNodes.first().copy(isFavorite = true)) +
            original.messageNodes.drop(1))
        run.repository.save(edited)
        assertTrue(run.settlePreviousGeneration())
        assertTrue(run.repository.stored.messageNodes.first().isFavorite)
        assertEquals(final.messageNodes.last(), run.repository.stored.messageNodes.last())
        assertFalse(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertFalse(run.journal.hasCheckpoint(original.id))
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
    }

    @Test fun `failed final database commit recovers in the same initialized session before another send`() {
        val run = Workflow()
        val original = run.repository.stored
        val session = run.session
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        run.repository.failWrites = 1

        assertNotNull(runCatching { run.finish(final) }.exceptionOrNull())
        assertSame(original, run.repository.stored)
        assertTrue(run.journal.hasCheckpoint(original.id))
        assertFalse(run.events.contains("journal.delete"))
        assertEquals(1, run.toolEffects)

        assertTrue(run.settlePreviousGeneration())
        assertSame(session, run.session)
        assertTrue(session.initialized)
        assertTrue(session.queuePaused)
        assertFalse(session.recoveryBlocked)
        assertEquals(final, run.repository.stored)
        assertEquals(final, session.visible)
        assertFalse(run.journal.hasCheckpoint(original.id))
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
        assertOrdered(run.events, "room.save.failed", "room.commit", "journal.delete")

        run.beginExplicitRequest()
        assertEquals(2, run.providerRequests)
        assertEquals(1, run.toolEffects) // Explicit new send must not replay the recovered call.
    }

    @Test fun `failed recovery commit keeps checkpoint and can be retried without replaying effects`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        run.repository.failWrites = 2 // Final write, then recovery write.

        assertNotNull(runCatching { run.finish(final) }.exceptionOrNull())
        val receipt = run.store.payload(original.id)
        assertFalse(run.settlePreviousGeneration())
        assertSame(original, run.repository.stored)
        assertSame(original, run.session.visible)
        assertTrue(run.session.queuePaused)
        assertTrue(run.session.recoveryBlocked)
        assertArrayEquals(receipt, run.store.payload(original.id))
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
        assertFalse(run.events.contains("journal.delete"))

        // The same session gets one safe persistence retry, not a provider/tool retry.
        assertTrue(run.settlePreviousGeneration())
        assertFalse(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertEquals(final, run.repository.stored)
        assertFalse(run.journal.hasCheckpoint(original.id))
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
    }

    @Test fun `database success followed by clear failure settles idempotently without duplicate writes`() {
        val run = Workflow()
        run.beginExplicitRequest()
        val final = run.completeSyntheticTool()
        run.store.failDeletes = 1

        assertNotNull(runCatching { run.finish(final) }.exceptionOrNull())
        assertEquals(final, run.repository.stored)
        assertEquals(1, run.repository.successfulWrites)
        assertTrue(run.journal.hasCheckpoint(final.id))

        assertTrue(run.settlePreviousGeneration())
        assertEquals(final, run.session.visible)
        assertEquals(1, run.repository.successfulWrites)
        assertEquals(final.messageNodes.map { it.id }, run.session.visible.messageNodes.map { it.id })
        assertEquals(1, run.toolEffects)
        assertEquals(1, run.providerRequests)
        assertTrue(run.session.queuePaused)
        assertFalse(run.journal.hasCheckpoint(final.id))
        assertOrdered(run.events, "room.commit", "journal.delete.failed", "journal.delete")
    }

    @Test fun `committed compact with interrupted journal rebase keeps new epoch and pauses old queue`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        run.completeSyntheticTool()
        val oldCheckpoint = run.store.payload(original.id)
        val compacted = original.copy(compactionEpoch = 1,
            messageNodes = listOf(UIMessage.assistant("synthetic committed summary").toMessageNode()))
        run.repository.save(compacted)
        run.session.visible = compacted
        run.store.failWrites = 1
        assertNotNull(runCatching {
            run.journal.rebaseAfterDurableCommit(checkNotNull(run.handle), run.repository.read())
        }.exceptionOrNull())
        assertArrayEquals(oldCheckpoint, run.store.payload(original.id))

        // A previous recovery failure must not leave a successfully settled session blocked.
        run.session.recoveryBlocked = true
        assertTrue(run.settlePreviousGeneration())
        assertEquals(compacted, run.repository.stored)
        assertEquals(compacted, run.session.visible)
        assertEquals(1L, run.session.visible.compactionEpoch)
        assertTrue(run.session.queuePaused)
        assertFalse(run.session.recoveryBlocked)
        assertEquals(1, run.repository.successfulWrites) // No old-epoch tail save occurred.
        assertEquals(1, run.providerRequests)
        assertEquals(1, run.toolEffects)
        assertFalse(run.journal.hasCheckpoint(original.id))
        assertOrdered(run.events, "room.commit", "journal.write.failed", "journal.delete")

        run.beginExplicitRequest()
        assertEquals(1L, checkNotNull(run.handle).epoch)
        assertEquals(compacted, run.session.visible)
    }

    @Test fun `higher database epoch does not permit discarding a possibly executed old tool`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        run.startSyntheticToolWithoutResult()
        val oldCheckpoint = run.store.payload(original.id)
        // Adversarial cross-writer state: production must never acknowledge a compact over STARTED.
        val newer = original.copy(compactionEpoch = 1,
            messageNodes = listOf(UIMessage.assistant("synthetic newer durable page").toMessageNode()))
        run.repository.save(newer)
        run.session.visible = newer

        assertFalse(run.settlePreviousGeneration())
        assertSame(newer, run.repository.stored)
        assertSame(newer, run.session.visible)
        assertTrue(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertArrayEquals(oldCheckpoint, run.store.payload(original.id))
        assertEquals(1, run.toolEffects)
        assertEquals(1, run.providerRequests)
        assertNotNull(runCatching { run.beginExplicitRequest() }.exceptionOrNull())
        assertEquals(1, run.providerRequests)
        assertFalse(run.events.contains("journal.delete"))
    }

    @Test fun `corrupt checkpoint blocks already initialized send before mutating conversation`() {
        val run = Workflow()
        val original = run.repository.stored
        run.beginExplicitRequest()
        run.startSyntheticToolWithoutResult()
        val corrupt = run.store.payload(original.id).copyOf(19)
        run.store.replaceForFault(original.id, corrupt)
        run.session.visible = original // Model the page reopened from its durable database.

        assertNotNull(runCatching { run.beginExplicitRequest() }.exceptionOrNull())
        assertTrue(run.session.initialized)
        assertTrue(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertSame(original, run.repository.stored)
        assertSame(original, run.session.visible)
        assertArrayEquals(corrupt, run.store.payload(original.id))
        assertEquals(0, run.repository.successfulWrites)
        assertEquals(1, run.toolEffects)
        assertEquals(1, run.providerRequests)
        assertFalse(run.events.contains("journal.delete"))
    }

    @Test fun `interrupted tool recovery persists explicit failure before clear without replay`() {
        val run = Workflow()
        run.beginExplicitRequest()
        run.startSyntheticToolWithoutResult()

        assertTrue(run.settlePreviousGeneration())
        val tool = run.repository.stored.currentMessages.last().getTools().single()
        assertEquals(HostToolFailure.INTERRUPTED, tool.hostToolFailure())
        assertFalse(tool.canResumeExecution)
        // isExecuted is a wire-completeness flag, NOT semantic success; assert the actual UI marker.
        val receipt = Json.parseToJsonElement((tool.output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(JsonPrimitive("failed"), receipt["status"])
        assertEquals(JsonNull, receipt["execution_performed"])
        assertEquals(1, run.toolEffects)
        assertEquals(1, run.providerRequests)
        assertTrue(run.session.queuePaused)
        assertFalse(run.journal.hasCheckpoint(run.session.visible.id))
        assertOrdered(run.events, "room.commit", "journal.delete")
    }

    @Test fun `changed database owner refuses recovery without overwriting either record`() {
        val run = Workflow()
        run.beginExplicitRequest()
        run.completeSyntheticTool()
        val checkpoint = run.store.payload(run.session.visible.id)
        val reassigned = run.repository.stored.copy(assistantId = Uuid.random())
        run.repository.save(reassigned)
        run.session.visible = reassigned

        assertFalse(run.settlePreviousGeneration())
        assertSame(reassigned, run.repository.stored)
        assertSame(reassigned, run.session.visible)
        assertTrue(run.session.recoveryBlocked)
        assertTrue(run.session.queuePaused)
        assertArrayEquals(checkpoint, run.store.payload(reassigned.id))
        assertEquals(1, run.repository.successfulWrites)
        assertEquals(1, run.toolEffects)
        assertEquals(1, run.providerRequests)
    }

    private fun assertOrdered(events: List<String>, vararg expected: String) {
        var after = -1
        expected.forEach { event ->
            after = events.indices.firstOrNull { it > after && events[it] == event }
                ?: throw AssertionError("Missing ordered event $event in $events")
        }
    }

    private class Session(var visible: Conversation) {
        val initialized = true
        var queuePaused = false
        var recoveryBlocked = false
    }

    private class Repository(initial: Conversation, private val events: MutableList<String>) {
        var stored = initial
            private set
        var failWrites = 0
        var successfulWrites = 0
            private set
        fun read(): Conversation = stored.also { events += "room.read" }
        fun save(snapshot: Conversation) {
            if (failWrites > 0) {
                failWrites--
                events += "room.save.failed"
                throw IOException("synthetic Room commit failure")
            }
            stored = snapshot
            successfulWrites++
            events += "room.commit"
        }
    }

    private class MemoryStore(private val events: MutableList<String>) : GenerationCheckpointStore {
        private val records = mutableMapOf<Uuid, ByteArray>()
        var failWrites = 0
        var failDeletes = 0
        override fun exists(conversationId: Uuid) = records.containsKey(conversationId)
        override fun read(conversationId: Uuid) = records[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            if (failWrites > 0) {
                failWrites--
                events += "journal.write.failed"
                throw IOException("synthetic interrupted atomic replace")
            }
            records[conversationId] = bytes.copyOf()
            events += "journal.write"
        }
        override fun delete(conversationId: Uuid) {
            if (failDeletes > 0) {
                failDeletes--
                events += "journal.delete.failed"
                throw IOException("synthetic clear failure")
            }
            check(records.remove(conversationId) != null)
            events += "journal.delete"
        }
        fun payload(id: Uuid) = records.getValue(id).copyOf()
        fun replaceForFault(id: Uuid, bytes: ByteArray) { records[id] = bytes.copyOf() }
    }

    private class Workflow {
        val events = mutableListOf<String>()
        val store = MemoryStore(events)
        val journal = GenerationCheckpointJournal(store)
        val repository = Repository(Conversation(assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("synthetic history"),
                UIMessage.assistant("synthetic prior reply"), UIMessage.user("synthetic current question"))
                .map { it.toMessageNode() }), events)
        val session = Session(repository.stored)
        var handle: GenerationCheckpointHandle? = null
        var providerRequests = 0
            private set
        var toolEffects = 0
            private set

        fun beginExplicitRequest() {
            check(settlePreviousGeneration()) { "synthetic send blocked by recovery" }
            handle = journal.begin(repository.read())
            providerRequests++
            events += "provider.request"
        }

        fun publish(snapshot: Conversation, transition: GenerationToolTransition? = null) {
            val current = checkNotNull(handle)
            journal.checkpoint(current, snapshot.assistantId, snapshot.compactionEpoch,
                snapshot.messageNodes.drop(current.prefixCount).dropLast(current.suffixCount), transition)
            session.visible = snapshot
            events += "ui.publish"
        }

        fun startSyntheticToolWithoutResult(): Conversation {
            val tool = UIMessagePart.Tool("synthetic-call", "synthetic_write", "{\"test\":true}")
            val request = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)).toMessageNode()
            val snapshot = session.visible.copy(messageNodes = session.visible.messageNodes + request)
            publish(snapshot, GenerationToolTransition(tool.toolCallId, tool.toolName, GenerationToolStatus.STARTED))
            toolEffects++ // In-memory fake effect only; no tool executor exists in recovery.
            events += "tool.effect"
            return snapshot
        }

        fun completeSyntheticTool(): Conversation {
            val started = startSyntheticToolWithoutResult()
            val node = started.messageNodes.last()
            val result = node.currentMessage.getTools().single().copy(
                output = listOf(UIMessagePart.Text("synthetic completed external receipt")))
            val finishedNode = node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(result,
                UIMessagePart.Text("synthetic complete answer")))))
            val final = started.copy(messageNodes = started.messageNodes.dropLast(1) + finishedNode)
            publish(final, GenerationToolTransition(result.toolCallId, result.toolName, GenerationToolStatus.COMPLETED))
            return final
        }

        fun finish(final: Conversation) {
            publish(final)
            repository.save(final)
            journal.clearAfterDurableCommit(checkNotNull(handle), repository.read())
            handle = null
        }

        /** The caller's lifecycle contract; actual journal decisions remain production code. */
        fun settlePreviousGeneration(): Boolean {
            var persisted: Conversation? = null
            return try {
                if (journal.hasCheckpoint(session.visible.id)) {
                    session.queuePaused = true
                    val current = repository.read().also { persisted = it }
                    val superseded = journal.discardSupersededByDurableEpoch(current)
                    val recovery = if (superseded) null else journal.recover(current)
                    if (recovery != null) {
                        if (recovery.changed) repository.save(recovery.conversation)
                        journal.clearAfterDurableCommit(recovery.handle, repository.read())
                    }
                    session.visible = repository.read()
                    session.recoveryBlocked = false
                    handle = null
                }
                check(!session.recoveryBlocked)
                true
            } catch (_: Exception) {
                session.queuePaused = true
                session.recoveryBlocked = true
                persisted?.let { session.visible = it }
                false
            }
        }
    }
}
