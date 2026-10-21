package me.rerere.rikkahub.data.orbis.activities

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** Immutable UI/read projection: never waits for a disk write's repository monitor. */
fun studyWrongQuestions(state: StudyState, subject: String = ""): List<StudyQuestion> {
    val attemptsByQuestion = state.sessions.flatMap { it.attempts }.groupBy { it.questionId }
    return state.questions.filter { question ->
        if (subject.isNotBlank() && question.subject != subject) return@filter false
        val attempts = attemptsByQuestion[question.id].orEmpty()
        if (attempts.any { it.flagged }) return@filter false
        val scores = attempts.mapNotNull { it.judgements.lastOrNull()?.score }
        val lastWrong = scores.indexOfLast { it < 100 }
        lastWrong >= 0 && scores.size - lastWrong - 1 < 3
    }
}

@Serializable
data class StudyFocusSession(val id: String, val subject: String, val startedAt: Long, val zoneId: String,
    val activeMillis: Long = 0, val checkpointAt: Long = startedAt, val finishedAt: Long? = null) {
    companion object { const val MAX_MILLIS = 86_400_000L }
}

/** Monotonic active time only. A wall-clock jump or time spent paused cannot inflate a session. */
class StudyFocusClock(initialMillis: Long, private val elapsed: () -> Long) {
    private var accumulated = initialMillis.coerceIn(0, StudyFocusSession.MAX_MILLIS)
    private var started: Long? = null
    val running get() = started != null
    fun resume() { if (started == null && accumulated < StudyFocusSession.MAX_MILLIS) started = elapsed() }
    fun value(): Long = (accumulated + (started?.let { (elapsed() - it).coerceAtLeast(0) } ?: 0)).coerceAtMost(StudyFocusSession.MAX_MILLIS)
    fun pause(): Long { accumulated = value(); started = null; return accumulated }
}

@Serializable
data class StudyStatistics(val cumulativeDays: Int, val consecutiveDays: Int, val studiedToday: Boolean,
    val studyDays: List<String>, val focusMillis: Long, val finishedFocusCount: Int, val pendingCount: Int,
    val legacyDatesEstimated: Boolean, val zoneId: String,
    val durationNote: String = "专注时长仅为人类计时器已保存的前台有效时间；不是页面打开时长。进行中记录最多延迟15秒。学习日按有效答卷提交计算，待批改不影响计日。") {
    companion object {
        fun from(state: StudyState, now: Long, zone: ZoneId, subject: String = ""): StudyStatistics {
            val sessions = state.sessions.filter { subject.isBlank() || it.subject == subject }
            val completed = sessions.filter { it.submittedAt != null && it.attempts.any { a -> a.answer.any(String::isNotBlank) } }
            val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val days = completed.map { it.submittedDay?.let(LocalDate::parse)
                ?: Instant.ofEpochMilli(it.submittedAt!!).atZone(zone).toLocalDate() }.distinct().sorted()
            val valid = days.filter { !it.isAfter(today) }.toSet()
            var cursor = if (today in valid) today else today.minusDays(1)
            var streak = 0
            while (cursor in valid) { streak++; cursor = cursor.minusDays(1) }
            val focus = state.focusSessions.filter { subject.isBlank() || it.subject == subject }
            return StudyStatistics(valid.size, streak, today in valid, days.map(LocalDate::toString),
                focus.sumOf { it.activeMillis }, focus.count { it.finishedAt != null },
                sessions.sumOf { it.attempts.count { a -> a.submitted && a.judgements.isEmpty() } },
                completed.any { it.submittedDay == null }, zone.id)
        }
    }
}
