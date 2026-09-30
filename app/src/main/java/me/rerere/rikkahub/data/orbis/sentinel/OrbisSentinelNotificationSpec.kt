package me.rerere.rikkahub.data.orbis.sentinel

import java.security.MessageDigest
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent

/** Content-free notification projection. Never copy event text, observations or errors here. */
data class OrbisSentinelNotificationSpec(
    val identity: String,
    val notificationId: Int,
    val channelId: String,
    val level: OrbisSentinelNotificationLevel,
    val title: String,
    val content: String,
    val conversationId: String,
    val receivedAtMs: Long,
) {
    val notificationTag: String get() = "orbis-native-sentinel:$identity"
}

/**
 * The caller must have checked durable acceptance, the current human master and non-duplication.
 * This projection additionally rejects mismatched binding/source and receipts that cannot truthfully
 * be presented as waiting for processing. A completed one-shot may already be disabled here.
 */
fun sentinelAcceptedNotificationSpec(
    rule: OrbisSentinelRule,
    event: OrbisInboxEvent,
): OrbisSentinelNotificationSpec? {
    if (event.source != "native_sentinel.${rule.id}" || event.assistantId != rule.assistantId ||
        event.conversationId != rule.conversationId || rule.conversationId.isBlank() ||
        rule.assistantId.isBlank() || rule.id.isBlank()) return null
    if (event.state !in setOf("accepted", "queued", "displayed", "generating", "pending_tool")) return null
    val identity = MessageDigest.getInstance("SHA-256")
        .digest("${rule.id}\n${rule.assistantId}\n${rule.conversationId}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val cleanName = rule.name.map {
        if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it
    }.joinToString("").trim().ifBlank { "本地哨兵" }
    val title = cleanName.substring(0, cleanName.offsetByCodePoints(0, minOf(80, cleanName.codePointCount(0, cleanName.length))))
    return OrbisSentinelNotificationSpec(
        identity = identity,
        notificationId = identity.take(7).toInt(16),
        channelId = when (rule.notificationLevel) {
            OrbisSentinelNotificationLevel.LIGHT -> "orbis_sentinel_light_v1"
            OrbisSentinelNotificationLevel.STRONG -> "orbis_sentinel_strong_v1"
        },
        level = rule.notificationLevel,
        title = title,
        content = "已送入固定会话，等待AI处理",
        conversationId = event.conversationId,
        receivedAtMs = event.receivedAt,
    )
}
