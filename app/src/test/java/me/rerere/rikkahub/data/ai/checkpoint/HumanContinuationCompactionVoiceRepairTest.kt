package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ConfiguredModelReceipt
import me.rerere.ai.ui.OrbisPreparedContextUsage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.attachPreparedContextUsage
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.orbisCompactionSummaryHash
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.repairCompactionVoiceMetadata
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic only. No device archive, message, credential or real identifier is embedded here. */
class HumanContinuationCompactionVoiceRepairTest {
    private class Store : GenerationCheckpointStore {
        val values = mutableMapOf<Uuid, ByteArray>()
        var deletes = 0
        override fun read(conversationId: Uuid) = values[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { values[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { deletes++; values.remove(conversationId) }
    }

    private class Fixture(oldPreparedEpoch: Long? = null) {
        val store = Store()
        val journal = HumanContinuationJournal(store)
        val run = Uuid.random()
        val assistantId = Uuid.random()
        val modelId = Uuid.random()
        val summary = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Text("synthetic context summary",
            buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })))
        val oldEnd = LocalDateTime.parse("2026-10-21T21:06:16")
        val newEnd = LocalDateTime.parse("2026-10-21T21:06:30")
        val old = UIMessage.assistant("").copy(createdAt = oldEnd, finishedAt = oldEnd,
            modelId = modelId, orbisVoiceCallId = "synthetic-call", orbisVoiceCallKind = "turn",
            orbisPreparedContextUsage = oldPreparedEpoch?.let { epoch ->
                OrbisPreparedContextUsage(12000, 1000, epoch, assistantId.toString(), modelId.toString(),
                    "a".repeat(64), "b".repeat(64))
            },
            parts = (0..3).flatMap { index -> listOf(
                UIMessagePart.Reasoning("synthetic reasoning $index",
                    createdAt = kotlin.time.Instant.parse("2026-10-21T21:06:15Z"),
                    finishedAt = kotlin.time.Instant.parse("2026-10-21T21:06:16Z")),
                UIMessagePart.Tool("tool-$index", if (index == 3) "compact" else "synthetic_tool", "{}",
                    output = listOf(UIMessagePart.Text(if (index == 3) buildJsonObject {
                        put("status", "compacted"); put("new_start_message_id", summary.id.toString())
                        put("summary_sha256", orbisCompactionSummaryHash(summary.toText()))
                    }.toString() else "synthetic completed receipt")))
            ) })
        val baseline = Conversation(assistantId = assistantId, compactionEpoch = 75,
            messageNodes = listOf(summary.toMessageNode()) +
                List(31) { UIMessage.user("retained synthetic $it").toMessageNode() } + old.toMessageNode())
        private val completedWithoutPreparedUsage = old.copy(parts = old.parts + listOf(
            UIMessagePart.Reasoning("new synthetic reasoning",
                createdAt = kotlin.time.Instant.parse("2026-10-21T21:06:20Z"),
                finishedAt = kotlin.time.Instant.parse("2026-10-21T21:06:30Z")),
            UIMessagePart.Text("new synthetic reply")), finishedAt = newEnd,
            usage = TokenUsage(promptTokens = 100, completionTokens = 20),
            configuredModelReceipt = ConfiguredModelReceipt(baseline.assistantId.toString(), baseline.id.toString(),
                "synthetic-provider", "synthetic-model", "synthetic-label"))
        val completed = attachPreparedContextUsage(baseline.currentMessages.dropLast(1) + completedWithoutPreparedUsage,
            tokens = 24685, preparedAtMillis = 1792587990000, epoch = baseline.compactionEpoch,
            assistantId = baseline.assistantId, modelId = requireNotNull(old.modelId), configuration = "a".repeat(64)).last()
        val corrected = baseline.copy(messageNodes = baseline.messageNodes.dropLast(1) +
            baseline.messageNodes.last().copy(messages = listOf(completed)) +
            List(36) { UIMessage.user("later synthetic $it").toMessageNode() })
        val poisoned = corrected.copy(messageNodes = corrected.messageNodes.mapIndexed { index, node ->
            if (index != 0) node else node.copy(messages = listOf(summary.copy(
                orbisVoiceCallId = "synthetic-call", orbisVoiceCallKind = "turn")))
        })
        init { journal.checkpoint(run, baseline, baseline) }
        fun withTail(message: UIMessage) = poisoned.copy(messageNodes = poisoned.messageNodes.mapIndexed { index, node ->
            if (index == 32) node.copy(messages = listOf(message)) else node
        })
    }

