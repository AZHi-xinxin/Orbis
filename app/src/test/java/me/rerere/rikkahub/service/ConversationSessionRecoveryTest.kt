package me.rerere.rikkahub.service

import kotlinx.coroutines.*
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionRecoveryTest {
    @Test fun `cancelled predecessor remains busy after latest job completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        val release = CompletableDeferred<Unit>()
        try {
            val first = scope.launch(start = CoroutineStart.LAZY) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await() } }
            }
            session.setJob(first)
            val second = scope.launch(start = CoroutineStart.LAZY) { }
            session.setJob(second)
            second.join()
            assertTrue(first.isCancelled)
            assertFalse(first.isCompleted)
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
            assertTrue(session.hasUnfinishedJobs())
            assertTrue(session.isInUse)
            release.complete(Unit)
            first.join()
            assertFalse(session.hasUnfinishedJobs())
        } finally { release.complete(Unit); session.cleanup(); scope.cancel() }
    }

    @Test fun `unconfirmed gateway evidence keeps session alive without a generation`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, {})
        try {
            assertFalse(session.isInUse)
            session.gatewayRecoveryBlocked = true
            assertFalse(session.hasUnfinishedJobs())
            assertTrue(session.isInUse)
            session.gatewayRecoveryBlocked = false
            assertFalse(session.isInUse)
        } finally { session.cleanup(); scope.cancel() }
    }
}
