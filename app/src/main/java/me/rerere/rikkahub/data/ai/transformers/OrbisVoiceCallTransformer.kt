package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.voice.*

/** Optional live acoustics only. Ordinary chat never receives recurring ended-call notices. */
object OrbisVoiceCallTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!ctx.settings.callAcoustics.enabled) return messages
        return try {
            projectActiveVoiceCallAcoustics(messages, ctx.assistant.id.toString(),
                readRecord = { callId -> OrbisVoiceCallRepository(ctx.context).get(callId) },
                readAcousticNote = { callId, messageId ->
                    me.rerere.rikkahub.service.OrbisVoiceCallRuntime.get(ctx.context)
                        .acousticNote(callId, messageId)
                })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { messages }
    }
}
