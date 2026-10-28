package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withMessageRecall
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HumanContinuationJournalTest {
    @Test fun `committed recalls survive independent restart and do not block next checkpoint`() {
        val source = base()
        val id = Uuid.random(); val store = Store(); val journal = HumanContinuationJournal(store)
        val next = source.append()
        journal.checkpoint(id, source, next)
        val recalled = source.currentMessages.fold(source) { current, message ->
            current.withMessageRecall(message.id, message.role)
        }
        val recovery = HumanContinuationJournal(store).recover(recalled)!!.snapshot
        assertTrue(recovery.currentMessages.take(source.currentMessages.size).all { it.orbisRecalled })
        journal.checkpoint(id, source, recovery)
        val tampered = recalled.copy(messageNodes = recalled.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.copy(parts = listOf(UIMessagePart.Text("tampered"))) })
        })
        assertTrue(runCatching { HumanContinuationJournal(store).recover(tampered) }.isFailure)
        assertTrue(journal.clearCommitted(id, recovery))
    }
    @Test fun `browsing three existing replies widens recovery tail without changing any version`() {
        val store = Store(); val id = Uuid.random()
        val source = base().let { c -> c.copy(messageNodes = c.messageNodes.dropLast(1) +
            c.messageNodes.last().copy(messages = listOf(UIMessage.assistant("one"), UIMessage.assistant("two"),
                UIMessage.assistant("three")), selectIndex = 2)) }.append()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(id, source, source)
        val selected = source.copy(messageNodes = source.messageNodes.mapIndexed { index, node ->
            if (index == 1) node.copy(selectIndex = 0) else node
        })
        journal.checkpoint(id, source, selected)
        val recovery = HumanContinuationJournal(store).recover(source)!!
        assertEquals("one", recovery.snapshot.currentMessages[1].toText())
        assertEquals(source.messageNodes[1].messages, recovery.snapshot.messageNodes[1].messages)
        assertEquals(source.messageNodes.takeLast(2), recovery.snapshot.messageNodes.takeLast(2))
        assertTrue(journal.clearCommitted(id, selected))
    }

    @Test fun `selection checkpoint cannot smuggle a modified body in the protected prefix`() {
        val store = Store(); val id = Uuid.random(); val source = base().append()
        val journal = HumanContinuationJournal(store); journal.checkpoint(id, source, source)
        val forged = source.copy(messageNodes = source.messageNodes.mapIndexed { index, node ->
            if (index == 0) node.copy(messages = node.messages + UIMessage.user("replaced"), selectIndex = 1) else node
        })
        val old = store.read(source.id)
        assertTrue(runCatching { journal.checkpoint(id, source, forged) }.isFailure)
        assertArrayEquals(old, store.read(source.id))
    }
    private class Store : GenerationCheckpointStore {
        val values = mutableMapOf<Uuid, ByteArray>()
        var fail = false
        override fun read(conversationId: Uuid) = values[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) {
            check(!fail) { "synthetic disk failure" }
            values[conversationId] = bytes.copyOf()
        }
        override fun delete(conversationId: Uuid) { values.remove(conversationId) }
    }
    private fun base() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        UIMessage.user("old question").toMessageNode(), UIMessage.assistant("old partial reply").toMessageNode()))
    private fun Conversation.append() = copy(messageNodes = messageNodes + listOf(
        UIMessage.user("new input").toMessageNode(), UIMessage.assistant("new answer").toMessageNode()))

    private fun Conversation.replyText(text: String) = copy(messageNodes = messageNodes.dropLast(1) +
        messageNodes.last().let { it.copy(messages = listOf(it.currentMessage.copy(parts = listOf(UIMessagePart.Text(text))))) })

    @Test fun `recorded Room partial reply recovers later same-node text after journal reopen`() {
        val store = Store(); val base = base(); val early = base.append().replyText("A"); val latest = early.replyText("AB")
        val id = Uuid.random(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(id, base, early)
        journal.prepareRoomCommit(id, base, base, early)
        // Room commits A; a later streaming checkpoint stores AB, retaining the write proof.
        journal.checkpoint(id, base, latest)
        val bytes = store.read(base.id)
        val recovered = HumanContinuationJournal(store).recover(early)!!
        assertEquals(latest.messageNodes, recovered.snapshot.messageNodes)
        assertEquals(early.messageNodes, recovered.durableBaseline!!.messageNodes)
        assertArrayEquals(bytes, store.read(base.id)) // Recovery itself does not replace either source.
    }

    @Test fun `known partial reasoning may append prose and finish without changing protected metadata`() {
        val store = Store(); val base = base(); val id = Uuid.random()
        val reasoning = UIMessagePart.Reasoning("A", createdAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"), finishedAt = null)
        val reply = UIMessage.assistant("").copy(parts = listOf(reasoning))
        val early = base.copy(messageNodes = base.messageNodes + UIMessage.user("fresh").toMessageNode() + reply.toMessageNode())
        val latest = early.copy(messageNodes = early.messageNodes.dropLast(1) + early.messageNodes.last().copy(messages = listOf(
            reply.copy(parts = listOf(reasoning.copy(reasoning = "AB", finishedAt = reasoning.createdAt), UIMessagePart.Text("answer")),
                finishedAt = reply.createdAt))))
        val journal = HumanContinuationJournal(store); journal.checkpoint(id, base, early)
        journal.prepareRoomCommit(id, base, base, early)
        journal.checkpoint(id, base, latest)
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(early)!!.snapshot.messageNodes)
    }

    @Test fun `a matching text prefix without an exact Room write receipt remains a conflict`() {
        val store = Store(); val base = base(); val early = base.append().replyText("A"); val latest = early.replyText("AB")
        val journal = HumanContinuationJournal(store); val id = Uuid.random()
        journal.checkpoint(id, base, latest)
        val original = store.read(base.id)
        assertThrows(IllegalStateException::class.java) { journal.recover(early) }
        assertArrayEquals(original, store.read(base.id))
        journal.prepareRoomCommit(id, base, base, early)
        // A third text prefix was never proposed by this lane's CAS; it is not inferred safe.
        val different = early.replyText("")
        val proof = store.read(base.id)
        assertThrows(IllegalStateException::class.java) { journal.recover(different) }
        assertArrayEquals(proof, store.read(base.id))
    }

    @Test fun `Room receipt cannot authorize edited user text divergent reply branch or tool transition`() {
        val base = base(); val early = base.append().replyText("A")
        val replyNode = early.messageNodes.last(); val reply = replyNode.currentMessage
        val variants = listOf(
            early.replyText("different"),
            early.copy(messageNodes = early.messageNodes.dropLast(1) + replyNode.copy(messages = listOf(reply, UIMessage.assistant("new branch")), selectIndex = 1)),
            early.copy(messageNodes = early.messageNodes.dropLast(1) + replyNode.copy(messages = listOf(reply.copy(modelId = Uuid.random(), parts = listOf(UIMessagePart.Text("AB")))))),
            early.copy(messageNodes = early.messageNodes.dropLast(1) + replyNode.copy(messages = listOf(reply.copy(parts = listOf(UIMessagePart.Text("AB"), UIMessagePart.Tool("call", "write", "{}")))))),
            early.copy(messageNodes = early.messageNodes.mapIndexed { index, node ->
                if (index != base.messageNodes.size) node else node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text("new input edited")))))
            }),
        )
        variants.forEach { latest ->
            val store = Store(); val id = Uuid.random(); val journal = HumanContinuationJournal(store)
            journal.checkpoint(id, base, early); journal.prepareRoomCommit(id, base, base, early)
            journal.checkpoint(id, base, latest)
            val bytes = store.read(base.id)
            assertThrows(IllegalStateException::class.java) { journal.recover(early) }
            assertArrayEquals(bytes, store.read(base.id))
        }
    }

    @Test fun `failed Room proof write keeps earlier complete journal and failed CAS does not discard tail`() {
        val store = Store(); val base = base(); val early = base.append().replyText("A"); val latest = early.replyText("AB")
        val id = Uuid.random(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(id, base, latest); val bytes = store.read(base.id)
        store.fail = true
        assertThrows(IllegalStateException::class.java) { journal.prepareRoomCommit(id, base, base, early) }
        assertArrayEquals(bytes, store.read(base.id))
        store.fail = false
        journal.prepareRoomCommit(id, base, base, early)
        // Crash or DB failure before the proposed A commit leaves Room at base. AB still restores.
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(base)!!.snapshot.messageNodes)
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(early)!!.snapshot.messageNodes)
    }

    @Test fun `version one checksum remains readable but has no invented Room proof`() {
        val store = Store(); val base = base(); val early = base.append().replyText("A"); val latest = early.replyText("AB")
        val journal = HumanContinuationJournal(store); journal.checkpoint(Uuid.random(), base, latest)
        val json = kotlinx.serialization.json.Json
        val envelope = json.parseToJsonElement(store.read(base.id)!!.toString(Charsets.UTF_8)) as kotlinx.serialization.json.JsonObject
        val record = envelope.getValue("record") as kotlinx.serialization.json.JsonObject
        val legacy = kotlinx.serialization.json.JsonObject(record.toMutableMap().apply {
            put("version", kotlinx.serialization.json.JsonPrimitive(1)); remove("roomSnapshotHashes")
        })
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(legacy.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacyBytes = kotlinx.serialization.json.JsonObject(mapOf("record" to legacy,
            "sha256" to kotlinx.serialization.json.JsonPrimitive(digest))).toString().toByteArray()
        store.values[base.id] = legacyBytes
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(base)!!.snapshot.messageNodes)
        assertThrows(IllegalStateException::class.java) { HumanContinuationJournal(store).recover(early) }
        assertArrayEquals(legacyBytes, store.read(base.id))
    }

    @Test fun `new journal instance restores complete human tail without modifying old Room page`() {
        val store = Store(); val base = base(); val latest = base.append().append(); val id = Uuid.random()
        HumanContinuationJournal(store).checkpoint(id, base, latest)
        val recovery = checkNotNull(HumanContinuationJournal(store).recover(base))
        assertEquals(latest, recovery.snapshot)
        assertEquals(base, recovery.baseline)
        assertEquals(id, recovery.runId)
        assertEquals(2, base.messageNodes.size)
        assertNotNull(store.read(base.id))
    }

    @Test fun `old generation checkpoint and independent human copy never share storage`() {
        val oldStore = Store(); val newStore = Store(); val base = base()
        val old = GenerationCheckpointJournal(oldStore)
        old.begin(base)
        val original = oldStore.read(base.id)
        HumanContinuationJournal(newStore).checkpoint(Uuid.random(), base, base.append())
        assertEquals(4, HumanContinuationJournal(newStore).recover(base)!!.snapshot.messageNodes.size)
        assertArrayEquals(original, oldStore.read(base.id))
    }

    @Test fun `long immutable history is not copied into every recovery file`() {
        val store = Store(); val baseline = base().let { c -> c.copy(messageNodes =
            List(400) { UIMessage.user("private history " + "x".repeat(10000)).toMessageNode() } + c.messageNodes) }
        val latest = baseline.append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), baseline, latest)
        assertTrue(store.read(baseline.id)!!.size < 10000)
        assertEquals(latest, HumanContinuationJournal(store).recover(baseline)!!.snapshot)
    }

    @Test fun `unfinished tools become receipts and reasoning timer stops at the saved point`() {
        val store = Store(); val base = base()
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("partial reasoning", createdAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"), finishedAt = null),
            UIMessagePart.Tool("pending", "write_file", "{}", approvalState = ToolApprovalState.Pending),
            UIMessagePart.Tool("unknown", "network_write", "{}")))
        val latest = base.copy(messageNodes = base.messageNodes + pending.toMessageNode())
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, latest)
        val first = HumanContinuationJournal(store).recover(base)!!.snapshot
        val second = HumanContinuationJournal(store).recover(base)!!.snapshot
        assertEquals(first, second)
        assertTrue(HumanContinuationJournal(store).clearCommitted(
            HumanContinuationJournal(store).recover(base)!!.runId, first))
        assertTrue(first.currentMessages.last().getTools().all { it.isExecuted })
        assertNotNull((first.currentMessages.last().parts.first() as UIMessagePart.Reasoning).finishedAt)
        assertTrue(first.currentMessages.last().getTools().first().output.toString().contains("approval_cancelled"))
        assertTrue(first.currentMessages.last().getTools().last().output.toString().contains("tool_execution_interrupted"))
    }

    @Test fun `persisted newer append wins and already committed recovery copy is removed`() {
        val store = Store(); val base = base(); val saved = base.append(); val newer = saved.append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, saved)
        assertNull(HumanContinuationJournal(store).recover(newer))
        assertNull(store.read(base.id))
        assertEquals(6, newer.messageNodes.size)
    }

    @Test fun `unfinished reasoning before the final node remains in the mutable recovery tail`() {
        val store = Store()
        val pending = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("old incomplete reasoning", finishedAt = null))).toMessageNode()
        val baseline = base().let { it.copy(messageNodes = listOf(pending) + it.messageNodes) }
        val settled = baseline.copy(messageNodes = baseline.messageNodes.map { node ->
            node.copy(messages = node.messages.map { it.finishReasoning() })
        }).append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), baseline, settled)
        assertEquals(settled, HumanContinuationJournal(store).recover(baseline)!!.snapshot)
    }

    @Test fun `unrelated append changed prefix owner and epoch keep both records`() {
        val store = Store(); val base = base(); val latest = base.append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, latest)
        val bytes = store.read(base.id)
        val candidates = listOf(base.append(), base.copy(assistantId = Uuid.random()),
            base.copy(compactionEpoch = 1), base.copy(messageNodes = listOf(UIMessage.user("edited").toMessageNode()) + base.messageNodes.drop(1)))
        candidates.forEach { changed ->
            assertThrows(IllegalStateException::class.java) { HumanContinuationJournal(store).recover(changed) }
            assertArrayEquals(bytes, store.read(base.id))
        }
    }

    @Test fun `failed write retains the last complete copy and stale handoff cannot clear newer revision`() {
        val store = Store(); val base = base(); val first = base.append(); val latest = first.append(); val id = Uuid.random()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(id, base, first)
        store.fail = true
        assertThrows(IllegalStateException::class.java) { journal.checkpoint(id, base, latest) }
        assertEquals(first, journal.recover(base)!!.snapshot)
        store.fail = false
        journal.checkpoint(id, base, latest)
        assertFalse(journal.clearCommitted(id, first))
        assertFalse(journal.clearCommitted(Uuid.random(), latest))
        assertTrue(journal.clearCommitted(id, latest))
        assertNull(store.read(base.id))
    }

    @Test fun `old in-flight baseline difference does not silently overwrite Room on restore`() {
        val store = Store(); val base = base(); val latest = base.append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, latest)
        val oldTail = base.messageNodes.last()
        val changed = base.copy(messageNodes = base.messageNodes.dropLast(1) + oldTail.copy(messages = listOf(
            oldTail.currentMessage.copy(parts = listOf(UIMessagePart.Text("late old result"))))))
        val recovery = HumanContinuationJournal(store).recover(changed)!!
        assertEquals("late old result", changed.currentMessages.last().toText())
        assertEquals(latest.messageNodes, recovery.snapshot.messageNodes)
        assertNotEquals(changed.messageNodes, recovery.baseline.messageNodes) // Room CAS must refuse.
    }

    @Test fun `unsaved old partial tail can still be restored without requiring it in Room`() {
        val store = Store(); val base = base(); val latest = base.append()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, latest)
        val persisted = base.copy(messageNodes = base.messageNodes.dropLast(1))
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(persisted)!!.snapshot.messageNodes)
    }

    @Test fun `malformed recovery preserves bytes and does not leak private text in error`() {
        val store = Store(); val base = base()
        store.values[base.id] = "private malformed content".toByteArray()
        val error = assertThrows(IllegalStateException::class.java) { HumanContinuationJournal(store).recover(base) }
        assertFalse(error.message!!.contains("private")); assertNull(error.cause)
        assertEquals("private malformed content", store.read(base.id)!!.toString(Charsets.UTF_8))
    }

    @Test fun `valid JSON with changed chat bytes fails checksum and remains recoverable for inspection`() {
        val store = Store(); val base = base()
        HumanContinuationJournal(store).checkpoint(Uuid.random(), base, base.append())
        val altered = store.read(base.id)!!.toString(Charsets.UTF_8).replace("new answer", "tampered answer").toByteArray()
        store.values[base.id] = altered
        assertThrows(IllegalStateException::class.java) { HumanContinuationJournal(store).recover(base) }
        assertArrayEquals(altered, store.read(base.id))
    }

    @Test fun `favorite flags survive recovery and do not prevent exact committed cleanup`() {
        val store = Store(); val base = base(); val snapshot = base.append(); val id = Uuid.random()
        val marked = base.copy(messageNodes = base.messageNodes.map { it.copy(isFavorite = true) })
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(id, base, snapshot)
        val restored = journal.recover(marked)!!.snapshot
        assertTrue(restored.messageNodes.take(base.messageNodes.size).all { it.isFavorite })
        assertTrue(journal.clearCommitted(id, restored))
    }
}
