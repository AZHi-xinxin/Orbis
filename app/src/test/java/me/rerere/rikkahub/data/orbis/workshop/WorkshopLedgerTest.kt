package me.rerere.rikkahub.data.orbis.workshop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkshopLedgerTest {
    @Test fun `new acceptance callback precedes enqueue but old request never gains another callback`() {
        val ledger = Disk().open(); val sequence = mutableListOf<String>(); val candidate = job()
        val accepted = ledger.submitAndSchedule(candidate, { entry ->
            assertEquals(entry, ledger.get(entry.id)); sequence += "accepted"
        }, { sequence += "enqueue" })
        assertEquals(listOf("accepted", "enqueue"), sequence)
        sequence.clear()
        val old = ledger.submitAndSchedule(job(), { fail("idempotent old job is not new authority") }, { sequence += "enqueue" })
        assertEquals(accepted.id, old.id); assertEquals(listOf("enqueue"), sequence)
    }

    @Test fun `failed acceptance never calls observer or enqueues and failed observer keeps durable acceptance`() {
        val disk = Disk(); val ledger = disk.open(); disk.fail = true
        assertTrue(runCatching { ledger.submitAndSchedule(job(), { fail("not saved") }, { fail("not saved") }) }.isFailure)
        disk.fail = false; var enqueued: String? = null
        val accepted = ledger.submitAndSchedule(job(), { error("observer unavailable") }, { enqueued = it })
        assertEquals(accepted.id, enqueued); assertEquals(accepted, disk.open().get(accepted.id))
    }

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val conversation = "22222222-2222-4222-8222-222222222222"
    private val model = "33333333-3333-4333-8333-333333333333"
    private class Disk { var raw: String? = null; var fail = false
        fun open() = WorkshopLedger({ raw }, { if (fail) error("disk_failure"); raw = it }) }
    private fun job(request: String = "request-1", ai: Boolean = true) = WorkshopJob(
        requestId = request, assistantId = owner, conversationId = conversation, byAi = ai,
        modelId = model, maker = "test image model", configHash = "hash", prompt = "draw a flower")

    @Test fun `AI enabled by default without writing`() { val disk = Disk(); assertTrue(disk.open().state.value.allowAi); assertNull(disk.raw) }
    @Test fun `accepted job survives restart`() { val disk = Disk(); val entry = disk.open().submit(job()); assertEquals(entry, disk.open().get(entry.id)) }
    @Test fun `same scoped request id is idempotent`() {
        val ledger = Disk().open(); val first = ledger.submit(job()); val duplicate = ledger.submit(job())
        assertEquals(first.id, duplicate.id); assertEquals(1, ledger.state.value.jobs.size)
    }
    @Test fun `same request id different prompt rejected`() {
        val ledger = Disk().open(); ledger.submit(job()); assertEquals("workshop_request_conflict", runCatching { ledger.submit(job().copy(prompt = "different")) }.exceptionOrNull()?.message)
    }
    @Test fun `request ids are isolated by original conversation`() {
        val ledger = Disk().open(); ledger.submit(job()); ledger.submit(job().copy(conversationId = owner)); assertEquals(2, ledger.state.value.jobs.size)
    }
    @Test fun `closed AI gate rejects AI but human can create`() {
        val ledger = Disk().open(); ledger.preferences(false)
        assertEquals("workshop_ai_disabled", runCatching { ledger.submit(job()) }.exceptionOrNull()?.message)
        assertNotNull(ledger.submit(job(ai = false)))
    }
    @Test fun `queued AI request rechecks human gate before charge`() {
        val ledger = Disk().open(); val job = ledger.submit(job()); ledger.preferences(false)
        assertNull(ledger.claim(job.id)); assertEquals(WorkshopStatus.CANCELLED, ledger.get(job.id)?.status)
    }
    @Test fun `claim is durably one shot`() {
        val disk = Disk(); val ledger = disk.open(); val job = ledger.submit(job())
        assertNotNull(ledger.claim(job.id)); assertNull(ledger.claim(job.id)); assertNull(disk.open().claim(job.id))
    }
    @Test fun `interrupted request never automatically returns to queue`() {
        val disk = Disk(); val ledger = disk.open(); val job = ledger.submit(job()); ledger.claim(job.id)
        val restarted = disk.open(); restarted.recoverInterrupted(job.id)
        assertEquals(WorkshopStatus.INTERRUPTED, restarted.get(job.id)?.status); assertNull(restarted.claim(job.id))
    }
    @Test fun `failed storage never publishes accepted job`() {
        val disk = Disk(); val ledger = disk.open(); disk.fail = true
        assertTrue(runCatching { ledger.submit(job()) }.isFailure); assertTrue(ledger.state.value.jobs.isEmpty())
    }
    @Test fun `committed write followed by exception uses verified disk state`() {
        var raw: String? = null
        val ledger = WorkshopLedger({ raw }, { raw = it; error("close_failed_after_commit") })
        val job = ledger.submit(job())
        assertNotNull(ledger.claim(job.id)); assertNull(ledger.claim(job.id))
        val reopened = WorkshopLedger({ raw }, { raw = it })
        assertEquals(WorkshopStatus.RUNNING, reopened.get(job.id)?.status)
    }
    @Test fun `unverifiable claim fails closed before a charge`() {
        var raw: String? = null; var loseRead = false
        val ledger = WorkshopLedger({ if (loseRead) error("unavailable"); raw }, { raw = it })
        val job = ledger.submit(job()); loseRead = true
        assertEquals("workshop_storage_unverified", runCatching { ledger.claim(job.id) }.exceptionOrNull()?.message)
        loseRead = false
        assertEquals("workshop_storage_unverified", runCatching { ledger.claim(job.id) }.exceptionOrNull()?.message)
        val reopened = WorkshopLedger({ raw }, { raw = it })
        assertNull(reopened.claim(job.id)); reopened.recoverInterrupted(job.id)
        assertEquals(WorkshopStatus.INTERRUPTED, reopened.get(job.id)?.status)
    }
    @Test fun `lost writes never publish a job`() {
        val ledger = WorkshopLedger({ null }, {})
        assertEquals("workshop_storage_unverified", runCatching { ledger.submit(job()) }.exceptionOrNull()?.message)
        assertTrue(ledger.state.value.jobs.isEmpty())
    }
    @Test fun `oversized state is rejected before writing`() {
        var raw: String? = null; var writes = 0
        assertEquals(32 * 1024 * 1024, WorkshopLedger.MAX_LEDGER_BYTES)
        val ledger = WorkshopLedger({ raw }, { writes++; raw = it }, maxBytes = 4096)
        val job = ledger.submit(job()); val before = writes
        assertEquals("workshop_storage_large", runCatching {
            ledger.update(job.id) { it.copy(maker = "x".repeat(8192)) }
        }.exceptionOrNull()?.message)
        assertEquals(before, writes); assertEquals("test image model", ledger.get(job.id)?.maker)
    }
    @Test fun `human cancelled queued job cannot be claimed by a late worker`() {
        val ledger = Disk().open(); val job = ledger.submit(job())
        ledger.update(job.id) { it.copy(status = WorkshopStatus.CANCELLED) }
        assertNull(ledger.claim(job.id)); assertEquals(WorkshopStatus.CANCELLED, ledger.get(job.id)?.status)
    }
    @Test fun `scheduler failure preserves accepted receipt and one possible charge`() {
        val disk = Disk(); val ledger = disk.open(); val accepted = ledger.submit(job())
        val pending = ledger.scheduleAccepted(accepted) { error("scheduler_not_available") }
        assertEquals(accepted.id, pending.id); assertEquals(WorkshopStatus.QUEUED, pending.status)
        assertEquals("workshop_schedule_pending", pending.error)
        assertEquals(accepted.id, ledger.submit(job()).id); assertEquals(1, ledger.state.value.jobs.size)
        val restarted = disk.open(); assertNotNull(restarted.claim(accepted.id)); assertNull(restarted.claim(accepted.id))
    }
    @Test fun `scheduler marker storage failure does not revoke prior acceptance`() {
        val disk = Disk(); val ledger = disk.open(); val accepted = ledger.submit(job()); disk.fail = true
        val result = ledger.scheduleAccepted(accepted) { error("scheduler_unavailable") }
        assertEquals(accepted, result); assertEquals(accepted, disk.open().get(accepted.id))
    }
    @Test fun `late error never replaces human cancellation or completed result`() {
        val ledger = Disk().open(); val cancelled = ledger.submit(job())
        ledger.update(cancelled.id) { it.copy(status = WorkshopStatus.CANCELLED) }
        assertEquals(WorkshopStatus.CANCELLED, ledger.fail(cancelled.id, "workshop_provider_failed").status)
        val complete = ledger.submit(job("second")); ledger.update(complete.id) { it.copy(status = WorkshopStatus.COMPLETE) }
        assertEquals(WorkshopStatus.COMPLETE, ledger.fail(complete.id, "workshop_timeout").status)
    }
    @Test fun `human confirmed retry retains original notification target without AI gate`() {
        val disk = Disk(); val ledger = disk.open(); ledger.preferences(false)
        val original = job()
        val retry = original.copy(id = java.util.UUID.randomUUID().toString(), requestId = "retry-${original.id}", byAi = false,
            notifyConversation = true)
        val accepted = ledger.submit(retry)
        assertNotNull(ledger.claim(accepted.id))
        assertTrue(disk.open().get(accepted.id)!!.notifyConversation)
        assertEquals(conversation, disk.open().get(accepted.id)?.conversationId)
        assertEquals(accepted.id, ledger.submit(retry.copy(id = java.util.UUID.randomUUID().toString())).id)
    }
    @Test fun `recheck blocks revoked authority before each following image`() {
        val original = job()
        verifyWorkshopRequest(original, true, true, true, "hash", true)
        assertEquals("workshop_owner_missing", runCatching { verifyWorkshopRequest(original, false, true, true, "hash", true) }.exceptionOrNull()?.message)
        assertEquals("workshop_target_changed", runCatching { verifyWorkshopRequest(original, true, false, true, "hash", true) }.exceptionOrNull()?.message)
        assertEquals("workshop_provider_unsupported", runCatching { verifyWorkshopRequest(original, true, true, false, "hash", true) }.exceptionOrNull()?.message)
        assertEquals("workshop_configuration_changed", runCatching { verifyWorkshopRequest(original, true, true, true, "rotated", true) }.exceptionOrNull()?.message)
        assertEquals("workshop_ai_disabled", runCatching { verifyWorkshopRequest(original, true, true, true, "hash", false) }.exceptionOrNull()?.message)
        verifyWorkshopRequest(original.copy(byAi = false, notifyConversation = true), true, true, true, "hash", false)
    }
    @Test fun `notification routing requires original conversation`() {
        assertEquals("workshop_target_changed", runCatching {
            Disk().open().submit(job(ai = false).copy(conversationId = null, notifyConversation = true))
        }.exceptionOrNull()?.message)
    }
    @Test fun `export selection is saveable metadata with exact original names`() {
        val assets = listOf(WorkshopAsset("a".repeat(64) + ".png", "pose-neutral", "image/png", true),
            WorkshopAsset("b".repeat(64) + ".jpg", "image", "image/jpeg", false))
        val savedState = Json.encodeToString(assets)
        assertEquals(assets, Json.decodeFromString<List<WorkshopAsset>>(savedState))
        assertFalse(savedState.contains("base64"))
    }
    @Test fun `cannot move result into another assistants conversation`() {
        val ledger = Disk().open(); val job = ledger.submit(job())
        assertTrue(runCatching { ledger.update(job.id) { it.copy(assistantId = model) } }.isFailure)
        assertEquals(owner, ledger.get(job.id)?.assistantId)
    }
    @Test fun `reference traversal and arbitrary url rejected`() {
        assertFalse(validWorkshopFile("../secret.png")); assertFalse(validWorkshopFile("https://server/image.png"))
        assertTrue(validWorkshopFile("a".repeat(64) + ".png"))
        assertTrue(runCatching { Disk().open().submit(job().copy(references = listOf("secret.png"))) }.isFailure)
    }
    @Test fun `many requests have no daily quota`() {
        val ledger = Disk().open(); repeat(120) { ledger.submit(job("r$it")) }; assertEquals(120, ledger.state.value.jobs.size)
    }
    @Test fun `partial originals retained after failure`() {
        val disk = Disk(); val ledger = disk.open(); val job = ledger.submit(job())
        val asset = WorkshopAsset("a".repeat(64) + ".png", "pose-neutral", "image/png", true)
        ledger.claim(job.id); ledger.update(job.id) { it.copy(assets = listOf(asset), status = WorkshopStatus.FAILED) }
        assertEquals(listOf(asset), disk.open().get(job.id)?.assets)
    }
    @Test fun `widget template produces seven poses and seven expressions individually`() {
        assertEquals(14, WIDGET_KIT_VARIANTS.size); assertEquals(14, WIDGET_KIT_VARIANTS.map { it.first }.distinct().size)
        assertEquals(7, WIDGET_KIT_VARIANTS.count { it.first.startsWith("pose-") })
        val prompt = widgetKitPrompt("black hair", WIDGET_KIT_VARIANTS.first().second)
        assertTrue(prompt.contains("black hair")); assertTrue(prompt.contains("alpha")); assertTrue(prompt.contains("不拼图")); assertTrue(prompt.contains("全部手指"))
    }
}
