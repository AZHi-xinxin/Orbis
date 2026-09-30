package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Private device storage only. Call every method on IO, never on the UI thread.
 * Implementations must atomically replace a whole bounded record and throw on failed durability.
 * No credentials/settings are accepted: records contain only this generation's chat tail.
 */
interface GenerationCheckpointStore {
    fun read(conversationId: Uuid): ByteArray?
    /** Presence only, not proof of validity. Disk implementations should use metadata, not decode.
     * Unreadable storage must throw rather than claim there is no unresolved generation.
     */
    fun exists(conversationId: Uuid): Boolean = read(conversationId) != null
    fun writeAtomic(conversationId: Uuid, bytes: ByteArray)
    fun delete(conversationId: Uuid)
}

class GenerationCheckpointException(val code: String) : IllegalStateException(
    "回复恢复记录未能安全处理（$code）；原记录保留，未自动执行工具。",
)

@Serializable
enum class GenerationToolStatus { STARTED, COMPLETED }

data class GenerationToolTransition(
    val callId: String,
    val name: String,
    val status: GenerationToolStatus,
)

data class GenerationCheckpointHandle internal constructor(
    val conversationId: Uuid,
    val runId: Uuid,
    val ownerId: Uuid,
    val epoch: Long,
    val prefixCount: Int,
    val suffixCount: Int = 0,
)

/** Nothing in this result authorizes generation, tool execution, or queue dispatch.
 * Persist [conversation], pause the old turn/queue, and only then clear its exact handle.
 */
data class GenerationCheckpointRecovery(
    val handle: GenerationCheckpointHandle,
    val conversation: Conversation,
    val changed: Boolean,
    val unknownToolIds: Set<String>,
)

@Serializable
private data class CheckpointAnchor(val nodeId: Uuid, val messageIds: List<Uuid>)

@Serializable
private data class CheckpointTool(
    val callId: String,
    val name: String,
    val inputHash: String,
    val status: GenerationToolStatus,
    val completedHash: String? = null,
)

@Serializable
private data class CheckpointRecord(
    val version: Int = 1,
    val conversationId: Uuid,
    val ownerId: Uuid,
    val runId: Uuid,
    val epoch: Long,
    val prefixCount: Int,
    val prefixHash: String,
    val suffixCount: Int,
    val suffixHash: String,
    val baseTailHash: String,
    val anchors: List<CheckpointAnchor>,
    val tail: List<MessageNode>,
    val tools: List<CheckpointTool> = emptyList(),
)

@Serializable
private data class CheckpointEnvelope(val record: CheckpointRecord, val sha256: String)

/** One exact generation per window. No whole-history text is stored or encoded per chunk.
 * Protected prefix/suffix hashing happens only at begin/rebase/recovery/confirmed final commit.
 * A process crash between a remote side effect and COMPLETED remains explicitly unknown.
 */
@OptIn(ExperimentalSerializationApi::class)
class GenerationCheckpointJournal(private val store: GenerationCheckpointStore) {
    private val json = Json { encodeDefaults = true }

    /** Cheap gate for already-initialized sessions too. A true result MUST be recovered/settled
     * before starting another send. It may be corrupt; never interpret existence as validity.
     * This method does not acknowledge, delete, merge, or publish a checkpoint.
     */
    @Synchronized
    fun hasCheckpoint(conversationId: Uuid): Boolean = guarded { store.exists(conversationId) }

    @Synchronized
    fun begin(
        base: Conversation,
        prefixCount: Int = base.messageNodes.lastIndex.coerceAtLeast(0),
        suffixCount: Int = 0,
    ): GenerationCheckpointHandle = guarded {
        checkSafe(read(base.id) == null, "unresolved_previous_generation")
        val next = baseline(base, prefixCount, suffixCount, Uuid.random())
        commit(next)
        next.handle()
    }

