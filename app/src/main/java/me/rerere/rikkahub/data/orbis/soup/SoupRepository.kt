package me.rerere.rikkahub.data.orbis.soup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.util.UUID

internal interface SoupStorage { fun read(): String?; fun write(value: String) }

/** One process-local lock, atomic independent storage, and no automatic paid retry or fabricated result. */
internal class SoupRepository(
    private val storage: SoupStorage, private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private var expectedStorage = false
    private val mutableState = MutableStateFlow(load())
    val state = mutableState.asStateFlow()
    private val mutableBlocked = MutableStateFlow(false)
    val blocked = mutableBlocked.asStateFlow()

    private fun load(): SoupState {
        val raw = storage.read()
        if (raw == null) { check(!expectedStorage) { "soup_storage_missing" }; return SoupState() }
        require(raw.toByteArray().size <= 4 * 1024 * 1024) { "soup_storage_large" }
        return soupJson.decodeFromString<SoupState>(raw).also { validate(it); expectedStorage = true }
    }

    private fun validate(value: SoupState) {
        require(value.version == 1 && value.revision >= 0 && value.sessions.size <= 50) { "soup_storage_invalid" }
        require(value.sessions.map { it.id }.distinct().size == value.sessions.size)
        require(value.activeId == null || value.sessions.any { it.id == value.activeId })
        value.hostModelId?.let { UUID.fromString(it) }
        value.sessions.forEach { s ->
            UUID.fromString(s.id)
            val puzzle = SoupCatalogue.get(s.puzzleId)
            require(s.startedAt >= 0 && s.hintsUsed in 0..minOf(s.mode.hints, puzzle.hints.size))
            require(s.questions.size <= SOUP_CALL_LIMIT && s.attempts.size <= SOUP_CALL_LIMIT)
            require(s.attempts.map { it.id }.distinct().size == s.attempts.size)
            require(s.attempts.count { it.state in setOf(SoupAttemptState.RUNNING, SoupAttemptState.UNKNOWN) } <= 1)
            require(s.submissions.map { it.player }.distinct().size == s.submissions.size)
            require(s.proposals.size <= 60 && s.proposals.map { it.id }.distinct().size == s.proposals.size)
            require(s.proposals.count { it.state == SoupProposalState.PENDING } <= 1)
            require(s.proposal == null || s.pending == null)
            s.proposals.forEach { proposal ->
                UUID.fromString(proposal.id); soupText(proposal.text, if (proposal.action == SoupAction.ASK) 1000 else 6000)
                require(proposal.at >= s.startedAt)
            }
            s.questions.forEach { q -> soupText(q.text, 1000); require(q.answer in SOUP_ANSWERS && q.at >= s.startedAt) }
            s.submissions.forEach { score ->
                soupText(score.submitted, 6000); soupText(score.comment, 1200)
                require(listOf(score.keyPlot, score.logic, score.detail).all { it in 0..100 } && score.at >= s.startedAt)
            }
            s.attempts.forEach { a ->
                UUID.fromString(a.id); UUID.fromString(a.modelId); soupText(a.modelLabel, 240); soupText(a.endpoint, 240)
                soupText(a.text, if (a.action == SoupAction.ASK) 1000 else 6000); require(a.at >= s.startedAt)
            }
            val completed = s.attempts.filter { it.state == SoupAttemptState.COMPLETE }
            require(completed.size == s.questions.size + s.submissions.size)
            require(s.questions.all { q -> completed.count { it.action == SoupAction.ASK && it.player == q.player && it.text == q.text && it.at <= q.at } == 1 })
            require(s.submissions.all { score -> completed.count { it.action == SoupAction.SUBMIT && it.player == score.player && it.text == score.submitted && it.at <= score.at } == 1 })
            SoupPlayer.entries.forEach { p -> require(s.mode.questions == null || s.questions.count { it.player == p } <= s.mode.questions) }
        }
    }

    @Synchronized fun snapshot(): SoupState { check(!blocked.value) { "soup_reload_required" }; return state.value }
    private fun commit(value: SoupState) {
        check(!blocked.value) { "soup_reload_required" }
        val next = value.copy(revision = state.value.revision + 1)
        validate(next)
        val raw = soupJson.encodeToString(next)
        require(raw.toByteArray().size <= 4 * 1024 * 1024) { "soup_storage_large" }
        try { storage.write(raw) } catch (error: Throwable) { mutableBlocked.value = true; throw error }
        expectedStorage = true; mutableState.value = next
    }
    private fun replace(session: SoupSession) = commit(state.value.copy(sessions = state.value.sessions.map { if (it.id == session.id) session else it }))
    private fun current(id: String): SoupSession = snapshot().active?.also { require(it.id == id) { "soup_stale_session" } } ?: error("soup_no_game")

    @Synchronized fun reload() { mutableBlocked.value = true; val fresh = load(); mutableState.value = fresh; mutableBlocked.value = false; recoverInterrupted() }
    @Synchronized fun recoverInterrupted() {
        val before = snapshot()
        if (before.sessions.any { s -> s.attempts.any { it.state == SoupAttemptState.RUNNING } }) commit(before.copy(
            sessions = before.sessions.map { s -> s.copy(attempts = s.attempts.map { if (it.state == SoupAttemptState.RUNNING) it.copy(state = SoupAttemptState.UNKNOWN) else it }) },
        ))
    }
    @Synchronized fun selectHost(modelId: String) {
        UUID.fromString(modelId); check(snapshot().active?.pending?.state != SoupAttemptState.RUNNING) { "soup_call_running" }
        commit(state.value.copy(hostModelId = modelId))
    }
    @Synchronized fun start(puzzleId: String, mode: SoupMode): SoupSession {
        val before = snapshot(); require(before.active?.playable != true) { "soup_game_active" }
        SoupCatalogue.get(puzzleId); require(before.sessions.size < 50) { "soup_archive_full" }
        val session = SoupSession(newId(), puzzleId, mode, now())
        commit(before.copy(activeId = session.id, sessions = before.sessions + session)); return session
    }
    @Synchronized fun takeHumanTurn(id: String) {
        val s = current(id); require(s.playable && s.pending == null && s.proposal == null && s.remaining(SoupPlayer.HUMAN) != 0)
        replace(s.copy(currentPlayer = SoupPlayer.HUMAN))
    }
    @Synchronized fun hint(id: String) {
        val s = current(id); require(s.playable && s.pending == null && s.proposal == null) { "soup_action_unavailable" }
        require(s.hintsUsed < minOf(s.mode.hints, SoupCatalogue.get(s.puzzleId).hints.size)) { "soup_no_hints" }
        replace(s.copy(hintsUsed = s.hintsUsed + 1))
    }
    @Synchronized fun reveal(id: String) {
        val s = current(id); require(!s.abandoned && s.pending?.state != SoupAttemptState.RUNNING) { "soup_action_unavailable" }
        replace(s.copy(revealed = true, attempts = acknowledgeUnknown(s), proposals = declinePending(s)))
    }
    @Synchronized fun abandon(id: String) {
        val s = current(id); require(s.pending?.state != SoupAttemptState.RUNNING) { "soup_call_running" }
        replace(s.copy(abandoned = true, attempts = acknowledgeUnknown(s), proposals = declinePending(s)))
    }
    private fun acknowledgeUnknown(s: SoupSession) = s.attempts.map { if (it.state == SoupAttemptState.UNKNOWN) it.copy(state = SoupAttemptState.ACKNOWLEDGED) else it }
    private fun declinePending(s: SoupSession) = s.proposals.map { if (it.state == SoupProposalState.PENDING) it.copy(state = SoupProposalState.DECLINED) else it }

    /** A teammate tool can only queue text. It cannot mint a paid-call approval, even with remember-all. */
    @Synchronized fun propose(id: String, action: SoupAction, text: String): SoupProposal {
        checkAction(id, action, SoupPlayer.TEAMMATE, text, false)
        val s = current(id)
        s.proposal?.let { return it }
        require(s.proposals.size < 60) { "soup_proposal_limit" }
        val proposal = SoupProposal(newId(), action, text, maxOf(now(), s.startedAt))
        replace(s.copy(proposals = s.proposals + proposal)); return proposal
    }
    @Synchronized fun declineProposal(id: String, proposalId: String) {
        val s = current(id); require(s.proposal?.id == proposalId) { "soup_stale_proposal" }
        replace(s.copy(proposals = declinePending(s)))
    }

    @Synchronized fun checkAction(id: String, action: SoupAction, player: SoupPlayer, text: String, retry: Boolean) {
        val s = current(id); require(s.playable) { "soup_game_finished" }
        s.proposal?.let { require(!retry && player == SoupPlayer.TEAMMATE && action == it.action && text == it.text) { "soup_proposal_pending" } }
        require(s.attempts.size < SOUP_CALL_LIMIT) { "soup_call_limit" }
        soupText(text, if (action == SoupAction.ASK) 1000 else 6000)
        if (retry) {
            val prior = requireNotNull(s.pending) { "soup_no_retry" }
            require(prior.state == SoupAttemptState.UNKNOWN && prior.action == action && prior.player == player && prior.text == text) { "soup_invalid_retry" }
        } else require(s.pending == null) { "soup_unconfirmed_call" }
        require(s.submissions.none { it.player == player }) { "soup_already_submitted" }
        if (action == SoupAction.ASK) {
            require(s.currentPlayer == player) { "soup_other_turn" }
            require(s.remaining(player) != 0) { "soup_no_questions" }
            require(soupYesNoQuestion(text)) { "soup_yes_no_required" }
            require(s.questions.none { it.text.trim() == text.trim() }) { "soup_duplicate_question" }
        }
    }
    @Synchronized fun reserve(
        id: String, expectedRevision: Long, action: SoupAction, player: SoupPlayer, text: String,
        modelId: String, modelLabel: String, endpoint: String, retry: Boolean,
    ): SoupAttempt {
        require(snapshot().revision == expectedRevision) { "soup_changed_since_confirmation" }
        checkAction(id, action, player, text, retry)
        val s = current(id)
        val ticket = SoupAttempt(newId(), action, player, text, modelId, modelLabel, endpoint, maxOf(now(), s.startedAt))
        replace(s.copy(attempts = acknowledgeUnknown(s) + ticket,
            proposals = s.proposals.map { if (it.state == SoupProposalState.PENDING) it.copy(state = SoupProposalState.CONFIRMED) else it })); return ticket
    }
    @Synchronized fun complete(id: String, attemptId: String, response: String) {
        val s = current(id); val ticket = requireNotNull(s.pending)
        require(ticket.id == attemptId && ticket.state == SoupAttemptState.RUNNING) { "soup_stale_result" }
        val at = maxOf(now(), ticket.at)
        val result = if (ticket.action == SoupAction.ASK) s.copy(
            questions = s.questions + SoupQuestion(ticket.player, ticket.text, soupParseAnswer(response), at),
            currentPlayer = if (ticket.player == SoupPlayer.HUMAN) SoupPlayer.TEAMMATE else SoupPlayer.HUMAN,
        ) else s.copy(submissions = s.submissions + soupParseScore(response, ticket.player, ticket.text, at))
        replace(result.copy(attempts = result.attempts.map { if (it.id == attemptId) it.copy(state = SoupAttemptState.COMPLETE) else it }))
    }
    @Synchronized fun failed(id: String, attemptId: String) {
        val s = current(id)
        if (s.pending?.id == attemptId && s.pending?.state == SoupAttemptState.RUNNING)
            replace(s.copy(attempts = s.attempts.map { if (it.id == attemptId) it.copy(state = SoupAttemptState.UNKNOWN) else it }))
    }
}
