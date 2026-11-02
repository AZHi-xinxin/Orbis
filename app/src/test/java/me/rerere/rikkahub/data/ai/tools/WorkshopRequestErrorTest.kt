package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.workshop.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkshopRequestErrorTest {
    @Test fun deletedRequestRejectsBeforeDispatchWhileFreshGenerateAndEditRemainAvailable() {
        for (references in listOf(emptyList(), listOf("a".repeat(64) + ".png"))) {
            var disk: String? = null
            var ledger = WorkshopLedger({ disk }, { disk = it })
            val old = WorkshopJob(requestId = "old-request-06", assistantId = UUID.randomUUID().toString(),
                conversationId = UUID.randomUUID().toString(), byAi = true, modelId = UUID.randomUUID().toString(),
                maker = "synthetic", configHash = "hash", prompt = "a blue star")
            ledger.submit(old)
            ledger.update(old.id) { it.copy(status = WorkshopStatus.COMPLETE) }
            ledger.delete(old.id, old.assistantId)
            ledger = WorkshopLedger({ disk }, { disk = it })
            val tombstone = ledger.get(old.id)
            val candidate = old.copy(id = UUID.randomUUID().toString(), prompt = "a red star", references = references)
            var dispatches = 0
            var acceptedCallbacks = 0
            fun submit(job: WorkshopJob) = ledger.submitAndSchedule(job, { acceptedCallbacks++ }) { id ->
                if (ledger.claim(id) != null) dispatches++ // A provider would only start after this claim.
            }
            val failure = runCatching { submit(candidate) }.exceptionOrNull()
            assertTrue(failure is WorkshopRequestDeletedException)
            val result = workshopToolFailure(failure as Exception)
            assertEquals("workshop_request_deleted", result.getValue("error").jsonPrimitive.content)
            assertEquals("request_id", result.getValue("error_scope").jsonPrimitive.content)
            assertEquals(old.requestId, result.getValue("request_id").jsonPrimitive.content)
            assertFalse(result.getValue("accepted").jsonPrimitive.boolean)
            assertFalse(result.getValue("provider_request_started").jsonPrimitive.boolean)
            assertFalse(result.getValue("automatic_retry").jsonPrimitive.boolean)
            assertTrue(result.getValue("message").jsonPrimitive.content.contains("不表示参考图过期"))
            assertEquals(0, dispatches); assertEquals(0, acceptedCallbacks)
            assertEquals(tombstone, ledger.get(old.id))

            val fresh = candidate.copy(id = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString())
            val accepted = submit(fresh)
            assertEquals(WorkshopStatus.RUNNING, accepted.status)
            assertEquals(references, accepted.references)
            assertEquals(1, dispatches); assertEquals(1, acceptedCallbacks)
            assertEquals(accepted.id, submit(fresh.copy(id = UUID.randomUUID().toString())).id)
            assertEquals(1, dispatches); assertEquals(1, acceptedCallbacks)
            assertEquals(tombstone, ledger.get(old.id))
            assertEquals(2, ledger.state.value.jobs.size)
        }
    }

    @Test fun unknownFailureNeverClaimsProviderWasNotCalledOrPermitsAutomaticRetry() {
        val result = workshopToolFailure(IllegalStateException("unknown transport failure"))
        assertEquals("workshop_unavailable", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.getValue("automatic_retry").jsonPrimitive.boolean)
        assertFalse(result.containsKey("provider_request_started"))
        assertFalse(result.containsKey("accepted"))
        assertFalse(result.toString().contains("transport"))
    }
}
