package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.OrbisEventMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class OrbisEventPresentationTest {
    private val event = OrbisEventMetadata("synthetic-receipt", "lc_sentinel", "synthetic-event", 1000L,
        occurredAt = 500L)
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-22T18:00:00Z")

    @Test fun openingUnreadEventMarksReadWithoutChangingIdentityOrSource() {
        assertEquals(event.copy(collapsed = false, read = true), toggleOrbisEventPresentation(event))
    }

    @Test fun collapsingReadEventDoesNotMarkUnread() {
        val opened = event.copy(collapsed = false, read = true)
        assertEquals(event.copy(read = true), toggleOrbisEventPresentation(opened))
    }

    @Test fun collapsingLegacyExpandedUnreadEventKeepsReadStatus() {
        assertEquals(event, toggleOrbisEventPresentation(event.copy(collapsed = false)))
    }

    @Test fun reopeningSavedReadEventIsStillRead() {
        assertEquals(event.copy(collapsed = false, read = true),
            toggleOrbisEventPresentation(event.copy(read = true)))
    }

    @Test fun todaysOccurrenceIsOnlyHourAndMinute() {
        val result = present("2026-09-22T15:42:38Z")
        assertEquals("15:42", result.compactLabel)
        assertEquals("来源提供的触发时间 · 2026-09-22 15:42:38", result.detailLabel)
    }

    @Test fun yesterdayIncludesFriendlyDayLabel() {
        assertEquals("昨天 23:05", present("2026-09-21T23:05:00Z").compactLabel)
    }

    @Test fun olderDayInCurrentYearIncludesMonthAndDay() {
        assertEquals("8月3日 09:07", present("2026-08-03T09:07:00Z").compactLabel)
    }

    @Test fun priorYearIncludesYearMonthAndDay() {
        assertEquals("2025年9月22日 08:06", present("2025-09-22T08:06:00Z").compactLabel)
    }

    @Test fun occurrenceIsPreferredOverLaterReceiptWithoutInventingAnotherTime() {
        val result = orbisEventTimePresentation(event.copy(
            occurredAt = Instant.parse("2026-09-21T20:00:00Z").toEpochMilli(),
            receivedAt = Instant.parse("2026-09-22T16:03:00Z").toEpochMilli(),
        ), now, utc)
        assertEquals("昨天 20:00", result.compactLabel)
        assertTrue(result.detailLabel.endsWith("2026-09-21 20:00:00"))
    }

    @Test fun legacyRecordExplicitlyLabelsReceiptFallbackRatherThanOccurrence() {
        val result = orbisEventTimePresentation(event.copy(occurredAt = null,
            receivedAt = Instant.parse("2026-09-22T16:03:00Z").toEpochMilli()), now, utc)
        assertEquals("16:03", result.compactLabel)
        assertEquals("接收时间（未提供触发时间） · 2026-09-22 16:03:00", result.detailLabel)
    }

    @Test fun relativeDayAndClockUseTheInjectedLocalZoneNotUtc() {
        val event = event.copy(occurredAt = Instant.parse("2026-09-21T23:30:00Z").toEpochMilli())
        val nearMidnight = Instant.parse("2026-09-22T00:15:00Z")
        assertEquals("昨天 23:30", orbisEventTimePresentation(event, nearMidnight, utc).compactLabel)
        assertEquals("07:30", orbisEventTimePresentation(event, nearMidnight, ZoneId.of("Asia/Shanghai")).compactLabel)
    }

    @Test fun yesterdayTakesPrecedenceAtYearBoundary() {
        val event = event.copy(occurredAt = Instant.parse("2025-12-31T23:55:00Z").toEpochMilli())
        assertEquals("昨天 23:55", orbisEventTimePresentation(event,
            Instant.parse("2026-01-01T00:15:00Z"), utc).compactLabel)
    }

    private fun present(occurred: String) = orbisEventTimePresentation(
        event.copy(occurredAt = Instant.parse(occurred).toEpochMilli()), now, utc)
}
