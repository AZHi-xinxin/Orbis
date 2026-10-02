package me.rerere.rikkahub.data.ai.transformers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.OrbisUserMessageTime
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val TIME_PROJECTION = "orbis_user_message_time_projection"

internal fun captureOrbisUserMessageTime(
    enabled: Boolean,
    epochMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): OrbisUserMessageTime? = if (!enabled) null else OrbisUserMessageTime(
    epochMillis, zone.id, zone.rules.getOffset(Instant.ofEpochMilli(epochMillis)).totalSeconds,
)

/** Request-only metadata projection; does not mutate a saved part, role, or the system prefix. */
internal fun applyOrbisUserMessageTimes(messages: List<UIMessage>, enabled: Boolean): List<UIMessage> =
    messages.map { message ->
        val parts = message.parts.filterNot { part ->
            part is UIMessagePart.Text &&
                (part.metadata?.get(TIME_PROJECTION) as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull == true
        }
        val saved = message.orbisUserMessageTime
        val human = message.role == MessageRole.USER && !message.isSynthetic && message.orbisEvent == null &&
            message.orbisVoiceCallKind in setOf(null, "turn") && message.getTools().isEmpty()
        val text = if (enabled && human && saved != null) formatOrbisUserMessageTime(saved) else null
        message.copy(parts = if (text == null) parts else parts + UIMessagePart.Text(
            "<orbis_user_message_time>发送时刻：$text。此为本条人类消息的历史时点，不是当前时间。</orbis_user_message_time>",
            metadata = buildJsonObject { put(TIME_PROJECTION, true) },
        ))
    }

private fun formatOrbisUserMessageTime(time: OrbisUserMessageTime): String? = runCatching {
    require(time.epochMillis in 0..253402300799999L)
    require(time.zoneId.length in 1..128 && time.zoneId.none(Char::isISOControl))
    ZoneId.of(time.zoneId) // Reject untrusted/malformed metadata; do not reinterpret its original offset.
    val value = Instant.ofEpochMilli(time.epochMillis).atOffset(ZoneOffset.ofTotalSeconds(time.offsetSeconds))
    "${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value)} [${time.zoneId}]"
}.getOrNull()

object OrbisUserMessageTimeTransformer : InputMessageTransformer {
    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        applyOrbisUserMessageTimes(messages, ctx.assistant.enableUserMessageTime)
}
