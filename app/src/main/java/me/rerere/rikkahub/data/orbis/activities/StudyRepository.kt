package me.rerere.rikkahub.data.orbis.activities

import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StudyQuestion(val id: String, val subject: String, val kind: String, val prompt: String,
    val options: List<String> = emptyList(), val topics: List<String> = emptyList(), val source: String = "",
    val fingerprint: String = "")
@Serializable
data class StudyAnswerKey(val questionId: String, val answers: List<String>, val explanation: String,
    val trim: Boolean = true, val ignoreCase: Boolean = false, val normalizeWidth: Boolean = false)
@Serializable
data class StudyInputQuestion(val kind: String, val prompt: String, val options: List<String> = emptyList(),
    val answers: List<String>, val explanation: String = "", val topics: List<String> = emptyList(),
    val source: String = "", val trim: Boolean = true, val ignoreCase: Boolean = false, val normalizeWidth: Boolean = false)
@Serializable
data class StudyJudgement(val score: Int, val comment: String, val at: Long, val by: String)
@Serializable
data class StudyAttempt(val questionId: String, val answer: List<String> = emptyList(), val submitted: Boolean = false,
    val judgements: List<StudyJudgement> = emptyList(), val flagged: Boolean = false)
@Serializable
data class StudySession(val id: String, val subject: String, val questionIds: List<String>, val createdAt: Long,
    val attempts: List<StudyAttempt>, val submittedAt: Long? = null,
    val submittedDay: String? = null, val submittedZone: String? = null)
@Serializable
data class StudyProgress(val subject: String, val revision: Long = 0, val plan: String = "", val topics: Set<String> = emptySet())
@Serializable
data class StudyCommit(val operationId: String, val sessionId: String, val requestHash: String)
@Serializable
data class StudyState(val version: Int = 1, val questions: List<StudyQuestion> = emptyList(),
    val keys: List<StudyAnswerKey> = emptyList(), val sessions: List<StudySession> = emptyList(),
    val progress: List<StudyProgress> = emptyList(), val commits: List<StudyCommit> = emptyList(),
    val focusSessions: List<StudyFocusSession> = emptyList())

