package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.voice.*

/** Update historical call state in place; never invent a new user turn after the real input. */
object OrbisVoiceCallTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        projectVoiceCallState(messages, ctx.assistant.id.toString()) { callId ->
            OrbisVoiceCallRepository(ctx.context).get(callId)
        }
}
