package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation

/** Use only after local producers joined, under the history mutex, with durable old-tool
 * holds still in force. Favorite comes from a separate table; every message field must match.
 */
internal fun sameDurableGenerationSnapshot(visible: Conversation, stored: Conversation): Boolean =
    visible.id == stored.id && visible.assistantId == stored.assistantId &&
        visible.compactionEpoch == stored.compactionEpoch && visible.consultation == stored.consultation &&
        visible.messageNodes.size == stored.messageNodes.size &&
        visible.messageNodes.zip(stored.messageNodes).all { (a, b) ->
            a.id == b.id && a.selectIndex == b.selectIndex && a.messages == b.messages
        }
