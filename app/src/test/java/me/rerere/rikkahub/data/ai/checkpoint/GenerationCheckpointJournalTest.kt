package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.hostToolFailure
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisEventPresentationEdit
import me.rerere.rikkahub.data.model.applyEventPresentation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.persistOrbisEventPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

/** Synthetic conversations and private temporary files only; no app, Room, model, or tools. */
class GenerationCheckpointJournalTest {
    @Test fun `rescue returns only the single exact tail node and does not clear journal`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val added = response(UIMessagePart.Text("a complete synthetic reply"))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + added)
        val bytes = store.bytes.getValue(original.id).copyOf()
        val broken = original.copy(messageNodes = original.messageNodes + added.copy(messages = emptyList()))
        assertEquals(added, j.previewDamagedNode(broken, added.id))
        assertArrayEquals(bytes, store.bytes.getValue(original.id))
    }

    @Test fun `rescue rejects changed epoch owner prefix and neighboring tail`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val added = response(UIMessagePart.Text("complete"))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + added)
        val broken = original.copy(messageNodes = original.messageNodes + added.copy(messages = emptyList()))
        reject { j.previewDamagedNode(broken.copy(compactionEpoch = 1), added.id) }
        reject { j.previewDamagedNode(broken.copy(assistantId = Uuid.random()), added.id) }
        reject { j.previewDamagedNode(broken.copy(messageNodes = broken.messageNodes.toMutableList().also {
            it[0] = UIMessage.user("changed protected history").toMessageNode()
        }), added.id) }
        reject { j.previewDamagedNode(broken.copy(messageNodes = broken.messageNodes.toMutableList().also {
            it[it.lastIndex - 1] = UIMessage.user("changed neighboring tail").toMessageNode()
        }), added.id) }
    }

    @Test fun `rescue refuses unknown external tool effects`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val added = response(tool())
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + added,
            transition(status = GenerationToolStatus.STARTED))
        val broken = original.copy(messageNodes = original.messageNodes + added.copy(messages = emptyList()))
        reject("unknown_tool_requires_manual_review") { j.previewDamagedNode(broken, added.id) }
    }

    @Test fun `rescue refuses missing branch or extra database row`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val added = response(UIMessagePart.Text("complete"))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + added)
        val broken = original.copy(messageNodes = original.messageNodes + added.copy(messages = emptyList(), selectIndex = 1))
        reject("database_branch_changed") { j.previewDamagedNode(broken, added.id) }
        reject { j.previewDamagedNode(broken.copy(messageNodes = broken.messageNodes + response(UIMessagePart.Text("newer"))), added.id) }
    }

    @Test fun `rescue refuses absent or corrupted checkpoint`() {
        val store = MemoryStore(); val j = journal(store); val original = base()
        reject("no_complete_recovery_copy") { j.previewDamagedNode(original, original.messageNodes.last().id) }
        store.bytes[original.id] = "bad private bytes".toByteArray()
        reject { j.previewDamagedNode(original, original.messageNodes.last().id) }
    }
    private class MemoryStore : GenerationCheckpointStore {
        val bytes = mutableMapOf<Uuid, ByteArray>()
        var failWrite = false
        var failDelete = false
        override fun read(conversationId: Uuid) = bytes[conversationId]?.copyOf()
        override fun exists(conversationId: Uuid) = bytes.containsKey(conversationId)
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            if (failWrite) error("synthetic private detail must not escape")
            this.bytes[conversationId] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) {
            if (failDelete) error("synthetic delete failure")
            bytes.remove(conversationId)
        }
    }

    private fun base(count: Int = 3) = Conversation(assistantId = Uuid.random(),
        messageNodes = (0 until count).map { UIMessage.user("synthetic history $it").toMessageNode() })
    private fun response(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT,
        parts = parts.toList()).toMessageNode()
    private fun tool(id: String = "call-1", output: String? = null) = UIMessagePart.Tool(id, "synthetic_write", "{\"fixture\":true}",
        output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty())
    private fun transition(id: String = "call-1", status: GenerationToolStatus) =
        GenerationToolTransition(id, "synthetic_write", status)
    private fun journal(store: MemoryStore) = GenerationCheckpointJournal(store)
    private fun reject(code: String? = null, action: () -> Unit): GenerationCheckpointException {
        try { action(); fail("expected rejection") }
        catch (error: GenerationCheckpointException) {
            if (code != null) assertEquals(code, error.code)
            assertNull(error.cause)
            return error
        }
        error("unreachable")
    }

    @Test fun `checkpoint stores only bounded tail of five thousand messages and survives new instance`() {
        val store = MemoryStore(); val j = journal(store); val original = base(5401)
        val h = j.begin(original)
        val added = response(UIMessagePart.Text("synthetic new response"))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + added)
        val bytes = store.bytes.getValue(original.id)
        assertTrue(bytes.size < 20_000)
        assertFalse(bytes.toString(Charsets.UTF_8).contains("synthetic history 1000"))
        val restored = journal(store).recover(original)!!
        assertEquals(original.messageNodes + added, restored.conversation.messageNodes)
        assertTrue(restored.changed)
        assertEquals(5400, restored.handle.prefixCount)
    }

    @Test fun `started tool is explicitly unknown and cannot automatically resume after process death`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(tool())
        j.checkpoint(h, original.assistantId, 0, tail, transition(status = GenerationToolStatus.STARTED))
        val recovered = journal(store).recover(original)!!
        val interrupted = recovered.conversation.currentMessages.last().getTools().single()
        assertEquals(setOf("call-1"), recovered.unknownToolIds)
        assertTrue(interrupted.isExecuted)
        assertFalse(interrupted.canResumeExecution)
        assertEquals(HostToolFailure.INTERRUPTED, interrupted.hostToolFailure())
        assertTrue((interrupted.output.single() as UIMessagePart.Text).text.contains("是否执行未知"))
    }

    @Test fun `completed tool output is exact and previous completed effect is not replayed`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val request = response(tool())
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + request,
            transition(status = GenerationToolStatus.STARTED))
        val result = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool(output = "remote result")))))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + result,
            transition(status = GenerationToolStatus.COMPLETED))
        val restored = journal(store).recover(original)!!
        assertEquals(result, restored.conversation.messageNodes.last())
        assertTrue(restored.unknownToolIds.isEmpty())
        reject("tool_must_not_be_replayed") {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + request,
                transition(status = GenerationToolStatus.STARTED))
        }
    }

    @Test fun `crash between two tools keeps first result and marks second unknown`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val request = response(tool("one"), tool("two"))
        val prefix = original.messageNodes.takeLast(1)
        j.checkpoint(h, original.assistantId, 0, prefix + request, transition("one", GenerationToolStatus.STARTED))
        val afterOne = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool("one", "stored"), tool("two")))))
        j.checkpoint(h, original.assistantId, 0, prefix + afterOne, transition("one", GenerationToolStatus.COMPLETED))
        j.checkpoint(h, original.assistantId, 0, prefix + afterOne, transition("two", GenerationToolStatus.STARTED))
        val recovered = journal(store).recover(original)!!
        assertEquals(setOf("two"), recovered.unknownToolIds)
        assertEquals(tool("one", "stored"), recovered.conversation.currentMessages.last().getTools().first())
        assertTrue(recovered.conversation.currentMessages.last().getTools().all { it.isExecuted })
    }

    @Test fun `planned but never started tool gets interrupted receipt without inventing remote success`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + response(tool()))
        val restored = journal(store).recover(original)!!
        assertTrue(restored.unknownToolIds.isEmpty())
        assertTrue(restored.conversation.currentMessages.last().getTools().single().isExecuted)
        assertEquals(HostToolFailure.INTERRUPTED,
            restored.conversation.currentMessages.last().getTools().single().hostToolFailure())
    }

    @Test fun `failed started write never authorizes execution and retains old checkpoint`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val previous = store.bytes.getValue(original.id).copyOf()
        store.failWrite = true
        val error = reject {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + response(tool()),
                transition(status = GenerationToolStatus.STARTED))
        }
        assertFalse(error.message!!.contains("synthetic private detail"))
        assertArrayEquals(previous, store.bytes.getValue(original.id))
    }

    @Test fun `failed completed write leaves unknown effect rather than retryable call`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val request = response(tool())
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + request,
            transition(status = GenerationToolStatus.STARTED))
        store.failWrite = true
        reject {
            val result = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool(output = "successful side effect")))))
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + result,
                transition(status = GenerationToolStatus.COMPLETED))
        }
        assertEquals(setOf("call-1"), journal(store).recover(original)!!.unknownToolIds)
    }

    @Test fun `recovery rejects changed owner epoch prefix and tail without deleting journal`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); j.begin(original)
        reject("owner_or_epoch_changed") { journal(store).recover(original.copy(assistantId = Uuid.random())) }
        reject("owner_or_epoch_changed") { journal(store).recover(original.copy(compactionEpoch = 1)) }
        reject("database_prefix_changed") {
            journal(store).recover(original.copy(messageNodes = listOf(UIMessage.user("new prefix").toMessageNode()) + original.messageNodes.drop(1)))
        }
        reject("database_tail_changed") {
            journal(store).recover(original.copy(messageNodes = original.messageNodes.dropLast(1) + UIMessage.user("new tail").toMessageNode()))
        }
        assertTrue(store.bytes.containsKey(original.id))
    }

    /** Stage46 characterization: passing proves the current conflict, not that it is repaired. */
    @Test fun `stage46 changing only old event read or collapsed during generation conflicts with protected prefix`() = runBlocking {
        listOf(true to true, false to false).forEach { (read, collapsed) ->
            val seed = base()
            val event = seed.currentMessages.first().copy(orbisEvent = OrbisEventMetadata(
                "synthetic-receipt", "synthetic-source", "synthetic-event", 1000L, occurredAt = 900L))
            val original = seed.copy(messageNodes = listOf(seed.messageNodes.first().copy(messages = listOf(event))) +
                seed.messageNodes.drop(1))
            val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
            val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("synthetic new response"))
            j.checkpoint(h, original.assistantId, 0, tail)
            val live = MutableStateFlow(original.copy(messageNodes = original.messageNodes.take(h.prefixCount) + tail))
            var stored = original
            val edit = OrbisEventPresentationEdit(original.messageNodes.first().id, event.id,
                checkNotNull(event.orbisEvent), event.toText(), read, collapsed)
            // Same production helper as ChatService's card-save path; storage is only a local value.
            persistOrbisEventPresentation(live, original.assistantId, edit) {
                stored = stored.applyEventPresentation(original.assistantId, edit)
            }
            assertEquals(original.currentMessages.first().parts, stored.currentMessages.first().parts)
            assertEquals(original.messageNodes.map { it.id }, stored.messageNodes.map { it.id })
            val before = store.bytes.getValue(original.id).copyOf()
            reject("database_prefix_changed") { j.recover(stored) }
            reject("database_prefix_changed") { j.clearAfterDurableCommit(h, live.value) }
            assertArrayEquals(before, store.bytes.getValue(original.id))
            assertEquals(read, stored.currentMessages.first().orbisEvent!!.read)
            assertEquals(collapsed, stored.currentMessages.first().orbisEvent!!.collapsed)
        }
    }

    @Test fun `stage46 event presentation committed before baseline is recoverable`() {
        val seed = base()
        val first = seed.currentMessages.first().copy(orbisEvent = OrbisEventMetadata(
            "synthetic-receipt", "synthetic-source", "synthetic-event", 1000L, read = true, collapsed = false))
        val original = seed.copy(messageNodes = listOf(seed.messageNodes.first().copy(messages = listOf(first))) +
            seed.messageNodes.drop(1))
        val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("synthetic reply"))
        j.checkpoint(h, original.assistantId, 0, tail)
        val recovered = journal(store).recover(original)!!
        assertEquals(first, recovered.conversation.currentMessages.first())
        j.clearAfterDurableCommit(h, recovered.conversation)
        assertFalse(j.hasCheckpoint(original.id))
    }

    @Test fun `stage46 changing transient favorite in protected prefix does not cause conflict`() {
        val original = base(); val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("synthetic durable reply"))
        j.checkpoint(h, original.assistantId, 0, tail)
        val changed = original.copy(messageNodes = listOf(original.messageNodes.first().copy(isFavorite = true)) +
            original.messageNodes.drop(1))
        val recovered = journal(store).recover(changed)!!
        assertTrue(recovered.conversation.messageNodes.first().isFavorite)
        assertEquals(tail, recovered.conversation.messageNodes.takeLast(2))
        j.clearAfterDurableCommit(h, recovered.conversation)
        assertFalse(j.hasCheckpoint(original.id))
    }

    @Test fun `stage46 leftover checkpoint rejects translation or branch change without losing either record`() {
        val seed = base()
        val historical = seed.messageNodes.first().let { it.copy(messages = it.messages + UIMessage.user("synthetic alternative")) }
        val original = seed.copy(messageNodes = listOf(historical) + seed.messageNodes.drop(1))
        val variants = listOf(
            historical.copy(messages = historical.messages.map { it.copy(translation = "synthetic translated history") }),
            historical.copy(selectIndex = 1),
        )
        variants.forEach { modified ->
            val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) +
                response(UIMessagePart.Text("synthetic pending reply")))
            val before = store.bytes.getValue(original.id).copyOf()
            val changed = original.copy(messageNodes = listOf(modified) + original.messageNodes.drop(1))
            assertEquals(original.messageNodes.map { it.id }, changed.messageNodes.map { it.id })
            reject("database_prefix_changed") { journal(store).recover(changed) }
            reject("unresolved_previous_generation") { journal(store).begin(changed) }
            assertArrayEquals(before, store.bytes.getValue(original.id))
            assertEquals(modified, changed.messageNodes.first())
        }
    }

    @Test fun `stage46 one character of protected text or historical tool result must remain a conflict`() {
        val first = response(UIMessagePart.Text("synthetic A"), tool("old-tool", "synthetic receipt A"))
        val seed = base(); val original = seed.copy(messageNodes = listOf(first) + seed.messageNodes.drop(1))
        val variants = listOf(
            listOf(UIMessagePart.Text("synthetic B"), tool("old-tool", "synthetic receipt A")),
            listOf(UIMessagePart.Text("synthetic A"), tool("old-tool", "synthetic receipt B")),
        )
        variants.forEach { parts ->
            val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
            val before = store.bytes.getValue(original.id).copyOf()
            val altered = first.copy(messages = listOf(first.currentMessage.copy(parts = parts)))
            val changed = original.copy(messageNodes = listOf(altered) + original.messageNodes.drop(1))
            assertEquals(original.currentMessages.map { it.id }, changed.currentMessages.map { it.id })
            reject("database_prefix_changed") { j.recover(changed) }
            reject("database_prefix_changed") { j.clearAfterDurableCommit(h, changed) }
            assertArrayEquals(before, store.bytes.getValue(original.id))
        }
    }

    @Test fun `stage46 completed current tool receipt altered in database cannot be acknowledged`() {
        val original = base(); val store = MemoryStore(); val j = journal(store); val h = j.begin(original)
        val request = response(tool())
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + request,
            transition(status = GenerationToolStatus.STARTED))
        val completed = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool(output = "synthetic receipt A")))))
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + completed,
            transition(status = GenerationToolStatus.COMPLETED))
        val before = store.bytes.getValue(original.id).copyOf()
        val modified = completed.copy(messages = listOf(completed.currentMessage.copy(parts = listOf(tool(output = "synthetic receipt B")))))
        val changed = original.copy(messageNodes = original.messageNodes + modified)
        reject("database_tail_changed") { j.recover(changed) }
        reject("checkpoint_not_committed") { j.clearAfterDurableCommit(h, changed) }
        assertArrayEquals(before, store.bytes.getValue(original.id))
        assertEquals("synthetic receipt A", journal(store).recover(original)!!.conversation.currentMessages.last().getTools().single().output.single().let {
            (it as UIMessagePart.Text).text
        })
    }

    @Test fun `branch loss and duplicate node cannot be checkpointed`() {
        val store = MemoryStore(); val j = journal(store)
        val last = MessageNode(messages = listOf(UIMessage.assistant("first"), UIMessage.assistant("second")), selectIndex = 1)
        val original = base().let { it.copy(messageNodes = it.messageNodes + last) }; val h = j.begin(original)
        reject("baseline_branch_missing") { j.checkpoint(h, original.assistantId, 0, listOf(last.copy(messages = listOf(last.currentMessage), selectIndex = 0))) }
        reject("duplicate_tail_nodes") { j.checkpoint(h, original.assistantId, 0, listOf(last, last)) }
    }

    @Test fun `range regeneration preserves baseline suffix alternatives`() {
        val store = MemoryStore(); val j = journal(store); val original = base(7); val h = j.begin(original, 3)
        val last = original.messageNodes.last()
        val alternative = UIMessage.assistant("regenerated")
        val tail = original.messageNodes.drop(3).dropLast(1) + last.copy(messages = last.messages + alternative, selectIndex = 1)
        j.checkpoint(h, original.assistantId, 0, tail)
        assertEquals(original.messageNodes.take(3) + tail, journal(store).recover(original)!!.conversation.messageNodes)
    }

    @Test fun `recovery preserves separately derived favorite flags`() {
        val store = MemoryStore(); val j = journal(store)
        val original = base().let { it.copy(messageNodes = it.messageNodes.dropLast(1) + it.messageNodes.last().copy(isFavorite = true)) }
        val h = j.begin(original)
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + response(UIMessagePart.Text("new response")))
        assertTrue(journal(store).recover(original)!!.conversation.messageNodes[original.messageNodes.lastIndex].isFavorite)
    }

    @Test fun `early regeneration protects five thousand later nodes and only stores changed middle`() {
        val store = MemoryStore(); val j = journal(store)
        val older = response(UIMessagePart.Text("synthetic original early reply"))
        val seed = base(5403)
        val original = seed.copy(messageNodes = seed.messageNodes.take(2) + older + seed.messageNodes.drop(3))
        val h = j.begin(original, prefixCount = 2, suffixCount = 5400)
        assertEquals(5400, h.suffixCount)
        val regenerated = UIMessage.assistant("synthetic regenerated early reply")
        val updated = older.copy(messages = older.messages + regenerated, selectIndex = 1)
        j.checkpoint(h, original.assistantId, 0, listOf(updated))
        assertTrue(store.bytes.getValue(original.id).size < 20_000)
        assertFalse(store.bytes.getValue(original.id).toString(Charsets.UTF_8).contains("synthetic history 1000"))
        val recovered = journal(store).recover(original)!!
        assertEquals(original.messageNodes.take(2) + updated + original.messageNodes.takeLast(5400), recovered.conversation.messageNodes)
        assertEquals(older.messages + regenerated, recovered.conversation.messageNodes[2].messages)
        assertEquals(original.messageNodes.takeLast(5400), recovered.conversation.messageNodes.takeLast(5400))
        assertFalse(journal(store).recover(recovered.conversation)!!.changed)
        j.clearAfterDurableCommit(h, recovered.conversation)
        assertFalse(j.hasCheckpoint(original.id))
    }

    @Test fun `early tool continuation can insert bounded nodes before protected suffix`() {
        val store = MemoryStore(); val j = journal(store); val original = base(5100)
        val h = j.begin(original, prefixCount = 2, suffixCount = 5097)
        val request = response(tool())
        val mutableSegment = listOf(original.messageNodes[2], request)
        j.checkpoint(h, original.assistantId, 0, mutableSegment, transition(status = GenerationToolStatus.STARTED))
        val recovered = journal(store).recover(original)!!
        assertEquals(original.messageNodes.size + 1, recovered.conversation.messageNodes.size)
        assertEquals(original.messageNodes.takeLast(5097), recovered.conversation.messageNodes.takeLast(5097))
        assertEquals(HostToolFailure.INTERRUPTED, recovered.conversation.messageNodes[3].currentMessage.getTools().single().hostToolFailure())
        assertFalse(journal(store).recover(recovered.conversation)!!.changed)
        j.clearAfterDurableCommit(h, recovered.conversation)
    }

    @Test fun `suffix content branch and removal conflicts preserve checkpoint and refuse recovery`() {
        val store = MemoryStore(); val j = journal(store); val original = base(10)
        j.begin(original, prefixCount = 2, suffixCount = 7)
        val finalNode = original.messageNodes.last()
        val branch = finalNode.copy(messages = finalNode.messages + UIMessage.assistant("new branch"), selectIndex = 1)
        reject("database_suffix_changed") {
            j.recover(original.copy(messageNodes = original.messageNodes.dropLast(1) + branch))
        }
        reject("database_suffix_changed") {
            j.recover(original.copy(messageNodes = original.messageNodes.dropLast(1)))
        }
        assertTrue(j.hasCheckpoint(original.id))
    }

    @Test fun `invalid protected ranges and tail overlap never overwrite database history`() {
        val store = MemoryStore(); val j = journal(store); val original = base(10)
        reject("invalid_baseline") { j.begin(original, prefixCount = 5, suffixCount = 6) }
        reject("invalid_baseline") { j.begin(original, prefixCount = 2, suffixCount = -1) }
        val h = j.begin(original, prefixCount = 2, suffixCount = 7)
        j.checkpoint(h, original.assistantId, 0, listOf(original.messageNodes[2], original.messageNodes.last()))
        reject("checkpoint_overlaps_protected_nodes") { j.recover(original) }
        assertTrue(j.hasCheckpoint(original.id))
    }

    @Test fun `rebase removes protected suffix after durable whole-window compaction`() {
        val store = MemoryStore(); val j = journal(store); val original = base(5100)
        val old = j.begin(original, prefixCount = 2, suffixCount = 5097)
        val compacted = original.copy(messageNodes = listOf(response(UIMessagePart.Text("summary"))), compactionEpoch = 1)
        val next = j.rebaseAfterDurableCommit(old, compacted)
        assertEquals(0, next.suffixCount)
        assertEquals(compacted, j.recover(compacted)!!.conversation)
    }

    @Test fun `tail over limits is rejected without discarding prior recovery`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val before = store.bytes.getValue(original.id).copyOf()
        reject("checkpoint_tail_limit_or_missing") {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) +
                (0 until 256).map { response(UIMessagePart.Text("extra $it")) })
        }
        reject("checkpoint_too_large") {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) +
                response(UIMessagePart.Text("x".repeat(GenerationCheckpointJournal.MAX_BYTES))))
        }
        assertArrayEquals(before, store.bytes.getValue(original.id))
    }

    @Test fun `corrupt truncated and oversized records fail closed without overwrite`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); j.begin(original)
        val complete = store.bytes.getValue(original.id)
        store.bytes[original.id] = complete.copyOf(20)
        reject { journal(store).recover(original) }
        reject { journal(store).begin(original) }
        assertEquals(20, store.bytes.getValue(original.id).size)
        store.bytes[original.id] = ByteArray(GenerationCheckpointJournal.MAX_BYTES + 1)
        reject("checkpoint_too_large") { journal(store).recover(original) }
    }

    @Test fun `checksum mutation cannot become recovered history`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); j.begin(original)
        store.bytes[original.id] = store.bytes.getValue(original.id).toString(Charsets.UTF_8)
            .replace("synthetic history 2", "synthetic history X").toByteArray()
        reject("checkpoint_checksum_mismatch") { journal(store).recover(original) }
    }

    @Test fun `new generation cannot replace unresolved old one`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); j.begin(original)
        reject("unresolved_previous_generation") { journal(store).begin(original) }
    }

    @Test fun `checkpoint presence gates initialized sessions including corrupt unresolved records`() {
        val store = MemoryStore(); val j = journal(store); val original = base()
        assertFalse(j.hasCheckpoint(original.id))
        val h = j.begin(original)
        assertTrue(j.hasCheckpoint(original.id))
        assertTrue(journal(store).hasCheckpoint(original.id))
        j.clearAfterDurableCommit(h, original)
        assertFalse(j.hasCheckpoint(original.id))
        store.bytes[original.id] = "not valid JSON".toByteArray()
        assertTrue(j.hasCheckpoint(original.id))
        reject { j.recover(original) }
        assertTrue(j.hasCheckpoint(original.id))
    }

    @Test fun `presence does not read or decode payload and storage errors never mean absent`() {
        val id = Uuid.random()
        val metadataOnly = object : GenerationCheckpointStore {
            override fun read(conversationId: Uuid): ByteArray? = error("payload must not be read")
            override fun exists(conversationId: Uuid) = true
            override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) = error("not used")
            override fun delete(conversationId: Uuid) = error("not used")
        }
        assertTrue(GenerationCheckpointJournal(metadataOnly).hasCheckpoint(id))
        val unreadable = object : GenerationCheckpointStore by metadataOnly {
            override fun exists(conversationId: Uuid): Boolean = error("synthetic access failure")
        }
        reject("checkpoint_storage_or_decode_failed") { GenerationCheckpointJournal(unreadable).hasCheckpoint(id) }
    }

    @Test fun `clear requires actual committed tail and old handle cannot clear new run`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("new tail"))
        j.checkpoint(h, original.assistantId, 0, tail)
        reject("checkpoint_not_committed") { j.clearAfterDurableCommit(h, original) }
        val stored = original.copy(messageNodes = original.messageNodes.take(h.prefixCount) + tail)
        j.clearAfterDurableCommit(h, stored)
        assertNull(j.recover(stored))
        val newHandle = journal(store).begin(stored)
        reject("stale_generation_handle") { j.clearAfterDurableCommit(h, stored) }
        assertEquals(newHandle, journal(store).recover(stored)!!.handle)
    }

    @Test fun `already committed checkpoint is recognized without adding duplicate messages`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("checkpoint"))
        j.checkpoint(h, original.assistantId, 0, tail)
        val stored = original.copy(messageNodes = original.messageNodes.take(h.prefixCount) + tail)
        assertFalse(journal(store).recover(stored)!!.changed)
        assertEquals(stored, journal(store).recover(stored)!!.conversation)
    }

    @Test fun `unknown receipt must be persisted before clear and repeated recovery is idempotent`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val tail = original.messageNodes.takeLast(1) + response(tool())
        j.checkpoint(h, original.assistantId, 0, tail, transition(status = GenerationToolStatus.STARTED))
        reject("unknown_tool_requires_interrupted_receipt") {
            j.clearAfterDurableCommit(h, original.copy(messageNodes = original.messageNodes.take(h.prefixCount) + tail))
        }
        val recovered = journal(store).recover(original)!!
        assertFalse(journal(store).recover(recovered.conversation)!!.changed)
        j.clearAfterDurableCommit(h, recovered.conversation)
        assertNull(j.recover(recovered.conversation))
    }

    @Test fun `completed receipts cannot be removed or replaced by late chunks`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val request = response(tool()); val prefix = original.messageNodes.takeLast(1)
        j.checkpoint(h, original.assistantId, 0, prefix + request, transition(status = GenerationToolStatus.STARTED))
        val result = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool(output = "correct")))))
        j.checkpoint(h, original.assistantId, 0, prefix + result, transition(status = GenerationToolStatus.COMPLETED))
        reject("tool_receipt_changed") { j.checkpoint(h, original.assistantId, 0, prefix + request) }
        val changed = result.copy(messages = listOf(result.currentMessage.copy(parts = listOf(tool(output = "wrong")))))
        reject("tool_receipt_changed") { j.checkpoint(h, original.assistantId, 0, prefix + changed) }
    }

    @Test fun `completion without started or with changed input is refused`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        reject("tool_start_receipt_missing") {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + response(tool(output = "result")),
                transition(status = GenerationToolStatus.COMPLETED))
        }
        val request = response(tool())
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + request,
            transition(status = GenerationToolStatus.STARTED))
        val changed = request.copy(messages = listOf(request.currentMessage.copy(parts = listOf(tool(output = "result").copy(input = "different")))))
        reject("tool_completion_mismatch") {
            j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + changed,
                transition(status = GenerationToolStatus.COMPLETED))
        }
    }

    @Test fun `compaction rebase changes handle and cannot discard unknown external effects`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        val compacted = original.copy(messageNodes = listOf(response(UIMessagePart.Text("summary"))), compactionEpoch = 1)
        val next = j.rebaseAfterDurableCommit(h, compacted)
        assertNotEquals(h.runId, next.runId)
        reject("stale_generation_handle") { j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1)) }
        val request = response(tool())
        j.checkpoint(next, compacted.assistantId, 1, compacted.messageNodes + request,
            transition(status = GenerationToolStatus.STARTED))
        reject("unknown_tool_cannot_rebase") { j.rebaseAfterDurableCommit(next, compacted.copy(compactionEpoch = 2)) }
    }

    @Test fun `delete failure keeps recoverable journal and exposes no private details`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        store.failDelete = true
        reject { j.clearAfterDurableCommit(h, original) }
        assertNotNull(journal(store).recover(original))
    }

    @Test fun `durable later epoch supersedes old journal without replacing new history`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); j.begin(original)
        assertFalse(j.discardSupersededByDurableEpoch(original))
        reject("owner_or_epoch_changed") {
            j.discardSupersededByDurableEpoch(original.copy(assistantId = Uuid.random(), compactionEpoch = 1))
        }
        val compacted = original.copy(messageNodes = listOf(response(UIMessagePart.Text("new summary"))), compactionEpoch = 1)
        assertTrue(journal(store).discardSupersededByDurableEpoch(compacted))
        assertNull(j.recover(compacted))
    }

    @Test fun `later epoch alone cannot discard unknown external effect`() {
        val store = MemoryStore(); val j = journal(store); val original = base(); val h = j.begin(original)
        j.checkpoint(h, original.assistantId, 0, original.messageNodes.takeLast(1) + response(tool()),
            transition(status = GenerationToolStatus.STARTED))
        reject("unknown_tool_cannot_rebase") { j.discardSupersededByDurableEpoch(original.copy(compactionEpoch = 1)) }
        assertNotNull(j.recover(original))
    }

    @Test fun `disk roundtrip without shared memory restores checkpoint and ignores partial new file`() {
        val directory = Files.createTempDirectory("orbis-synthetic-checkpoint-").toFile()
        try {
            val disk = object : GenerationCheckpointStore {
                private fun file(id: Uuid) = File(directory, "$id.json")
                override fun read(conversationId: Uuid) = file(conversationId).takeIf { it.exists() }?.readBytes()
                override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
                    val pending = File(directory, "$conversationId.new")
                    FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                    Files.move(pending.toPath(), file(conversationId).toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                }
                override fun delete(conversationId: Uuid) { check(file(conversationId).delete()) }
            }
            val original = base(); val j = GenerationCheckpointJournal(disk); val h = j.begin(original)
            val tail = original.messageNodes.takeLast(1) + response(UIMessagePart.Text("durable reply"))
            j.checkpoint(h, original.assistantId, 0, tail)
            File(directory, "${original.id}.new").writeText("synthetic incomplete next write")
            val recovered = GenerationCheckpointJournal(disk).recover(original)!!
            assertEquals(tail, recovered.conversation.messageNodes.takeLast(2))
        } finally {
            // Test-created unique directory only; no application/user directories are reachable.
            directory.listFiles()?.forEach { check(it.delete()) }
            check(directory.delete())
        }
    }
}
