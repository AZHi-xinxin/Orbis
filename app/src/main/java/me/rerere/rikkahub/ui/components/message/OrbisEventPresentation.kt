package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.OrbisEventMetadata
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Only local presentation metadata changes; opening explicitly marks this event as read. */
internal fun toggleOrbisEventPresentation(event: OrbisEventMetadata): OrbisEventMetadata =
    event.copy(collapsed = !event.collapsed, read = event.read || event.collapsed)

internal data class OrbisEventTimePresentation(val compactLabel: String, val detailLabel: String)

/** Receipt is not occurrence. Keep the distinction visible in details and accessibility text. */
internal fun orbisEventTimePresentation(
    event: OrbisEventMetadata,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): OrbisEventTimePresentation {
    val source = if (event.occurredAt != null) "来源提供的触发时间" else "接收时间（未提供触发时间）"
    return runCatching {
        val dateTime = Instant.ofEpochMilli(event.occurredAt ?: event.receivedAt).atZone(zone)
        val date = dateTime.toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        val pattern = when {
            date == today -> "HH:mm"
            date == today.minusDays(1) -> "'昨天' HH:mm"
            date.year == today.year -> "M月d日 HH:mm"
            else -> "yyyy年M月d日 HH:mm"
        }
        OrbisEventTimePresentation(
            compactLabel = dateTime.format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA)),
            detailLabel = "$source · ${dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA))}",
        )
    }.getOrElse { OrbisEventTimePresentation("时间不可用", "$source · 时间不可用") }
}
