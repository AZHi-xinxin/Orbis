package me.rerere.rikkahub.data.repository

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConsultationConversationBinding
import org.junit.Assert.*
import org.junit.Test

class ConsultationConversationMappingTest {
    @Test fun normalChatRemainsVisibleAndConsultationBindingRoundTrips() {
        val normal = Conversation.ofId(Uuid.random(), Uuid.random())
        assertEquals("", encodeConversationEntity(normal).consultationBinding)
        assertFalse(decodeConversationEntity(encodeConversationEntity(normal), emptyList()).isConsultation)
        val consultation = normal.copy(consultation = ConsultationConversationBinding("synthetic-session", "synthetic-subject"))
        val restored = decodeConversationEntity(encodeConversationEntity(consultation), emptyList())
        assertEquals(consultation.consultation, restored.consultation)
        assertEquals(normal.assistantId, restored.assistantId)
        assertTrue(restored.isConsultation)
    }
}
