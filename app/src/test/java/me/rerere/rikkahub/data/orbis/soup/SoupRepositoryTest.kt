package me.rerere.rikkahub.data.orbis.soup

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal class SoupMemoryStorage : SoupStorage {
    var value: String? = null
    var writes = 0
    var failAfterWrite = false
    override fun read() = value
    override fun write(value: String) { this.value = value; writes++; if (failAfterWrite) error("synthetic_save_uncertain") }
}

class SoupRepositoryTest {
    private val model = UUID(1, 1).toString()
    private fun repo(storage: SoupMemoryStorage = SoupMemoryStorage()): SoupRepository = SoupRepository(storage, { 1000L })
    private fun rejected(block: () -> Unit) { try { block(); fail("must reject") } catch (_: Exception) { } }
    private fun ask(repository: SoupRepository, player: SoupPlayer, text: String = "这是一个问题吗？"): SoupAttempt {
        val state = repository.snapshot()
        return repository.reserve(requireNotNull(state.activeId), state.revision, SoupAction.ASK, player, text, model, "synthetic-model", "https://api.deepseek.com/v1", false)
    }

    @Test fun startsEmptyAndStoresOnlyRealActionsWithoutPrivatePuzzleFields() {
        val storage = SoupMemoryStorage(); val repository = repo(storage)
        assertNull(repository.snapshot().active)
        val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        assertTrue(session.questions.isEmpty()); assertTrue(session.attempts.isEmpty()); assertEquals(6, session.remaining(SoupPlayer.HUMAN))
        val raw = requireNotNull(storage.value)
        assertFalse(raw.contains("secret_solution")); assertFalse(raw.contains("key_elements")); assertFalse(raw.contains(SoupCatalogue.get(session.puzzleId).solution))
        assertEquals(session, repo(storage).snapshot().active)
        rejected { repository.start("soup_sample_002", SoupMode.EASY) }
        assertEquals(session.id, repository.snapshot().activeId)
    }

    @Test fun successfulQuestionsAlternateAndBothNormalBudgetsAreEnforced() {
        val repository = repo(); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        repeat(12) { index ->
            val player = if (index % 2 == 0) SoupPlayer.HUMAN else SoupPlayer.TEAMMATE
            val ticket = ask(repository, player, "问题${index}成立吗？")
            assertEquals(SoupAttemptState.RUNNING, repository.snapshot().active!!.pending!!.state)
            assertEquals(index, repository.snapshot().active!!.questions.size)
            repository.complete(session.id, ticket.id, """{"answer":"否"}""")
        }
        val final = repository.snapshot().active!!
        assertEquals(0, final.remaining(SoupPlayer.HUMAN)); assertEquals(0, final.remaining(SoupPlayer.TEAMMATE))
        assertEquals(12, final.questions.size); assertNull(final.pending)
        rejected { ask(repository, SoupPlayer.HUMAN, "再问一次吗？") }
    }

    @Test fun invalidOpenQuestionsDuplicatesAndWrongTurnNeverReservePaidAttempt() {
        val repository = repo(); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        rejected { ask(repository, SoupPlayer.HUMAN, "为什么会这样？") }
        rejected { ask(repository, SoupPlayer.TEAMMATE) }
        assertTrue(repository.snapshot().active!!.attempts.isEmpty())
        val first = ask(repository, SoupPlayer.HUMAN)
        repository.complete(session.id, first.id, """{"answer":"是"}""")
        rejected { ask(repository, SoupPlayer.TEAMMATE) }
        assertEquals(1, repository.snapshot().active!!.attempts.size)
    }

    @Test fun hardModeHasNoHintsAndEasyModeDoesNotDeductQuestionBudget() {
        val strict = repo(); val strictGame = strict.start("soup_sample_001", SoupMode.HARD)
        assertEquals(3, strictGame.remaining(SoupPlayer.HUMAN)); rejected { strict.hint(strictGame.id) }
        val easy = repo(); val easyGame = easy.start("soup_sample_001", SoupMode.EASY)
        assertNull(easyGame.remaining(SoupPlayer.HUMAN))
        repeat(3) { easy.hint(easyGame.id) }
        assertEquals(3, easy.snapshot().active!!.hintsUsed); rejected { easy.hint(easyGame.id) }
    }