    @Test fun `known poison yields metadata only plan preserving all newer nodes and tools`() {
        val f = Fixture(); val bytes = f.store.read(f.baseline.id)
        assertThrows(IllegalStateException::class.java) { f.journal.recover(f.poisoned) }
        val plan = checkNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
        assertTrue(plan.needsMetadataCorrection)
        assertEquals(f.corrected, plan.corrected)
        assertEquals(69, plan.corrected.messageNodes.size)
        assertSame(f.poisoned.messageNodes[32], plan.corrected.messageNodes[32])
        assertEquals(f.old.parts, plan.corrected.currentMessages[32].parts.take(8))
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
        assertEquals(0, f.store.deletes)
    }

    @Test fun `cleanup refuses original poison and accepts only exact durable correction`() {
        val f = Fixture(); val plan = checkNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
        assertFalse(f.journal.finishCompactionVoiceMetadataRepair(plan, f.poisoned))
        assertNotNull(f.store.read(f.baseline.id))
        assertTrue(f.journal.finishCompactionVoiceMetadataRepair(plan, f.corrected))
        assertNull(f.store.read(f.baseline.id))
        assertNull(f.journal.recover(f.corrected))
        assertEquals(69, f.corrected.messageNodes.size)
    }

    @Test fun `crash after Room correction can retry without restoring obsolete journal tail`() {
        val f = Fixture()
        checkNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
        val reopened = HumanContinuationJournal(f.store)
        val retry = checkNotNull(reopened.planCompactionVoiceMetadataRepair(f.corrected))
        assertFalse(retry.needsMetadataCorrection)
        assertSame(f.corrected, retry.corrected)
        assertTrue(reopened.finishCompactionVoiceMetadataRepair(retry, f.corrected))
        assertNull(f.store.read(f.baseline.id))
    }

