package me.rerere.rikkahub.data.orbis

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A fixed, explicit day does not drift when the device time zone changes. */
fun gardenEntryDate(entry: OrbisGardenEntry, zone: ZoneId): LocalDate =
    entry.entryDate?.let(::parseGardenEntryDate) ?: gardenCreatedDate(entry.createdAt, zone)

internal fun parseGardenEntryDate(value: String): LocalDate {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "garden_invalid_entry" }
    val day = runCatching { LocalDate.parse(value) }.getOrNull()
    require(day != null && day.year in 1..9999) { "garden_invalid_entry" }
    return day
}

/** Calendar day for a stored creation instant in the currently selected device time zone. */
fun gardenCreatedDate(createdAt: Long, zone: ZoneId): LocalDate {
    require(createdAt >= 0) { "garden_invalid_created_at" }
    return Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
}

/** Monday-first month grid. Adjacent-month slots are null and the tail always ends on Sunday. */
fun gardenCalendarCells(month: YearMonth): List<LocalDate?> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val trailing = (7 - (leading + days) % 7) % 7
    return buildList(leading + days + trailing) {
        repeat(leading) { add(null) }
        for (day in 1..days) add(month.atDay(day))
        repeat(trailing) { add(null) }
    }
}
