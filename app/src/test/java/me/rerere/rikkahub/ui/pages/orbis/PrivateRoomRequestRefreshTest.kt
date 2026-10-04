package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAccessRequest
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultAvailability
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultRequestStatus
import me.rerere.rikkahub.data.orbis.privacy.PrivateVaultStatus
import org.junit.Assert.*
import org.junit.Test

class PrivateRoomRequestRefreshTest {
    private val ready = PrivateVaultStatus(PrivateVaultAvailability.READY, true, true, 2, 1)
    private val pending = PrivateVaultAccessRequest("synthetic-request", "synthetic purpose", listOf("synthetic-record"),
        1, 60_000, PrivateVaultRequestStatus.PENDING)

    @Test fun failedRefreshRetainsPendingRequestAndBlocksNewSubmission() {
        val result = privateRoomRefreshRequests(ready, listOf(pending)) { error("synthetic read failure") }
        assertEquals(listOf(pending), result.requests)
        assertFalse(result.known)
        assertFalse(privateRoomCanSubmitRequest(false, result.known, "new purpose", 2, result.requests))
    }

    @Test fun failureAfterCommitWithoutLocalReceiptDoesNotLookLikeEmptyHistory() {
        val result = privateRoomRefreshRequests(ready, emptyList()) { error("synthetic receipt unavailable") }
        assertTrue(result.requests.isEmpty())
        assertFalse(result.known)
        assertFalse(privateRoomCanSubmitRequest(false, result.known, "try again", 2, result.requests))
        val retried = privateRoomRefreshRequests(ready, result.requests) { listOf(pending) }
        assertTrue(retried.known)
        assertEquals(listOf(pending), retried.requests)
        assertFalse(privateRoomCanSubmitRequest(false, retried.known, "try again", 2, retried.requests))
    }

    @Test fun onlySuccessfulNoPendingRefreshCanEnableExplicitNewRequest() {
        val result = privateRoomRefreshRequests(ready, listOf(pending)) {
            listOf(pending.copy(status = PrivateVaultRequestStatus.DENIED))
        }
        assertTrue(result.known)
        assertTrue(privateRoomCanSubmitRequest(false, result.known, "new purpose", 2, result.requests))
        assertFalse(privateRoomCanSubmitRequest(true, result.known, "new purpose", 2, result.requests))
        assertFalse(privateRoomCanSubmitRequest(false, result.known, "", 2, result.requests))
        assertFalse(privateRoomCanSubmitRequest(false, result.known, "new purpose", 0, result.requests))
    }

    @Test fun absentRecoveryRequiredAndUnreadableRoomsDoNotReadRequestsOrCreateLockFiles() {
        var reads = 0
        listOf(PrivateVaultAvailability.ABSENT, PrivateVaultAvailability.RECOVERY_REQUIRED,
            PrivateVaultAvailability.UNREADABLE).forEach { availability ->
            val result = privateRoomRefreshRequests(PrivateVaultStatus(availability), listOf(pending)) {
                reads++; emptyList()
            }
            assertFalse(result.known)
            assertTrue(result.requests.isEmpty())
        }
        assertEquals(0, reads)
    }

    @Test fun cancellationNeverBecomesKnownEmptyRequestHistory() {
        try {
            privateRoomRefreshRequests(ready, listOf(pending)) { throw CancellationException("synthetic cancellation") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
