package me.rerere.rikkahub.data.orbis.voice

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode

/** Replace only call-owned nodes. Interleaved text, sentinels and alternative branches survive. */
internal fun collapseVoiceCallNodes(
    nodes: List<MessageNode>, callId: String, archived: List<MessageNode>, summary: UIMessage,
): List<MessageNode> {
    if (nodes.any { it.currentMessage.orbisVoiceCallId == callId &&
            it.currentMessage.orbisVoiceCallKind == "summary" }) return nodes
    val expected = archived.associateBy { it.id }
    val owned = nodes.filter { node -> node.messages.any { it.orbisVoiceCallId == callId } }
    check(owned.isNotEmpty()) { "通话原文不在当前分支，未修改聊天。记录库仍保留完整归档。" }
    check(owned.all { node ->
        val saved = expected[node.id]
        saved != null && saved.messages == node.messages && saved.selectIndex == node.selectIndex &&
            node.messages.all { it.orbisVoiceCallId == callId }
    }) { "通话原文已被编辑或切换分支，未覆盖更改；完整归档已保留。" }
    val ids = owned.map { it.id }.toSet()
    val last = nodes.indexOfLast { it.id in ids }
    return buildList {
        nodes.forEachIndexed { index, node ->
            if (node.id !in ids) add(node)
            if (index == last) add(summary.toMessageNode())
        }
    }
}

/** Every turn explains text-only input, even when a finite context limit drops the start marker. */
internal fun voiceTurnForModel(callId: String, text: String): String =
    OrbisVoiceCallProtocol.userTurn(callId, text).let {
        val line = it.indexOf('\n')
        it.take(line + 1) + "当前为语音通话：以下是转写文字，并非原始音频。你的回复会被朗读，请用自然短句，避免代码块、装饰符号和括号动作。\n" + it.substring(line + 1)
    }

internal fun voiceArchiveRequest(record: OrbisVoiceCallRecord,
    author: OrbisVoiceArchiveAuthor = OrbisVoiceArchiveAuthor.FALLBACK): String =
    OrbisVoiceCallProtocol.end(record) + "\n【已结束语音通话】收音和播放已经停止，后续回复不会自动朗读。" +
        (if (author == OrbisVoiceArchiveAuthor.ASSISTANT) "你是刚才参与这次通话的当前助手。请保持自己的身份，为自己和人类整理本次通话；这不是让其他人格替你发言。"
        else "你是独立的兜底记录整理器，不扮演通话中的任何一方，不冒充当前助手本人。") +
        "只输出一个 JSON 对象，两个字符串字段：summary（仅据原文归纳本次通话），" +
        "transcript（按顺序写出本次通话的文字记录，标明说话者；不虚构未说过的话）。" +
        "只写本次通话，不汇总之前的聊天；工具回执、系统提示不是任何一方亲口说的话。" +
        "宿主已另存真实原文，不需要你假装听到音色或语气。通话记录 ID：${record.id}。"