    /** [tailNodes] is the mutable segment: current.messageNodes.drop(prefixCount)
     * .dropLast(suffixCount). The default suffixCount=0 preserves append-at-end callers.
     * For earlier regeneration, protect unchanged later nodes instead of copying them per chunk.
     */
    @Synchronized
    fun checkpoint(
        handle: GenerationCheckpointHandle,
        ownerId: Uuid,
        epoch: Long,
        tailNodes: List<MessageNode>,
        toolState: GenerationToolTransition? = null,
    ) = guarded {
        val previous = requireCurrent(handle)
        checkSafe(ownerId == previous.ownerId && epoch == previous.epoch, "owner_or_epoch_changed")
        validateTail(tailNodes, previous.anchors)
        val tools = previous.tools.toMutableList()
        if (toolState != null) {
            checkSafe(toolState.callId.length in 1..256 && toolState.name.length in 1..512, "invalid_tool_identity")
            val tool = findTool(tailNodes, toolState.callId)
            checkSafe(tool.toolName == toolState.name, "tool_identity_changed")
            val index = tools.indexOfFirst { it.callId == toolState.callId }
            when (toolState.status) {
                GenerationToolStatus.STARTED -> {
                    checkSafe(index < 0 && !tool.isExecuted, "tool_must_not_be_replayed")
                    tools += CheckpointTool(toolState.callId, toolState.name, inputHash(tool), GenerationToolStatus.STARTED)
                }
                GenerationToolStatus.COMPLETED -> {
                    checkSafe(index >= 0, "tool_start_receipt_missing")
                    val started = tools[index]
                    checkSafe(started.status == GenerationToolStatus.STARTED && started.name == tool.toolName &&
                        started.inputHash == inputHash(tool) && tool.isExecuted, "tool_completion_mismatch")
                    tools[index] = started.copy(status = GenerationToolStatus.COMPLETED, completedHash = toolHash(tool))
                }
            }
        }
        validateTools(tailNodes, tools)
        commit(previous.copy(tail = tailNodes, tools = tools))
    }

    /** Call only after Room committed an AI compact/rollback. No pending external effect may rebase. */
    @Synchronized
    fun rebaseAfterDurableCommit(
        handle: GenerationCheckpointHandle,
        newBase: Conversation,
        prefixCount: Int = newBase.messageNodes.lastIndex.coerceAtLeast(0),
        suffixCount: Int = 0,
    ): GenerationCheckpointHandle = guarded {
        val previous = requireCurrent(handle)
        checkSafe(newBase.id == previous.conversationId && newBase.assistantId == previous.ownerId &&
            newBase.compactionEpoch > previous.epoch, "invalid_rebase")
        checkSafe(previous.tools.none { it.status == GenerationToolStatus.STARTED }, "unknown_tool_cannot_rebase")
        val next = baseline(newBase, prefixCount, suffixCount, Uuid.random())
        // Atomic replace: never delete the old generation before the new baseline is durable.
        commit(next)
        next.handle()
    }

    /** Read-only recovery decision. An unrelated/newer DB snapshot is never overwritten. */
    @Synchronized
    fun recover(persisted: Conversation): GenerationCheckpointRecovery? = guarded {
        val record = read(persisted.id) ?: return@guarded null
        validateBaseIdentity(record, persisted)
        val protectedIds = (persisted.messageNodes.take(record.prefixCount) +
            persisted.messageNodes.takeLast(record.suffixCount)).map { it.id }.toSet()
        checkSafe(record.tail.none { it.id in protectedIds }, "checkpoint_overlaps_protected_nodes")
        val storedTail = persisted.messageNodes.drop(record.prefixCount).dropLast(record.suffixCount)
        val storedHash = nodesHash(storedTail)
        val rawHash = nodesHash(record.tail)
        val restoredTail = recoveryTail(record)
        val recoveryHash = nodesHash(restoredTail)
        checkSafe(storedHash == record.baseTailHash || storedHash == rawHash || storedHash == recoveryHash,
            "database_tail_changed")
        val changed = storedHash != recoveryHash
        // Favorite state is derived from its separate Room table and is intentionally transient
        // in MessageNode JSON. A recovery must not reset the current page's derived UI flag.
        val favorites = storedTail.associate { it.id to it.isFavorite }
        val restoredWithFavorites = restoredTail.map { node ->
            favorites[node.id]?.let { node.copy(isFavorite = it) } ?: node
        }
        GenerationCheckpointRecovery(record.handle(),
            if (changed) persisted.copy(messageNodes = persisted.messageNodes.take(record.prefixCount) +
                restoredWithFavorites + persisted.messageNodes.takeLast(record.suffixCount))
            else persisted,
            changed,
            record.tools.filter { it.status == GenerationToolStatus.STARTED }.map { it.callId }.toSet(),
        )
    }

