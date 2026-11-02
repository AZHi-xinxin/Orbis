package me.rerere.rikkahub.data.orbis.workshop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkshopPrivacyDeletionTest {
    private val owner = UUID.randomUUID().toString()
    private val chat = UUID.randomUUID().toString()
    private val a = WorkshopAsset("a".repeat(64) + ".png", "a", "image/png", false)
    private val b = a.copy(file = "b".repeat(64) + ".png", label = "b")
    private var disk: String? = null
    private fun open() = WorkshopLedger({ disk }, { disk = it })
    private fun job(ai: Boolean = false) = WorkshopJob(requestId = UUID.randomUUID().toString(), assistantId = owner,
        conversationId = chat, byAi = ai, modelId = UUID.randomUUID().toString(), maker = "synthetic", configHash = "hash", prompt = "private prompt")
    private fun complete(ledger: WorkshopLedger, ai: Boolean = false): WorkshopJob {
        val accepted = ledger.submit(job(ai))
        return ledger.update(accepted.id) { it.copy(status = WorkshopStatus.COMPLETE, assets = listOf(a, b)) }
    }

    @Test fun legacyHumanNotificationFlagGrantsNoAccessAndNoReplay() {
        val human = job().copy(notifyConversation = true, status = WorkshopStatus.COMPLETE, assets = listOf(a))
        disk = Json { encodeDefaults = true }.encodeToString(WorkshopState(jobs = listOf(human)))
        val ledger = open()
        assertFalse(ledger.get(human.id)!!.canNotifyConversation())
        assertFalse(human.needsRecovery())
        assertFalse(human.acceptsResultEvent(owner, chat, listOf(a.file)))
        assertTrue(ledger.visibleJobs(owner, chat).isEmpty())
        assertFalse(ledger.ownsAiReference(owner, chat, a.file))
        assertEquals(human, ledger.get(human.id)) // Reading does not rewrite originals/history.
    }

    @Test fun humanCannotOptIntoNotificationsOrBecomeAiByUpdate() {
        val ledger = open()
        assertEquals("workshop_human_private", runCatching { ledger.submit(job().copy(notifyConversation = true)) }.exceptionOrNull()?.message)
        val human = ledger.submit(job())
        assertTrue(runCatching { ledger.update(human.id) { it.copy(byAi = true) } }.isFailure)
    }

    @Test fun privateRequestIdCannotRevealHumanJobToAi() {
        val ledger = open(); val human = ledger.submit(job())
        val ai = ledger.submit(human.copy(id = UUID.randomUUID().toString(), byAi = true, notifyConversation = true))
        assertNotEquals(human.id, ai.id)
        assertEquals(listOf(ai), ledger.visibleJobs(owner, chat))
    }

    @Test fun aiResultsStayVisibleOnlyToTheirOrigin() {
        val ledger = open(); val ai = complete(ledger, true)
        assertTrue(ai.acceptsResultEvent(owner, chat, listOf(a.file)))
        assertFalse(ai.acceptsResultEvent(owner, UUID.randomUUID().toString(), listOf(a.file)))
        assertFalse(ai.acceptsResultEvent(UUID.randomUUID().toString(), chat, listOf(a.file)))
        assertTrue(ledger.ownsAiReference(owner, chat, a.file))
        assertTrue(ledger.visibleJobs(owner, UUID.randomUUID().toString()).isEmpty())
    }

    @Test fun removeOneImageRetainsPromptSiblingAndRevokesPendingDelivery() {
        val ledger = open(); val entry = complete(ledger)
        ledger.delete(entry.id, owner, a.file)
        val next = open().get(entry.id)!!
        assertEquals(listOf(b), next.assets); assertEquals(entry.prompt, next.prompt)
        val removed = mutableListOf<String>(); ledger.cleanDeletedFiles { removed += it; true }
        assertEquals(listOf(a.file), removed); assertTrue(open().state.value.pendingFileDeletes.isEmpty())
    }

    @Test fun wholeRecordDeletionErasesContentButBlocksStalePaidRequest() {
        val ledger = open(); val entry = complete(ledger)
        ledger.delete(entry.id, owner)
        val reloaded = open(); val tombstone = reloaded.get(entry.id)!!
        assertTrue(tombstone.deleted); assertEquals("", tombstone.prompt)
        assertTrue(tombstone.assets.isEmpty()); assertTrue(tombstone.references.isEmpty())
        assertNull(reloaded.claim(entry.id)); assertFalse(tombstone.needsRecovery())
        assertEquals("workshop_request_deleted", runCatching { reloaded.submit(entry.copy(status = WorkshopStatus.QUEUED, assets = emptyList())) }.exceptionOrNull()?.message)
        assertTrue(runCatching { reloaded.update(entry.id) { it.copy(deleted = false) } }.isFailure)
    }

    @Test fun deletionDoesNotBreakPublishedOriginalsOrRemainingReferences() {
        val ledger = open(); val shared = complete(ledger, true)
        ledger.delete(shared.id, owner)
        ledger.cleanDeletedFiles { fail("Chat originals cannot be unlinked"); true }
        assertTrue(open().state.value.sharedOriginals.containsAll(listOf(a.file, b.file)))
        assertTrue(ledger.visibleJobs(owner, chat).isEmpty())
        assertFalse(ledger.ownsAiReference(owner, chat, a.file))
    }

    @Test fun sharedPrivateReferenceIsRemovedOnlyAfterLastRecordGoes() {
        val ledger = open(); val original = complete(ledger)
        val referencing = ledger.submit(job().copy(references = listOf(a.file)))
        ledger.update(referencing.id) { it.copy(status = WorkshopStatus.CANCELLED) }
        ledger.delete(original.id, owner)
        val removed = mutableListOf<String>(); ledger.cleanDeletedFiles { removed += it; true }
        assertEquals(listOf(b.file), removed)
        ledger.delete(referencing.id, owner); ledger.cleanDeletedFiles { removed += it; true }
        assertEquals(setOf(a.file, b.file), removed.toSet())
    }

    @Test fun deletionRechecksRunningStatusOwnerAndPersistence() {
        val ledger = open(); val entry = ledger.submit(job())
        assertEquals("workshop_delete_busy", runCatching { ledger.delete(entry.id, owner) }.exceptionOrNull()?.message)
        ledger.claim(entry.id)
        assertEquals("workshop_delete_busy", runCatching { ledger.delete(entry.id, owner, a.file) }.exceptionOrNull()?.message)
        ledger.update(entry.id) { it.copy(status = WorkshopStatus.COMPLETE, assets = listOf(a)) }
        assertTrue(runCatching { ledger.delete(entry.id, UUID.randomUUID().toString()) }.isFailure)
        val failing = WorkshopLedger({ disk }, { error("disk failure") })
        assertTrue(runCatching { failing.delete(entry.id, owner) }.isFailure)
        assertFalse(open().get(entry.id)!!.deleted)
    }

    @Test fun unlinkFailureSurvivesRestartWithoutRepeatingGeneration() {
        val ledger = open(); val entry = complete(ledger)
        ledger.delete(entry.id, owner); ledger.cleanDeletedFiles { false }
        assertEquals(2, open().state.value.pendingFileDeletes.size)
        val reloaded = open(); reloaded.cleanDeletedFiles { true }
        assertTrue(open().state.value.pendingFileDeletes.isEmpty()); assertNull(reloaded.claim(entry.id))
    }
}
