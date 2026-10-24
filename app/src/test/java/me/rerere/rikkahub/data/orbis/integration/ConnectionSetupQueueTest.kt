package me.rerere.rikkahub.data.orbis.integration

import org.junit.Assert.*
import org.junit.Test

class ConnectionSetupQueueTest {
    private fun proposal(owner: String = "owner", kind: String = "model_openai") = ConnectionProposal(owner,
        ConnectionDraft(kind, "Synthetic", "https://example.invalid", model = "model", voice = "voice"))

    @Test fun allSupportedKindsRemainBoundToOriginatingAssistantAndConversation() {
        listOf("model_openai", "tts_openai", "mcp_http", "mcp_sse").forEach { kind ->
            val queue = ConnectionSetupQueue(); val p = proposal(kind = kind)
            queue.offer("chat1", p)
            assertNull(queue.take("other", "chat1", listOf(p)))
            assertNull(queue.take("owner", "chat2", listOf(p)))
            assertEquals(p, queue.take("owner", "chat1", listOf(p))!!.proposal)
            assertTrue(queue.requests.value.isEmpty())
        }
    }
    @Test fun deletedRejectedOrChangedProposalNeverOpensOldCredentialForm() {
        val p = proposal()
        listOf(emptyList(), listOf(p.copy(status = "rejected")), listOf(p.copy(draft = p.draft.copy(model = "changed")))).forEach {
            val queue = ConnectionSetupQueue(); queue.offer("chat", p)
            assertNull(queue.take("owner", "chat", it))
            assertTrue(queue.requests.value.isEmpty())
        }
    }
    @Test fun repeatedRequestsCoalesceAndDismissDoesNotAffectOtherChats() {
        val queue = ConnectionSetupQueue(); val p = proposal()
        repeat(10) { queue.offer("chat", p) }; queue.offer("other-chat", p)
        assertEquals(2, queue.requests.value.size)
        queue.dismiss("owner", "chat")
        assertEquals("other-chat", queue.requests.value.single().conversation)
    }
}