    /** Room committed a later compaction epoch before the rebase acknowledgement reached IO.
     * The caller must enforce that new-epoch generation cannot precede rebase acknowledgement.
     * This never merges old contents into the new page, and never drops a pending external effect.
     */
    @Synchronized
    fun discardSupersededByDurableEpoch(persisted: Conversation): Boolean = guarded {
        val record = read(persisted.id) ?: return@guarded false
        checkSafe(persisted.assistantId == record.ownerId, "owner_or_epoch_changed")
        if (persisted.compactionEpoch <= record.epoch) return@guarded false
        checkSafe(record.tools.none { it.status == GenerationToolStatus.STARTED }, "unknown_tool_cannot_rebase")
        store.delete(persisted.id)
        true
    }

    /** [storedConversation] must be read/acknowledged after Room's successful commit, not a draft.
     * A stale run or a failed/partial save cannot erase a newer recovery record.
     */
    @Synchronized
    fun clearAfterDurableCommit(handle: GenerationCheckpointHandle, storedConversation: Conversation) = guarded {
        val record = requireCurrent(handle)
        validateBaseIdentity(record, storedConversation)
        val storedHash = nodesHash(storedConversation.messageNodes.drop(record.prefixCount).dropLast(record.suffixCount))
        val recoveryHash = nodesHash(recoveryTail(record))
        if (record.tools.any { it.status == GenerationToolStatus.STARTED }) {
            checkSafe(storedHash == recoveryHash, "unknown_tool_requires_interrupted_receipt")
        } else {
            checkSafe(storedHash == nodesHash(record.tail) || storedHash == recoveryHash, "checkpoint_not_committed")
        }
        store.delete(handle.conversationId)
    }

    private fun baseline(base: Conversation, prefixCount: Int, suffixCount: Int, runId: Uuid): CheckpointRecord {
        checkSafe(base.compactionEpoch >= 0 && prefixCount in 0..base.messageNodes.size &&
            suffixCount in 0..(base.messageNodes.size - prefixCount), "invalid_baseline")
        val tail = base.messageNodes.drop(prefixCount).dropLast(suffixCount)
        validateTail(tail, emptyList())
        return CheckpointRecord(conversationId = base.id, ownerId = base.assistantId, runId = runId,
            epoch = base.compactionEpoch, prefixCount = prefixCount,
            prefixHash = nodesHash(base.messageNodes.take(prefixCount)),
            suffixCount = suffixCount, suffixHash = nodesHash(base.messageNodes.takeLast(suffixCount)),
            baseTailHash = nodesHash(tail),
            anchors = tail.map { CheckpointAnchor(it.id, it.messages.map { message -> message.id }) }, tail = tail)
    }

    private fun requireCurrent(handle: GenerationCheckpointHandle): CheckpointRecord {
        // Re-read the bounded tail, not the history. A handle from an earlier journal instance
        // must not erase/overwrite a later generation after a restart or explicit recovery.
        val record = read(handle.conversationId)
        checkSafe(record != null && record.handle() == handle, "stale_generation_handle")
        return checkNotNull(record)
    }

    private fun CheckpointRecord.handle() = GenerationCheckpointHandle(conversationId, runId, ownerId, epoch, prefixCount, suffixCount)

    private fun validateBaseIdentity(record: CheckpointRecord, current: Conversation) {
        checkSafe(current.id == record.conversationId && current.assistantId == record.ownerId &&
            current.compactionEpoch == record.epoch, "owner_or_epoch_changed")
        checkSafe(record.prefixCount <= current.messageNodes.size &&
            record.suffixCount <= current.messageNodes.size - record.prefixCount &&
            nodesHash(current.messageNodes.take(record.prefixCount)) == record.prefixHash, "database_prefix_changed")
        checkSafe(nodesHash(current.messageNodes.takeLast(record.suffixCount)) == record.suffixHash,
            "database_suffix_changed")
    }

    private fun validateTail(tail: List<MessageNode>, anchors: List<CheckpointAnchor>) {
        checkSafe(tail.size <= MAX_TAIL_NODES && tail.size >= anchors.size, "checkpoint_tail_limit_or_missing")
        checkSafe(tail.map { it.id }.distinct().size == tail.size, "duplicate_tail_nodes")
        tail.forEach { node ->
            checkSafe(node.messages.isNotEmpty() && node.selectIndex in node.messages.indices &&
                node.messages.map { it.id }.distinct().size == node.messages.size, "invalid_tail_branch")
        }
        anchors.forEachIndexed { index, anchor ->
            val node = tail[index]
            checkSafe(node.id == anchor.nodeId && anchor.messageIds.all { id -> node.messages.any { it.id == id } },
                "baseline_branch_missing")
        }
    }

