package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class OrbisAssistantVoiceArchiveRequestTest {
    private val model = Model(modelId = "synthetic-original-model",
        customBodies = listOf(CustomBody("messages", JsonPrimitive("forbidden-body"))),
        customHeaders = listOf(CustomHeader("X-Old-Session", "forbidden-header")))
    private val assistant = Assistant(chatModelId = model.id, systemPrompt = "original persona")
    private val chat = Conversation(assistantId = assistant.id, customSystemPrompt = "override persona",
        orbisPrompt = OrbisConversationPrompt("enabled conversation prompt", true, "disabled worldbook", false),
        messageNodes = listOf(UIMessage.user("unrelated current chat").toMessageNode()))
    private val record = OrbisVoiceCallRecord("synthetic-call", chat.id.toString(), assistant.id.toString(), 1,
        modelId = model.id.toString(), connectedAtMs = 2, endedAtMs = 3, durationMs = 1,
        status = OrbisVoiceCallStatus.ENDED,
        transcript = listOf(OrbisVoiceTranscriptEntry("speech", "USER", "accepted synthetic speech", 2),
            OrbisVoiceTranscriptEntry("tool", "TOOL", "old private tool receipt", 2)))
    private fun settings(persona: Assistant = assistant) = Settings(assistants = listOf(persona), chatModelId = model.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))

    @Test fun `original persona and enabled conversation prompt retained without interactive side effects`() {
        val request = prepareAssistantVoiceArchive(record, chat, settings(), model.id)
        val text = request.messages.joinToString { it.toText() }
        assertTrue(text.contains("original persona"))
        assertTrue(text.contains("enabled conversation prompt"))
        assertTrue(text.contains("accepted synthetic speech"))
        listOf("override persona", "disabled worldbook", "unrelated current chat", "old private tool receipt",
            "forbidden-body", "forbidden-header").forEach { assertFalse(text.contains(it)) }
        assertFalse(text.contains("你是独立的记录整理器"))
        assertTrue(request.params.tools.isEmpty())
        assertTrue(request.params.model.tools.isEmpty())
        assertTrue(request.params.model.customBodies.isEmpty())
        assertTrue(request.params.model.customHeaders.isEmpty())
        assertTrue(request.params.customHeaders.isEmpty())
        assertTrue(request.params.customBody.isEmpty())
        assertNull(request.params.sessionId)
        assertNull(request.params.orbisConversationId)
        assertEquals(0, request.params.maxAutomaticContinuations)
    }

    @Test fun `conversation system override requires original assistant permission`() {
        val request = prepareAssistantVoiceArchive(record, chat, settings(assistant.copy(allowConversationSystemPrompt = true)), model.id)
        val text = request.messages.joinToString { it.toText() }
        assertTrue(text.contains("override persona"))
        assertFalse(text.contains("original persona"))
    }
}
