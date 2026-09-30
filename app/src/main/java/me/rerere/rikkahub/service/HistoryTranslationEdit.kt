package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation

/** Pure CAS projection. Caller guards the live state and persistence with the history edit mutex. */
internal fun Conversation.withTranslationIfUnchanged(
    expected: UIMessage,
    expectedTranslation: String?,
    translation: String?,
): Conversation {
    var nodeIndex = -1
    var messageIndex = -1
    messageNodes.forEachIndexed { index, node ->
        node.messages.forEachIndexed { branch, message ->
            if (message.id == expected.id) {
                check(nodeIndex == -1) { "消息标识不唯一，本次未修改翻译。" }
                nodeIndex = index
                messageIndex = branch
            }
        }
    }
    check(nodeIndex >= 0) { "消息已不存在，本次未修改翻译。" }
    val node = messageNodes[nodeIndex]
    val target = node.messages[messageIndex]
    check(target.role == expected.role && target.parts == expected.parts && target.translation == expectedTranslation) {
        "消息或翻译已变化，本次翻译未覆盖现有内容，请重试。"
    }
    if (target.translation == translation) return this

    val messages = node.messages.toMutableList().also {
        it[messageIndex] = target.copy(translation = translation)
    }
    return copy(messageNodes = messageNodes.toMutableList().also {
        it[nodeIndex] = node.copy(messages = messages)
    })
}
