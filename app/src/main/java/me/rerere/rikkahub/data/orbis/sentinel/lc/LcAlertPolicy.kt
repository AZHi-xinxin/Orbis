package me.rerere.rikkahub.data.orbis.sentinel.lc

import java.math.RoundingMode
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** All behavioral thresholds are configuration, not unchangeable permission gates. */
@Serializable
data class LcAlertPolicyConfig(
    val enabled: Boolean = false,
    val cooldownMs: Long = 30 * 60_000L,
    /** Zero disables this frequency limit. */
    val maxPerHour: Int = 6,
    val dedupMs: Long = 24 * 60 * 60_000L,
    /** AI-authored text is delivered verbatim. No interpolation or appended instructions. */
    val prompt: String? = null,
    /** Explicit migration only; new rules do not silently inherit someone else's wording. */
    val useLegacyFormatter: Boolean = false,
    val legacyTimeZone: String = "Asia/Shanghai",
    /** Explicit migration only; new rules include their rule ID in the event identity. */
    val legacyIdentity: Boolean = false,
    /** Migration rules may share a group to preserve the original cross-type hourly budget. */
    val throttleGroup: String? = null,
    val eventTypes: Set<String> = setOf("app_timeout", "night_usage", "manual_test", "visual_interaction"),
    /** Empty filters mean any otherwise-valid package/reason. */
    val appPackages: Set<String> = emptySet(),
    val visualReasons: Set<String> = emptySet(),
    val minimumDurationMinutes: Int? = null,
    val minimumUsageThresholdMinutes: Int = 60,
    val maximumUsageMinutes: Int = 1440,
    val maximumVisualCharacters: Int = 200,
    val eventClockWindowMs: Long = 10 * 60_000L,
    val usageSnapshotMaxAgeMs: Long = 120_000L,
    val excludedUsagePackages: Set<String> = LC_LEGACY_EXCLUDED_PACKAGES,
    val excludedUsagePackageFamilies: Set<String> = setOf("me.rerere.rikkahub", "com.lover.connect"),
)

val LC_LEGACY_EXCLUDED_PACKAGES: Set<String> = setOf(
    "android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller",
    "com.android.launcher", "com.android.launcher2", "com.android.launcher3", "com.miui.home",
    "com.mi.android.globallauncher", "com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher",
    "com.huawei.android.launcher", "com.oppo.launcher", "com.coloros.launcher", "com.vivo.launcher",
    "com.bbk.launcher", "com.android.inputmethod.latin", "com.google.android.inputmethod.latin",
    "com.samsung.android.honeyboard", "com.touchtype.swiftkey", "com.baidu.input", "com.baidu.input_mi",
    "com.baidu.input_huawei", "com.sohu.inputmethod.sogou", "com.sohu.inputmethod.sogou.xiaomi",
    "com.iflytek.inputmethod", "com.iflytek.inputmethod.miui",
)

private val LEGACY_TYPES = setOf("app_timeout", "night_usage", "manual_test", "visual_interaction")
private val LEGACY_REASONS = setOf("low_battery", "night_observation", "interesting_content", "visual_observation")
private val PACKAGE = Regex("^[A-Za-z0-9_.]{1,180}$")
private val policyJson = Json { encodeDefaults = true }

@Serializable
data class LcValidatedAlert(
    val eventId: String,
    val type: String,
    val timestampMs: Long,
    val appPackage: String,
    val appLabel: String,
    val reason: String? = null,
    val message: String? = null,
    val durationMinutes: Int? = null,
    val usageThresholdMinutes: Int? = null,
    val usageSnapshotAtMs: Long? = null,
) {
    val occurredAtMs: Long get() = usageSnapshotAtMs ?: timestampMs
    val cooldownKey: String get() = "$type:$appPackage" + if (type == "visual_interaction") ":$reason" else ""
}

data class LcAlertValidation(val event: LcValidatedAlert? = null, val error: String? = null)

