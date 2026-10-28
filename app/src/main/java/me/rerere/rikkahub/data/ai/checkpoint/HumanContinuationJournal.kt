package me.rerere.rikkahub.data.ai.checkpoint

import me.rerere.rikkahub.data.model.withoutRecallFlags
import me.rerere.rikkahub.data.model.containsRecallFlagsOf
import me.rerere.rikkahub.data.model.preserveMessageRecalls

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.finishInterruptedHostTools
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
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
        val version: Int = 2,
        val runId: Uuid,
        val conversationId: Uuid,
        val assistantId: Uuid,
        val epoch: Long,
        val prefixCount: Int,
        val prefixHash: String,
        val baseTail: List<MessageNode>,
        val tail: List<MessageNode>,
        val savedAtMillis: Long = 0,
        // Write-ahead proof of this lane's last CAS attempt, not permission to replay tools.
        // Omit the default so version-1 records retain their exact historical checksum.
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val roomSnapshotHashes: List<String> = emptyList(),
    )
    @Serializable private data class Envelope(val record: Record, val sha256: String)

    data class Recovery(val runId: Uuid, val baseline: Conversation, val snapshot: Conversation,
        val durableBaseline: Conversation? = null)

    data class SessionRecovery(val recovery: Recovery?, val conflictPreserved: Boolean = false)

    /** Startup only, under the conversation edit lock, before creating a live lane.
     * An incompatible but valid copy must not occupy the sole active slot indefinitely.
     * Preserve exact bytes first, never infer success or replay its old tools. Failed reads,
     * damaged/foreign records and failed archival leave the active file untouched.
     */
    @Synchronized
    fun recoverForSession(persisted: Conversation,
        proveInFlightBaseline: (Conversation, Conversation) -> Boolean = { _, _ -> false },
        mayPreserveConflict: () -> Boolean = { true },
        beforeRetireConflict: () -> Unit = {},
    ): SessionRecovery = guarded {
        val original = store.read(persisted.id)
        try {
            SessionRecovery(recover(persisted, proveInFlightBaseline))
        } catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            val bytes = original ?: throw failure
            // Decode and verify again: an I/O error or malformed file is NOT permission to retire it.
            val record = read(persisted.id) ?: throw failure
            if (record.conversationId != persisted.id || record.assistantId != persisted.assistantId ||
                persisted.consultation != null || !mayPreserveConflict()) throw failure
            check(store.read(persisted.id)?.contentEquals(bytes) == true)
            store.preserveConflict(persisted.id, bytes)
            beforeRetireConflict()
            check(mayPreserveConflict() && store.read(persisted.id)?.contentEquals(bytes) == true)
            store.delete(persisted.id)
            check(store.read(persisted.id) == null)
            SessionRecovery(null, conflictPreserved = true)
        }
    }

    class CompactionVoiceMetadataRepair internal constructor(
        val before: Conversation,
        val corrected: Conversation,
        internal val recordHash: String,
        internal val runId: Uuid,
    ) {
        val needsMetadataCorrection: Boolean get() = before.messageNodes != corrected.messageNodes
    }

    /** No writes. Only the known first-summary call mislabel is normalized, and only when that
     * single two-field correction reproduces the ORIGINAL protected-prefix hash exactly.
     * The newer Room tail/branches/suffix remain authoritative: nothing is copied from this file.
     */
    @Synchronized
    fun planCompactionVoiceMetadataRepair(persisted: Conversation): CompactionVoiceMetadataRepair? = guarded {
        val record = read(persisted.id) ?: return@guarded null
        planCompactionVoiceMetadataRepair(record, persisted)
    }

    private fun planCompactionVoiceMetadataRepair(record: Record,
        persisted: Conversation): CompactionVoiceMetadataRepair? {
        if (record.version != 2 || record.conversationId != persisted.id || record.assistantId != persisted.assistantId ||
            record.epoch != persisted.compactionEpoch || persisted.consultation != null || record.prefixCount < 1 ||
            record.baseTail.size != 1 || record.tail.size != 1 ||
            persisted.messageNodes.size <= record.prefixCount) return null
        validateNodes(persisted.messageNodes)
        val first = persisted.messageNodes.first()
        val summary = first.currentMessage
        if (!summary.isCompactionSummary()) return null
        val anchor = record.tail.single().currentMessage
        if (!hasCompactionVoiceReceipt(anchor, summary)) return null
        // Null/null also permits retry after the CAS succeeded but journal retirement was interrupted.
        if (!(summary.orbisVoiceCallId == null && summary.orbisVoiceCallKind == null) &&
            !(summary.orbisVoiceCallId == anchor.orbisVoiceCallId && summary.orbisVoiceCallKind == "turn")) return null
        val normalized = if (summary.orbisVoiceCallId == null) persisted else persisted.copy(messageNodes =
            listOf(first.copy(messages = first.messages.map { message ->
                if (message.id == summary.id) message.copy(orbisVoiceCallId = null, orbisVoiceCallKind = null) else message
            })) + persisted.messageNodes.drop(1))
        if (!matchesNodesHash(normalized.messageNodes.take(record.prefixCount), listOf(record.prefixHash))) return null
        if (!retainsCompactionVoiceTail(record.baseTail.single(), record.tail.single(), summary, persisted) ||
            !retainsCompactionVoiceTail(record.tail.single(), persisted.messageNodes[record.prefixCount], summary, persisted)) return null
        return CompactionVoiceMetadataRepair(persisted, normalized, hashRecord(record), record.runId)
    }

    /** Caller has committed the exact proposed metadata correction and independently reread Room.
     * A new journal run/tail or changed Room row cannot be acknowledged by a stale plan.
     */
    @Synchronized
    fun finishCompactionVoiceMetadataRepair(plan: CompactionVoiceMetadataRepair, persisted: Conversation): Boolean = guarded {
        if (persisted.id != plan.corrected.id || persisted.assistantId != plan.corrected.assistantId ||
            persisted.compactionEpoch != plan.corrected.compactionEpoch || persisted.consultation != plan.corrected.consultation ||
            hashNodes(persisted.messageNodes) != hashNodes(plan.corrected.messageNodes)) return@guarded false
        val record = read(persisted.id) ?: return@guarded true
        if (record.runId != plan.runId || hashRecord(record) != plan.recordHash) return@guarded false
        val proof = planCompactionVoiceMetadataRepair(record, persisted) ?: return@guarded false
        if (proof.needsMetadataCorrection) return@guarded false
        store.delete(persisted.id)
        check(store.read(persisted.id) == null)
        true
    }

    @Synchronized
    fun checkpoint(runId: Uuid, baseline: Conversation, snapshot: Conversation) = guarded {
        check(baseline.id == snapshot.id && baseline.assistantId == snapshot.assistantId &&
            baseline.compactionEpoch == snapshot.compactionEpoch && baseline.consultation == snapshot.consultation)
        val previous = read(snapshot.id)
        var record = previous ?: run {
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
        // Browsing a prior reply selects an EXISTING alternative, not a destructive edit.
        // Widen the journal tail to include that selection. All original versions remain in
        // baseTail/tail, and restart recovery still compares every message body and identity.
        val changedSelection = snapshot.messageNodes.take(record.prefixCount).zip(baseline.messageNodes)
            .indexOfFirst { (next, base) -> next.selectIndex != base.selectIndex }
        if (changedSelection >= 0) {
            check(snapshot.messageNodes.take(record.prefixCount).zip(baseline.messageNodes).all { (next, base) ->
                next.copy(selectIndex = base.selectIndex).withoutRecallFlags() == base.withoutRecallFlags()
            })
            record = record.copy(prefixCount = changedSelection,
                prefixHash = hashNodes(baseline.messageNodes.take(changedSelection)),
                baseTail = baseline.messageNodes.drop(changedSelection))
        }
        // The lane cannot mutate protected history. Reference equality avoids hashing a long
        // immutable prefix on every chunk; restored/equal values still get an exact comparison.
        check(snapshot.messageNodes.take(record.prefixCount).zip(baseline.messageNodes).all { (a, b) ->
            a == b || a.withoutRecallFlags() == b.withoutRecallFlags()
        })
        val tail = snapshot.messageNodes.drop(record.prefixCount)
        validateNodes(tail)
        check(tail.size >= record.baseTail.size)
        record.baseTail.zip(tail).forEach { (base, next) ->
            check(base.id == next.id && base.messages.all { old -> next.messages.any { it.id == old.id } })
        }
        val updated = record.copy(version = 2, tail = tail, savedAtMillis = System.currentTimeMillis())
        write(updated)
    }

    /** Called only AFTER the current Room baseline was proved, immediately before its CAS.
     * A streaming checkpoint keeps these two bounded hashes, so a crash between Room and
     * the next checkpoint can identify the earlier version without guessing from text.
     * Recording a failed attempt grants nothing: recovery also requires exact identities,
     * branches, protected history and a strictly limited prose-only forward transition.
     */
    @Synchronized
    internal fun prepareRoomCommit(runId: Uuid, baseline: Conversation,
        persisted: Conversation, candidate: Conversation) = guarded {
        check(baseline.id == persisted.id && baseline.assistantId == persisted.assistantId &&
            baseline.compactionEpoch == persisted.compactionEpoch &&
            candidate.id == persisted.id && candidate.assistantId == persisted.assistantId &&
            candidate.compactionEpoch == persisted.compactionEpoch && candidate.consultation == persisted.consultation)
        val record = read(persisted.id) ?: run {
            checkpoint(runId, baseline, candidate)
            checkNotNull(read(persisted.id))
        }
        check(record.runId == runId)
        requirePrefix(record, persisted)
        // Never replace the latest streaming tail with the possibly older saving snapshot.
        write(record.copy(version = 2, roomSnapshotHashes = listOf(
            hashNodes(persisted.messageNodes), hashNodes(candidate.messageNodes)).distinct()))
    }

    /** A Room transaction may commit just before its caller loses the completion receipt.
     * Only this run's exact write-ahead candidate can advance the CAS anchor. Concurrent
     * edits, changed branches/tools, shorter snapshots and unknown rows remain conflicts.
     */
    @Synchronized
    internal fun provesRoomWrite(runId: Uuid, persisted: Conversation, candidate: Conversation): Boolean = guarded {
        val record = read(persisted.id) ?: return@guarded false
        if (record.runId != runId || persisted.id != candidate.id || persisted.assistantId != candidate.assistantId ||
            persisted.compactionEpoch != candidate.compactionEpoch || persisted.consultation != candidate.consultation)
            return@guarded false
        requirePrefix(record, persisted)
        if (!matchesNodesHash(persisted.messageNodes, record.roomSnapshotHashes) ||
            candidate.messageNodes.size < persisted.messageNodes.size) return@guarded false
        validateNodes(candidate.messageNodes)
        persisted.messageNodes.zip(candidate.messageNodes).all { (stored, next) ->
            sameNode(stored, next) || isProvenContinuationTextAdvance(stored, next)
        }
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
        val knownRoomWrite = matchesNodesHash(persisted.messageNodes, record.roomSnapshotHashes)
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
            if (base == null) check(sameNode(stored, saved) || sameNode(stored, tail[index]) ||
                knownRoomWrite && isProvenContinuationTextAdvance(stored, saved))
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
        val committed = persisted.messageNodes.associateBy { it.id }
        Recovery(record.runId, baseline,
            persisted.copy(messageNodes = prefix + tail.map { node ->
                val restored = node.copy(isFavorite = favorites[node.id] ?: false)
                committed[node.id]?.let { preserveMessageRecalls(restored, it) } ?: restored
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
            current.messageNodes.drop(record.prefixCount).take(tail.size).zip(tail).all { (a, b) ->
                sameNode(a, b) && a.containsRecallFlagsOf(b)
            }

    // Favorite is derived from a separate table, not serialized chat content.
    private fun sameNode(a: MessageNode, b: MessageNode) =
        a.id == b.id && a.selectIndex == b.selectIndex &&
            a.withoutRecallFlags().messages == b.withoutRecallFlags().messages

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
            matchesNodesHash(current.messageNodes.take(record.prefixCount), listOf(record.prefixHash)))
    }

    private fun read(id: Uuid): Record? {
        val bytes = store.read(id) ?: return null
        check(bytes.size <= GenerationCheckpointJournal.MAX_BYTES)
        val envelope = json.decodeFromString<Envelope>(bytes.toString(Charsets.UTF_8))
        val record = envelope.record
        check(envelope.sha256 == hashRecord(record) || envelope.sha256 ==
            CheckpointModelReceiptCompatibility.recordHash(json, json.encodeToJsonElement(record), setOf("baseTail", "tail")))
        check(record.version in 1..2 && record.conversationId == id && record.epoch >= 0 && record.prefixCount >= 0)
        check(record.roomSnapshotHashes.size <= 2 && record.roomSnapshotHashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        validateNodes(record.baseTail)
        validateNodes(record.tail)
        return record
    }

    private fun write(record: Record) {
        val bytes = BoundedOutput().also { json.encodeToStream(Envelope(record, hashRecord(record)), it) }.toByteArray()
        store.writeAtomic(record.conversationId, bytes)
        check(store.read(record.conversationId)?.contentEquals(bytes) == true)
    }

    private fun validateNodes(nodes: List<MessageNode>) {
        check(nodes.map { it.id }.distinct().size == nodes.size)
        nodes.forEach { node ->
            check(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices &&
                node.messages.map { it.id }.distinct().size == node.messages.size)
        }
    }

    private fun matchesNodesHash(nodes: List<MessageNode>, expectedHashes: Collection<String>): Boolean =
        expectedHashes.isNotEmpty() && (hashNodes(nodes) in expectedHashes ||
            hashNodes(nodes, legacyModelReceipt = true) in expectedHashes)

    private fun hashNodes(nodes: List<MessageNode>, legacyModelReceipt: Boolean = false): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = DigestOutputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }, digest)
        nodes.forEach { original ->
            val node = original.withoutRecallFlags()
            if (legacyModelReceipt) json.encodeToStream(
                CheckpointModelReceiptCompatibility.node(json.encodeToJsonElement(node)), sink)
            else json.encodeToStream(node, sink)
            digest.update(0.toByte())
        }
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
    catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
    catch (_: Exception) {
        // Decode errors can contain chat text. No private payload or exception cause in logs/UI.
        throw IllegalStateException("续聊恢复副本暂未能合并，原记录已保留，请勿清除应用数据。")
    }
}
