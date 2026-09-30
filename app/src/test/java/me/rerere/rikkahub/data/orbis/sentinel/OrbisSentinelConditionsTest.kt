package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure synthetic observations; no clock, Android context, or device sampling. */
class OrbisSentinelConditionsTest {
    private fun rule(type: OrbisSentinelType) = OrbisSentinelRule(
        id = "synthetic-rule", assistantId = "11111111-1111-4111-8111-111111111111",
        conversationId = "22222222-2222-4222-8222-222222222222", type = type,
        prompt = "synthetic", enabled = true, createdAtMs = 1000, updatedAtMs = 1000,
        intervalMs = if (type == OrbisSentinelType.INTERVAL) 1000 else null,
        thresholdMs = if (type in setOf(OrbisSentinelType.CHAT_IDLE, OrbisSentinelType.CHAT_LEFT, OrbisSentinelType.APP_USAGE)) 1000 else null,
        dueAtMs = if (type == OrbisSentinelType.ONCE) 2000 else null,
        appPackage = if (type == OrbisSentinelType.APP_USAGE) "synthetic.example.app" else null,
    )

    @Test fun `once waits until exact due boundary and skips a due time missed during human pause`() {
        val rule = rule(OrbisSentinelType.ONCE)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(1999)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(2000)))
        assertEquals(OrbisSentinelCondition.RESET, sentinelCondition(rule, OrbisSentinelObservation(5000), resumedAtMs = 2001))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(2000), resumedAtMs = 2000))
    }

    @Test fun `interval measures from creation or last accepted fire and respects exact boundary`() {
        val rule = rule(OrbisSentinelType.INTERVAL)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(1999)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(2000)))
        val fired = rule.copy(lastFiredAtMs = 4000)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(fired, OrbisSentinelObservation(4999)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(fired, OrbisSentinelObservation(5000)))
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(fired, OrbisSentinelObservation(3999)))
    }

    @Test fun `human resume restarts interval baseline instead of delivering missed intervals`() {
        val rule = rule(OrbisSentinelType.INTERVAL).copy(lastFiredAtMs = 2000)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(10000), resumedAtMs = 10000))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(10999), resumedAtMs = 10000))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(11000), resumedAtMs = 10000))
    }

    @Test fun `chat idle does not invent a missing or future human message time`() {
        val rule = rule(OrbisSentinelType.CHAT_IDLE)
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule, OrbisSentinelObservation(3000)))
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule, OrbisSentinelObservation(3000, lastHumanMessageMs = 3001)))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(3999, lastHumanMessageMs = 3000)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(4000, lastHumanMessageMs = 3000)))
    }

    @Test fun `old human and absence timestamps are clipped to rule creation and human resume`() {
        for (type in listOf(OrbisSentinelType.CHAT_IDLE, OrbisSentinelType.CHAT_LEFT)) {
            val rule = rule(type)
            fun facts(now: Long) = OrbisSentinelObservation(now, lastHumanMessageMs = 100, chatLeftAtMs = 100)
            assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, facts(1999)))
            assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, facts(2000)))
            assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, facts(10999), resumedAtMs = 10000))
            assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, facts(11000), resumedAtMs = 10000))
        }
    }

    @Test fun `visible chat is reset and a newly observed absence must cross threshold`() {
        val rule = rule(OrbisSentinelType.CHAT_LEFT)
        assertEquals(OrbisSentinelCondition.RESET, sentinelCondition(rule, OrbisSentinelObservation(9000)))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, OrbisSentinelObservation(9999, chatLeftAtMs = 9000)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, OrbisSentinelObservation(10000, chatLeftAtMs = 9000)))
    }

    @Test fun `app observation distinguishes unavailable wrong package and confirmed duration`() {
        val rule = rule(OrbisSentinelType.APP_USAGE)
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule, OrbisSentinelObservation(5000)))
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule,
            OrbisSentinelObservation(5000, appPackage = rule.appPackage)))
        assertEquals(OrbisSentinelCondition.RESET, sentinelCondition(rule,
            OrbisSentinelObservation(5000, appPackage = "synthetic.other.app", appContinuousMs = 9000)))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule,
            OrbisSentinelObservation(5000, appPackage = rule.appPackage, appContinuousMs = 999)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule,
            OrbisSentinelObservation(5000, appPackage = rule.appPackage, appContinuousMs = 1000)))
    }

    @Test fun `app duration cannot include time before rule creation or human resume`() {
        val rule = rule(OrbisSentinelType.APP_USAGE)
        fun facts(now: Long) = OrbisSentinelObservation(now, appPackage = rule.appPackage, appContinuousMs = 99999)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, facts(1999)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, facts(2000)))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule, facts(10999), resumedAtMs = 10000))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule, facts(11000), resumedAtMs = 10000))
    }
}
