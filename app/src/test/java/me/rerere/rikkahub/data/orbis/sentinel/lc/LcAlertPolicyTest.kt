package me.rerere.rikkahub.data.orbis.sentinel.lc

import java.security.MessageDigest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LcAlertPolicyTest {
    private fun validate(payload: String, config: LcAlertPolicyConfig = LC_TEST_CONFIG) =
        validateLcAlert(payload, config, LC_TEST_NOW)

    @Test fun everyLegacyAlertTypeValidatesButLocationDoesNot() {
        listOf("visual_interaction", "app_timeout", "night_usage", "manual_test").forEach {
            assertNotNull(it, validate(lcTestPayload(type = it)).event)
        }
        assertEquals("unsupported_event_type", validate(lcTestPayload(type = "location_departed")).error)
    }

    @Test fun malformedPayloadDoesNotLeakPayloadInError() {
        listOf("secret-not-json", "[]", "null", "true").forEach {
            assertEquals("invalid_payload", validate(it).error)
        }
        assertEquals("payload_too_large", validate("x".repeat(65537)).error)
    }

    @Test fun eventIdentityUsesUnicodeCodePointsAndRejectsControls() {
        assertNotNull(validate(lcTestPayload(id = "😀".repeat(8))).event)
        listOf("short", "x".repeat(129), "event\n0001", "event\u202E0001").forEach {
            assertEquals("invalid_event_id", validate(lcTestPayload(id = it)).error)
        }
    }

    @Test fun timestampHasInclusiveWindowAndNoCoercion() {
        assertNotNull(validate(lcTestPayload(at = LC_TEST_NOW - 600_000)).event)
        assertNotNull(validate(lcTestPayload(at = LC_TEST_NOW + 600_000)).event)
        assertEquals("stale_timestamp", validate(lcTestPayload(at = LC_TEST_NOW - 600_001)).error)
        assertEquals("stale_timestamp", validate(lcTestPayload(at = LC_TEST_NOW + 600_001)).error)
        listOf(JsonPrimitive(true), JsonPrimitive("1800000000"), JsonNull, JsonPrimitive(1e100)).forEach {
            assertEquals("invalid_timestamp", validate(lcTestPayload(overrides = mapOf("timestamp" to it))).error)
        }
    }

    @Test fun invalidLabelsAndPackagesAreNotAccepted() {
        assertEquals("invalid_label", validate(lcTestPayload(overrides = mapOf("app_label" to JsonPrimitive("x\nsecret")))).error)
        assertEquals("invalid_label", validate(lcTestPayload(overrides = mapOf("app_label" to JsonPrimitive("😀".repeat(65))))).error)
        assertEquals("invalid_package", validate(lcTestPayload(overrides = mapOf("app_package" to JsonPrimitive("https://device")))).error)
        assertEquals("Reader", validate(lcTestPayload()).event!!.appLabel)
    }

    @Test fun usageRequiresContinuousBasisAndTrueIntegerThresholdAndDuration() {
        assertEquals("invalid_usage_basis", validate(lcTestPayload(type = "app_timeout", overrides = mapOf("usage_basis" to JsonPrimitive("screen_time")))).error)
        listOf(JsonPrimitive(true), JsonPrimitive("60"), JsonPrimitive(60.0), JsonPrimitive(59)).forEach {
            assertEquals("invalid_usage_threshold", validate(lcTestPayload(type = "app_timeout", overrides = mapOf("usage_threshold_minutes" to it))).error)
        }
        listOf(JsonPrimitive(false), JsonPrimitive(59), JsonPrimitive(65.5), JsonPrimitive(1441)).forEach {
            assertEquals("invalid_duration", validate(lcTestPayload(type = "app_timeout", overrides = mapOf("duration_minutes" to it))).error)
        }
    }

    @Test fun usageThresholdIsConfigurableNotPermanentlySixtyMinutes() {
        val event = lcTestPayload(type = "app_timeout", overrides = mapOf(
            "usage_threshold_minutes" to JsonPrimitive(5), "duration_minutes" to JsonPrimitive(6),
        ))
        assertNotNull(validate(event, LC_TEST_CONFIG.copy(minimumUsageThresholdMinutes = 5)).event)
        assertNotNull(validate(lcTestPayload(type = "app_timeout", overrides = mapOf(
            "usage_threshold_minutes" to JsonPrimitive(2000), "duration_minutes" to JsonPrimitive(2001),
        )), LC_TEST_CONFIG.copy(maximumUsageMinutes = 3000)).event)
    }

    @Test fun usageSnapshotMustBeFreshNonFutureAndPrecedeEnvelope() {
        fun snapshot(ms: Long, timestamp: Long = LC_TEST_NOW) = lcTestPayload(type = "app_timeout", at = timestamp,
            overrides = mapOf("usage_snapshot_at" to JsonPrimitive(ms.toBigDecimal().movePointLeft(3))))
        assertNotNull(validate(snapshot(LC_TEST_NOW - 120_000)).event)
        listOf(LC_TEST_NOW - 120_001, LC_TEST_NOW + 1, Long.MIN_VALUE).forEach {
            assertEquals("invalid_usage_snapshot", validate(snapshot(it)).error)
        }
        assertEquals("invalid_usage_snapshot", validate(snapshot(LC_TEST_NOW, LC_TEST_NOW - 1)).error)
        val result = validate(snapshot(LC_TEST_NOW - 1000)).event!!
        assertEquals(LC_TEST_NOW - 1000, result.occurredAtMs)
    }

    @Test fun systemAndCompanionUsageExcludedButCanBeConfigured() {
        listOf("android", "COM.ANDROID.SYSTEMUI", "me.rerere.rikkahub", "com.lover.connect.dev").forEach {
            val payload = lcTestPayload(type = "app_timeout", overrides = mapOf("app_package" to JsonPrimitive(it)))
            assertEquals("excluded_usage_package", validate(payload).error)
            assertNotNull(validate(payload, LC_TEST_CONFIG.copy(excludedUsagePackages = emptySet(), excludedUsagePackageFamilies = emptySet())).event)
        }
    }

    @Test fun visualValidationCountsEmojiAsOneAndDiscardsUnrelatedUsage() {
        val event = validate(lcTestPayload(overrides = mapOf(
            "message" to JsonPrimitive("😀".repeat(200)), "duration_minutes" to JsonPrimitive(900),
            "usage_snapshot_at" to JsonPrimitive(1), "usage_threshold_minutes" to JsonPrimitive(60),
        ))).event!!
        assertNull(event.durationMinutes)
        assertNull(event.usageSnapshotAtMs)
        assertEquals(LC_TEST_NOW, event.occurredAtMs)
        assertEquals("invalid_visual_message", validate(lcTestPayload(overrides = mapOf("message" to JsonPrimitive("😀".repeat(201))))).error)
        assertNotNull(validate(lcTestPayload(overrides = mapOf("message" to JsonPrimitive("x".repeat(300)))), LC_TEST_CONFIG.copy(maximumVisualCharacters = 300)).event)
    }

    @Test fun visualReasonsAndMessageControlsRemainValidated() {
        listOf("low_battery", "night_observation", "interesting_content", "visual_observation").forEach {
            assertNotNull(validate(lcTestPayload(overrides = mapOf("reason" to JsonPrimitive(it)))).event)
        }
        assertEquals("invalid_visual_reason", validate(lcTestPayload(overrides = mapOf("reason" to JsonPrimitive("free_text")))).error)
        listOf("", " ", "a\nb", "a\u202Eb", "a\u2028b").forEach {
            assertEquals("invalid_visual_message", validate(lcTestPayload(overrides = mapOf("message" to JsonPrimitive(it)))).error)
        }
    }

    @Test fun legacyManualDurationIsOptionalAndDecimalTruncatedNotCoerced() {
        assertEquals(0, validate(lcTestPayload(type = "manual_test")).event!!.durationMinutes)
        assertEquals(3, validate(lcTestPayload(type = "night_usage", overrides = mapOf("duration_minutes" to JsonPrimitive(3.9)))).event!!.durationMinutes)
        listOf(JsonPrimitive(true), JsonPrimitive("3"), JsonPrimitive(-1), JsonPrimitive(1441)).forEach {
            assertEquals("invalid_duration", validate(lcTestPayload(type = "manual_test", overrides = mapOf("duration_minutes" to it))).error)
        }
    }

    @Test fun triggerFiltersAreOptionalAndExplicit() {
        val event = validate(lcTestPayload()).event!!
        assertNull(lcAlertConditionMismatch(event, LC_TEST_CONFIG))
        assertEquals("disabled", lcAlertConditionMismatch(event, LC_TEST_CONFIG.copy(enabled = false)))
        assertEquals("type_not_selected", lcAlertConditionMismatch(event, LC_TEST_CONFIG.copy(eventTypes = setOf("manual_test"))))
        assertEquals("package_not_selected", lcAlertConditionMismatch(event, LC_TEST_CONFIG.copy(appPackages = setOf("other.app"))))
        assertEquals("reason_not_selected", lcAlertConditionMismatch(event, LC_TEST_CONFIG.copy(visualReasons = setOf("low_battery"))))
        assertEquals("duration_below_trigger", lcAlertConditionMismatch(event, LC_TEST_CONFIG.copy(minimumDurationMinutes = 1)))
    }

    @Test fun aiPromptIsVerbatimAndLegacyFormatterNeverImplicit() {
        val event = validate(lcTestPayload()).event!!
        assertEquals(LC_TEST_CONFIG.prompt, lcAlertText(event, LC_TEST_CONFIG))
        assertNull(lcAlertText(event, LcAlertPolicyConfig(enabled = true)))
        assertEquals(LC_TEST_CONFIG.prompt, lcAlertText(event, LC_TEST_CONFIG.copy(useLegacyFormatter = true)))
        assertNotNull(lcAlertText(event, LcAlertPolicyConfig(enabled = true, useLegacyFormatter = true)))
    }

    @Test fun legacyIdentityMatchesOriginalAndCustomRulesCannotCollide() {
        val expected = "lc:" + MessageDigest.getInstance("SHA-256").digest("lc:alert:event-00001".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, lcAlertStableEventId("rule-a", "event-00001", true))
        assertEquals(expected, lcAlertStableEventId("rule-b", "event-00001", true))
        assertNotEquals(lcAlertStableEventId("rule-a", "event-00001", false), lcAlertStableEventId("rule-b", "event-00001", false))
    }

    @Test fun invalidPoliciesFailWithFixedNonSensitiveCodes() {
        listOf(LC_TEST_CONFIG.copy(cooldownMs = -1), LC_TEST_CONFIG.copy(maxPerHour = -1),
            LC_TEST_CONFIG.copy(eventTypes = setOf("location_departed")), LC_TEST_CONFIG.copy(prompt = " "),
            LC_TEST_CONFIG.copy(useLegacyFormatter = true, legacyTimeZone = "bad-zone")
        ).forEach { config ->
            val error = assertThrows(IllegalArgumentException::class.java) { validate(lcTestPayload(), config) }
            assertTrue(error.message!!.startsWith("lc_invalid_"))
            assertFalse(error.message!!.contains("AI-authored"))
        }
    }
}
