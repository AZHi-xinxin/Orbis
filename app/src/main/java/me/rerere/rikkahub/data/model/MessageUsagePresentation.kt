package me.rerere.rikkahub.data.model

import me.rerere.ai.core.TokenUsage
import java.text.NumberFormat
import java.util.Locale

data class MessageUsageDetail(val label: String, val value: String)

data class MessageUsagePresentation(
    val summary: String,
    val details: List<MessageUsageDetail>,
    val explanations: List<String>,
)

/** Presentation only: never changes stored usage, combines calls, estimates price or computes a hit rate. */
fun presentMessageUsage(usage: TokenUsage?, durationMillis: Long?): MessageUsagePresentation? {
    if (usage == null) return null
    val duration = durationMillis?.takeIf { it > 0 }?.let { millis ->
        if (millis < 100) "不到 0.1 秒" else String.format(Locale.ROOT, "%.1f 秒", millis / 1000.0)
    }
    fun units(value: Int): String = if (value < 0) "记录异常，暂不能确认"
        else "${NumberFormat.getIntegerInstance(Locale.CHINA).format(value)} token"
    val cached = when {
        usage.cachedTokens < 0 -> "记录异常，暂不能确认"
        usage.cachedTokens == 0 -> "尚不能确认（记录值为 0，也可能未提供）"
        else -> units(usage.cachedTokens)
    }
    val details = listOf(
        MessageUsageDetail("发送给模型（记录值）", units(usage.promptTokens)),
        MessageUsageDetail("模型生成（记录值）", units(usage.completionTokens)),
        MessageUsageDetail("缓存复用（记录值）", cached),
        MessageUsageDetail("记录用时", duration ?: "尚无有效的完成用时记录"),
    )
    return MessageUsagePresentation(
        summary = if (duration == null) "用量记录" else "用量记录 · 用时$duration",
        details = details,
        explanations = emptyList(),
    )
}
