package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class ModelContinuityTest {
    private val first = ConfiguredModelReceipt("owner", "window", "provider", "model-a", "OpenAI compatible")
    @Test fun sameChangedUnknownAndWindowIsolation() {
        assertTrue(ModelContinuity.notice(first, null)!!.contains("unknown"))
        assertTrue(ModelContinuity.notice(first, first)!!.contains("same"))
        assertTrue(ModelContinuity.notice(first.copy(modelId="model-b"), first)!!.contains("changed"))
        assertTrue(ModelContinuity.notice(first.copy(providerId="other"), first)!!.contains("changed"))
        assertTrue(ModelContinuity.notice(first.copy(conversationId="other"), first)!!.contains("unknown"))
        assertNull(ModelContinuity.notice(null, first))
        assertNull(ModelContinuity.safeModelId("https://secret.example/api?key=x"))
    }
    @Test fun onlySuccessfulTerminalAdvancesAndSurvivesRestart() {
        val history = listOf(UIMessage(role=MessageRole.ASSISTANT, parts=listOf(UIMessagePart.Text("ok"))))
        assertNull(ModelContinuity.previous(ModelContinuity.attachSuccess(history, first, false), first))
        val completed = ModelContinuity.attachSuccess(history, first, true)
        val restored = Json.decodeFromString<List<UIMessage>>(Json.encodeToString(completed))
        assertEquals(first, ModelContinuity.previous(restored, first))
        assertEquals(first, ModelContinuity.previous(restored + UIMessage(role=MessageRole.USER, parts=listOf(UIMessagePart.Text("next"))), first))
        assertNull(ModelContinuity.previous(restored, first.copy(assistantId="different")))
    }
    @Test fun snapshotDoesNotRerenderOrRewriteHistoryForTools() = runTest {
        val original = listOf(UIMessage(role=MessageRole.USER, parts=listOf(UIMessagePart.Text("hello"))))
        val notice = ModelContinuity.notice(first, null)
        val snapshot = GenerationInputSnapshot(modelNotice = notice)
        val initial = snapshot.input { original }
        assertEquals(1, original.single().parts.size); assertEquals(2, initial.single().parts.size)
        assertEquals(initial, snapshot.input { error("must not re-evaluate on continuation") })
        assertEquals(original, GenerationInputSnapshot().input { original })
    }
}