    @Test fun interruptionPersistsUnknownAndOnlyExplicitMatchingRetryCanReserveAgain() {
        val storage = SoupMemoryStorage(); val first = repo(storage)
        val session = first.start("soup_sample_001", SoupMode.NORMAL); val ticket = ask(first, SoupPlayer.HUMAN)
        val restored = repo(storage); restored.recoverInterrupted()
        assertEquals(SoupAttemptState.UNKNOWN, restored.snapshot().active!!.pending!!.state)
        assertEquals(6, restored.snapshot().active!!.remaining(SoupPlayer.HUMAN))
        rejected { ask(restored, SoupPlayer.HUMAN, "换个问题吗？") }
        val before = restored.snapshot()
        val retry = restored.reserve(session.id, before.revision, ticket.action, ticket.player, ticket.text, model, "synthetic-model", "https://api.deepseek.com/v1", true)
        assertNotEquals(ticket.id, retry.id)
        assertEquals(SoupAttemptState.ACKNOWLEDGED, restored.snapshot().active!!.attempts.first().state)
        rejected { restored.complete(session.id, ticket.id, """{"answer":"是"}""") }
        restored.complete(session.id, retry.id, """{"answer":"无关"}""")
        assertEquals(1, restored.snapshot().active!!.questions.size)
        assertEquals(2, restored.snapshot().active!!.attempts.size)
    }

    @Test fun unknownRevealKeepsAttemptAndOnlyThenPublishesSolutionAndGradingComment() {
        val repository = repo(); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        val initial = repository.snapshot()
        val submit = repository.reserve(session.id, initial.revision, SoupAction.SUBMIT, SoupPlayer.HUMAN, "合成推理", model, "synthetic-model", "https://api.deepseek.com/v1", false)
        repository.complete(session.id, submit.id, """{"key_plot":100,"logic":90,"detail":90,"overall":1,"rank":"任意标签","comment":"private-grading-sentinel"}""")
        val score = repository.snapshot().active!!.submissions.single()
        assertEquals(94, score.overall); assertEquals("完全还原", score.rank)
        val publicBefore = soupPublicSession(repository.snapshot().active!!).toString()
        assertFalse(publicBefore.contains("private-grading-sentinel")); assertFalse(publicBefore.contains("soup_bottom")); assertFalse(publicBefore.contains("key_elements"))
        rejected { repository.checkAction(session.id, SoupAction.SUBMIT, SoupPlayer.HUMAN, "第二份推理", false) }
        repository.reveal(session.id)
        val publicAfter = soupPublicSession(repository.snapshot().active!!)
        assertEquals(SoupCatalogue.get(session.puzzleId).solution, publicAfter["soup_bottom"]!!.jsonPrimitive.content)
        assertTrue(publicAfter.toString().contains("private-grading-sentinel"))
    }

    @Test fun invalidHostReplyCannotConsumeQuestionsOrFabricateAnAnswer() {
        val repository = repo(); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        val ticket = ask(repository, SoupPlayer.HUMAN)
        rejected { repository.complete(session.id, ticket.id, """{"answer":"是","solution":"do not expose"}""") }
        repository.failed(session.id, ticket.id)
        assertTrue(repository.snapshot().active!!.questions.isEmpty())
        assertEquals(6, repository.snapshot().active!!.remaining(SoupPlayer.HUMAN))
        assertEquals(SoupAttemptState.UNKNOWN, repository.snapshot().active!!.pending!!.state)
    }

    @Test fun uncertainStorageWriteBlocksFurtherMutationsUntilExplicitReload() {
        val storage = SoupMemoryStorage(); val repository = repo(storage)
        repository.start("soup_sample_001", SoupMode.NORMAL)
        storage.failAfterWrite = true
        rejected { ask(repository, SoupPlayer.HUMAN) }
        assertTrue(repository.blocked.value)
        val written = storage.value
        rejected { repository.hint(repository.state.value.activeId!!) }
        assertEquals(written, storage.value)
        storage.failAfterWrite = false; repository.reload()
        assertFalse(repository.blocked.value)
        assertEquals(SoupAttemptState.UNKNOWN, repository.snapshot().active!!.pending!!.state)
    }

