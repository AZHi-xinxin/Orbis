package me.rerere.ai.ui

import me.rerere.ai.core.MessageRole
import kotlinx.serialization.json.JsonPrimitive

const val HUMAN_RECALL_NOTICE = "该条消息已撤回"
const val AI_RECALL_NOTICE = "此条消息已被对方撤回"
const val AI_RECALL_CONTEXT_NOTE = "本消息已被你撤回，现在人类无法看到此条消息内容。"

fun UIMessage.recallNotice(): String = if (role == MessageRole.ASSISTANT) AI_RECALL_NOTICE else HUMAN_RECALL_NOTICE

/** Disposable presentation only. The owning assistant retains its recalled reply. */
fun UIMessage.recallPresentation(): UIMessage = if (orbisRecalled) copy(
    parts = listOf(UIMessagePart.Text(recallNotice())), annotations = emptyList(), translation = null,
    orbisQuote = null, orbisEvent = null, deletedToolRecords = emptyList(), usage = null,
    orbisPreparedContextUsage = null, configuredModelReceipt = null,
    privateRoomContentHidden = false, privateRoomPendingPresentation = false,
) else if (orbisQuoteRecalled) copy(orbisQuote = null) else this

/** Provider projection. Never save this over the original history. */
fun UIMessage.recallModelInput(): UIMessage = when {
    orbisRecalled && role == MessageRole.USER -> recallPresentation().copy(parts = listOf(UIMessagePart.Text(HUMAN_RECALL_NOTICE)))
    orbisRecalled && role == MessageRole.ASSISTANT -> copy(
        parts = parts.filterNot {
            orbisQuoteRecalled && it is UIMessagePart.Text &&
                it.metadata?.get("orbis_quoted_context_v1") == JsonPrimitive(true)
        } + UIMessagePart.Text(AI_RECALL_CONTEXT_NOTE),
        orbisQuote = if (orbisQuoteRecalled) null else orbisQuote,
    )
    orbisQuoteRecalled -> copy(orbisQuote = null, parts = parts.filterNot {
        it is UIMessagePart.Text && it.metadata?.get("orbis_quoted_context_v1") == JsonPrimitive(true)
    })
    else -> this
}
