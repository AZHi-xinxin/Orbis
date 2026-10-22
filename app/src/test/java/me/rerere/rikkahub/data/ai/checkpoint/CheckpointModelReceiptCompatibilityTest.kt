package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.json.*
import me.rerere.ai.ui.ConfiguredModelReceipt
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Synthetic DTO fixtures only. The old encoder below is independent of the production
 * compatibility helper and freezes nested message encoding AND all persisted node hashes.
 */
class CheckpointModelReceiptCompatibilityTest {
    private val json = Json { encodeDefaults = true }

    private class Store : GenerationCheckpointStore {
        val values = mutableMapOf<Uuid, ByteArray>()
        override fun read(conversationId: Uuid) = values[conversationId]?.copyOf()
        override fun writeAtomic(conversationId: Uuid, bytes: ByteArray) { values[conversationId] = bytes.copyOf() }
        override fun delete(conversationId: Uuid) { values.remove(conversationId) }
    }

    private fun base() = Conversation(assistantId = Uuid.random(), compactionEpoch = 21,
        messageNodes = listOf(UIMessage.user("protected question").toMessageNode(),
            UIMessage.assistant("baseline answer").toMessageNode()))

    private fun Conversation.append() = copy(messageNodes = messageNodes + listOf(
        UIMessage.user("synthetic input").toMessageNode(), UIMessage.assistant("A").toMessageNode()))

    private fun MessageNode.text(value: String) = copy(messages = messages.map { message ->
        message.copy(parts = listOf(UIMessagePart.Text(value)))
    })

    private fun Conversation.reply(value: String) = copy(messageNodes = messageNodes.dropLast(1) + messageNodes.last().text(value))
    private fun record(store: Store, id: Uuid) = json.parseToJsonElement(store.read(id)!!.decodeToString()).jsonObject.getValue("record").jsonObject
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun save(store: Store, id: Uuid, record: JsonObject) {
        store.writeAtomic(id, JsonObject(linkedMapOf("record" to record,
            "sha256" to JsonPrimitive(sha(record.toString().encodeToByteArray())))).toString().encodeToByteArray())
    }

    private fun oldNode(encoded: JsonElement): JsonObject {
        val node = encoded.jsonObject
        return JsonObject(node.toMutableMap().apply {
            this["messages"] = JsonArray(node.getValue("messages").jsonArray.map { value ->
                JsonObject(value.jsonObject.toMutableMap().apply {
                    if (get("configuredModelReceipt") == JsonNull) remove("configuredModelReceipt")
                })
            })
        })
    }