    @Test fun unknownSchemaOrMissingExpectedFileIsNeverRecreated() {
        val storage = SoupMemoryStorage(); storage.value = """{"version":99}"""
        rejected { repo(storage) }; assertEquals(0, storage.writes)
        storage.value = null
        val repository = repo(storage); repository.start("soup_sample_001", SoupMode.NORMAL)
        storage.value = null; rejected { repository.reload() }; assertTrue(repository.blocked.value)
        assertNull(storage.value)
    }

    @Test fun actionApprovalCannotApplyAfterLocalStateChanges() {
        val repository = repo(); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        val old = repository.snapshot(); repository.hint(session.id)
        rejected { repository.reserve(session.id, old.revision, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？", model, "synthetic-model", "https://api.deepseek.com/v1", false) }
        assertTrue(repository.snapshot().active!!.attempts.isEmpty())
    }

    @Test fun endAndNewGameKeepPreviousRecordsWithoutAutomaticReveal() {
        val repository = repo(); val first = repository.start("soup_sample_001", SoupMode.NORMAL)
        repository.abandon(first.id)
        val next = repository.start("soup_sample_005", SoupMode.HARD)
        assertNotEquals(first.id, next.id); assertEquals(2, repository.snapshot().sessions.size)
        val old = repository.snapshot().sessions.first(); assertTrue(old.abandoned); assertFalse(old.revealed)
        assertFalse(soupPublicSession(old).containsKey("soup_bottom"))
    }

    @Test fun teammateProposalIsDurableAndNeverReservesCallUntilNativeApproval() {
        val storage = SoupMemoryStorage(); val repository = repo(storage)
        val game = repository.start("soup_sample_001", SoupMode.NORMAL)
        val first = ask(repository, SoupPlayer.HUMAN)
        repository.complete(game.id, first.id, """{"answer":"是"}""")
        val proposal = repository.propose(game.id, SoupAction.ASK, "伙伴的新问题成立吗？")
        val queued = repository.snapshot().active!!
        assertEquals(1, queued.attempts.size); assertEquals(1, queued.questions.size)
        assertEquals(6, queued.remaining(SoupPlayer.TEAMMATE))
        assertEquals(proposal, repo(storage).snapshot().active!!.proposal)
        assertEquals(proposal, repository.propose(game.id, SoupAction.ASK, proposal.text))
        assertEquals(1, repository.snapshot().active!!.proposals.size)
        val public = soupPublicSession(queued)
        assertEquals("awaiting_human_confirmation", public["pending_proposal"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertFalse(public.containsKey("soup_bottom")); assertFalse(public.containsKey("key_elements"))
        val ticket = repository.reserve(game.id, repository.snapshot().revision, proposal.action, SoupPlayer.TEAMMATE,
            proposal.text, model, "synthetic-model", "https://api.deepseek.com/v1", false)
        assertNull(repository.snapshot().active!!.proposal)
        assertEquals(SoupProposalState.CONFIRMED, repository.snapshot().active!!.proposals.single().state)
        repository.complete(game.id, ticket.id, """{"answer":"否"}""")
        assertEquals(5, repository.snapshot().active!!.remaining(SoupPlayer.TEAMMATE))
    }

    @Test fun decliningProposalPreservesHistoryAndCannotBeReplayedWithOldRevision() {
        val repository = repo(); val game = repository.start("soup_sample_001", SoupMode.NORMAL)
        val proposal = repository.propose(game.id, SoupAction.SUBMIT, "伙伴的共同推理草稿")
        val proposedRevision = repository.snapshot().revision
        repository.declineProposal(game.id, proposal.id)
        assertNull(repository.snapshot().active!!.proposal)
        assertEquals(SoupProposalState.DECLINED, repository.snapshot().active!!.proposals.single().state)
        assertTrue(repository.snapshot().active!!.attempts.isEmpty())
        rejected { repository.reserve(game.id, proposedRevision, proposal.action, SoupPlayer.TEAMMATE, proposal.text,
            model, "synthetic-model", "https://api.deepseek.com/v1", false) }
        assertTrue(repository.snapshot().active!!.attempts.isEmpty())
    }
}
