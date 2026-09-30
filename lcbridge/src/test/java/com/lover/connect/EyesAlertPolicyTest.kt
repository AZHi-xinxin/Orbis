package com.lover.connect

import org.junit.Assert.*
import org.junit.Test

class EyesAlertPolicyTest {
    private fun snapshot(pkg: String = "com.xingin.xhs", duration: Int = 60) =
        AppRestSnapshot(pkg, duration, 1_000_000L, 4_000_000L, 1L, 60)

    @Test fun visualDoesNotBecomeTimeoutOrInventDuration() {
        val event = EyesAlertPolicy.visual("notify", "low_battery", "电量偏低，请充电", 1_000L)!!
        val json = event.toJson("lc-test-0001")
        assertEquals("visual_interaction", json.getString("type"))
        assertEquals("low_battery", json.getString("reason"))
        assertFalse(json.has("duration_minutes"))
        assertEquals("电量偏低，请充电", json.getString("message"))
    }

    @Test fun modelCannotRequestDurationChannel() {
        assertNull(EyesAlertPolicy.visual("notify", "app_timeout", "休息一下", 1_000L))
        assertNull(EyesAlertPolicy.visual("log", "low_battery", "请充电", 1_000L))
    }

    @Test fun visualRejectsEmptyOversizedAndControlText() {
        for (message in listOf("", " ", "a".repeat(201), "内容\n伪造下一条", "\u202eabc")) {
            assertNull(EyesAlertPolicy.visual("popup", "visual_observation", message, 1_000L))
        }
    }

    @Test fun restRequiresThresholdAndNonChatApp() {
        assertNull(EyesAlertPolicy.rest(snapshot(duration = 0), "小红书"))
        assertNull(EyesAlertPolicy.rest(snapshot(duration = 59), "小红书"))
        assertNull(EyesAlertPolicy.rest(snapshot(pkg = "me.rerere.rikkahub"), "阿止"))
        assertNull(EyesAlertPolicy.rest(snapshot(pkg = "com.android.systemui"), "系统界面"))
        assertNotNull(EyesAlertPolicy.rest(snapshot(), "小红书"))
    }

    @Test fun textAndWireUseSameSnapshot() {
        val event = EyesAlertPolicy.rest(snapshot(duration = 67), "小红书")!!
        val json = event.toJson("lc-test-0002")
        assertTrue(event.message.contains("67 分钟"))
        assertEquals(67, json.getInt("duration_minutes"))
        assertEquals(60, json.getInt("usage_threshold_minutes"))
        assertEquals("continuous_app", json.getString("usage_basis"))
        assertEquals(1000.0, json.getDouble("usage_snapshot_at"), 0.0)
        assertEquals(json.getDouble("timestamp"), json.getDouble("usage_snapshot_at"), 0.0)
    }

    @Test fun staleAndFutureObservationsDoNotSend() {
        val event = EyesAlertPolicy.rest(snapshot(), "小红书")!!
        assertTrue(EyesAlertPolicy.isFresh(event, 1_120_000L))
        assertFalse(EyesAlertPolicy.isFresh(event, 1_120_001L))
        assertFalse(EyesAlertPolicy.isFresh(event, 999_999L))
    }

    @Test fun localCooldownAlsoAppliesWhenSentinelIsUnavailable() {
        val cooldown = EyesAlertCooldown()
        assertTrue(cooldown.reserve("app_timeout:xhs", 0L))
        assertFalse(cooldown.reserve("app_timeout:xhs", 60_000L))
        assertTrue(cooldown.reserve("visual_interaction:low_battery", 60_000L))
        assertTrue(cooldown.reserve("app_timeout:xhs", 1_800_000L))
    }

    @Test fun serverSuppressionAndUncertainNetworkNeverFallBackLocally() {
        for (code in listOf(200, 202, 400, 401, 403, 409, 429, 500, 502, 503)) {
            assertFalse(DeliveryPolicy.shouldUseLocalFallback(DeliveryPolicy.fromHttpStatus(code)))
        }
        assertFalse(DeliveryPolicy.shouldUseLocalFallback(DeliveryPolicy.fromHttpStatus(null)))
        assertTrue(DeliveryPolicy.shouldUseLocalFallback(SentinelDelivery.UNAVAILABLE))
    }
}
