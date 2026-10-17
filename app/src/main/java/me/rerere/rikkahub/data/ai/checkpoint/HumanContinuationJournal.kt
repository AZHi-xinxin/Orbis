package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** A recovery copy, NOT an execution receipt. It never grants approval or resumes a tool.
 * Kept separate from both Room and the old generation journal: neither old writer owns it.
 * All methods run on IO. Only the mutable tail is encoded per streamed checkpoint.
 */
@OptIn(ExperimentalSerializationApi::class)
class HumanContinuationJournal(private val store: GenerationCheckpointStore) {
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Record(
        val version: Int = 1,
        val runId: Uuid,
        val conversationId: Uuid,
        val assistantId: Uuid,
        val epoch: Long,
        val prefixCount: Int,
        val prefixHash: String,
        val baseTail: List<MessageNode>,
        val tail: List<MessageNode>,
        val savedAtMillis: Long = 0,
    )
    @Serializable private data class Envelope(val record: Record, val sha256: String)

    data class Recovery(val runId: Uuid, val baseline: Conversation, val snapshot: Conversation,
        val durableBaseline: Conversation? = null)

    @Synchronized
    fun checkpoint(runId: Uuid, baseline: Conversation, snapshot: Conversation) = guarded {
        check(baseline.id == snapshot.id && baseline.assistantId == snapshot.assistantId &&
            baseline.compactionEpoch == snapshot.compactionEpoch && baseline.consultation == snapshot.consultation)
        val previous = read(snapshot.id)
        val record = previous ?: run {
            // Sending fresh input settles unfinished tools/reasoning, potentially before the last node.
            val pending = baseline.messageNodes.indexOfFirst { node ->
                node.messages.any { message -> message.getTools().any { !it.isExecuted } ||
                    message.parts.any { it is UIMessagePart.Reasoning && it.finishedAt == null } }
            }
            val prefix = if (pending >= 0) pending else baseline.messageNodes.lastIndex.coerceAtLeast(0)
            Record(runId = runId, conversationId = baseline.id, assistantId = baseline.assistantId,
                epoch = baseline.compactionEpoch, prefixCount = prefix,
                prefixHash = hashNodes(baseline.messageNodes.take(prefix)),
                baseTail = baseline.messageNodes.drop(prefix), tail = emptyList())
        }
        check(record.runId == runId && record.assistantId == snapshot.assistantId && record.epoch == snapshot.compactionEpoch)
        check(snapshot.messageNodes.size >= record.prefixCount)
        // The lane cannot mutate protected history. Reference equality avoids hashing a long
        // immutable prefix on every chunk; restored/equal values still get an exact comparison.
        check(snapshot.messageNodes.take(record.prefixCount).zip(baseline.messageNodes).all { (a, b) -> a == b })
        val tail = snapshot.messageNodes.drop(record.prefixCount)
        validateNodes(tail)
        check(tail.size >= record.baseTail.size)
        record.baseTail.zip(tail).forEach { (base, next) ->
            check(base.id == next.id && base.messages.all { old -> next.messages.any { it.id == old.id } })
        }
        val updated = record.copy(tail = tail, savedAtMillis = System.currentTimeMillis())
        val bytes = BoundedOutput().also { json.encodeToStream(Envelope(updated, hashRecord(updated)), it) }.toByteArray()
        store.writeAtomic(snapshot.id, bytes)
        check(store.read(snapshot.id)?.contentEquals(bytes) == true)
    }

    /** Load before the old generation journal. Never overwrite a later durable edit or append.
     * The original Room page remains intact until a separate compare-and-set commit succeeds.
     */
    @Synchronized
    fun recover(persisted: Conversation,
        proveInFlightBaseline: (Conversation, Conversation) -> Boolean = { _, _ -> false },
    ): Recovery? = guarded {
        val record = read(persisted.id) ?: return@guarded null
        requirePrefix(record, persisted)
        val tail = settled(record)
        if (contains(persisted, record, record.tail) || contains(persisted, record, tail)) {
            store.delete(persisted.id)
            return@guarded null
        }
        val current = persisted.messageNodes.drop(record.prefixCount)
        // A later append, deletion, branch change or unrelated replacement needs explicit
        // reconciliation, not silent truncation. Keep the recovery file on every refusal.
        check(current.size <= record.tail.size)
        current.zip(record.tail).forEachIndexed { index, (stored, saved) ->
            val base = record.baseTail.getOrNull(index)
            check(stored.id == saved.id)
            check(stored.messages.all { message -> saved.messages.any { it.id == message.id } })
            check(stored.selectIndex == saved.selectIndex || stored.selectIndex == base?.selectIndex)
            // Only the old in-flight baseline may differ in content. Newly appended turns
            // already on disk must match a saved prefix, not be replaced by older text.
            if (base == null) check(sameNode(stored, saved) || sameNode(stored, tail[index]))
        }
        val prefix = persisted.messageNodes.take(record.prefixCount)
        val favorites = persisted.messageNodes.associate { it.id to it.isFavorite }
        val baseline = persisted.copy(messageNodes = prefix + record.baseTail)
        // Keep the immutable journal anchor separate from the Room compare-and-set baseline.
        // After a crash, an already committed prefix of this lane may be newer than its anchor.
        val storedBase = current.take(record.baseTail.size)
        val exactBase = storedBase.size == record.baseTail.size && storedBase.indices.all { i ->
            sameNode(storedBase[i], record.baseTail[i]) || sameNode(storedBase[i], record.tail[i]) ||
                sameNode(storedBase[i], tail[i])
        }
        // A damaged old journal must not prevent displaying this independent recovery copy.
        // It merely withholds the optional permission to bridge a different Room baseline.
        val proven = exactBase || try {
            proveInFlightBaseline(persisted.copy(messageNodes = prefix + storedBase), baseline)
        } catch (_: Exception) { false }
        val durable = if (proven) persisted else null
        Recovery(record.runId, baseline,
            persisted.copy(messageNodes = prefix + tail.map { node ->
                node.copy(isFavorite = favorites[node.id] ?: false)
            }), durable)
    }

