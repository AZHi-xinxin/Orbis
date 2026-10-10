package me.rerere.rikkahub.service

import org.junit.Assert.*
import org.junit.Test

class ScreenShareChatRecoveryPolicyTest {
    @Test fun `ready is not permission to replay or dismiss another turn`() {
        assertNull(ScreenShareChatAdmission().blockReason)
        assertFalse(ScreenShareChatAdmission().mayDismissForFreshInput)
    }

    @Test fun `ended text pause can permit only fresh input even with preserved remote evidence`() {
        val paused = ScreenShareChatAdmission(queuePaused = true)
        assertEquals("queue_paused", paused.blockReason)
        assertTrue(paused.mayDismissForFreshInput)
        assertTrue(paused.copy(gatewayBlocked = true).mayDismissForFreshInput)
        assertEquals("gateway_blocked", ScreenShareChatAdmission(gatewayBlocked = true).blockReason)
    }

    @Test fun `active work unknown tools checkpoint and changed owner must not be cleared`() {
        val paused = ScreenShareChatAdmission(queuePaused = true)
        listOf(paused.copy(ready = false), paused.copy(ownerMatches = false), paused.copy(busy = true),
            paused.copy(saving = true), paused.copy(pendingTools = true), paused.copy(recoveryBlocked = true))
            .forEach { assertFalse(it.mayDismissForFreshInput) }
    }

    @Test fun `labels distinguish waiting work from broken connection`() {
        assertEquals("busy", ScreenShareChatAdmission(busy = true, pendingTools = true).blockReason)
        assertEquals("saving", ScreenShareChatAdmission(saving = true).blockReason)
        assertEquals("pending_tools", ScreenShareChatAdmission(pendingTools = true).blockReason)
        assertEquals("recovery_blocked", ScreenShareChatAdmission(recoveryBlocked = true).blockReason)
        assertEquals("not_ready", ScreenShareChatAdmission(ready = false).blockReason)
        assertEquals("owner_changed", ScreenShareChatAdmission(ownerMatches = false).blockReason)
    }
}
