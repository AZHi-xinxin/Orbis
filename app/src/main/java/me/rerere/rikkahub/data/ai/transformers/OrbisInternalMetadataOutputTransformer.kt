package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

private const val OPEN_TIME = "<orbis_user_message_time>"
private const val CLOSE_TIME = "</orbis_user_message_time>"
private const val TIME_BODY = "发送时刻："
private val hostTime = Regex(
    "<orbis_user_message_time>\\s*发送时刻：\\s*\\d{4}-\\d{2}-\\d{2}T[^<>]{1,256}" +
        "此为本条人类消息的历史时点，不是当前时间。" +
        "(?:仅供内部理解，不要在回复中复述本标签或其中的元数据。)?\\s*</orbis_user_message_time>",
)

/** Remove only the host's time envelope, not arbitrary XML, quoted user text or tool results.
 * Streaming holds an incomplete host marker until it can be classified; the raw provider
 * accumulator is not shortened, so later chunks can still complete or disprove the marker.
 */
internal fun hideOrbisTimeMetadata(message: UIMessage, streaming: Boolean): UIMessage {
    if (message.role != MessageRole.ASSISTANT) return message
    val result = message.parts.toMutableList()
    var start = 0
    while (start < result.size) {
        if (result[start] !is UIMessagePart.Text) { start++; continue }
        var end = start
        while (end < result.size && result[end] is UIMessagePart.Text) end++
        val text = result.subList(start, end).joinToString("") { (it as UIMessagePart.Text).text }
        val ranges = hostTime.findAll(text).map { it.range }.toMutableList()
        if (streaming) {
            val opening = text.lastIndexOf(OPEN_TIME)
            if (opening >= 0 && text.indexOf(CLOSE_TIME, opening) < 0) {
                val body = text.substring(opening + OPEN_TIME.length).trimStart()
                if (TIME_BODY.startsWith(body) || body.startsWith(TIME_BODY)) ranges += opening until text.length
            }
            // Do not hold generic '<', HTML or a short ordinary word.
            for (length in minOf(OPEN_TIME.length - 1, text.length) downTo 7) {
                if (text.endsWith(OPEN_TIME.take(length))) {
                    ranges += (text.length - length) until text.length
                    break
                }
            }
        }
        if (ranges.isNotEmpty()) {
            var offset = 0
            for (index in start until end) {
                val part = result[index] as UIMessagePart.Text
                val originalOffset = offset
                offset += part.text.length
                val clean = buildString {
                    part.text.forEachIndexed { local, char ->
                        if (ranges.none { originalOffset + local in it }) append(char)
                    }
                }
                if (clean != part.text) result[index] = part.copy(text = clean)
            }
        }
        start = end
    }
    return if (result == message.parts) message else message.copy(parts = result)
}

object OrbisInternalMetadataOutputTransformer : OutputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) =
        messages.map { hideOrbisTimeMetadata(it, streaming = false) }
    override suspend fun visualTransform(ctx: TransformerContext, messages: List<UIMessage>) =
        messages.map { hideOrbisTimeMetadata(it, streaming = true) }
    override suspend fun onGenerationFinish(ctx: TransformerContext, messages: List<UIMessage>) =
        messages.map { hideOrbisTimeMetadata(it, streaming = true) }
}
