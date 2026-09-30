package me.rerere.rikkahub.data.orbis.sentinel

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrbisSentinelLocationStoreTest {
    private fun event(id: String, type: String, at: Long = 1000, override: Boolean = false) = buildJsonObject {
        put("event_id", id); put("away_session_id", "trip-1"); put("type", type)
        put("zone_label", "合成区域"); put("occurred_at", at); put("reported_override", override)
    }.toString()
    private class Fixture {
        var disk: String? = null
        fun store() = OrbisSentinelLocationStore({ disk }, { disk = it })
    }
    @Test fun departureJobAndDuplicateSurviveRestart() {
        val f = Fixture()
        assertTrue(f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, true))
        assertFalse(f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2001, true))
        assertEquals(1, f.store().due(2001).size)
    }
    @Test fun arrivalCancelsPendingReportAndAddsOnlyArrival() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, true)
        f.store().accept("rule-1", event("e2", "zone_enter_confirmed", 3000), 3000, true)
        assertEquals(listOf("arrival"), f.store().due(9999).map { it.kind })
    }
    @Test fun reportAcknowledgementSuppressesChecksAndOnlyCorrectsAnEarlierNotice() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, true)
        val first = f.store().due(2000).single()
        f.store().mark(first, "accepted", 2000)
        f.store().accept("rule-1", event("e2", "distance_tier_crossed", 3000), 3000, true)
        f.store().accept("rule-1", event("e3", "report_acknowledged", 4000, true), 4000, true)
        assertEquals(listOf("correction"), f.store().due(2_000_000).map { it.kind })
    }
    @Test fun distanceBeforeFirstDeliveryStillSchedulesSecondAfterDelay() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, true)
        f.store().accept("rule-1", event("e2", "distance_tier_crossed", 3000), 3000, true)
        f.store().mark(f.store().due(4000).single(), "accepted", 4000)
        assertTrue(f.store().due(4000 + OrbisSentinelLocationStore.SECOND_DELAY - 1).isEmpty())
        assertEquals("distance", f.store().due(4000 + OrbisSentinelLocationStore.SECOND_DELAY).single().kind)
    }
    @Test fun pausedEventsNeverBecomeNewJobsAfterResume() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, false)
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 3000, true)
        assertTrue(f.store().due(10_000).isEmpty())
    }
    @Test fun inactiveRulesAndResumeCancelOldJobs() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed"), 2000, true)
        f.store().cancelInactive(setOf("rule-1"), 3000)
        assertTrue(f.store().due(10_000).isEmpty())
    }
    @Test fun reportedDepartureAndOfflineReturnAvoidStaleDeparture() {
        val f = Fixture()
        f.store().accept("rule-1", event("e1", "zone_exit_confirmed", override = true), 2000, true)
        assertTrue(f.store().due(2000).isEmpty())
        f.store().accept("rule-1", event("e2", "offline_trip_summary", 3000), 3000, true)
        val job = f.store().due(4000).single()
        assertEquals("offline", job.kind)
        assertTrue(sentinelLocationFacts(job, f.store().trip(job)!!).contains("不补发"))
    }
    @Test fun humanReportNegationQuestionAndHypotheticalDoNotCount() {
        for (text in listOf("我不出门了", "如果我出门你会知道吗", "我出门了吗？", "假如我回家"))
            assertFalse(text, sentinelHumanReported(listOf(text)))
        for (text in listOf("我出门啦", "我现在在外面", "我已经到家了", "我准备去公司"))
            assertTrue(text, sentinelHumanReported(listOf(text)))
    }

    @Test fun lateDepartureCannotReopenAlreadyCompletedTrip() {
        val f = Fixture()
        f.store().accept("rule-1", event("return", "zone_enter_confirmed", 4000), 5000, true)
        f.store().accept("rule-1", event("late-exit", "zone_exit_confirmed", 1000), 6000, true)
        assertEquals(listOf("arrival"), f.store().due(7000).map { it.kind })
        assertFalse(f.store().trip(f.store().due(7000).single())!!.away)
    }

    @Test fun offlineAndOnlineArrivalOnlyNotifyOncePerTrip() {
        val f = Fixture()
        f.store().accept("rule-1", event("offline", "offline_trip_summary", 4000), 5000, true)
        f.store().accept("rule-1", event("online", "zone_enter_confirmed", 4001), 6000, true)
        assertEquals(listOf("offline"), f.store().due(7000).map { it.kind })
    }

    @Test fun reportScanBoundsTotalRecentTextNotEveryMessageIndividually() {
        assertFalse(sentinelHumanReported(listOf("我准备出门", "空".repeat(6000))))
        assertTrue(sentinelHumanReported(listOf("空".repeat(6000), "我准备出门")))
    }
    @Test fun laterCancelledPlanDoesNotSuppressActualDeparture() {
        assertFalse(sentinelHumanReported(listOf("我待会儿出门", "我不出门了")))
        assertTrue(sentinelHumanReported(listOf("我不出门了", "我现在出门了")))
    }
}