/** Accepts only the old alert envelope, never a location event or arbitrary model instruction. */
fun validateLcAlert(payloadJson: String, config: LcAlertPolicyConfig, nowMs: Long): LcAlertValidation {
    validateLcPolicyConfig(config)
    require(nowMs >= 0) { "lc_invalid_clock" }
    if (payloadJson.toByteArray().size > 64 * 1024) return invalid("payload_too_large")
    val body = runCatching { policyJson.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
        ?: return invalid("invalid_payload")
    val type = body.string("type") ?: return invalid("unsupported_event_type")
    if (type !in LEGACY_TYPES) return invalid("unsupported_event_type")
    val id = body.string("event_id") ?: return invalid("invalid_event_id")
    if (id.codePointCount(0, id.length) !in 8..128 || hasLcControls(id)) return invalid("invalid_event_id")
    val timestamp = body.milliseconds("timestamp") ?: return invalid("invalid_timestamp")
    if (outsideClockWindow(timestamp, nowMs, config.eventClockWindowMs)) return invalid("stale_timestamp")
    val appPackage = if ("app_package" in body) body.string("app_package") ?: return invalid("invalid_package") else ""
    if (appPackage.isNotEmpty() && !PACKAGE.matches(appPackage)) return invalid("invalid_package")
    val label = if ("app_label" in body) body.string("app_label") ?: return invalid("invalid_label") else ""
    if (label.codePointCount(0, label.length) > 64 || hasLcControls(label)) return invalid("invalid_label")
    val base = LcValidatedAlert(id, type, timestamp, appPackage, label.trim().ifBlank { "ĳ��Ӧ��" })
    val result = when (type) {
        "app_timeout" -> {
            if (appPackage.isBlank() || isExcludedLcUsagePackage(appPackage, config)) return invalid("excluded_usage_package")
            if (body.string("usage_basis") != "continuous_app") return invalid("invalid_usage_basis")
            val threshold = body.integer("usage_threshold_minutes") ?: return invalid("invalid_usage_threshold")
            if (threshold !in config.minimumUsageThresholdMinutes..config.maximumUsageMinutes) return invalid("invalid_usage_threshold")
            val duration = body.integer("duration_minutes") ?: return invalid("invalid_duration")
            if (duration !in threshold..config.maximumUsageMinutes) return invalid("invalid_duration")
            val snapshot = body.milliseconds("usage_snapshot_at") ?: return invalid("invalid_usage_snapshot")
            if (snapshot < 0 || snapshot > nowMs || nowMs - snapshot > config.usageSnapshotMaxAgeMs || snapshot > timestamp) {
                return invalid("invalid_usage_snapshot")
            }
            base.copy(durationMinutes = duration, usageThresholdMinutes = threshold, usageSnapshotAtMs = snapshot)
        }
        "visual_interaction" -> {
            val reason = body.string("reason") ?: return invalid("invalid_visual_reason")
            if (reason !in LEGACY_REASONS) return invalid("invalid_visual_reason")
            val message = body.string("message") ?: return invalid("invalid_visual_message")
            if (message.isBlank() || message.codePointCount(0, message.length) !in 1..config.maximumVisualCharacters ||
                hasLcControls(message)) return invalid("invalid_visual_message")
            // Never interpret duration fields attached to a visual observation as a usage snapshot.
            base.copy(reason = reason, message = message.trim())
        }
        else -> {
            val duration = if ("duration_minutes" in body) body.nonNegativeDuration("duration_minutes", config.maximumUsageMinutes)
                ?: return invalid("invalid_duration") else 0
            base.copy(durationMinutes = duration)
        }
    }
    return LcAlertValidation(event = result)
}

fun lcAlertConditionMismatch(event: LcValidatedAlert, config: LcAlertPolicyConfig): String? = when {
    !config.enabled -> "disabled"
    event.type !in config.eventTypes -> "type_not_selected"
    config.appPackages.isNotEmpty() && event.appPackage !in config.appPackages -> "package_not_selected"
    event.type == "visual_interaction" && config.visualReasons.isNotEmpty() && event.reason !in config.visualReasons -> "reason_not_selected"
    config.minimumDurationMinutes != null && (event.durationMinutes ?: -1) < config.minimumDurationMinutes -> "duration_below_trigger"
    else -> null
}

fun lcAlertText(event: LcValidatedAlert, config: LcAlertPolicyConfig): String? =
    config.prompt ?: if (config.useLegacyFormatter) formatLegacyLcAlert(event, ZoneId.of(config.legacyTimeZone)) else null

fun lcAlertStableEventId(ruleId: String, rawEventId: String, legacyIdentity: Boolean): String =
    "lc:" + lcAlertSha256(if (legacyIdentity) "lc:alert:$rawEventId" else "native-lc:$ruleId:$rawEventId")

internal fun lcAlertPayloadHash(event: LcValidatedAlert): String = lcAlertSha256(policyJson.encodeToString(event))
internal fun lcAlertSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun validateLcPolicyConfig(config: LcAlertPolicyConfig) {
    require(config.cooldownMs >= 0 && config.dedupMs >= 0 && config.maxPerHour >= 0 &&
        config.eventClockWindowMs >= 0 && config.usageSnapshotMaxAgeMs >= 0 &&
        config.minimumUsageThresholdMinutes >= 1 && config.maximumUsageMinutes >= config.minimumUsageThresholdMinutes &&
        config.maximumVisualCharacters >= 1 && (config.minimumDurationMinutes == null || config.minimumDurationMinutes >= 0)) {
        "lc_invalid_policy"
    }
    require(config.eventTypes.all { it in LEGACY_TYPES } && config.visualReasons.all { it in LEGACY_REASONS } &&
        config.appPackages.all { PACKAGE.matches(it) }) { "lc_invalid_trigger_filter" }
    require(config.throttleGroup == null || config.throttleGroup.isNotBlank() && config.throttleGroup.length <= 180) {
        "lc_invalid_throttle_group"
    }
    require(config.prompt == null || config.prompt.isNotBlank() && config.prompt.toByteArray().size <= 64 * 1024) { "lc_invalid_prompt" }
    if (config.useLegacyFormatter) require(runCatching { ZoneId.of(config.legacyTimeZone) }.isSuccess) { "lc_invalid_timezone" }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.integer(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
private fun JsonObject.milliseconds(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content
    ?.toBigDecimalOrNull()?.let { value -> runCatching { value.movePointRight(3).setScale(0, RoundingMode.DOWN).longValueExact() }.getOrNull() }
private fun JsonObject.nonNegativeDuration(key: String, maximum: Int): Int? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBigDecimalOrNull()?.let {
        if (it.signum() < 0 || it > maximum.toBigDecimal()) null else it.toInt()
    }
private fun outsideClockWindow(timestamp: Long, now: Long, window: Long): Boolean =
    timestamp < 0 || if (timestamp <= now) now - timestamp > window else timestamp - now > window
private fun invalid(code: String) = LcAlertValidation(error = code)
private fun isExcludedLcUsagePackage(value: String, config: LcAlertPolicyConfig): Boolean {
    val normalized = value.lowercase(Locale.ROOT)
    return normalized in config.excludedUsagePackages || config.excludedUsagePackageFamilies.any {
        normalized == it || normalized.startsWith("$it.")
    }
}
private fun hasLcControls(value: String): Boolean = value.codePoints().anyMatch {
    Character.getType(it) in setOf(Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(),
        Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt())
}
