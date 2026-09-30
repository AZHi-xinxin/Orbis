package com.lover.connect

import org.json.JSONObject

/** A bounded observation, never a model-controlled request to the duration channel. */
data class EyesAlertEvent(
    val type: String,
    val appPackage: String,
    val appLabel: String,
    val message: String,
    val observedAtMs: Long,
    val reason: String? = null,
    val usage: AppRestSnapshot? = null,
) {
    val cooldownKey: String get() = "$type:$appPackage:${reason.orEmpty()}"

    fun toJson(eventId: String): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("type", type)
        put("app_package", appPackage)
        put("app_label", appLabel)
        put("timestamp", observedAtMs / 1000.0)
        if (reason != null) {
            put("reason", reason)
            put("message", message)
        }
        usage?.let {
            put("duration_minutes", it.durationMinutes)
            put("usage_basis", "continuous_app")
            put("usage_threshold_minutes", it.thresholdMinutes)
            put("usage_snapshot_at", it.capturedAtMs / 1000.0)
        }
    }
}

object EyesAlertPolicy {
    private val visualReasons = setOf(
        "low_battery", "night_observation", "interesting_content", "visual_observation",
    )

    fun visual(action: String, reason: String, message: String, observedAtMs: Long,
               senderPackage: String = "com.lover.connect"): EyesAlertEvent? {
        if (action !in setOf("notify", "popup") || reason !in visualReasons ||
            message.isBlank() || message.codePointCount(0, message.length) > 200 ||
            hasUnsafeCharacters(message) || observedAtMs <= 0L) {
            return null
        }
        // This is the sender, not a claim that SystemUI or LC was the observed foreground app.
        return EyesAlertEvent(
            "visual_interaction", senderPackage, "屏幕观察", message.trim(), observedAtMs, reason,
        )
    }

    fun rest(snapshot: AppRestSnapshot, appLabel: String): EyesAlertEvent? {
        if (snapshot.thresholdMinutes !in AppRestPolicy.MIN_THRESHOLD_MINUTES..1440 ||
            snapshot.durationMinutes !in snapshot.thresholdMinutes..1440 ||
            AppRestPolicy.isExcludedPackage(snapshot.packageName) || snapshot.capturedAtMs <= 0L) return null
        val label = appLabel.filterNot { hasUnsafeCharacters(it.toString()) }.take(64).ifBlank { "当前应用" }
        return EyesAlertEvent(
            "app_timeout", snapshot.packageName, label,
            "你已连续使用「$label」约 ${snapshot.durationMinutes} 分钟，可以休息一下。",
            snapshot.capturedAtMs, usage = snapshot,
        )
    }

    fun isFresh(event: EyesAlertEvent, nowWallMs: Long): Boolean =
        nowWallMs >= event.observedAtMs && nowWallMs - event.observedAtMs <= 120_000L

    private fun hasUnsafeCharacters(value: String): Boolean = value.codePoints().anyMatch {
        Character.getType(it) in setOf(
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
        )
    }
}

/** Independent of screenshot cadence and the continuous-use threshold. In-memory, no history stored. */
class EyesAlertCooldown(private val cooldownMs: Long = 30 * 60_000L) {
    private val attempts = mutableMapOf<String, Long>()

    @Synchronized fun reserve(key: String, nowElapsedMs: Long): Boolean {
        if (nowElapsedMs < 0L) return false
        attempts.entries.removeAll { nowElapsedMs >= it.value && nowElapsedMs - it.value >= cooldownMs }
        val previous = attempts[key]
        if (previous != null) return false // Fail closed even if an invalid clock moves backwards.
        attempts[key] = nowElapsedMs
        return true
    }
}
