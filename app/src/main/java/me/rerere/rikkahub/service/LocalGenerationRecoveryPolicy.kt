package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.withoutRecallFlags
import me.rerere.rikkahub.data.model.containsRecallFlagsOf

/** Use only after local producers joined, under the history mutex, with durable old-tool
 * holds still in force. Favorite is separate; stored recall flags may only advance.
 */
internal fun sameDurableGenerationSnapshot(visible: Conversation, stored: Conversation): Boolean =
    visible.id == stored.id && visible.assistantId == stored.assistantId &&
        visible.compactionEpoch == stored.compactionEpoch && visible.consultation == stored.consultation &&
        visible.messageNodes.size == stored.messageNodes.size &&
        visible.messageNodes.zip(stored.messageNodes).all { (a, b) ->
            a.id == b.id && a.selectIndex == b.selectIndex &&
                a.withoutRecallFlags().messages == b.withoutRecallFlags().messages && b.containsRecallFlagsOf(a)
        }
