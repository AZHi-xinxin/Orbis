package me.rerere.rikkahub.data.orbis.activities

import org.junit.Assert.*
import org.junit.Test

class PetAiRulesTest {
    @Test fun dailyBudgetPersistsAttemptsAndNeverBlocksLocalCare() {
        val r = repo(); r.configureAiBudget(1)
        val first = r.reserveAi("talk")!!
        r.finishAi(first, null)
        assertNull(r.reserveAi("talk"))
        r.act("water", "local-care-still-works")
        assertEquals(1, r.snapshot().aiUsageCount)
        clock += 24 * 3_600_000L
        val next = r.reserveAi("talk")!!
        assertEquals(1, r.snapshot().aiUsageCount)
        r.finishAi(next, PetAiReply(next.id, listOf("ask_pet")))
        assertEquals(listOf("ask_pet"), r.snapshot().events.last().aiActions)
        r.configureAiBudget(0)
        assertNotNull(r.reserveAi("talk"))
    }
    @Test fun noticesRollOnceAreBoundedAndNeverRepeatOnReplay() {
        var time = 1000L; var rolls = 0
        val r = PetRepository(object: ActivityStorage {
            var value: String? = null
            override fun read() = value
            override fun write(text: String) { value = text }
        }, { time }, noticeCoin = { rolls++; true })
        r.adopt("墨团"); r.configureAi("00000000-0000-0000-0000-000000000001", true)
        val conversation = "00000000-0000-0000-0000-000000000002"
        r.configureNotices(conversation, true)
        val ticket = r.reserveAi("idle")!!; val reply = PetAiReply(ticket.id, listOf("noisy", "mess_up"))
        assertTrue(r.finishAi(ticket, reply)); assertFalse(r.finishAi(ticket, reply)); assertEquals(1, rolls)
        assertEquals(conversation, r.claimNotice()!!.conversationId); assertNull(r.claimNotice())
        time += 3_600_001
        val next = r.reserveAi("idle")!!; r.finishAi(next, PetAiReply(next.id, listOf("noisy")))
        r.configureNotices(conversation, false)
        assertNull(r.claimNotice()); assertEquals(2, rolls)
    }
    private var clock = 1_000L
    private fun repo(): PetRepository = PetRepository(object: ActivityStorage {
        var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
    }, {clock}).also { it.adopt("墨团"); it.configureAi("00000000-0000-0000-0000-000000000001", true) }
    @Test fun controlsAreNotFoundByScanningSpeech() {
        val r = repo(); val ticket = r.reserveAi("talk", "hi")!!
        r.finishAi(ticket, PetAiReply(ticket.id, listOf("speak"), "mess_up sleep hungry"))
        assertFalse(r.snapshot().messy); assertEquals(0L, r.snapshot().sleepingUntil)
    }
    @Test fun duplicateAndStaleRepliesCannotAffectNewCare() {
        val r = repo(); val ticket = r.reserveAi("idle")!!
        val reply = PetAiReply(ticket.id, listOf("mess_up"))
        assertTrue(r.finishAi(ticket, reply)); assertFalse(r.finishAi(ticket, reply))
        val next = r.reserveAi("talk")!!; r.act("tidy", "tidy-once")
        assertFalse(r.finishAi(next, PetAiReply(next.id, listOf("noisy"))))
        assertFalse(r.snapshot().messy)
    }
    @Test fun boardingAndTimeContinueWithoutPage() {
        val r = repo(); r.act("board", "board")
        clock += 7 * 24 * 3_600_000L
        val state = r.snapshot()
        assertEquals(100.0, state.food, .001); assertEquals(100.0, state.water, .001)
        assertEquals(16.0, state.mood, .001)
    }
    @Test fun aiNeverSeesUnredeemedWordsThroughCompanionStatus() {
        val r = repo(); val ticket = r.reserveAi("talk")!!
        r.finishAi(ticket, PetAiReply(ticket.id, listOf("speak"), "我想你了"))
        assertEquals("喵喵喵喵", PetAiRules.visible(r.snapshot()).sayings.single().animal)
        assertEquals("", PetAiRules.visible(r.snapshot()).sayings.single().original)
        assertTrue(runCatching { r.translate(ticket.id) }.isFailure)
    }
    @Test fun malformedAndImpossibleActionsRejected() {
        val ticket = PetAiTicket("x", "m", "water", "", 0)
        listOf(PetAiReply("wrong", listOf("drink")), PetAiReply("x", listOf("mess_up")),
            PetAiReply("x", listOf("drink", "drink"))).forEach { assertTrue(runCatching { PetAiRules.validate(it, ticket) }.isFailure) }
    }
    @Test fun disablingAiInvalidatesInFlightAndDoesNotResetPet() {
        val r = repo(); val original = r.snapshot().pet; val ticket = r.reserveAi("talk")!!
        r.configureAi(null, false)
        assertFalse(r.finishAi(ticket, PetAiReply(ticket.id, listOf("speak"))))
        assertEquals(original, r.snapshot().pet)
    }
}
