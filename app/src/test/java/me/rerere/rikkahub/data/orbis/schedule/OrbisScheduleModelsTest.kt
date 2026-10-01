package me.rerere.rikkahub.data.orbis.schedule

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class OrbisScheduleModelsTest {
    private fun weekly() = OrbisScheduleDraft(OrbisScheduleKind.WEEKLY, "合成数学课", startTime = "08:00", endTime = "09:00", weekdays = listOf(1, 3))
    private fun entry(draft: OrbisScheduleDraft) = OrbisScheduleEntry("synthetic-1", draft, 1, 1)

    @Test fun `unbounded weekly schedule never expires and uses ISO weekdays`() {
        val value = entry(weekly())
        validateOrbisScheduleDraft(value.details)
        assertTrue(value.occursOn(LocalDate.parse("2026-09-30")))
        assertFalse(value.occursOn(LocalDate.parse("2026-10-01")))
        assertTrue(value.occursOn(LocalDate.parse("2046-10-01")))
    }

    @Test fun `weekly range includes both endpoints without creating dates outside it`() {
        val value = entry(weekly().copy(validFrom = "2026-09-28", validUntil = "2026-09-30"))
        validateOrbisScheduleDraft(value.details)
        assertTrue(value.occursOn(LocalDate.parse("2026-09-28")))
        assertTrue(value.occursOn(LocalDate.parse("2026-09-30")))
        assertFalse(value.occursOn(LocalDate.parse("2026-09-23")))
        assertFalse(value.occursOn(LocalDate.parse("2026-10-05")))
        validateOrbisScheduleDraft(weekly().copy(validFrom = "2026-09-28"))
        validateOrbisScheduleDraft(weekly().copy(validUntil = "2026-09-30"))
    }

    @Test fun `one off leap day and all day are explicit`() {
        val value = entry(OrbisScheduleDraft(OrbisScheduleKind.DATE, "合成重要事项", date = "2028-02-29", allDay = true, important = true))
        validateOrbisScheduleDraft(value.details)
        assertTrue(value.occursOn(LocalDate.parse("2028-02-29")))
        assertFalse(value.occursOn(LocalDate.parse("2028-03-01")))
        assertTrue(value.details.important)
    }

    @Test fun `invalid dates and times cannot normalize silently`() {
        listOf("2026-02-29", "2026-2-01", "0000-01-01", "2026-13-01", "2026-01-01Z").forEach {
            assertNotNull(runCatching { parseOrbisScheduleDate(it) }.exceptionOrNull())
        }
        listOf("8:00", "24:00", "09:60", "09:00:00", " 09:00").forEach {
            assertNotNull(runCatching { parseOrbisScheduleTime(it) }.exceptionOrNull())
        }
    }

    @Test fun `invalid recurrence range mixed kind and overnight fail validation`() {
        listOf(weekly().copy(weekdays = emptyList()), weekly().copy(weekdays = listOf(0)), weekly().copy(weekdays = listOf(1, 1)),
            weekly().copy(date = "2026-09-30"), weekly().copy(validFrom = "2026-10-01", validUntil = "2026-09-30"),
            weekly().copy(startTime = "23:00", endTime = "01:00"), weekly().copy(startTime = "09:00"),
            weekly().copy(allDay = true), weekly().copy(endTime = null), weekly().copy(kind = OrbisScheduleKind.DATE))
            .forEach { assertNotNull(runCatching { validateOrbisScheduleDraft(it) }.exceptionOrNull()) }
    }

    @Test fun `text bounds and malformed unicode do not enter storage`() {
        listOf(weekly().copy(title = " "), weekly().copy(title = "x".repeat(161)), weekly().copy(notes = "x".repeat(4_001)),
            weekly().copy(location = "x".repeat(161)), weekly().copy(title = "x\n"), weekly().copy(notes = "\u0000"),
            weekly().copy(title = "\uD800")).forEach { assertNotNull(runCatching { validateOrbisScheduleDraft(it) }.exceptionOrNull()) }
        validateOrbisScheduleDraft(weekly().copy(title = "合成📘", notes = "第一行\n第二行\t备注"))
    }

    @Test fun `calendar is Monday first with complete selectable weeks`() {
        val days = orbisScheduleMonthDays(YearMonth.of(2026, 9))
        assertEquals(LocalDate.parse("2026-08-31"), days.first())
        assertEquals(LocalDate.parse("2026-10-04"), days.last())
        assertEquals(35, days.size)
        assertEquals(28, orbisScheduleMonthDays(YearMonth.of(2021, 2)).size)
    }

    @Test fun `agenda combines weekly and dated entries and preserves overlap`() {
        val recurring = entry(weekly())
        val oneOff = OrbisScheduleEntry("synthetic-2", OrbisScheduleDraft(OrbisScheduleKind.DATE, "合成考试", date = "2026-09-30", startTime = "08:00", endTime = "10:00", important = true), 1, 1)
        val allDay = oneOff.copy(id = "synthetic-3", details = oneOff.details.copy(allDay = true, startTime = null, endTime = null))
        val result = OrbisScheduleSnapshot(revision = 1, entries = listOf(recurring, oneOff, allDay)).entriesOn(LocalDate.parse("2026-09-30"))
        assertEquals(listOf("synthetic-3", "synthetic-2", "synthetic-1"), result.map { it.id })
    }
}
