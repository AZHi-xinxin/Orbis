package me.rerere.rikkahub.data.orbis.sentinel

import org.junit.Assert.*
import org.junit.Test

/** Synthetic times/storage only: no phone, relay, network, or model calls. */
class OrbisTouchClockPolicyTest {
    private val origin = 1_790_000_000_000L
    private val binding = OrbisSentinelBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private fun rule(createdAt: Long = origin) = OrbisSentinelRule(
        id = "touch-rule", assistantId = binding.assistantId, conversationId = binding.conversationId,
        type = OrbisSentinelType.TOUCH, prompt = "synthetic touch", enabled = true, createdAtMs = createdAt,
    )
    private fun store(createdAt: Long = origin): OrbisSentinelRuleStore {
        var disk: String? = null
        return OrbisSentinelRuleStore({ disk }, { disk = it }, Any()).also { it.create(rule(createdAt)) }
    }
    private fun facts(source: Long, received: Long, now: Long = received) = OrbisSentinelObservation(
        nowMs = now, eventKey = "physical-touch", eventObservedAtMs = sentinelTouchObservedAtMs(source, received),
    )

    @Test fun smallForwardSkewIncludingObservedRangeClampsToFirstReceipt() {
        for (skew in listOf(0L, 1L, 190L, 273L, 4_999L, 5_000L)) {
            assertEquals(origin, sentinelTouchObservedAtMs(origin + skew, origin))
            assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(rule(), facts(origin + skew, origin)))
        }
    }

    @Test fun largerForwardSkewAndInvalidTimesFailClosed() {
        for (source in listOf(origin + 5_001L, origin + 300_000L, Long.MAX_VALUE, 0L, -1L, Long.MIN_VALUE)) {
            assertNull(sentinelTouchObservedAtMs(source, origin))
        }
        assertNull(sentinelTouchObservedAtMs(origin, 0L))
        assertNull(sentinelTouchObservedAtMs(origin, -1L))
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule(), facts(origin + 5_001L, origin)))
    }

    @Test fun nearLongBoundaryDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE - 5_000, sentinelTouchObservedAtMs(Long.MAX_VALUE, Long.MAX_VALUE - 5_000))
        assertNull(sentinelTouchObservedAtMs(Long.MAX_VALUE, Long.MAX_VALUE - 5_001))
    }

    @Test fun delayedAndPastEventsKeepOriginalAge() {
        assertEquals(origin - 1, sentinelTouchObservedAtMs(origin - 1, origin))
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(rule(), facts(origin - 1, origin)))
        assertNull(store().evaluateAndReserve("touch-rule", "old", facts(origin - 1, origin + 10_000)))
    }

    @Test fun sourceClockCannotCrossNewRuleBaseline() {
        val created = origin + 100
        val store = store(created)
        // The source appears after creation, but the phone received it before the rule existed.
        val old = facts(created + 200, created - 1, created + 500)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(store.get("touch-rule")!!, old))
        assertNull(store.evaluateAndReserve("touch-rule", "before-rule", old))
        assertNotNull(store.evaluateAndReserve("touch-rule", "at-rule", facts(created + 200, created)))
    }

    @Test fun humanPauseAndResumeBaselineRemainAuthoritative() {
        val store = store()
        store.setHumanEnabled(false, origin + 10)
        assertNull(store.evaluateAndReserve("touch-rule", "paused", facts(origin + 300, origin + 20)))
        val resumed = origin + 100
        store.setHumanEnabled(true, resumed)
        assertNull(store.evaluateAndReserve("touch-rule", "before-resume", facts(resumed + 200, resumed - 1, resumed + 500)))
        assertNull(store.evaluateAndReserve("touch-rule", "old-delayed", facts(resumed - 1, resumed + 500)))
        assertNotNull(store.evaluateAndReserve("touch-rule", "at-resume", facts(resumed + 200, resumed)))
    }

    @Test fun rulePauseAndResumeBaselineRemainAuthoritative() {
        val store = store()
        store.pause("touch-rule", binding, origin + 10)
        assertNull(store.evaluateAndReserve("touch-rule", "paused", facts(origin + 300, origin + 20)))
        val resumed = origin + 100
        store.resume("touch-rule", binding, resumed)
        assertNull(store.evaluateAndReserve("touch-rule", "before-resume", facts(resumed + 200, resumed - 1, resumed + 500)))
        assertNotNull(store.evaluateAndReserve("touch-rule", "at-resume", facts(resumed + 200, resumed)))
    }

    @Test fun ruleEditBaselineRemainsAuthoritative() {
        val store = store()
        val edited = origin + 100
        store.update("touch-rule", binding, edited) { it.copy(prompt = "edited synthetic touch") }
        assertNull(store.evaluateAndReserve("touch-rule", "before-edit", facts(edited + 200, edited - 1, edited + 500)))
        assertNotNull(store.evaluateAndReserve("touch-rule", "at-edit", facts(edited + 200, edited)))
    }

    @Test fun receiptKeepsOriginalTimestampAndCompletedIdentityCannotBeReplayed() {
        var disk: String? = null
        val ingress = OrbisSentinelIngressStore({ disk }, { disk = it })
        val source = origin + 273
        val (receipt, duplicate) = ingress.begin("touch", "touch-clock", source.toString(), source, origin)
        assertFalse(duplicate)
        assertEquals(source, receipt.occurredAtMs)
        assertEquals(origin, receipt.receivedAtMs)
        assertEquals(sentinelIdentityHash(source.toString()), receipt.payloadHash)
        assertEquals(origin, sentinelTouchObservedAtMs(receipt.occurredAtMs, receipt.receivedAtMs))
        ingress.finish("touch", "touch-clock", "suppressed")
        val (existing, repeated) = ingress.begin("touch", "touch-clock", source.toString(), source, origin + 10_000)
        assertTrue(repeated)
        assertEquals("suppressed", existing.status)
        assertEquals(origin, existing.receivedAtMs)
        assertEquals(source, existing.occurredAtMs)
    }

    @Test fun geofenceAndUnnormalizedConditionStillRejectAnyFutureTime() {
        val unnormalized = OrbisSentinelObservation(origin, eventKey = "event", eventObservedAtMs = origin + 1)
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule(), unnormalized))
        assertEquals(OrbisSentinelCondition.UNKNOWN, sentinelCondition(rule().copy(type = OrbisSentinelType.GEOFENCE), unnormalized))
    }
}
