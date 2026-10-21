package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.OrbisManualContextPreview
import me.rerere.rikkahub.data.model.manualContextFingerprint
import java.time.Instant
import kotlin.uuid.Uuid

/** Manual and AI compaction share the same two-writer handoff. The preview is checked
 * after acquiring both writers; a later fresh input is retained by the lane's rebase.
 */
internal suspend fun commitManualContinuation(
    lane: HumanContinuationLane,
    preview: OrbisManualContextPreview,
    requireReady: () -> Unit,
    rewrite: suspend (Conversation) -> Pair<Conversation, Uuid>,
): Uuid {
    val storage = checkNotNull(lane.contextStore)
    var archiveId: Uuid? = null
    var captured = false
    try {
        storage.compact(capture = {
            requireReady()
            lane.captureManualContext(preview).also { captured = true }
        }) { source, durable ->
            // A successful continuation write may advance updateAt/clear suggestions, not
            // the title, owner, prompt, branches or any other part of the approved preview.
            val persistedPreview = source.copy(updateAt = durable.updateAt, chatSuggestions = durable.chatSuggestions)
            if (manualContextFingerprint(persistedPreview) != manualContextFingerprint(durable)) {
                lane.refreshManualContextMetadata(source, durable)
                error("窗口或原文已变化，本次未整理，请重新预览。")
            }
            val (after, archive) = rewrite(durable)
            archiveId = archive
            after
        }
        return checkNotNull(archiveId)
    } finally {
        if (captured) lane.releaseManualContext()
    }
}

/** Metadata-only projection for an old producer that captured the stale header before
 * the manual preview was rejected. Message bodies, selection, epoch and fresh input
 * always belong to the producer; only the exact previously observed header is replaced.
 */
internal class ContinuationMetadataRefresh(source: Conversation, durable: Conversation) {
    private val sourceKey = metadataKey(source)
    private val header = durable.copy(messageNodes = emptyList())

    init { require(sameDurableGenerationSnapshot(source, durable)) }

    fun project(snapshot: Conversation): Conversation {
        if (metadataKey(snapshot) != sourceKey) return snapshot
        return header.copy(messageNodes = snapshot.messageNodes, compactionEpoch = snapshot.compactionEpoch,
            updateAt = snapshot.updateAt, chatSuggestions = snapshot.chatSuggestions,
            newConversation = snapshot.newConversation)
    }

    private fun metadataKey(snapshot: Conversation) = manualContextFingerprint(snapshot.copy(
        messageNodes = emptyList(), compactionEpoch = 0L, updateAt = Instant.EPOCH, chatSuggestions = emptyList(),
    ))
}

/** Capture the current displayed producer once. Forking does not mutate/retire its journals. */
internal fun forkDisplayedPrefix(main: Conversation, continuation: Conversation?, messageId: Uuid): Pair<Conversation, List<MessageNode>> {
    val source = continuation ?: main
    require(source.id == main.id && source.assistantId == main.assistantId) { "窗口归属已变化，请重新选择消息。" }
    val index = source.messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    check(index >= 0) { "所选消息已不在当前页面，请重新选择；原记录未改变。" }
    return source to source.messageNodes.take(index + 1)
}