/** Questions are immutable; a session points at the exact revision created for it. Answer keys never enter the quiz projection. */
class StudyRepository(private val storage: ActivityStorage, private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val json = Json { encodeDefaults = true }
    private val mutable = MutableStateFlow(storage.read()?.let { json.decodeFromString<StudyState>(it).also(::validate) } ?: StudyState())
    val state = mutable.asStateFlow()
    private var blocked = false
    @Synchronized fun reload() {
        val next = storage.read()?.let { json.decodeFromString<StudyState>(it).also(::validate) } ?: StudyState()
        mutable.value = next; blocked = false
    }
    @Synchronized fun snapshot() = mutable.value
    @Synchronized fun progress(subject: String) = mutable.value.progress.find { it.subject == subject } ?: StudyProgress(subject)
    @Synchronized fun commitQuestions(subject: String, expectedRevision: Long, operationId: String,
        questions: List<StudyInputQuestion>, plan: String): StudySession {
        require(subject.isNotBlank() && subject.length <= 80 && plan.length <= 8000) { "invalid_subject_or_plan" }
        require(operationId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "invalid_operation_id" }
        val before = mutable.value
        val requestHash = hash(subject + "\n" + json.encodeToString(questions) + "\n" + plan)
        before.commits.find { it.operationId == operationId }?.let {
            require(it.requestHash == requestHash) { "operation_id_reused" }
            return before.sessions.single { session -> session.id == it.sessionId }
        }
        val progress = progress(subject)
        require(expectedRevision == progress.revision) { "progress_changed_read_progress" }
        require(questions.size in 1..100) { "question_count_1_to_100" }
        questions.forEach(::validateInput)
        val entries = questions.map { q -> StudyQuestion(newId(), subject, q.kind, q.prompt, q.options, q.topics, q.source,
            hash(q.kind + "\n" + q.prompt.trim() + "\n" + q.options.joinToString("\n"))) }
        val keys = entries.zip(questions).map { (q, input) -> StudyAnswerKey(q.id, input.answers, input.explanation,
            input.trim, input.ignoreCase, input.normalizeWidth) }
        val session = StudySession(newId(), subject, entries.map { it.id }, now(), entries.map { StudyAttempt(it.id) })
        val updated = progress.copy(revision = progress.revision + 1, plan = plan, topics = progress.topics + questions.flatMap { it.topics })
        commit(before.copy(questions = before.questions + entries, keys = before.keys + keys, sessions = before.sessions + session,
            progress = before.progress.filterNot { it.subject == subject } + updated,
            commits = before.commits + StudyCommit(operationId, session.id, requestHash)))
        return session
    }
    @Synchronized fun draft(sessionId: String, questionId: String, answer: List<String>) {
        val session = session(sessionId)
        require(session.submittedAt == null && questionId in session.questionIds) { "session_already_submitted" }
        require(answer.size <= 100 && answer.sumOf { it.length } <= 20_000) { "answer_too_large" }
        val updated = session.copy(attempts = session.attempts.map { if (it.questionId == questionId) it.copy(answer = answer) else it })
        updateSession(updated)
    }
    @Synchronized fun submit(sessionId: String): StudySession {
        val session = session(sessionId)
        if (session.submittedAt != null) return session
        val time = now()
        val updated = session.copy(submittedAt = time,
            submittedDay = Instant.ofEpochMilli(time).atZone(zone()).toLocalDate().toString(), submittedZone = zone().id,
            attempts = session.attempts.map { attempt ->
            val question = mutable.value.questions.single { it.id == attempt.questionId }
            val key = mutable.value.keys.single { it.questionId == attempt.questionId }
            val judgement = if (question.kind == "written") emptyList() else listOf(
                StudyJudgement(if (correct(question.kind, attempt.answer, key)) 100 else 0, key.explanation, time, "machine"))
            attempt.copy(submitted = true, judgements = judgement)
        })
        updateSession(updated); return updated
    }
    @Synchronized fun judge(sessionId: String, questionId: String, score: Int, comment: String): StudySession {
        val session = session(sessionId)
        require(session.submittedAt != null && score in 0..100 && comment.length <= 8000) { "invalid_judgement" }
        require(questionId in session.questionIds) { "question_not_in_session" }
        val old = session.attempts.single { it.questionId == questionId }
        // Replayed identical grading is a read, not a new practice or score event.
        if (old.judgements.lastOrNull()?.let { it.score == score && it.comment == comment && it.by == "ai" } == true) return session
        val updated = session.copy(attempts = session.attempts.map {
            if (it.questionId == questionId) it.copy(judgements = it.judgements + StudyJudgement(score, comment, now(), "ai")) else it
        })
        updateSession(updated); return updated
    }
    @Synchronized fun flag(sessionId: String, questionId: String) {
        val session = session(sessionId); require(questionId in session.questionIds)
        updateSession(session.copy(attempts = session.attempts.map { if (it.questionId == questionId) it.copy(flagged = true) else it }))
    }
    @Synchronized fun reviewWrong(subject: String): StudySession {
        val ids = wrongQuestions(subject).map { it.id }
        require(ids.isNotEmpty()) { "no_wrong_questions" }
        val session = StudySession(newId(), subject, ids, now(), ids.map { StudyAttempt(it) })
        commit(mutable.value.copy(sessions = mutable.value.sessions + session)); return session
    }
    @Synchronized fun wrongQuestions(subject: String) = studyWrongQuestions(mutable.value, subject)
    @Synchronized fun startFocus(subject: String): StudyFocusSession {
        require(subject.length <= 80) { "invalid_subject_or_plan" }
        mutable.value.focusSessions.lastOrNull { it.finishedAt == null }?.let { return it }
        val session = StudyFocusSession(newId(), subject, now().coerceAtLeast(0), zone().id)
        commit(mutable.value.copy(focusSessions = mutable.value.focusSessions + session))
        return session
    }
    /** Absolute checkpoints are idempotent. Only the human timer writes time; tools are read-only. */
    @Synchronized fun checkpointFocus(id: String, activeMillis: Long, finish: Boolean = false): StudyFocusSession {
        val old = mutable.value.focusSessions.find { it.id == id } ?: error("focus_session_not_found")
        require(activeMillis >= old.activeMillis && activeMillis <= StudyFocusSession.MAX_MILLIS) { "invalid_focus_duration" }
        if (old.finishedAt != null) { require(activeMillis == old.activeMillis) { "focus_already_finished" }; return old }
        if (activeMillis == old.activeMillis && !finish) return old
        val updated = old.copy(activeMillis = activeMillis, checkpointAt = now().coerceAtLeast(0),
            finishedAt = if (finish) now().coerceAtLeast(0) else null)
        commit(mutable.value.copy(focusSessions = mutable.value.focusSessions.map { if (it.id == id) updated else it }))
        return updated
    }
    @Synchronized fun statistics(subject: String = "") = StudyStatistics.from(mutable.value, now(), zone(), subject)
    private fun session(id: String) = mutable.value.sessions.find { it.id == id } ?: error("session_not_found")
    private fun updateSession(session: StudySession) = commit(mutable.value.copy(sessions = mutable.value.sessions.map { if (it.id == session.id) session else it }))
    private fun commit(next: StudyState) {
        check(!blocked) { "study_storage_unavailable" }; validate(next)
        val encoded = json.encodeToString(next); require(encoded.toByteArray().size <= 16 * 1024 * 1024) { "study_storage_full" }
        try { storage.write(encoded) } catch (e: Exception) { blocked = true; throw e }
        mutable.value = next
    }
    companion object {
        fun validateInput(q: StudyInputQuestion) {
            require(q.kind in setOf("single", "multiple", "fill", "written") && q.prompt.isNotBlank() && q.prompt.length <= 8000) { "invalid_question" }
            require(q.options.size <= 20 && q.options.all { it.isNotBlank() && it.length <= 2000 } && q.options.distinct().size == q.options.size) { "invalid_options" }
            require(q.answers.isNotEmpty() && q.answers.size <= 100 && q.answers.all { it.isNotBlank() && it.length <= 8000 }) { "answer_key_required" }
            if (q.kind in setOf("single", "multiple")) require(q.options.size >= 2 && q.answers.all { it in q.options } &&
                (q.kind != "single" || q.answers.size == 1)) { "answers_must_match_options" }
            require(q.explanation.length <= 8000 && q.source.length <= 2000 && q.topics.size <= 20 && q.topics.all { it.length in 1..100 }) { "question_metadata_too_large" }
        }
        fun validate(s: StudyState) {
            require(s.version == 1 && s.questions.map { it.id }.distinct().size == s.questions.size && s.sessions.map { it.id }.distinct().size == s.sessions.size)
            require(s.keys.map { it.questionId }.toSet() == s.questions.map { it.id }.toSet() && s.keys.size == s.questions.size)
            val keys = s.keys.associateBy { it.questionId }
            val questions = s.questions.associateBy { it.id }
            fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,100}"))
            s.questions.forEach { q ->
                require(validId(q.id) && q.subject.isNotBlank() && q.subject.length <= 80)
                val k = keys.getValue(q.id); validateInput(StudyInputQuestion(q.kind, q.prompt, q.options, k.answers, k.explanation, q.topics, q.source))
            }
            val ids = questions.keys
            s.sessions.forEach { session ->
                require(validId(session.id) && session.createdAt >= 0 && (session.submittedAt == null || session.submittedAt >= 0))
                require((session.submittedDay == null) == (session.submittedZone == null))
                session.submittedDay?.let { require(session.submittedAt != null); java.time.LocalDate.parse(it); ZoneId.of(session.submittedZone!!) }
                require(session.questionIds.isNotEmpty() && session.questionIds.distinct().size == session.questionIds.size && session.questionIds.all { it in ids })
                require(session.questionIds.all { questions.getValue(it).subject == session.subject })
                require(session.attempts.map { it.questionId } == session.questionIds)
                require(session.attempts.all { a ->
                    a.answer.size <= 100 && a.answer.sumOf { it.length.toLong() } <= 20_000 &&
                    a.judgements.all { it.score in 0..100 && it.comment.length <= 8000 && it.at >= 0 && it.by in setOf("ai", "machine") } &&
                    a.submitted == (session.submittedAt != null) && (a.submitted || a.judgements.isEmpty())
                })
            }
            require(s.progress.map { it.subject }.distinct().size == s.progress.size)
            require(s.progress.all { it.subject.isNotBlank() && it.subject.length <= 80 && it.revision >= 0 && it.plan.length <= 8000 && it.topics.all { topic -> topic.length in 1..100 } })
            val sessions = s.sessions.map { it.id }.toSet()
            require(s.commits.map { it.operationId }.distinct().size == s.commits.size)
            require(s.commits.all { validId(it.operationId) && it.sessionId in sessions && it.requestHash.matches(Regex("[a-f0-9]{64}")) })
            require(s.focusSessions.map { it.id }.distinct().size == s.focusSessions.size)
            require(s.focusSessions.count { it.finishedAt == null } <= 1)
            s.focusSessions.forEach { f ->
                require(validId(f.id) && f.subject.length <= 80 && f.startedAt >= 0 && f.checkpointAt >= 0 &&
                    (f.finishedAt == null || f.finishedAt >= 0) && f.activeMillis in 0..StudyFocusSession.MAX_MILLIS)
                ZoneId.of(f.zoneId)
            }
        }
        private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        fun correct(kind: String, answer: List<String>, key: StudyAnswerKey): Boolean {
            fun normalized(s: String): String {
                var value = if (key.trim) s.trim() else s
                if (key.normalizeWidth) value = Normalizer.normalize(value, Normalizer.Form.NFKC)
                if (key.ignoreCase) value = value.lowercase(java.util.Locale.ROOT)
                return value
            }
            return when (kind) {
                "single" -> answer.size == 1 && answer == key.answers
                "multiple" -> answer.toSet() == key.answers.toSet()
                "fill" -> answer.size == 1 && key.answers.any { normalized(it) == normalized(answer.single()) }
                else -> false
            }
        }
    }
}
