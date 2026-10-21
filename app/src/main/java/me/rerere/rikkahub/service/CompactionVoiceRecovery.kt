package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.ai.checkpoint.HumanContinuationJournal
import me.rerere.rikkahub.data.model.Conversation

/** Caller holds the conversation edit mutex. Plan, CAS and reread precede journal retirement.
 * A failed/unknown CAS never acknowledges the recovery copy. Re-entry after a committed CAS
 * can prove the already-normalized prefix again without changing any message body or suffix.
 */
internal suspend fun repairCompactionVoiceMetadata(
    persisted: Conversation,
    journal: HumanContinuationJournal,
    read: suspend () -> Conversation?,
    commit: suspend (Conversation, Conversation) -> Conversation?,
): Conversation {
    val plan = journal.planCompactionVoiceMetadataRepair(persisted) ?: return persisted
    if (plan.needsMetadataCorrection) {
        checkNotNull(commit(plan.before, plan.corrected)) {
            "摘要归属核对期间聊天已更新，恢复副本已保留。"
        }
    }
    val verified = checkNotNull(read()) { "摘要归属核对未完成，恢复副本已保留。" }
    check(journal.finishCompactionVoiceMetadataRepair(plan, verified)) {
        "摘要归属核对期间记录已更新，恢复副本已保留。"
    }
    return verified
}