    @Test fun `unrelated prefix body call owner epoch and branches cannot be normalized`() {
        val f = Fixture(); val bytes = f.store.read(f.baseline.id)
        val wrongSummary = f.poisoned.messageNodes.first().let { node -> node.copy(messages = listOf(
            node.currentMessage.copy(orbisVoiceCallId = "another-call"))) }
        val changedPrefix = f.poisoned.messageNodes.mapIndexed { i, node ->
            if (i == 1) node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text("edited"))))) else node
        }
        val variants = listOf(
            f.poisoned.copy(assistantId = Uuid.random()), f.poisoned.copy(compactionEpoch = 76),
            f.poisoned.copy(messageNodes = listOf(wrongSummary) + f.poisoned.messageNodes.drop(1)),
            f.poisoned.copy(messageNodes = changedPrefix),
            f.poisoned.copy(messageNodes = f.poisoned.messageNodes.mapIndexed { i, node ->
                if (i == 32) node.copy(messages = node.messages + UIMessage.assistant("other branch"), selectIndex = 1) else node
            }),
        )
        variants.forEach { assertNull(f.journal.planCompactionVoiceMetadataRepair(it)) }
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
    }

    @Test fun `changed or missing completed tool receipt is not evidence of forward progress`() {
        val f = Fixture(); val bytes = f.store.read(f.baseline.id)
        val tool = f.completed.parts[7] as UIMessagePart.Tool
        val changedTools = listOf(tool.copy(input = "{\"changed\":true}"),
            tool.copy(output = listOf(UIMessagePart.Text("different result"))),
            tool.copy(approvalState = ToolApprovalState.Approved), tool.copy(output = emptyList()))
        changedTools.forEach { changed ->
            val message = f.completed.copy(parts = f.completed.parts.mapIndexed { i, part -> if (i == 7) changed else part })
            assertNull(f.journal.planCompactionVoiceMetadataRepair(f.withTail(message)))
        }
        assertNull(f.journal.planCompactionVoiceMetadataRepair(f.withTail(f.completed.copy(parts = f.completed.parts.drop(1)))))
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
    }

    @Test fun `different model binding earlier finish and replaced old reasoning remain conflicts`() {
        val f = Fixture(); val bytes = f.store.read(f.baseline.id)
        val variants = listOf(
            f.completed.copy(modelId = Uuid.random()), f.completed.copy(finishedAt = null),
            f.completed.copy(finishedAt = LocalDateTime.parse("2026-10-21T21:06:10")),
            f.completed.copy(configuredModelReceipt = f.completed.configuredModelReceipt!!.copy(assistantId = "different")),
            f.completed.copy(parts = f.completed.parts.mapIndexed { i, part ->
                if (i == 0) (part as UIMessagePart.Reasoning).copy(reasoning = "replaced old reasoning") else part
            }),
        )
        variants.forEach { assertNull(f.journal.planCompactionVoiceMetadataRepair(f.withTail(it))) }
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
    }

    @Test fun `prepared usage is validated against this owner epoch model and exact current history`() {
        val f = Fixture(); val receipt = requireNotNull(f.completed.orbisPreparedContextUsage)
        assertNull(f.old.orbisPreparedContextUsage)
        assertNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
        val invalid = listOf(receipt.copy(tokens = -1), receipt.copy(preparedAtMillis = 0),
            receipt.copy(contextEpoch = 74), receipt.copy(assistantId = Uuid.random().toString()),
            receipt.copy(modelId = Uuid.random().toString()), receipt.copy(configurationDigest = "invalid"),
            receipt.copy(historyDigest = "0".repeat(64)))
        invalid.forEach { value -> assertNull(f.journal.planCompactionVoiceMetadataRepair(
            f.withTail(f.completed.copy(orbisPreparedContextUsage = value)))) }
    }

    @Test fun `nonnull old prepared usage may advance only to a validated newer request receipt`() {
        // Both epochs are synthetic: the algorithm does not presume which prior epoch was used.
        for (oldEpoch in listOf(74L, 75L)) {
            val f = Fixture(oldPreparedEpoch = oldEpoch)
            assertNotNull(f.old.orbisPreparedContextUsage)
            val plan = checkNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
            assertEquals(f.completed, plan.corrected.currentMessages[32])
            assertEquals(69, plan.corrected.messageNodes.size)
            val backwards = f.completed.copy(orbisPreparedContextUsage =
                requireNotNull(f.completed.orbisPreparedContextUsage).copy(preparedAtMillis = 999))
            assertNull(f.journal.planCompactionVoiceMetadataRepair(f.withTail(backwards)))
        }
    }

    @Test fun `unfamiliar tool or media append is not included in this narrowly proven repair`() {
        val f = Fixture()
        listOf(UIMessagePart.Tool("new", "unknown", "{}"), UIMessagePart.Image("synthetic-image"))
            .forEach { part -> assertNull(f.journal.planCompactionVoiceMetadataRepair(f.withTail(
                f.completed.copy(parts = f.completed.parts + part, orbisPreparedContextUsage = null)))) }
    }

    @Test fun `stale repair cannot acknowledge a replacement journal or a later Room append`() {
        val f = Fixture(); val plan = checkNotNull(f.journal.planCompactionVoiceMetadataRepair(f.poisoned))
        val appended = f.corrected.copy(messageNodes = f.corrected.messageNodes + UIMessage.user("later").toMessageNode())
        assertFalse(f.journal.finishCompactionVoiceMetadataRepair(plan, appended))
        val successor = Store(); HumanContinuationJournal(successor).checkpoint(Uuid.random(), f.corrected, f.corrected)
        f.store.values[f.baseline.id] = successor.read(f.baseline.id)!!
        val bytes = f.store.read(f.baseline.id)
        assertFalse(f.journal.finishCompactionVoiceMetadataRepair(plan, f.corrected))
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
    }

    @Test fun `failed checksum never grants a repair plan`() {
        val f = Fixture()
        val changed = f.store.read(f.baseline.id)!!.toString(Charsets.UTF_8).replace("synthetic reasoning", "altered reasoning").toByteArray()
        f.store.values[f.baseline.id] = changed
        assertThrows(IllegalStateException::class.java) { f.journal.planCompactionVoiceMetadataRepair(f.poisoned) }
        assertArrayEquals(changed, f.store.read(f.baseline.id))
    }

    @Test fun `compact receipt must bind the exact summary identity and content`() {
        val f = Fixture()
        assertTrue(hasCompactionVoiceReceipt(f.old, f.summary))
        assertFalse(hasCompactionVoiceReceipt(f.old, f.summary.copy(id = Uuid.random())))
        assertFalse(hasCompactionVoiceReceipt(f.old, f.summary.copy(parts = listOf(UIMessagePart.Text("different summary")))))
        assertFalse(hasCompactionVoiceReceipt(f.old.copy(orbisVoiceCallId = null), f.summary))
    }

    @Test fun `coordinator only clears after successful CAS and exact independent reread`() = runTest {
        val f = Fixture(); var disk = f.poisoned; var commits = 0; var reads = 0
        val result = repairCompactionVoiceMetadata(disk, f.journal, read = { reads++; disk }, commit = { before, candidate ->
            assertEquals(disk, before); assertNotNull(f.store.read(before.id)); commits++; disk = candidate; disk
        })
        assertEquals(f.corrected, result); assertEquals(1, commits); assertEquals(1, reads)
        assertNull(f.store.read(f.baseline.id))
    }

    @Test fun `failed CAS and failed reread retain the journal and never write its older tail`() = runTest {
        for (failAtCas in listOf(true, false)) {
            val f = Fixture(); val bytes = f.store.read(f.baseline.id); var disk = f.poisoned
            val outcome = runCatching { repairCompactionVoiceMetadata(disk, f.journal,
                read = { error("synthetic read failure") }, commit = { _, candidate ->
                    if (failAtCas) null else { disk = candidate; disk }
                }) }
            assertTrue(outcome.isFailure)
            assertArrayEquals(bytes, f.store.read(f.baseline.id))
            assertEquals(69, disk.messageNodes.size)
            assertEquals(f.completed, disk.currentMessages[32])
        }
    }

    @Test fun `independent reread with concurrent append cannot retire the older plan`() = runTest {
        val f = Fixture(); val bytes = f.store.read(f.baseline.id)
        val changed = f.corrected.copy(messageNodes = f.corrected.messageNodes + UIMessage.user("new concurrent input").toMessageNode())
        val outcome = runCatching { repairCompactionVoiceMetadata(f.poisoned, f.journal,
            read = { changed }, commit = { _, candidate -> candidate }) }
        assertTrue(outcome.isFailure)
        assertArrayEquals(bytes, f.store.read(f.baseline.id))
        // A fresh plan can prove the newer Room state instead; no truncated snapshot is reused.
        val fresh = checkNotNull(f.journal.planCompactionVoiceMetadataRepair(changed))
        assertEquals(70, fresh.corrected.messageNodes.size)
        assertTrue(f.journal.finishCompactionVoiceMetadataRepair(fresh, changed))
    }
}