    private fun oldNodesHash(nodes: List<MessageNode>, normalizeEvents: Boolean = false): String {
        val digest = MessageDigest.getInstance("SHA-256")
        nodes.forEach { node ->
            val protected = if (!normalizeEvents) node else node.copy(messages = node.messages.map { message ->
                message.orbisEvent?.let { message.copy(orbisEvent = it.copy(read = false, collapsed = true)) } ?: message
            })
            digest.update(oldNode(json.encodeToJsonElement(protected)).toString().encodeToByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun freezeHuman(store: Store, base: Conversation, version: Int = 2, snapshots: List<Conversation> = emptyList()) {
        val fields = record(store, base.id).toMutableMap()
        val prefix = fields.getValue("prefixCount").jsonPrimitive.int
        fields["version"] = JsonPrimitive(version)
        fields["prefixHash"] = JsonPrimitive(oldNodesHash(base.messageNodes.take(prefix)))
        listOf("baseTail", "tail").forEach { key -> fields[key] = JsonArray(fields.getValue(key).jsonArray.map(::oldNode)) }
        if (version == 1 || snapshots.isEmpty()) fields.remove("roomSnapshotHashes")
        else fields["roomSnapshotHashes"] = JsonArray(snapshots.map { JsonPrimitive(oldNodesHash(it.messageNodes)) }.distinct())
        save(store, base.id, JsonObject(fields))
    }

    private fun freezeGeneration(store: Store, base: Conversation, version: Int = 2) {
        val fields = record(store, base.id).toMutableMap()
        val prefix = fields.getValue("prefixCount").jsonPrimitive.int
        val suffix = fields.getValue("suffixCount").jsonPrimitive.int
        fields["version"] = JsonPrimitive(version)
        fields["prefixHash"] = JsonPrimitive(oldNodesHash(base.messageNodes.take(prefix), version >= 2))
        fields["suffixHash"] = JsonPrimitive(oldNodesHash(base.messageNodes.takeLast(suffix), version >= 2))
        fields["baseTailHash"] = JsonPrimitive(oldNodesHash(base.messageNodes.drop(prefix).dropLast(suffix), version >= 2))
        fields["tail"] = JsonArray(fields.getValue("tail").jsonArray.map(::oldNode))
        save(store, base.id, JsonObject(fields))
    }

    private fun assertCurrentEnvelope(store: Store, id: Uuid, nodeFields: List<String>) {
        val envelope = json.parseToJsonElement(store.read(id)!!.decodeToString()).jsonObject
        val saved = envelope.getValue("record").jsonObject
        assertEquals(sha(saved.toString().encodeToByteArray()), envelope.getValue("sha256").jsonPrimitive.content)
        nodeFields.forEach { key -> saved.getValue(key).jsonArray.forEach { node ->
            node.jsonObject.getValue("messages").jsonArray.forEach { message ->
                assertTrue(message.jsonObject.containsKey("configuredModelReceipt"))
            }
        } }
    }

    @Test fun `old human v1 nested encoding recovers without inventing Room write proof`() {
        val store = Store(); val base = base(); val latest = base.append(); val run = Uuid.random()
        val journal = HumanContinuationJournal(store)
        journal.checkpoint(run, base, latest)
        freezeHuman(store, base, version = 1)
        assertFalse(record(store, base.id).containsKey("roomSnapshotHashes"))
        val before = store.read(base.id)
        val reopened = HumanContinuationJournal(store)
        assertEquals(latest.messageNodes, reopened.recover(base)!!.snapshot.messageNodes)
        assertFalse(reopened.provesRoomWrite(run, base, latest))
        assertArrayEquals(before, store.read(base.id))
    }

    @Test fun `old human v2 Room snapshot digest proves only the recorded partial write`() {
        val store = Store(); val base = base(); val early = base.append(); val latest = early.reply("AB")
        val run = Uuid.random(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(run, base, early)
        journal.prepareRoomCommit(run, base, base, early)
        journal.checkpoint(run, base, latest)
        freezeHuman(store, base, snapshots = listOf(base, early))
        val before = store.read(base.id)
        val reopened = HumanContinuationJournal(store)
        val recovered = reopened.recover(early)!!
        assertEquals(latest.messageNodes, recovered.snapshot.messageNodes)
        assertEquals(early.messageNodes, recovered.durableBaseline!!.messageNodes)
        assertTrue(reopened.provesRoomWrite(run, early, latest))
        assertFalse(reopened.provesRoomWrite(run, early.reply("unrecorded edit"), latest))
        assertArrayEquals(before, store.read(base.id))
    }

    @Test fun `old v1 without snapshots refuses to treat partial reply as its own Room write`() {
        val store = Store(); val base = base(); val early = base.append(); val latest = early.reply("AB")
        val run = Uuid.random(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(run, base, latest)
        freezeHuman(store, base, version = 1)
        val before = store.read(base.id)
        assertTrue(runCatching { HumanContinuationJournal(store).recover(early) }.isFailure)
        assertFalse(HumanContinuationJournal(store).provesRoomWrite(run, early, latest))
        assertArrayEquals(before, store.read(base.id))
    }

    @Test fun `old human read followed by new checkpoint writes current encoding and remains recoverable`() {
        val store = Store(); val base = base(); val early = base.append(); val latest = early.reply("AB")
        val run = Uuid.random(); val journal = HumanContinuationJournal(store)
        journal.checkpoint(run, base, early)
        freezeHuman(store, base, version = 1)
        journal.checkpoint(run, base, latest)
        journal.prepareRoomCommit(run, base, base, early)
        assertCurrentEnvelope(store, base.id, listOf("baseTail", "tail"))
        assertEquals(latest.messageNodes, HumanContinuationJournal(store).recover(early)!!.snapshot.messageNodes)
    }

    @Test fun `current explicit null human and generation records still recover unchanged`() {
        val base = base(); val latest = base.append(); val human = Store(); val generation = Store()
        HumanContinuationJournal(human).checkpoint(Uuid.random(), base, latest)
        val journal = GenerationCheckpointJournal(generation); val handle = journal.begin(base)
        journal.checkpoint(handle, base.assistantId, base.compactionEpoch, latest.messageNodes.drop(handle.prefixCount))
        assertCurrentEnvelope(human, base.id, listOf("baseTail", "tail"))
        assertCurrentEnvelope(generation, base.id, listOf("tail"))
        assertEquals(latest.messageNodes, HumanContinuationJournal(human).recover(base)!!.snapshot.messageNodes)
        assertEquals(latest.messageNodes, GenerationCheckpointJournal(generation).recover(base)!!.conversation.messageNodes)
    }

    @Test fun `old generation v1 and v2 retain prefix suffix and baseline tail protection`() {
        for (version in 1..2) {
            val store = Store(); val base = base().let { it.copy(messageNodes = it.messageNodes + UIMessage.user("protected suffix").toMessageNode()) }
            val journal = GenerationCheckpointJournal(store); val handle = journal.begin(base, prefixCount = 1, suffixCount = 1)
            val newTail = listOf(base.messageNodes[1].text("regenerated answer"))
            journal.checkpoint(handle, base.assistantId, base.compactionEpoch, newTail)
            freezeGeneration(store, base, version)
            val before = store.read(base.id)
            val reopened = GenerationCheckpointJournal(store)
            assertEquals(listOf(base.messageNodes.first()) + newTail + base.messageNodes.last(),
                reopened.recover(base)!!.conversation.messageNodes)
            assertArrayEquals(before, store.read(base.id))
            val editedSuffix = base.copy(messageNodes = base.messageNodes.dropLast(1) + base.messageNodes.last().text("edited suffix"))
            assertTrue(runCatching { reopened.recover(editedSuffix) }.isFailure)
            val editedBaseline = base.copy(messageNodes = listOf(base.messageNodes.first(), base.messageNodes[1].text("edited baseline"), base.messageNodes.last()))
            assertTrue(runCatching { reopened.recover(editedBaseline) }.isFailure)
            assertArrayEquals(before, store.read(base.id))
            journal.checkpoint(handle, base.assistantId, base.compactionEpoch, newTail)
            assertCurrentEnvelope(store, base.id, listOf("tail"))
            assertEquals(newTail.single(), GenerationCheckpointJournal(store).recover(base)!!.conversation.messageNodes[1])
        }
    }

    @Test fun `legacy prefix changes and epoch 21 to 23 reject recovery and retirement without altering bytes`() {
        val base = base(); val latest = base.append(); val run = Uuid.random()
        val human = Store(); val generation = Store()
        HumanContinuationJournal(human).checkpoint(run, base, latest)
        val gj = GenerationCheckpointJournal(generation); val handle = gj.begin(base)
        gj.checkpoint(handle, base.assistantId, base.compactionEpoch, latest.messageNodes.drop(handle.prefixCount))
        freezeHuman(human, base); freezeGeneration(generation, base)
        val humanBytes = human.read(base.id); val generationBytes = generation.read(base.id)
        val changedPrefixes = listOf(base.messageNodes.first().text("edited protected body"),
            base.messageNodes.first().copy(id = Uuid.random()),
            base.messageNodes.first().copy(messages = base.messageNodes.first().messages + UIMessage.user("another branch"), selectIndex = 1))
        val changed = changedPrefixes.map { base.copy(messageNodes = listOf(it) + base.messageNodes.drop(1)) } +
            base.copy(compactionEpoch = 23) + base.copy(assistantId = Uuid.random())
        changed.forEach { current ->
            val committed = current.copy(messageNodes = current.messageNodes.take(1) + latest.messageNodes.drop(1))
            assertTrue(runCatching { HumanContinuationJournal(human).recover(current) }.isFailure)
            assertTrue(runCatching { HumanContinuationJournal(human).clearCommitted(run, committed) }.isFailure)
            assertTrue(runCatching { GenerationCheckpointJournal(generation).recover(current, allowAlreadyCommittedTail = true) }.isFailure)
            assertTrue(runCatching { GenerationCheckpointJournal(generation).clearAfterDurableCommit(handle, committed,
                allowAlreadyCommittedTail = current.compactionEpoch != base.compactionEpoch) }.isFailure)
            assertFalse(GenerationCheckpointJournal(generation).clearContainedInDurableContinuation(committed))
            assertArrayEquals(humanBytes, human.read(base.id)); assertArrayEquals(generationBytes, generation.read(base.id))
        }
    }

    @Test fun `nonnull receipt survives fallback and changing it fails checksum or protected prefix`() {
        val receipt = ConfiguredModelReceipt("assistant", "conversation", "provider", "model-a", "provider label")
        val base = base().let { it.copy(messageNodes = it.messageNodes.map { node -> node.copy(messages = node.messages.map { msg -> msg.copy(configuredModelReceipt = receipt) }) }) }
        val latest = base.append(); val human = Store(); val generation = Store()
        HumanContinuationJournal(human).checkpoint(Uuid.random(), base, latest)
        val gj = GenerationCheckpointJournal(generation); val handle = gj.begin(base)
        gj.checkpoint(handle, base.assistantId, base.compactionEpoch, latest.messageNodes.drop(handle.prefixCount))
        freezeHuman(human, base); freezeGeneration(generation, base)
        assertEquals(receipt, HumanContinuationJournal(human).recover(base)!!.snapshot.messageNodes[1].currentMessage.configuredModelReceipt)
        assertEquals(receipt, GenerationCheckpointJournal(generation).recover(base)!!.conversation.messageNodes[1].currentMessage.configuredModelReceipt)
        val changedPrefix = base.copy(messageNodes = base.messageNodes.mapIndexed { index, node ->
            if (index != 0) node else node.copy(messages = node.messages.map { it.copy(configuredModelReceipt = receipt.copy(modelId = "model-b")) })
        })
        assertTrue(runCatching { HumanContinuationJournal(human).recover(changedPrefix) }.isFailure)
        assertTrue(runCatching { GenerationCheckpointJournal(generation).recover(changedPrefix) }.isFailure)
        listOf(human, generation).forEach { store -> store.writeAtomic(base.id,
            store.read(base.id)!!.decodeToString().replace("model-a", "model-b").encodeToByteArray()) }
        assertTrue(runCatching { HumanContinuationJournal(human).recover(base) }.isFailure)
        assertTrue(runCatching { GenerationCheckpointJournal(generation).recover(base) }.isFailure)
    }

    @Test fun `compatibility projection never visits parts tool values or nonnull receipts`() {
        val node = json.parseToJsonElement("""{"id":"node","configuredModelReceipt":null,"messages":[{"configuredModelReceipt":null,"parts":[{"configuredModelReceipt":null,"input":{"configuredModelReceipt":null}}]},{"configuredModelReceipt":{"modelId":"keep","configuredModelReceipt":null}}]}""").jsonObject
        val projected = CheckpointModelReceiptCompatibility.node(node).jsonObject
        assertEquals(JsonNull, projected["configuredModelReceipt"])
        assertFalse(projected.getValue("messages").jsonArray[0].jsonObject.containsKey("configuredModelReceipt"))
        assertEquals(node.getValue("messages").jsonArray[0].jsonObject["parts"], projected.getValue("messages").jsonArray[0].jsonObject["parts"])
        assertEquals(node.getValue("messages").jsonArray[1], projected.getValue("messages").jsonArray[1])
    }

    @Test fun `legacy record body tamper and unknown fields still fail strictly and preserve stored bytes`() {
        val base = base(); val latest = base.append(); val human = Store(); val generation = Store()
        HumanContinuationJournal(human).checkpoint(Uuid.random(), base, latest)
        val gj = GenerationCheckpointJournal(generation); val handle = gj.begin(base)
        gj.checkpoint(handle, base.assistantId, base.compactionEpoch, latest.messageNodes.drop(handle.prefixCount))
        freezeHuman(human, base); freezeGeneration(generation, base)
        val fixtures = listOf(human, generation).map { it.read(base.id)!! }
        listOf(human, generation).forEachIndexed { index, store ->
            store.writeAtomic(base.id, fixtures[index].decodeToString().replace("synthetic input", "tampered input").encodeToByteArray())
        }
        fun rejectedAndPreserved() {
            val h = human.read(base.id); val g = generation.read(base.id)
            assertTrue(runCatching { HumanContinuationJournal(human).recover(base) }.isFailure)
            assertTrue(runCatching { GenerationCheckpointJournal(generation).recover(base) }.isFailure)
            assertArrayEquals(h, human.read(base.id)); assertArrayEquals(g, generation.read(base.id))
        }
        rejectedAndPreserved()
        listOf(human, generation).forEachIndexed { index, store ->
            store.writeAtomic(base.id, fixtures[index])
            save(store, base.id, JsonObject(record(store, base.id) + ("unknownFutureField" to JsonPrimitive(true))))
        }
        rejectedAndPreserved()
    }

    @Test fun `legacy unknown tool stays interrupted and cannot be retired as an ordinary complete tail`() {
        val base = base(); val store = Store(); val journal = GenerationCheckpointJournal(store); val handle = journal.begin(base)
        val tool = UIMessagePart.Tool("synthetic-call", "synthetic-write", """{"configuredModelReceipt":null,"marker":"original-input"}""")
        val node = UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()
        val tail = base.messageNodes.takeLast(1) + node
        journal.checkpoint(handle, base.assistantId, base.compactionEpoch, tail,
            GenerationToolTransition(tool.toolCallId, tool.toolName, GenerationToolStatus.STARTED))
        freezeGeneration(store, base)
        val before = store.read(base.id)
        val recovered = GenerationCheckpointJournal(store).recover(base)!!
        assertEquals(setOf(tool.toolCallId), recovered.unknownToolIds)
        assertEquals(tool.input, recovered.conversation.messageNodes.last().currentMessage.getTools().single().input)
        assertFalse(GenerationCheckpointJournal(store).clearContainedInDurableContinuation(recovered.conversation))
        assertFalse(GenerationCheckpointJournal(store).clearSettledInDurableContinuation(recovered.conversation, unknownEffectsRetained = false))
        assertArrayEquals(before, store.read(base.id))
        // Even if the outer checksum is recomputed, the independent tool input receipt rejects it.
        val changed = record(store, base.id).toString().replace("original-input", "changed-input")
        save(store, base.id, json.parseToJsonElement(changed).jsonObject)
        val changedBytes = store.read(base.id)
        assertTrue(runCatching { GenerationCheckpointJournal(store).recover(base) }.isFailure)
        assertArrayEquals(changedBytes, store.read(base.id))
    }
}
