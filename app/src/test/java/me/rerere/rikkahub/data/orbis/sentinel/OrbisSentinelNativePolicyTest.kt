package me.rerere.rikkahub.data.orbis.sentinel

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic storage, injected draws and times. No phone or actual wakeups. */
class OrbisSentinelNativePolicyTest {
    private val binding = OrbisSentinelBinding("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val origin = time("2026-09-24T00:00:00Z")
    private class Disk {
        var text: String? = null
        var throwAfterWrite = false
        val lock = Any()
        fun store() = OrbisSentinelRuleStore({ text }, { text = it; if (throwAfterWrite) throw IOException("after_commit") }, lock)
    }
    private fun rule(type: OrbisSentinelType = OrbisSentinelType.AGREEMENT) = OrbisSentinelRule(
        id = "native", assistantId = binding.assistantId, conversationId = binding.conversationId,
        type = type, prompt = if (type in SENTINEL_SYSTEM_TYPES) "" else "  我的原文\n保持原样。  ",
        enabled = true, createdAtMs = origin, updatedAtMs = origin, timezone = "UTC",
        thresholdMs = if (type in setOf(OrbisSentinelType.AGREEMENT, OrbisSentinelType.NIGHT_USAGE, OrbisSentinelType.SCREEN_ON)) 1_800_000 else null,
        intervalMs = if (type == OrbisSentinelType.SCREEN_OBSERVATION) 1_800_000 else null,
        dailyAtLocal = if (type == OrbisSentinelType.RITUAL) "07:30" else null,
        probabilityPercent = if (type == OrbisSentinelType.AGREEMENT) 50 else 100,
        windowStartLocal = if (type == OrbisSentinelType.NIGHT_USAGE) "00:00" else null,
        windowEndLocal = if (type == OrbisSentinelType.NIGHT_USAGE) "07:30" else null,
    )
    private fun facts(now: Long, human: Long = origin) = OrbisSentinelObservation(now,
        lastHumanMessageMs = human, lastConversationActivityMs = human)
    private fun accept(store: OrbisSentinelRuleStore, id: String, now: Long) {
        store.stageEventText("native", id, "synthetic event")
        store.completeFire("native", id, now)
    }

    @Test fun `probability miss is durable and not rerolled every polling tick or restart`() {
        val disk = Disk(); val store = disk.store(); store.create(rule())
        var draws = 0
        fun missed(bound: Long): Long { draws++; return bound - 1 }
        val due = origin + 1_800_000
        assertNull(store.evaluateAndReserve("native", "miss", facts(due), randomBounded = ::missed))
        assertEquals(1, draws)
        repeat(20) { assertNull(disk.store().evaluateAndReserve("native", "retry$it", facts(due + it * 5_000), randomBounded = ::missed)) }
        assertEquals(1, draws)
        assertNull(store.evaluateAndReserve("native", "later-miss", facts(due + 1_800_000), randomBounded = ::missed))
        assertEquals(2, draws)
        assertEquals(0, store.get("native")!!.policy.consecutiveCount)
    }

    @Test fun `agreement measures every new wake but counter resets only actual human return`() {
        val store = Disk().store(); store.create(rule().copy(escalationAfter = 1, escalationPrompt = "我写的加强提示"))
        val first = origin + 1_800_000
        assertNotNull(store.evaluateAndReserve("native", "first", facts(first), randomBounded = { 0 }))
        accept(store, "first", first)
        assertEquals(1, store.get("native")!!.policy.consecutiveCount)
        val activity = facts(first + 1_800_000).copy(lastConversationActivityMs = first + 100_000)
        assertNull(store.evaluateAndReserve("native", "too-soon", activity, randomBounded = { 0 }))
        val secondAt = first + 1_900_000
        val second = store.evaluateAndReserve("native", "second", activity.copy(nowMs = secondAt), randomBounded = { 0 })!!
        val text = sentinelWakeText(second.rule, emittedAtMs = secondAt, zone = ZoneId.of("UTC"))
        assertTrue(text.startsWith("【第2次约定唤醒】"))
        assertTrue(text.contains("我写的加强提示"))
        accept(store, "second", secondAt)
        store.update("native", binding, secondAt + 1) { it.copy(quietStartLocal = "00:00", quietEndLocal = "07:30") }
        // Populate a count again without relying on the quiet period, then human return during quiet.
        val edited = store.get("native")!!
        assertNull(store.evaluateAndReserve("native", "human-return", facts(secondAt + 2, secondAt + 2), randomBounded = { error("quiet must not draw") }))
        assertEquals(secondAt + 2, store.get("native")!!.policy.lastHumanMessageMs)
        assertEquals(0, store.get("native")!!.policy.consecutiveCount)
        assertEquals(edited.prompt, store.get("native")!!.prompt)
    }

    @Test fun `quiet window spans midnight and exact end is awake`() {
        val r = rule().copy(quietStartLocal = "23:30", quietEndLocal = "07:30")
        listOf("2026-09-24T23:30:00Z", "2026-09-25T00:01:00Z", "2026-09-25T07:29:59Z").forEach {
            assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(r, facts(time(it))))
        }
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(r, facts(time("2026-09-25T07:30:00Z"))))
    }

    @Test fun `human return resets existing reminder count even during quiet time`() {
        val store = Disk().store()
        store.create(rule().copy(quietStartLocal = "23:30", quietEndLocal = "07:30"))
        val first = time("2026-09-24T22:00:00Z")
        assertNotNull(store.evaluateAndReserve("native", "evening", facts(first), randomBounded = { 0 }))
        accept(store, "evening", first)
        assertEquals(1, store.get("native")!!.policy.consecutiveCount)
        val returned = time("2026-09-24T23:40:00Z")
        assertNull(store.evaluateAndReserve("native", "returned", facts(returned, returned), randomBounded = { error("quiet") }))
        assertEquals(0, store.get("native")!!.policy.consecutiveCount)
        assertEquals(returned, store.get("native")!!.policy.lastHumanMessageMs)
    }

    @Test fun `daily random selection survives restart fires once and chooses again next day`() {
        val disk = Disk(); val store = disk.store(); store.create(rule(OrbisSentinelType.RITUAL).copy(dailyWindowEndLocal = "08:00"))
        val before = time("2026-09-24T07:00:00Z")
        var draws = 0
        fun midpoint(bound: Long): Long { draws++; return bound / 2 }
        assertNull(store.evaluateAndReserve("native", "before", facts(before), randomBounded = ::midpoint))
        val selected = time("2026-09-24T07:45:00Z")
        assertEquals(selected, disk.store().get("native")!!.policy.dailyScheduledAtMs)
        assertNull(disk.store().evaluateAndReserve("native", "stillbefore", facts(selected - 1), randomBounded = ::midpoint))
        assertNotNull(disk.store().evaluateAndReserve("native", "daily1", facts(selected), randomBounded = ::midpoint))
        accept(store, "daily1", selected)
        assertNull(store.evaluateAndReserve("native", "again", facts(selected + 60_000), randomBounded = ::midpoint))
        assertEquals(1, draws)
        assertNotNull(store.evaluateAndReserve("native", "daily2", facts(selected + 86_400_000), randomBounded = ::midpoint))
        assertEquals(2, draws)
    }

    @Test fun `night ritual ending24 respects same day boundary`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.RITUAL).copy(dailyAtLocal = "23:30", dailyWindowEndLocal = "24:00"))
        val due = time("2026-09-24T23:45:00Z")
        assertNotNull(store.evaluateAndReserve("native", "night", facts(due), randomBounded = { it / 2 }))
        accept(store, "night", due)
        assertNull(store.evaluateAndReserve("native", "midnight", facts(time("2026-09-25T00:00:00Z")), randomBounded = { it / 2 }))
    }

    @Test fun `cross midnight ritual belongs to previous calendar occurrence`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.RITUAL).copy(dailyAtLocal = "23:30", dailyWindowEndLocal = "00:30"))
        val due = time("2026-09-25T00:00:00Z")
        val pending = store.evaluateAndReserve("native", "cross", facts(due), randomBounded = { it / 2 })!!
        assertEquals("2026-09-24", pending.rule.policy.dailyDate)
        accept(store, "cross", due)
        assertNull(store.evaluateAndReserve("native", "crossagain", facts(due + 5_000), randomBounded = { error("must not redraw") }))
    }

    @Test fun `master stop resume never backfills a missed daily or pending event`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.RITUAL))
        store.setHumanEnabled(false, time("2026-09-24T07:00:00Z"))
        store.setHumanEnabled(true, time("2026-09-24T09:00:00Z"))
        assertNull(store.evaluateAndReserve("native", "missed", facts(time("2026-09-24T09:01:00Z")), randomBounded = { 0 }))
        assertNotNull(store.evaluateAndReserve("native", "tomorrow", facts(time("2026-09-25T07:30:00Z")), randomBounded = { 0 }))
    }

    @Test fun `daily fixed time remains local clock across DST`() {
        val r = rule(OrbisSentinelType.RITUAL).copy(timezone = "America/New_York", dailyAtLocal = "08:00")
        val first = sentinelDailySlot(time("2026-10-31T12:00:00Z"), "08:00", null, r.timezone)
        val second = sentinelDailySlot(time("2026-11-01T13:00:00Z"), "08:00", null, r.timezone)
        assertEquals(25 * 3_600_000L, second.startMs - first.startMs)
    }

    @Test fun `next due never advertises accepted daily occurrence or disabled pending rules`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.RITUAL))
        val due = time("2026-09-24T07:30:00Z")
        assertNull(store.evaluateAndReserve("native", "plan", facts(due - 1), randomBounded = { 0 }))
        assertEquals(due, sentinelNextDueAtMs(store.get("native")!!))
        val pending = store.evaluateAndReserve("native", "daily", facts(due), randomBounded = { 0 })!!
        assertNull(sentinelNextDueAtMs(pending.rule))
        accept(store, "daily", due)
        assertNull(sentinelNextDueAtMs(store.get("native")!!))
        assertNull(sentinelNextDueAtMs(rule(OrbisSentinelType.SCREEN_OBSERVATION).copy(enabled = false)))
    }

    @Test fun `low battery misses and accepted events both wait for fresh episode`() {
        val disk = Disk(); val store = disk.store(); store.create(rule(OrbisSentinelType.LOW_BATTERY).copy(probabilityPercent = 50))
        fun battery(now: Long, percent: Int = 15, charging: Boolean = false) = OrbisSentinelObservation(now, batteryPercent = percent, isCharging = charging)
        assertNull(store.evaluateAndReserve("native", "miss", battery(origin), randomBounded = { 99 }))
        assertNull(disk.store().evaluateAndReserve("native", "no-reroll", battery(origin + 5_000), randomBounded = { error("episode already drawn") }))
        assertNull(store.evaluateAndReserve("native", "charging", battery(origin + 6_000, charging = true), randomBounded = { error("reset") }))
        assertNotNull(store.evaluateAndReserve("native", "hit", battery(origin + 7_000), randomBounded = { 0 }))
        accept(store, "hit", origin + 7_000)
        assertNull(store.evaluateAndReserve("native", "stilllow", battery(origin + 8_000), randomBounded = { error("already accepted") }))
        assertNull(store.evaluateAndReserve("native", "charged", battery(origin + 9_000, 21), randomBounded = { error("reset") }))
        assertNotNull(store.evaluateAndReserve("native", "newlow", battery(origin + 10_000, 20), randomBounded = { 0 }))
    }

    @Test fun `unknown observations never consume chance`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.LOW_BATTERY))
        assertNull(store.evaluateAndReserve("native", "unknown", OrbisSentinelObservation(origin), randomBounded = { error("unknown") }))
        assertNull(store.get("native")!!.policy.lastEvaluatedAtMs)
    }

    @Test fun `night usage counts only time in window and resets when chat resumes`() {
        val r = rule(OrbisSentinelType.NIGHT_USAGE).copy(thresholdMs = 300_000)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(r,
            OrbisSentinelObservation(origin + 299_999, nonChatUsageMs = 600_000)))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(r,
            OrbisSentinelObservation(origin + 300_000, nonChatUsageMs = 600_000)))
        assertEquals(OrbisSentinelCondition.RESET, sentinelCondition(r,
            OrbisSentinelObservation(origin + 300_000, nonChatUsageMs = 0)))
        assertEquals(OrbisSentinelCondition.RESET, sentinelCondition(r,
            OrbisSentinelObservation(origin + 8 * 3_600_000, nonChatUsageMs = 600_000)))
    }

    @Test fun `screen duration after human resume excludes paused time`() {
        val r = rule(OrbisSentinelType.SCREEN_ON).copy(thresholdMs = 600_000)
        assertEquals(OrbisSentinelCondition.WAITING, sentinelCondition(r,
            OrbisSentinelObservation(origin + 1_200_000, screenOnMs = 2_000_000), origin + 1_000_000))
        assertEquals(OrbisSentinelCondition.DUE, sentinelCondition(r,
            OrbisSentinelObservation(origin + 1_600_000, screenOnMs = 2_000_000), origin + 1_000_000))
    }

    @Test fun `failed observation waits next configured interval instead of rescreenshot every tick`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.SCREEN_OBSERVATION))
        val due = origin + 1_800_000
        assertNotNull(store.evaluateAndReserve("native", "failed", facts(due)))
        store.abandonPending("native", "failed", due, "screenshot_failed")
        assertNull(store.evaluateAndReserve("native", "five-seconds", facts(due + 5_000)))
        assertNotNull(store.evaluateAndReserve("native", "next", facts(due + 1_800_000)))
    }

    @Test fun `external events cannot attach new facts to another pending identity`() {
        val store = Disk().store(); store.create(rule(OrbisSentinelType.TOUCH))
        fun touch(key: String, observed: Long = origin) = OrbisSentinelObservation(origin, eventKey = key, eventObservedAtMs = observed)
        assertNull(store.evaluateAndReserve("native", "future", touch("future", origin + 1)))
        assertNull(store.evaluateAndReserve("native", "old", touch("old", origin - 1)))
        val first = store.evaluateAndReserve("native", "touch-a", touch("a"))!!
        assertFalse(first.replay)
        assertNull(store.evaluateAndReserve("native", "touch-b", touch("b")))
        assertTrue(store.evaluateAndReserve("native", "touch-a", touch("a"))!!.replay)
        accept(store, "touch-a", origin)
        assertNull(store.evaluateAndReserve("native", "touch-a", touch("a")))
        assertNotNull(store.evaluateAndReserve("native", "touch-b", touch("b")))
    }

    @Test fun `postcommit failure recovers same probability and event without a second draw`() {
        val disk = Disk(); val store = disk.store(); store.create(rule())
        disk.throwAfterWrite = true
        assertTrue(runCatching { store.evaluateAndReserve("native", "chosen", facts(origin + 1_800_000), randomBounded = { 0 }) }.isFailure)
        disk.throwAfterWrite = false
        val recovered = disk.store().evaluateAndReserve("native", "replacement", facts(origin + 1_805_000), randomBounded = { error("no redraw") })!!
        assertTrue(recovered.replay)
        assertEquals("chosen", recovered.rule.pendingEventId)
        assertEquals(1, recovered.rule.policy.pendingOrdinal)
    }

    @Test fun `native runtime state cannot be supplied or edited by AI`() {
        val store = Disk().store()
        assertTrue(runCatching { store.create(rule().copy(policy = OrbisSentinelPolicyState(consecutiveCount = 9))) }.isFailure)
        store.create(rule())
        assertEquals("sentinel_runtime_state_changed", runCatching {
            store.update("native", binding, origin + 1) { it.copy(policy = it.policy.copy(consecutiveCount = 9)) }
        }.exceptionOrNull()!!.message)
    }

    @Test fun `native config rejects fake system prompts and invalid probability or intervals`() {
        val store = Disk().store()
        listOf(rule(OrbisSentinelType.LOW_BATTERY).copy(prompt = "fake observed facts"),
            rule().copy(probabilityPercent = 25), rule().copy(thresholdMs = 599_999),
            rule(OrbisSentinelType.SCREEN_OBSERVATION).copy(intervalMs = 1_900_000),
            rule(OrbisSentinelType.RITUAL).copy(dailyAtLocal = "24:00"),
            rule(OrbisSentinelType.RITUAL).copy(timezone = "invalid_zone"),
            rule(OrbisSentinelType.NIGHT_USAGE).copy(windowEndLocal = null)).forEach {
            assertTrue(runCatching { store.create(it) }.isFailure)
        }
    }

    @Test fun `timestamp is host generated preserves prompt and frozen by staging`() {
        val store = Disk().store(); store.create(rule())
        val now = origin + 1_800_000
        val pending = store.evaluateAndReserve("native", "time", facts(now), randomBounded = { 0 })!!
        val text = sentinelWakeText(pending.rule, emittedAtMs = now, zone = ZoneId.of("Asia/Shanghai"))
        assertTrue(text.contains(rule().prompt))
        assertTrue(text.contains("2026-09-24 08:30:00 +08:00 [Asia/Shanghai]"))
        store.stageEventText("native", "time", text)
        assertEquals(text, store.evaluateAndReserve("native", "retry", facts(now + 10_000))!!.rule.pendingText)
        assertTrue(runCatching { store.stageEventText("native", "time", text + "changed") }.isFailure)
    }

    @Test fun `system formatter requires facts and all categories include timestamp`() {
        for (type in OrbisSentinelType.entries) {
            val r = rule(type)
            if (type in SENTINEL_SYSTEM_TYPES) assertTrue(runCatching { sentinelWakeText(r, emittedAtMs = origin) }.isFailure)
            val text = sentinelWakeText(r, "宿主观测原文", origin, ZoneId.of("UTC"))
            assertTrue(text.startsWith("【"))
            assertTrue(text.contains("[系统时间：2026-09-24 00:00:00 Z [UTC]]"))
            assertTrue(text.contains(if (type in SENTINEL_SYSTEM_TYPES) "宿主观测原文" else r.prompt))
        }
    }
}