    /** Clear only this lane's exact, committed contents. A stale handoff cannot erase a successor. */
    @Synchronized
    fun clearCommitted(runId: Uuid, persisted: Conversation): Boolean = guarded {
        val record = read(persisted.id) ?: return@guarded true
        if (record.runId != runId) return@guarded false
        requirePrefix(record, persisted)
        if (!contains(persisted, record, record.tail) && !contains(persisted, record, settled(record))) return@guarded false
        store.delete(persisted.id)
        true
    }

    private fun contains(current: Conversation, record: Record, tail: List<MessageNode>): Boolean =
        current.messageNodes.size >= record.prefixCount + tail.size &&
            current.messageNodes.drop(record.prefixCount).take(tail.size).zip(tail).all { (a, b) -> sameNode(a, b) }

    // Favorite is derived from a separate table, not serialized chat content.
    private fun sameNode(a: MessageNode, b: MessageNode) =
        a.id == b.id && a.selectIndex == b.selectIndex && a.messages == b.messages

    private fun settled(record: Record) = record.tail.map { node ->
        node.copy(messages = node.messages.map { message ->
            val savedAt = kotlin.time.Instant.fromEpochMilliseconds(record.savedAtMillis)
            val interrupted = message.getTools().any { !it.isExecuted }
            // The live helper stamps the current time. Recovery must instead remain identical
            // on every read, including the reasoning it closes while creating tool receipts.
            message.finishInterruptedHostTools().let { stopped -> stopped.copy(
                finishedAt = if (interrupted) message.finishedAt ?: maxOf(message.createdAt,
                    savedAt.toLocalDateTime(TimeZone.currentSystemDefault())) else message.finishedAt,
                parts = stopped.parts.mapIndexed { index, part ->
                    val original = message.parts[index]
                    if (part is UIMessagePart.Reasoning && original is UIMessagePart.Reasoning &&
                        original.finishedAt == null) part.copy(finishedAt = maxOf(original.createdAt, savedAt))
                    else part
                }
            ) }
        })
    }

    private fun requirePrefix(record: Record, current: Conversation) {
        check(current.id == record.conversationId && current.assistantId == record.assistantId &&
            current.compactionEpoch == record.epoch && current.consultation == null)
        check(current.messageNodes.size >= record.prefixCount &&
            hashNodes(current.messageNodes.take(record.prefixCount)) == record.prefixHash)
    }

    private fun read(id: Uuid): Record? {
        val bytes = store.read(id) ?: return null
        check(bytes.size <= GenerationCheckpointJournal.MAX_BYTES)
        val envelope = json.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8))
        val record = envelope.record
        check(envelope.sha256 == hashRecord(record))
        check(record.version == 1 && record.conversationId == id && record.epoch >= 0 && record.prefixCount >= 0)
        validateNodes(record.baseTail)
        validateNodes(record.tail)
        return record
    }

    private fun validateNodes(nodes: List<MessageNode>) {
        check(nodes.map { it.id }.distinct().size == nodes.size)
        nodes.forEach { node ->
            check(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices &&
                node.messages.map { it.id }.distinct().size == node.messages.size)
        }
    }

    private fun hashNodes(nodes: List<MessageNode>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest)
        nodes.forEach { json.encodeToStream(it, sink); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashRecord(record: Record): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest)
        json.encodeToStream(record, sink)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            check(count < GenerationCheckpointJournal.MAX_BYTES)
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(length <= GenerationCheckpointJournal.MAX_BYTES - count)
            super.write(bytes, offset, length)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (_: Exception) {
        // Decode errors can contain chat text. No private payload or exception cause in logs/UI.
        throw IllegalStateException("续聊恢复副本暂未能合并，原记录已保留，请勿清除应用数据。")
    }
}
