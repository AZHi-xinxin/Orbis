package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.voice.*

/** Late queued turns cannot tell the AI it is still speaking after the user has hung up. */
object OrbisVoiceCallTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        val lastCall = messages.lastOrNull { it.orbisVoiceCallId != null } ?: return messages
        if (lastCall.orbisVoiceCallKind in setOf("archive", "summary", "ended_notice")) return messages
        val record = OrbisVoiceCallRepository(ctx.context).get(checkNotNull(lastCall.orbisVoiceCallId)) ?: return messages
        if (record.status == OrbisVoiceCallStatus.ACTIVE || record.status == OrbisVoiceCallStatus.CONNECTING) return messages
        return messages + UIMessage.user(OrbisVoiceCallProtocol.end(record) +
            "\n【已结束语音通话】这是一条宿主状态通知，不是用户口述。麦克风与播放已停止；前面的通话标记是历史状态，现在不再朗读回复。")
            .copy(isSynthetic = true)
    }
}
