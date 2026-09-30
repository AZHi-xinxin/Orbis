package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Audit metadata only. Neither summaries nor archived chat text are exposed by history. */
@Serializable
data class OrbisCompactionEvent(
    val id: Uuid,
    val conversationId: Uuid,
    val assistantId: Uuid,
    val windowTitle: String,
    val createdAtEpochMillis: Long,
    val beforeTokens: Long,
    val afterTokens: Long,
    val beforeBasis: String,
    val afterBasis: String,
    val summaryHash: String,
    val summaryMessageId: Uuid,
    val keptRecent: Int,
    val status: String = "committed",
    val rollbackAvailable: Boolean = false,
)

data class OrbisCompactionMetadata(
    val summaryMessageId: Uuid,
    val summaryText: String,
    val keepRecent: Int,
    val beforeTokens: Long,
    val afterTokens: Long,
    val beforeBasis: String,
    val afterBasis: String,
    val eventId: Uuid = Uuid.random(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

data class OrbisCompactionCommit(val conversation: Conversation, val event: OrbisCompactionEvent)

/** A stale stream/full-state save must not resurrect pre-compaction messages. */
class OrbisCompactionConflictException : IllegalStateException("conversation_compaction_changed_reload_required")

class OrbisRollbackLimitException(val estimatedTokens: Long, val maximumTokens: Long) :
    IllegalStateException("rollback_exceeds_threshold_plus_50k: $estimatedTokens > $maximumTokens")

internal fun requireCompactionEpoch(expected: Long, actual: Long) {
    if (expected != actual) throw OrbisCompactionConflictException()
}

fun orbisCompactionSummaryHash(summary: String): String =
    MessageDigest.getInstance("SHA-256").digest(summary.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** Includes alternatives, selected branches, attachments and completed tool results. */
internal fun compactionNodeFingerprint(nodes: List<MessageNode>): String =
    orbisCompactionSummaryHash(JsonInstant.encodeToString(nodes))

/** Restore the previous page without discarding messages/edits made after compaction.
 * Deleted retained nodes stay deleted; edits to retained nodes win over their backup.
 * The synthetic summary disappears. No older rollback slot is ever consulted.
 */
internal fun restoreCompactionNodes(
    original: List<MessageNode>,
    compactedNodeIds: Set<Uuid>,
    summaryNodeId: Uuid,
    current: List<MessageNode>,
): List<MessageNode> {
    require(original.isNotEmpty()) { "compaction_backup_empty" }
    require(original.map { it.id }.distinct().size == original.size) { "compaction_backup_duplicate_nodes" }
    require(current.map { it.id }.distinct().size == current.size) { "compaction_current_duplicate_nodes" }
    val currentById = current.associateBy { it.id }
    val originalIds = original.map { it.id }.toSet()
    val restored = original.mapNotNull { node ->
        if (node.id in compactedNodeIds && node.id != summaryNodeId) currentById[node.id]
        else currentById[node.id]?.takeUnless { it.id == summaryNodeId } ?: node
    }
    val appended = current.filter { it.id !in originalIds && it.id !in compactedNodeIds }
    return (restored + appended).also { require(it.isNotEmpty()) { "compaction_restore_would_be_empty" } }
}

internal fun requireCompactionRollbackWithinLimit(estimatedTokens: Long, thresholdTokens: Int) {
    require(estimatedTokens >= 0 && thresholdTokens >= 0) { "invalid_rollback_budget" }
    if (thresholdTokens > 0) {
        val maximum = thresholdTokens.toLong() + 50_000L
        if (estimatedTokens > maximum) throw OrbisRollbackLimitException(estimatedTokens, maximum)
    }
}
