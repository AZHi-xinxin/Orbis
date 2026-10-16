package me.rerere.rikkahub.data.orbis

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OrbisNotificationDismissTest {
    @Test fun closingOnlyChangesPresentationAndSurvivesReload() {
        var disk: String? = null
        val inbox = OrbisEventInbox({ disk }, { disk = it })
        val binding = OrbisEventBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        inbox.bind(setOf("lc_sentinel"), binding)
        val event = inbox.accept(OrbisIncomingEvent("test", "lc_sentinel", "synthetic"), binding, 100,
            independentDelivery = true).first
        inbox.dismissNotification(event.id, binding.assistantId, binding.conversationId)
        val saved = checkNotNull(OrbisEventInbox({ disk }, {}).get(event.id))
        assertEquals(event, saved.copy(notificationDismissed = false))
        assertTrue(saved.notificationDismissed)
        assertTrue(saved.needsIndependentDispatch())
        assertTrue(inbox.claimIndependentDispatch(event.id))
        assertThrows(IllegalArgumentException::class.java) {
            inbox.dismissNotification(event.id, "foreign", binding.conversationId)
        }
    }
}
