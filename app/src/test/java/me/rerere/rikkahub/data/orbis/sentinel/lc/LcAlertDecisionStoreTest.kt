package me.rerere.rikkahub.data.orbis.sentinel.lc

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class LcAlertDecisionStoreTest {
    private fun reserve(
        store: LcAlertDecisionStore,
        id: String = "event-00001",
        type: String = "visual_interaction",
        now: Long = LC_TEST_NOW,
        config: LcAlertPolicyConfig = LC_TEST_CONFIG,
        rule: String = "visual-rule",
        target: String = LC_TEST_TARGET,
        payload: String = lcTestPayload(id, type, now),
    ) = store.reserve(payload, rule, target, config, now)

    @Test fun durableReservationPrecedesDispatchAndReopenIsReceiptOnly() {
        val disk = LcTestStorage()
        val result = reserve(disk.store())
        assertEquals(LcAlertDecisionKind.RESERVED, result.kind)
        assertNotNull(disk.raw)
        assertEquals(result.reservation, disk.store().receipt(result.reservation!!.eventId))
        val again = reserve(disk.store())
        assertEquals(LcAlertDecisionKind.DUPLICATE, again.kind)
        assertEquals(LcAlertDeliveryState.RESERVED, again.reservation!!.state)
        assertEquals(1, disk.writes)
    }

    @Test fun unknownIsDurableAndNeverAuthorizesAnotherDispatch() {
        val disk = LcTestStorage()
        val store = disk.store()
        val first = reserve(store).reservation!!
        store.mark(first, LcAlertDeliveryState.UNKNOWN, LC_TEST_NOW + 1)
        val again = reserve(disk.store())
        assertEquals(LcAlertDecisionKind.DUPLICATE, again.kind)
        assertEquals(LcAlertDeliveryState.UNKNOWN, again.reservation!!.state)
        assertEquals(first.eventId, again.reservation.eventId)
        assertEquals(1, store.snapshot().reservations.size)
    }

    @Test fun acceptedAndDuplicateReceiptsAreNotDowngraded() {
        listOf(LcAlertDeliveryState.ACCEPTED, LcAlertDeliveryState.DUPLICATE).forEach { terminal ->
            val store = LcTestStorage().store()
            val initial = reserve(store).reservation!!
            store.mark(initial, LcAlertDeliveryState.UNKNOWN, LC_TEST_NOW + 1)
            val accepted = store.mark(initial, terminal, LC_TEST_NOW + 2)
            assertEquals(accepted, store.mark(initial, LcAlertDeliveryState.UNKNOWN, LC_TEST_NOW + 3))
            assertEquals(accepted, store.mark(initial, LcAlertDeliveryState.REJECTED, LC_TEST_NOW + 3))
        }
    }

    @Test fun receiptUpdateCannotModifyFrozenTextTargetOrPayload() {
        val store = LcTestStorage().store()
        val initial = reserve(store).reservation!!
        listOf(initial.copy(text = "changed"), initial.copy(targetKey = "a:b"), initial.copy(payloadHash = "0".repeat(64))).forEach {
            assertThrows(IllegalArgumentException::class.java) { store.mark(it, LcAlertDeliveryState.ACCEPTED, LC_TEST_NOW) }
        }
        assertEquals(initial, store.receipt(initial.eventId))
    }

    @Test fun reconfigurationDoesNotRewritePreviouslyReservedPrompt() {
        val store = LcTestStorage().store()
        val first = reserve(store)
        val second = reserve(store, config = LC_TEST_CONFIG.copy(prompt = "Different AI prompt"))
        assertEquals(LcAlertDecisionKind.DUPLICATE, second.kind)
        assertEquals(first.reservation!!.text, second.reservation!!.text)
    }

    @Test fun sameIdentityWithDifferentPayloadOrTargetIsRejected() {
        val store = LcTestStorage().store()
        reserve(store)
        assertEquals("event_identity_conflict", reserve(store, target = "other:conversation").code)
        assertEquals("event_identity_conflict", reserve(store, payload = lcTestPayload(overrides = mapOf("message" to JsonPrimitive("different")))).code)
        assertEquals(1, store.snapshot().reservations.size)
    }

    @Test fun sameRawEventInDifferentCustomRulesHasIndependentIdentity() {
        val store = LcTestStorage().store()
        val first = reserve(store, rule = "rule-a")
        val second = reserve(store, rule = "rule-b")
        assertEquals(LcAlertDecisionKind.RESERVED, first.kind)
        assertEquals(LcAlertDecisionKind.RESERVED, second.kind)
        assertNotEquals(first.reservation!!.eventId, second.reservation!!.eventId)
    }

    @Test fun explicitMigrationSharesOldStableIdentityAcrossRules() {
        val store = LcTestStorage().store()
        val legacy = LC_TEST_CONFIG.copy(legacyIdentity = true, throttleGroup = "legacy-alerts")
        val first = reserve(store, rule = "migration-a", config = legacy)
        val second = reserve(store, rule = "migration-b", config = legacy)
        assertEquals(LcAlertDecisionKind.DUPLICATE, second.kind)
        assertEquals(first.reservation!!.eventId, second.reservation!!.eventId)
    }

    @Test fun disabledRuleDoesNotParseOrWriteAnything() {
        val disk = LcTestStorage()
        val result = reserve(disk.store(), config = LC_TEST_CONFIG.copy(enabled = false), payload = "invalid and private")
        assertEquals("disabled", result.code)
        assertEquals(0, disk.writes)
    }

    @Test fun filteredOrUnconfiguredRuleDoesNotConsumeLimits() {
        val disk = LcTestStorage()
        val store = disk.store()
        assertEquals("reason_not_selected", reserve(store, config = LC_TEST_CONFIG.copy(visualReasons = setOf("low_battery"))).code)
        assertEquals("prompt_not_configured", reserve(store, config = LC_TEST_CONFIG.copy(prompt = null)).code)
        assertEquals(0, disk.writes)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store).kind)
    }

    @Test fun cooldownKeyIncludesTypePackageAndVisualReason() {
        val store = LcTestStorage().store()
        reserve(store)
        assertEquals("cooldown", reserve(store, id = "event-00002").code)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-00003", payload = lcTestPayload("event-00003", overrides = mapOf("app_package" to JsonPrimitive("other.app")))).kind)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-00004", payload = lcTestPayload("event-00004", overrides = mapOf("reason" to JsonPrimitive("low_battery")))).kind)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-00005", type = "night_usage").kind)
    }

    @Test fun defaultCooldownExpiresExactlyAtThirtyMinutes() {
        val store = LcTestStorage().store()
        reserve(store)
        assertEquals("cooldown", reserve(store, id = "event-00002", now = LC_TEST_NOW + 1_799_999).code)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-00003", now = LC_TEST_NOW + 1_800_000).kind)
    }

    @Test fun manualBypassesCooldownButStillConsumesDefaultSixPerHour() {
        val store = LcTestStorage().store()
        repeat(6) { assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "manual-000$it", type = "manual_test").kind) }
        assertEquals("rate_limited", reserve(store, id = "manual-0006", type = "manual_test").code)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "manual-0007", type = "manual_test", now = LC_TEST_NOW + 3_600_000).kind)
    }

    @Test fun sharedMigrationGroupPreservesCrossTypeHourlyBudget() {
        val store = LcTestStorage().store()
        val config = LC_TEST_CONFIG.copy(throttleGroup = "legacy-alerts", maxPerHour = 2)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, rule = "manual-rule", type = "manual_test", config = config).kind)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-00002", rule = "rest-rule", type = "app_timeout", config = config).kind)
        assertEquals("rate_limited", reserve(store, id = "event-00003", rule = "visual-rule", config = config).code)
    }

    @Test fun customRulesDoNotShareBudgetsUnlessConfigured() {
        val store = LcTestStorage().store()
        val config = LC_TEST_CONFIG.copy(maxPerHour = 1)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, rule = "a", config = config).kind)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, rule = "b", config = config).kind)
    }

    @Test fun aiCanDisableAllFrequencyLimitsWithoutHiddenSixPerHourCap() {
        val store = LcTestStorage().store()
        val config = LC_TEST_CONFIG.copy(cooldownMs = 0, maxPerHour = 0, dedupMs = 0)
        repeat(10) { assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, id = "event-000$it", config = config).kind) }
        assertEquals(LcAlertDecisionKind.DUPLICATE, reserve(store, id = "event-0000", config = config).kind)
    }

    @Test fun importedDedupExpiresAfterTwentyFourHoursButAttemptIdentityDoesNot() {
        val store = LcTestStorage().store()
        store.importThrottle("visual-rule", LC_TEST_TARGET, LC_TEST_CONFIG,
            LcAlertThrottleState(seen = mapOf("event-00001" to LC_TEST_NOW - 86_399_999)))
        assertEquals("seen_before_migration", reserve(store).code)
        assertEquals(LcAlertDecisionKind.RESERVED, reserve(store, now = LC_TEST_NOW + 1).kind)
        val noLimits = LC_TEST_CONFIG.copy(cooldownMs = 0, maxPerHour = 0, dedupMs = 0, eventClockWindowMs = Long.MAX_VALUE)
        assertEquals(LcAlertDecisionKind.DUPLICATE, reserve(store, now = LC_TEST_NOW + 172_800_000,
            config = noLimits, payload = lcTestPayload(at = LC_TEST_NOW + 1)).kind)
    }

    @Test fun migrationImportsCooldownAndBudgetWithoutReplacingLiveCounters() {
        val store = LcTestStorage().store()
        val imported = LcAlertThrottleState(cooldowns = mapOf("visual_interaction:com.example.reader:visual_observation" to LC_TEST_NOW),
            hourly = List(6) { LC_TEST_NOW })
        store.importThrottle("visual-rule", LC_TEST_TARGET, LC_TEST_CONFIG, imported)
        store.importThrottle("visual-rule", LC_TEST_TARGET, LC_TEST_CONFIG, imported)
        assertEquals("cooldown", reserve(store).code)
        assertEquals("rate_limited", reserve(store, type = "manual_test").code)
        assertThrows(IllegalArgumentException::class.java) {
            store.importThrottle("visual-rule", LC_TEST_TARGET, LC_TEST_CONFIG, LcAlertThrottleState())
        }
    }

    @Test fun clockRollbackDoesNotEvadeCooldownOrRateLimit() {
        val store = LcTestStorage().store()
        reserve(store)
        assertEquals("cooldown", reserve(store, id = "event-00002", now = LC_TEST_NOW - 60_000).code)
        val limited = LcTestStorage().store()
        val config = LC_TEST_CONFIG.copy(maxPerHour = 1)
        reserve(limited, type = "manual_test", config = config)
        assertEquals("rate_limited", reserve(limited, id = "event-00002", type = "manual_test", now = LC_TEST_NOW - 60_000, config = config).code)
    }

    @Test fun failedWriteCannotReturnDispatchableDecision() {
        val store = LcAlertDecisionStore(read = { null }, write = { throw IOException("synthetic write failure") })
        assertThrows(IOException::class.java) { reserve(store) }
        assertTrue(store.snapshot().reservations.isEmpty())
    }

    @Test fun silentNoOpWriteIsDetected() {
        val store = LcAlertDecisionStore(read = { null }, write = {})
        val error = assertThrows(IOException::class.java) { reserve(store) }
        assertEquals("lc_store_write_not_verified", error.message)
    }

    @Test fun writeCommittedThenThrewIsReceiptOnlyOnRetry() {
        var raw: String? = null
        val store = LcAlertDecisionStore(read = { raw }, write = { raw = it; throw IOException("after commit") })
        assertThrows(IOException::class.java) { reserve(store) }
        assertEquals(LcAlertDecisionKind.DUPLICATE, reserve(store).kind)
        assertEquals(1, store.snapshot().reservations.size)
    }

    @Test fun corruptedOrDisappearedStorageNeverSilentlyResetsDedup() {
        val error = assertThrows(IOException::class.java) { LcAlertDecisionStore(read = { "private invalid JSON" }, write = {}) }
        assertEquals("lc_store_unreadable", error.message)
        val disk = LcTestStorage()
        val store = disk.store()
        reserve(store)
        disk.raw = null
        assertEquals("lc_store_disappeared", assertThrows(IllegalStateException::class.java) { store.snapshot() }.message)
    }

    @Test fun concurrentStoresAuthorizeExactlyOneDispatch() {
        val disk = LcTestStorage()
        val stores = List(8) { disk.store() }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = stores.map { store -> pool.submit(Callable { start.await(); reserve(store).kind }) }
            start.countDown()
            val decisions = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, decisions.count { it == LcAlertDecisionKind.RESERVED })
            assertEquals(7, decisions.count { it == LcAlertDecisionKind.DUPLICATE })
            assertEquals(1, disk.writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun invalidScopesAndImportedTimesAreRejectedWithoutWriting() {
        val disk = LcTestStorage()
        val store = disk.store()
        assertThrows(IllegalArgumentException::class.java) { reserve(store, rule = "bad/rule") }
        assertThrows(IllegalArgumentException::class.java) { reserve(store, target = "bad\ntarget") }
        assertThrows(IllegalArgumentException::class.java) {
            store.importThrottle("visual-rule", LC_TEST_TARGET, LC_TEST_CONFIG, LcAlertThrottleState(hourly = listOf(-1)))
        }
        assertEquals(0, disk.writes)
    }
}
