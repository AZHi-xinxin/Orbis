package me.rerere.rikkahub.data.ai.checkpoint

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.preparedContextHistoryDigest
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.orbisCompactionSummaryHash

/** Narrow evidence for the old call-summary mislabel only. A complete compact receipt binds
 * the selected assistant tail to this exact summary. Tool inputs, outputs, approvals and all
 * existing non-text parts must remain identical; no tool is executed or given a new receipt.
 */
internal fun hasCompactionVoiceReceipt(message: UIMessage, summary: UIMessage): Boolean =
    message.role == MessageRole.ASSISTANT && !message.orbisVoiceCallId.isNullOrBlank() &&
        message.orbisVoiceCallKind == "turn" && message.getTools().any { tool ->
            tool.toolName == "compact" && tool.isExecuted && tool.output.any output@{ part ->
                val text = (part as? UIMessagePart.Text)?.text ?: return@output false
                val receipt = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: return@output false
                fun field(name: String) = (receipt[name] as? JsonPrimitive)?.contentOrNull
                field("status") == "compacted" && field("new_start_message_id") == summary.id.toString() &&
                    field("summary_sha256") == orbisCompactionSummaryHash(summary.toText())
            }
        }

internal fun retainsCompactionVoiceTail(
    before: MessageNode, after: MessageNode, summary: UIMessage, conversation: Conversation,
): Boolean {
    if (before.id != after.id || before.selectIndex != after.selectIndex ||
        before.messages.size != after.messages.size) return false
    return before.messages.zip(after.messages).all { (old, current) ->
        if (old == current) true else {
            if (old.id != before.currentMessage.id || !hasCompactionVoiceReceipt(old, summary) ||
                old.isSynthetic || old.orbisEvent != null || old.deletedToolRecords.isNotEmpty() ||
                old.getTools().any { !it.isExecuted } || current.parts.size < old.parts.size ||
                current.finishedAt?.let { it < old.createdAt } == true ||
                old.finishedAt?.let { end -> current.finishedAt?.let { it >= end } != true } == true) return false
            // A request may add its first successful model receipt after compact's tool boundary.
            // Existing model/provider receipts may NOT be replaced, even if the prose matches.
            val receipt = current.configuredModelReceipt
            if (old.configuredModelReceipt != receipt && (old.configuredModelReceipt != null || receipt == null ||
                    receipt.assistantId != conversation.assistantId.toString() ||
                    receipt.conversationId != conversation.id.toString() ||
                    receipt.providerId.isBlank() || receipt.modelId.isBlank())) return false
            // This display receipt is produced at the next completed request boundary. It is
            // not ignored: bind it to the current selected history through this exact tail.
            val prepared = current.orbisPreparedContextUsage
            if (old.orbisPreparedContextUsage != prepared) {
                val tailIndex = conversation.messageNodes.indexOfFirst { it.id == after.id }
                if (prepared == null || tailIndex < 0 || prepared.tokens < 0 || prepared.preparedAtMillis <= 0 ||
                    prepared.contextEpoch != conversation.compactionEpoch ||
                    prepared.assistantId != conversation.assistantId.toString() ||
                    current.modelId == null || prepared.modelId != current.modelId.toString() ||
                    !prepared.configurationDigest.matches(Regex("[0-9a-f]{64}")) ||
                    old.orbisPreparedContextUsage?.let { prepared.preparedAtMillis < it.preparedAtMillis } == true ||
                    prepared.historyDigest != preparedContextHistoryDigest(
                        conversation.messageNodes.take(tailIndex + 1).map { it.currentMessage })) return false
            }
            // All other identity, model, call, annotation, edit and presentation fields are exact.
            if (old.copy(parts = current.parts, finishedAt = current.finishedAt,
                    usage = current.usage, configuredModelReceipt = receipt,
                    orbisPreparedContextUsage = prepared) != current) return false
            // The confirmed incident only appends ordinary response/reasoning parts. Keep
            // unfamiliar new tools/media as a conflict rather than broadening this repair.
            if (current.parts.drop(old.parts.size).any {
                    it !is UIMessagePart.Text && it !is UIMessagePart.Reasoning }) return false
            old.parts.zip(current.parts).all { (part, retained) ->
                part == retained || when {
                    part is UIMessagePart.Text && retained is UIMessagePart.Text ->
                        retained.text.startsWith(part.text) && part.copy(text = retained.text) == retained
                    part is UIMessagePart.Reasoning && retained is UIMessagePart.Reasoning ->
                        part.finishedAt == null && retained.reasoning.startsWith(part.reasoning) &&
                            retained.finishedAt?.let { it < part.createdAt } != true &&
                            part.copy(reasoning = retained.reasoning, finishedAt = retained.finishedAt) == retained
                    else -> false // Includes all completed tools/receipts/media/metadata.
                }
            }
        }
    }
}
