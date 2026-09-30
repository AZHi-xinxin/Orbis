package me.rerere.rikkahub.data.orbis

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OrbisGardenCalendarTest {
    @Test fun leapFebruaryUsesMondayFirstAndPadsToWholeWeeks() {
        val cells = gardenCalendarCells(YearMonth.of(2024, 2))
        assertEquals(35, cells.size)
        assertEquals(listOf(null, null, null), cells.take(3))
        assertEquals(LocalDate.of(2024, 2, 1), cells[3])
        assertEquals(LocalDate.of(2024, 2, 29), cells[31])
        assertEquals(listOf(null, null, null), cells.takeLast(3))
        assertEquals(0, cells.size % 7)
    }

    @Test fun mondayBoundaryHasNoLeadingCells() {
        val cells = gardenCalendarCells(YearMonth.of(2024, 1))
        assertEquals(LocalDate.of(2024, 1, 1), cells.first())
        assertEquals(LocalDate.of(2024, 1, 31), cells[30])
        assertEquals(35, cells.size)
        assertTrue(cells.takeLast(4).all { it == null })
    }

    @Test fun sundayBoundaryUsesSixLeadingCells() {
        val cells = gardenCalendarCells(YearMonth.of(2024, 9))
        assertEquals(42, cells.size)
        assertTrue(cells.take(6).all { it == null })
        assertEquals(LocalDate.of(2024, 9, 1), cells[6])
        assertEquals(LocalDate.of(2024, 9, 30), cells[35])
        assertTrue(cells.takeLast(6).all { it == null })
    }

    @Test fun decemberAndJanuaryStayInsideTheirOwnYearMonth() {
        val december = gardenCalendarCells(YearMonth.of(2024, 12))
        val january = gardenCalendarCells(YearMonth.of(2025, 1))
        assertEquals(LocalDate.of(2024, 12, 1), december.filterNotNull().first())
        assertEquals(LocalDate.of(2024, 12, 31), december.filterNotNull().last())
        assertEquals(LocalDate.of(2025, 1, 1), january.filterNotNull().first())
        assertEquals(LocalDate.of(2025, 1, 31), january.filterNotNull().last())
        assertTrue(december.filterNotNull().all { YearMonth.from(it) == YearMonth.of(2024, 12) })
        assertTrue(january.filterNotNull().all { YearMonth.from(it) == YearMonth.of(2025, 1) })
    }

    @Test fun creationInstantUsesRequestedZoneAndCanCrossDate() {
        val createdAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli()
        assertEquals(LocalDate.of(2026, 1, 1), gardenCreatedDate(createdAt, ZoneId.of("UTC")))
        assertEquals(
            LocalDate.of(2025, 12, 31),
            gardenCreatedDate(createdAt, ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test fun legacyEntriesContinueUsingCreationInstantAndRequestedZone() {
        val entry = OrbisGardenEntry(
            id = "00000000-0000-0000-0000-000000000001",
            kind = OrbisGardenKind.DIARY,
            title = "legacy",
            body = "plain text",
            author = "me",
            createdAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(),
            updatedAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(),
        )

        assertNull(entry.entryDate)
        assertEquals(LocalDate.of(2026, 1, 1), gardenEntryDate(entry, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2025, 12, 31), gardenEntryDate(entry, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun explicitLeapDayOverridesCreationDateAndNeverShiftsWithTimeZone() {
        val entry = OrbisGardenEntry(
            id = "00000000-0000-0000-0000-000000000001",
            kind = OrbisGardenKind.SONG,
            title = "leap day song",
            body = "plain text",
            author = "me",
            createdAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(),
            updatedAt = Instant.parse("2026-01-01T00:30:00Z").toEpochMilli(),
            entryDate = "2024-02-29",
        )

        listOf("UTC", "America/Los_Angeles", "Asia/Shanghai", "Pacific/Kiritimati").forEach { zone ->
            assertEquals(LocalDate.of(2024, 2, 29), gardenEntryDate(entry, ZoneId.of(zone)))
        }
    }

    @Test fun invalidExplicitDateCannotFallBackToCreationDate() {
        val entry = OrbisGardenEntry(
            id = "00000000-0000-0000-0000-000000000001",
            kind = OrbisGardenKind.DIARY,
            title = "invalid date",
            body = "plain text",
            author = "me",
            createdAt = 1,
            updatedAt = 2,
            entryDate = "2025-02-29",
        )

        try {
            gardenEntryDate(entry, ZoneId.of("UTC"))
            fail("An invalid explicit date must not silently use createdAt")
        } catch (error: IllegalArgumentException) {
            assertEquals("garden_invalid_entry", error.message)
        }
    }
}
