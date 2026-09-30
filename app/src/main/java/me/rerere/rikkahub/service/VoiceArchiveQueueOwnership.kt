package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

/** Cleanup owns one allocated queue ID, never every historical request for the same call. */
internal fun ownedVoiceArchiveQueueItem(
    isolated: Boolean,
    requestMessageId: Uuid?,
    callId: String,
    messages: List<QueuedMessage>,
): Uuid? {
    if (isolated || requestMessageId == null) return null
    return messages.firstOrNull {
        it.id == requestMessageId && it.voiceCallId == callId && it.voiceCallKind in setOf("archive", "restore")
    }?.id
}

/** Read-only inputs only. A block means keep READY in the archive, not repair the old chat. */
internal fun isolatedVoiceArchiveCommitBlockReason(
    sessionPresent: Boolean,
    initialized: Boolean,
    hasCheckpoint: Boolean,
    busy: Boolean,
    recoveryBlocked: Boolean,
    ownerMatches: Boolean,
): String? = when {
    !sessionPresent || !initialized -> "摘要已保存在记录库；原窗口尚未就绪，未初始化聊天或恢复旧队列，也未回写聊天。"
    hasCheckpoint || recoveryBlocked -> "摘要已保存在记录库；原窗口的中断回复尚待核对，未恢复旧队列、清除恢复记录或回写聊天。"
    busy -> "摘要已保存在记录库；原窗口仍有生成或写入，未改动旧队列或回写聊天，请稍后仅恢复已有摘要。"
    !ownerMatches -> "摘要已保存在记录库；原窗口已更换 AI，未回写其他 AI 的聊天。"
    else -> null
}
