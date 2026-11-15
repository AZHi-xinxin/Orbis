package me.rerere.rikkahub.data.orbis.workshop

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkshopReferenceLibraryTest {
    private fun id() = UUID.randomUUID().toString()
    private fun asset(char: Char) = WorkshopAsset("${char.toString().repeat(64)}.png", "synthetic", "image/png", false)
    private var disk: String? = null
    private fun ledger() = WorkshopLedger({ disk }, { disk = it })
    private fun failure(code: String, block: () -> Unit) {
        assertEquals(code, runCatching(block).exceptionOrNull()?.message)
    }
    @Test fun `library survives reopen is assistant scoped and defaults private`() {
        val owner = id(); val l = ledger()
        val human = l.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset('a')))
        val partner = l.saveReference(WorkshopReference(assistantId = owner, name = "阿止", asset = asset('b'), allowAi = true))
        val reopened = ledger()
        assertEquals(listOf(human, partner), reopened.visibleReferences(owner, false))
        assertEquals(listOf(partner), reopened.visibleReferences(owner, true))
        assertTrue(reopened.visibleReferences(id(), true).isEmpty())
        failure("workshop_reference_missing") { reopened.resolveReferences(id(), true, listOf(partner.id)) }
        failure("workshop_reference_missing") { reopened.resolveReferences(owner, true, listOf(human.id)) }
        reopened.preferences(allowAi = false)
        assertTrue(reopened.visibleReferences(owner, true).isEmpty())
        assertEquals(2, reopened.visibleReferences(owner, false).size)
    }
    @Test fun `edits use expected revision and revocation invalidates queued snapshots`() {
        val l = ledger(); val owner = id()
        val old = l.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset('a'), allowAi = true))
        val fresh = l.saveReference(old.copy(asset = asset('b')), old.revision)
        failure("workshop_reference_changed") { l.validateReferences(owner, true, listOf(old)) }
        failure("workshop_reference_changed") { l.saveReference(old, old.revision) }
        l.validateReferences(owner, true, listOf(fresh))
        l.saveReference(fresh.copy(allowAi = false), fresh.revision)
        failure("workshop_reference_missing") { l.validateReferences(owner, true, listOf(fresh)) }
        failure("workshop_reference_changed") { l.saveReference(fresh.copy(assistantId = id()), fresh.revision + 1) }
    }
    @Test fun `duplicate names reject while different assistants can use the same name`() {
        val l = ledger(); val owner = id()
        l.saveReference(WorkshopReference(assistantId = owner, name = " 我 ", asset = asset('a')))
        failure("workshop_reference_name_duplicate") { l.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset('b'))) }
        l.saveReference(WorkshopReference(assistantId = id(), name = "我", asset = asset('b')))
        failure("workshop_reference_name") { l.saveReference(WorkshopReference(assistantId = owner, name = "A\nB", asset = asset('b'))) }
    }
    @Test fun `deleting a source job preserves library photo until reference is removed`() {
        val l = ledger(); val owner = id(); val photo = asset('a')
        val job = l.submit(WorkshopJob(requestId = id(), assistantId = owner, byAi = false, modelId = id(), maker = "test", configHash = "test", prompt = "synthetic"))
        l.update(job.id) { it.copy(status = WorkshopStatus.COMPLETE, assets = listOf(photo)) }
        val ref = l.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = photo))
        l.delete(job.id, owner)
        val removed = mutableListOf<String>(); l.cleanDeletedFiles { removed += it; true }
        assertTrue(removed.isEmpty())
        l.deleteReference(ref.id, owner); l.cleanDeletedFiles { removed += it; true }
        assertEquals(listOf(photo.file), removed)
        assertTrue(ledger().visibleReferences(owner, false).isEmpty())
    }
    @Test fun `AI job cannot borrow a private reference and repeated request does not add a job`() {
        val l = ledger(); val owner = id()
        val ref = l.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset('a'), allowAi = true))
        val candidate = WorkshopJob(requestId = id(), assistantId = owner, conversationId = id(), byAi = true,
            modelId = id(), maker = "test", configHash = "test", prompt = "synthetic", references = listOf(ref.asset.file), namedReferences = listOf(ref))
        val accepted = l.submit(candidate)
        assertEquals(accepted, l.submit(candidate.copy(id = id())))
        assertEquals(1, l.state.value.jobs.size)
        failure("workshop_reference") { l.submit(candidate.copy(requestId = id(), references = emptyList())) }
        l.saveReference(ref.copy(allowAi = false), ref.revision)
        failure("workshop_reference_missing") { l.submit(candidate.copy(id = id(), requestId = id())) }
        assertEquals(1, l.state.value.jobs.size)
    }
    @Test fun `reference mapping follows real file order not display order and never invents files`() {
        val owner = id(); val me = WorkshopReference(assistantId = owner, name = "我", asset = asset('a'))
        val partner = WorkshopReference(assistantId = owner, name = "阿止", asset = asset('b'))
        val prompt = workshopReferencePrompt("一起旅行", listOf(partner.asset.file, me.asset.file), listOf(me, partner))
        assertTrue(prompt.contains("\"image_number\":2,\"person_name\":\"我\""))
        assertTrue(prompt.contains("\"image_number\":1,\"person_name\":\"阿止\""))
        failure("workshop_reference") { workshopReferencePrompt("draw", emptyList(), listOf(me)) }
        assertEquals("unchanged", workshopReferencePrompt("unchanged", emptyList(), emptyList()))
    }
    @Test fun `legacy ledger opens without reference library or snapshot fields`() {
        disk = """{"version":1,"jobs":[],"allowAi":true}"""
        assertTrue(ledger().state.value.referenceLibrary.isEmpty())
    }
}
