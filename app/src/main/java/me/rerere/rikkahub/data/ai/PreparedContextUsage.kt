package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.OrbisPreparedContextUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import java.security.MessageDigest
import kotlin.uuid.Uuid

private val receiptJson = Json { encodeDefaults = true }

/** Display evidence only. Changing the threshold merely recomputes the ratio, not the input. */
internal fun preparedContextConfiguration(
    assistant: Assistant,
    model: Model?,
    systemPrompt: String?,
    orbisPrompt: OrbisConversationPrompt,
    modeIds: Set<Uuid>,
    lorebookIds: Set<Uuid>,
    workspaceCwd: String?,
): String = digestStrings(listOf(
    assistant.copy(compactionThresholdTokens = 0).toString(), model.toString(),
    systemPrompt.orEmpty(), orbisPrompt.toString(),
    modeIds.map { it.toString() }.sorted().joinToString(","),
    lorebookIds.map { it.toString() }.sorted().joinToString(","), workspaceCwd.orEmpty(),
))

/** Stream each part to the digest, not a second giant copy of the whole conversation.
 * Deliberately ignore display-only flags, translations, provider usage and this receipt itself.
 * Run only at completed step boundaries and off the main thread in UI, never on streamed tokens.
 */
internal fun preparedContextHistoryDigest(messages: List<UIMessage>): String = digestStrings(sequence {
    messages.forEach { message ->
        yield(message.id.toString())
        yield(message.role.name)
        yield(message.modelId.toString())
        yield(message.usageContextInvalidated.toString())
        yield(message.toolRecordRevision.toString())
        message.parts.forEach { yield(receiptJson.encodeToString(UIMessagePart.serializer(), it)) }
        yield("/message")
    }
}.asIterable())

private fun digestStrings(values: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun attachPreparedContextUsage(
    messages: List<UIMessage>, tokens: Long, preparedAtMillis: Long,
    epoch: Long, assistantId: Uuid, modelId: Uuid, configuration: String,
): List<UIMessage> {
    require(tokens >= 0)
    val last = messages.lastOrNull() ?: return messages
    if (last.role != MessageRole.ASSISTANT) return messages
    val receipt = OrbisPreparedContextUsage(tokens, preparedAtMillis, epoch,
        assistantId.toString(), modelId.toString(), configuration, preparedContextHistoryDigest(messages))
    return messages.dropLast(1) + last.copy(orbisPreparedContextUsage = receipt)
}

/** Never search older replies or another branch when the latest receipt is absent/stale. */
internal fun validPreparedContextUsage(
    messages: List<UIMessage>, epoch: Long, assistantId: Uuid, modelId: Uuid?, configuration: String,
): OrbisPreparedContextUsage? {
    val last = messages.lastOrNull() ?: return null
    if (last.role != MessageRole.ASSISTANT || last.finishedAt == null) return null
    val receipt = last.orbisPreparedContextUsage ?: return null
    if (receipt.tokens < 0 || receipt.preparedAtMillis <= 0 || receipt.contextEpoch != epoch ||
        receipt.assistantId != assistantId.toString() || receipt.modelId != modelId?.toString() ||
        receipt.configurationDigest != configuration) return null
    return receipt.takeIf { it.historyDigest == preparedContextHistoryDigest(messages) }
}
