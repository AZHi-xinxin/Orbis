package me.rerere.rikkahub.data.orbis.health

import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** Positions are in the decoded image; never persisted or sent to a provider. */
internal data class HealthOcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height get() = (bottom - top).coerceAtLeast(1)
}
internal data class HealthScreenshotRecognition(val text: String, val lines: List<HealthOcrLine>)

private val healthLabels = linkedMapOf(
    "sleepMinutes" to "总睡眠|睡眠时长|睡眠时间|睡眠|Total sleep",
    "steps" to "步数|步教|Steps",
    "activeMinutes" to "活动时长|法动时长|活动时间|Active minutes",
    "calories" to "活动热量|卡路里|Active calories",
    "restingHeart" to "静息心率|Resting heart rate",
    "averageHeart" to "平均心率|Average heart rate",
    "heartRate" to "当次心率|心率|名率|Heart rate",
    "spo2" to "血氧饱和度|血氧|SpO2",
)

/** Associate the nearest value in the label's column, independently of OCR reading order.
 * Goal denominators, other cards, dates and ambiguous duplicate measurements are not values.
 */
internal fun parseHealthScreenshot(result: HealthScreenshotRecognition): Map<String, JsonPrimitive> {
    if (result.lines.isEmpty()) return parseHealthScreenshot(result.text)
    require(result.lines.size <= 5000) { "health_ocr_too_large" }
    data class Anchor(val id: String, val line: HealthOcrLine, val remainder: String)
    val anchors = result.lines.mapNotNull { line ->
        healthLabels.entries.firstNotNullOfOrNull { (id, labels) ->
            Regex("^[^\\p{L}\\p{IsHan}]{0,3}(?:$labels)(?=\\s|[：:0-9]|$)[ \\t：:]*", RegexOption.IGNORE_CASE)
                .find(line.text.trim())?.let { Anchor(id, line, line.text.trim().substring(it.range.last + 1)) }
        }
    }
    fun value(id: String, text: String): JsonPrimitive? {
        val t = text.trim().trim('|', '｜').trim().replace(",", "").replace("，", "")
        if (id == "sleepMinutes") {
            val m = Regex("^(\\d{1,2})\\s*(?:小时|时|h)\\s*(?:(\\d{1,2})\\s*(?:分钟|分|m))?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
            val minutes = m.groupValues[2].toIntOrNull() ?: 0
            if (minutes !in 0..59) return null
            return runCatching { healthValue(healthFields.first { it.id == id }, (m.groupValues[1].toInt() * 60 + minutes).toString()) }.getOrNull()
        }
        // Bare values are accepted only when geometry supplies a nearby matching label.
        val unit = when(id) {
            "steps" -> "步|steps"; "activeMinutes" -> "分钟|分|min"; "calories" -> "千卡|kcal"
            "spo2" -> "%|％"; else -> "次\\s*/\\s*(?:分钟|分)|bpm"
        }
        val m = Regex("^([0-9]{1,6}(?:\\.[0-9]{1,2})?)\\s*(?:$unit)?$", RegexOption.IGNORE_CASE).matchEntire(t) ?: return null
        return runCatching { healthValue(healthFields.first { it.id == id }, m.groupValues[1]) }.getOrNull()
    }
    val candidates = mutableMapOf<String, MutableList<JsonPrimitive>>()
    for (anchor in anchors) {
        val inline = value(anchor.id, anchor.remainder)
        if (inline != null) { candidates.getOrPut(anchor.id) { mutableListOf() }.add(inline); continue }
        val a = anchor.line
        // A few visually similar handwritten glyphs need independent unit evidence.
        // These remain editable OCR suggestions, never automatic health writes.
        val uncertainUnit = when {
            a.text.contains("步教") -> "步"
            a.text.contains("法动时长") -> "分钟|分"
            a.text.contains("名率") -> "次\\s*/\\s*(?:分钟|分)|bpm"
            else -> null
        }
        if(uncertainUnit!=null && result.lines.none { line ->
            line.top>=a.bottom-a.height/2 && line.top-a.bottom<=a.height*5 &&
                abs(line.left-a.left)<=maxOf(a.height*3,a.right-a.left) &&
                Regex("[0-9]\\s*(?:$uncertainUnit)",RegexOption.IGNORE_CASE).containsMatchIn(line.text)
        }) continue
        val nearby = result.lines.mapNotNull { line ->
            if (line === a || line.top < a.bottom - a.height / 2 || line.top - a.bottom > a.height * 4) return@mapNotNull null
            val dx = abs(line.left - a.left)
            if (dx > maxOf(a.height * 3, a.right - a.left)) return@mapNotNull null
            if (anchors.any { other -> other !== anchor && abs(other.line.top-a.top) < a.height &&
                    abs(line.left-other.line.left) < dx }) return@mapNotNull null
            val parsed = value(anchor.id, line.text) ?: return@mapNotNull null
            Triple(line.top - a.bottom, dx, parsed)
        }.sortedWith(compareBy<Triple<Int,Int,JsonPrimitive>> { it.first }.thenBy { it.second })
        val first = nearby.firstOrNull() ?: continue
        // Equal-row conflicting numbers need human correction, not a guessed winner.
        val row = nearby.filter { abs(it.first-first.first) < a.height/2 }.map { it.third }.distinct()
        if (row.size == 1) candidates.getOrPut(anchor.id) { mutableListOf() }.add(row.single())
    }
    return candidates.mapNotNull { (id, values) -> values.distinct().singleOrNull()?.let { id to it } }.toMap()
}