    private fun validateTools(tail: List<MessageNode>, tools: List<CheckpointTool>) {
        checkSafe(tools.size <= MAX_TOOL_RECEIPTS && tools.map { it.callId }.distinct().size == tools.size,
            "tool_receipt_limit_or_duplicate")
        tools.forEach { receipt ->
            val tool = findTool(tail, receipt.callId)
            checkSafe(tool.toolName == receipt.name && inputHash(tool) == receipt.inputHash, "tool_identity_changed")
            checkSafe(if (receipt.status == GenerationToolStatus.COMPLETED)
                tool.isExecuted && receipt.completedHash == toolHash(tool)
            else !tool.isExecuted && receipt.completedHash == null, "tool_receipt_changed")
        }
    }

    private fun findTool(tail: List<MessageNode>, callId: String): UIMessagePart.Tool {
        val found = tail.flatMap { it.currentMessage.parts.filterIsInstance<UIMessagePart.Tool>() }
            .filter { it.toolCallId == callId }
        checkSafe(found.size == 1, "tool_receipt_target_missing_or_ambiguous")
        return found.single()
    }

    private fun recoveryTail(record: CheckpointRecord): List<MessageNode> {
        return record.tail.map { node ->
            node.copy(messages = node.messages.mapIndexed { index, message ->
                if (index != node.selectIndex) message else message.copy(parts = message.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted)
                        part.withHostToolFailure(HostToolFailure.INTERRUPTED) else part
                })
            })
        }
    }

    private fun read(id: Uuid): CheckpointRecord? {
        val bytes = store.read(id) ?: return null
        checkSafe(bytes.size <= MAX_BYTES, "checkpoint_too_large")
        val envelope = json.decodeFromString<CheckpointEnvelope>(bytes.toString(Charsets.UTF_8))
        val record = envelope.record
        checkSafe(record.version == 1 && record.conversationId == id && record.epoch >= 0 &&
            record.prefixCount >= 0 && record.suffixCount >= 0 && record.suffixCount <= Int.MAX_VALUE - record.prefixCount,
            "invalid_checkpoint_header")
        checkSafe(envelope.sha256 == hashJson(record), "checkpoint_checksum_mismatch")
        validateTail(record.tail, record.anchors)
        validateTools(record.tail, record.tools)
        return record
    }

    private fun commit(record: CheckpointRecord) {
        val out = BoundedOutput()
        json.encodeToStream(CheckpointEnvelope(record, hashJson(record)), out)
        val bytes = out.toByteArray()
        store.writeAtomic(record.conversationId, bytes)
    }

    private fun nodesHash(nodes: List<MessageNode>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = DigestOutputStream(NullOutput, digest)
        nodes.forEach { node ->
            json.encodeToStream(node, out)
            // Literal NUL never occurs in serialized JSON; delimit nodes without a history blob.
            digest.update(0.toByte())
        }
        return digest.digest().hex()
    }

    private fun toolHash(tool: UIMessagePart.Tool) = hashJson(tool)
    private fun inputHash(tool: UIMessagePart.Tool) = hashJson(listOf(tool.toolCallId, tool.toolName, tool.input))
    private inline fun <reified T> hashJson(value: T): String {
        val digest = MessageDigest.getInstance("SHA-256")
        json.encodeToStream(value, DigestOutputStream(NullOutput, digest))
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun checkSafe(condition: Boolean, code: String) { if (!condition) throw GenerationCheckpointException(code) }

    private object NullOutput : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private class BoundedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) {
            if (count >= MAX_BYTES) throw GenerationCheckpointException("checkpoint_too_large")
            super.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length > MAX_BYTES - count) throw GenerationCheckpointException("checkpoint_too_large")
            super.write(bytes, offset, length)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (error: GenerationCheckpointException) { throw error }
    // Parsing/IO exceptions may embed private chat text or paths. Never retain or log their cause.
    catch (_: Exception) { throw GenerationCheckpointException("checkpoint_storage_or_decode_failed") }

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_TAIL_NODES = 256
        private const val MAX_TOOL_RECEIPTS = 256
    }
}
