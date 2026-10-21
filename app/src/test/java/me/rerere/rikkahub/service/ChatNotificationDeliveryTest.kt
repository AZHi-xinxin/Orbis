package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ChatNotificationDeliveryTest {
    @Test fun `one failed update does not prevent a later completed notification`() {
        val delivered = mutableListOf<ChatNotificationDeliveryKind>()
        val failures = mutableListOf<ChatNotificationDeliveryKind>()
        listOf(ChatNotificationDeliveryKind.UPDATE, ChatNotificationDeliveryKind.COMPLETED).forEach { kind ->
            deliverChatNotificationEvent(kind, { failures += it }) {
                if (kind == ChatNotificationDeliveryKind.UPDATE) throw SecurityException("private synthetic content")
                delivered += kind
            }
        }
        assertEquals(listOf(ChatNotificationDeliveryKind.UPDATE), failures)
        assertEquals(listOf(ChatNotificationDeliveryKind.COMPLETED), delivered)
    }

    @Test fun `failed completed notification does not discard any subsequent events or retry the old one`() {
        val attempted = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val failures = mutableListOf<ChatNotificationDeliveryKind>()
        for (index in 1..3) deliverChatNotificationEvent(ChatNotificationDeliveryKind.COMPLETED, { failures += it }) {
            attempted += index
            if (index == 1) throw IllegalStateException("synthetic binder failure")
            delivered += index
        }
        assertEquals(listOf(1, 2, 3), attempted)
        assertEquals(listOf(2, 3), delivered)
        assertEquals(listOf(ChatNotificationDeliveryKind.COMPLETED), failures)
    }

    @Test fun `progress cleanup failure still permits delivery of this completed reply`() {
        val failures = mutableListOf<ChatNotificationDeliveryKind>()
        var completionDelivered = false
        deliverChatNotificationEvent(ChatNotificationDeliveryKind.COMPLETED, { failures += it }) {
            deliverChatNotificationEvent(ChatNotificationDeliveryKind.CLEANUP, { failures += it }) {
                throw SecurityException("synthetic cancel failure")
            }
            completionDelivered = true
        }
        assertTrue(completionDelivered)
        assertEquals(listOf(ChatNotificationDeliveryKind.CLEANUP), failures)
    }

    @Test fun `successful delivery produces no failure diagnostics`() {
        var deliveries = 0
        deliverChatNotificationEvent(ChatNotificationDeliveryKind.COMPLETED, { fail("unexpected diagnostic") }) {
            deliveries++
        }
        assertEquals(1, deliveries)
    }

    @Test fun `cancelled subscriber is not revived and cancellation is not reported as delivery failure`() {
        val cancelled = CancellationException("scope cancelled")
        var failureReports = 0
        val thrown = assertThrows(CancellationException::class.java) {
            deliverChatNotificationEvent(ChatNotificationDeliveryKind.UPDATE, { failureReports++ }) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertEquals(0, failureReports)
    }

    @Test fun `diagnostic failure does not retire subscriber but diagnostic cancellation still propagates`() {
        deliverChatNotificationEvent(ChatNotificationDeliveryKind.COMPLETED,
            { throw IllegalStateException("logging unavailable") }) { throw SecurityException() }
        val cancelled = CancellationException("scope cancelled during reporting")
        val thrown = assertThrows(CancellationException::class.java) {
            deliverChatNotificationEvent(ChatNotificationDeliveryKind.UPDATE, { throw cancelled }) { throw SecurityException() }
        }
        assertSame(cancelled, thrown)
    }

    @Test fun `failure receipts contain only fixed stage codes`() {
        assertEquals(setOf("chat_notification_update_failed", "chat_notification_completed_failed",
            "chat_notification_cleanup_failed"), ChatNotificationDeliveryKind.entries.map { it.failureCode }.toSet())
    }
}
