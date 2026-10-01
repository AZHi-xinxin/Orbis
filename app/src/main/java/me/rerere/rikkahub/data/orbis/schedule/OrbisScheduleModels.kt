package me.rerere.rikkahub.data.orbis.schedule

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.serialization.Serializable

/** Device-local wall-clock dates/times; no implicit timezone conversion or system calendar sync. */
@Serializable
enum class OrbisScheduleKind { WEEKLY, DATE }

@Serializable
data class OrbisScheduleDraft(
    val kind: OrbisScheduleKind,
    val title: String,
    val notes: String = "",
    val location: String = "",
    val important: Boolean = false,
    val allDay: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
    val weekdays: List<Int> = emptyList(),
    val date: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
)

@Serializable
data class OrbisScheduleEntry(
    val id: String,
    val details: OrbisScheduleDraft,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class OrbisScheduleSnapshot(
    val version: Int = 1,
    val revision: Int = 0,
    val entries: List<OrbisScheduleEntry> = emptyList(),
)

internal const val ORBIS_SCHEDULE_MAX_ENTRIES = 2_000
internal const val ORBIS_SCHEDULE_MAX_BYTES = 16 * 1024 * 1024

fun parseOrbisScheduleDate(value: String): LocalDate {
    require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "schedule_invalid_date" }
    val date = try { LocalDate.parse(value) } catch (_: Exception) { error("schedule_invalid_date") }
    require(date.year in 1..9999 && date.toString() == value) { "schedule_invalid_date" }
    return date
}

fun parseOrbisScheduleTime(value: String): LocalTime {
    require(value.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "schedule_invalid_time" }
    return try { LocalTime.parse(value) } catch (_: Exception) { error("schedule_invalid_time") }
}

internal fun validOrbisScheduleText(value: String, maxLength: Int, multiline: Boolean = false): Boolean {
    if (value.length > maxLength) return false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isISOControl() && !(multiline && (char == '\n' || char == '\t'))) return false
        if (char.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
            index++
        } else if (char.isLowSurrogate()) return false
        index++
    }
    return true
}

fun validateOrbisScheduleDraft(draft: OrbisScheduleDraft) {
    require(draft.title.isNotBlank() && validOrbisScheduleText(draft.title, 160)) { "schedule_invalid_title" }
    require(validOrbisScheduleText(draft.notes, 4_000, multiline = true)) { "schedule_invalid_notes" }
    require(validOrbisScheduleText(draft.location, 160)) { "schedule_invalid_location" }
    if (draft.allDay) {
        require(draft.startTime == null && draft.endTime == null) { "schedule_invalid_time" }
    } else {
        val start = parseOrbisScheduleTime(draft.startTime ?: error("schedule_invalid_time"))
        val end = parseOrbisScheduleTime(draft.endTime ?: error("schedule_invalid_time"))
        require(end > start) { "schedule_time_order" }
    }
    when (draft.kind) {
        OrbisScheduleKind.DATE -> {
            parseOrbisScheduleDate(draft.date ?: error("schedule_invalid_date"))
            require(draft.weekdays.isEmpty() && draft.validFrom == null && draft.validUntil == null) { "schedule_invalid_recurrence" }
        }
        OrbisScheduleKind.WEEKLY -> {
            require(draft.date == null && draft.weekdays.size in 1..7 &&
                draft.weekdays.distinct().size == draft.weekdays.size && draft.weekdays.all { it in 1..7 }) { "schedule_invalid_recurrence" }
            val from = draft.validFrom?.let(::parseOrbisScheduleDate)
            val until = draft.validUntil?.let(::parseOrbisScheduleDate)
            require(from == null || until == null || from <= until) { "schedule_date_order" }
        }
    }
}

internal fun validateOrbisScheduleId(id: String) {
    require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}"))) { "schedule_invalid_id" }
}

internal fun validateOrbisScheduleSnapshot(snapshot: OrbisScheduleSnapshot) {
    require(snapshot.version == 1 && snapshot.revision >= 0 &&
        (snapshot.revision != 0 || snapshot.entries.isEmpty()) &&
        snapshot.entries.size <= ORBIS_SCHEDULE_MAX_ENTRIES &&
        snapshot.entries.map { it.id }.distinct().size == snapshot.entries.size) { "schedule_invalid_storage" }
    snapshot.entries.forEach {
        validateOrbisScheduleId(it.id)
        validateOrbisScheduleDraft(it.details)
        require(it.createdAt >= 0 && it.updatedAt >= it.createdAt) { "schedule_invalid_storage" }
    }
}

/** Range endpoints are inclusive. Empty endpoints mean no start/end restriction, not expiry. */
fun OrbisScheduleEntry.occursOn(day: LocalDate): Boolean = when (details.kind) {
    OrbisScheduleKind.DATE -> details.date == day.toString()
    OrbisScheduleKind.WEEKLY -> day.dayOfWeek.value in details.weekdays &&
        (details.validFrom == null || day >= parseOrbisScheduleDate(details.validFrom)) &&
        (details.validUntil == null || day <= parseOrbisScheduleDate(details.validUntil))
}

fun OrbisScheduleSnapshot.entriesOn(day: LocalDate): List<OrbisScheduleEntry> = entries.filter { it.occursOn(day) }
    .sortedWith(compareBy<OrbisScheduleEntry> { !it.details.allDay }.thenBy { it.details.startTime.orEmpty() }
        .thenByDescending { it.details.important }.thenBy { it.details.title }.thenBy { it.id })

/** Monday-first calendar; leading/trailing dates remain real dates and are selectable. */
fun orbisScheduleMonthDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    val weeks = (first.dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
    return List(weeks * 7) { start.plusDays(it.toLong()) }
}
