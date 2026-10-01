package me.rerere.rikkahub.data.orbis.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceArchiveRequestTest {
    private val model = Model(modelId = "synthetic-voice-model")
    private val assistant = Assistant(chatModelId = model.id, name = "original AI", systemPrompt = "original persona")
    private val conversation = Conversation(assistantId = assistant.id,
        messageNodes = listOf(UIMessage.user("unrelated-current-chat-secret").toMessageNode()))
    private fun settings() = Settings(assistantId = assistant.id, assistants = listOf(assistant),
        chatModelId = model.id, orbisVoiceArchiveModelId = model.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(model))))
    private fun record() = OrbisVoiceCallRecord("call-1", conversation.id.toString(), assistant.id.toString(), 1000,
        modelId = model.id.toString(), connectedAtMs = 1100, endedAtMs = 2000, durationMs = 900,
        status = OrbisVoiceCallStatus.INTERRUPTED, archiveStatus = OrbisVoiceArchiveStatus.FAILED,
        transcript = listOf(OrbisVoiceTranscriptEntry("turn-1", "USER", "原来的通话内容", 1500),
            OrbisVoiceTranscriptEntry("turn-2", "TOOL", "已成功的工具回执，不要重发", 1600)),
        sourceNodesJson = "[]")

    @Test fun `manual archive uses explicitly selected model without either private persona or current history`() {
        val other = Assistant(name = "other AI", systemPrompt = "foreign persona")
        val otherModel = Model(modelId = "different-model")
        val selected = settings().copy(assistantId = other.id, assistants = listOf(assistant.copy(chatModelId = otherModel.id), other),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model, otherModel))))
        val request = prepareIsolatedVoiceArchive(record(), conversation, selected)
        assertEquals(model.id, request.params.model.id)
        assertEquals(model.modelId, request.params.model.modelId)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), request.messages.map { it.role })
        val text = request.messages.joinToString { it.toText() }
        assertFalse(text.contains("original persona"))
        assertFalse(text.contains("foreign persona"))
        assertFalse(text.contains("unrelated-current-chat-secret"))
        assertFalse(text.contains("synthetic-exact-source-kept-locally"))
        assertTrue(text.contains("原来的通话内容"))
        assertFalse(text.contains("已成功的工具回执"))
        assertTrue(request.messages.last().isSynthetic)
        assertTrue(Json.parseToJsonElement(request.messages.last().toText()).jsonObject.containsKey("captured_transcript"))
    }

    @Test fun `no tool definitions routing continuation identifiers or injected messages can enter archive envelope`() {
        val customized = model.copy(customBodies = listOf(CustomBody("tools", JsonPrimitive("hidden")),
            CustomBody("messages", JsonPrimitive("replace source")), CustomBody("previous_response_id", JsonPrimitive("old")),
            CustomBody("model", JsonPrimitive("different")), CustomBody("temperature", JsonPrimitive(0.4))),
            customHeaders = listOf(CustomHeader("X-ST-Session", "old-session"), CustomHeader("X-Orbis-Conversation-Id", "old-window")))
        val request = prepareIsolatedVoiceArchive(record(), conversation,
            settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(customized)))))
        assertTrue(request.params.tools.isEmpty())
        assertTrue(request.params.model.tools.isEmpty())
        assertTrue(request.params.model.customBodies.isEmpty())
        assertTrue(request.params.customBody.isEmpty())
        assertTrue(request.params.customHeaders.isEmpty())
        assertNull(request.params.sessionId)
        assertNull(request.params.orbisConversationId)
        assertEquals(0, request.params.maxAutomaticContinuations)
    }

    @Test fun `missing explicit model never inherits active chat model and old record model is not required`() {
        val other = Model(modelId = "helper")
        val changed = settings().copy(chatModelId = other.id,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(other))))
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record(), conversation, changed) }
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record(), conversation, settings().copy(orbisVoiceArchiveModelId = null)) }
        assertEquals(model.id, prepareIsolatedVoiceArchive(record().copy(modelId = null), conversation, settings()).params.model.id)
        assertEquals(model.id, prepareIsolatedVoiceArchive(record().copy(modelId = "old-deleted-model"), conversation, settings()).params.model.id)
    }

    @Test fun `active unconnected missing owner and missing raw source cannot request manual archive`() {
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record().copy(status = OrbisVoiceCallStatus.ACTIVE), conversation, settings()) }
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record().copy(connectedAtMs = null), conversation, settings()) }
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record(), conversation.copy(assistantId = Assistant().id), settings()) }
        assertEquals(model.id, prepareIsolatedVoiceArchive(record(), conversation, settings().copy(assistants = emptyList())).params.model.id)
        assertThrows(IllegalStateException::class.java) { prepareIsolatedVoiceArchive(record().copy(transcript = emptyList()), conversation, settings()) }
    }

    @Test fun `incoming opening is an identified host connection event not a dictated script`() {
        val prompt = incomingVoiceOpeningForModel("call-1", "担心你，想确认你还好吗")
        assertTrue(prompt.contains("宿主事件"))
        assertTrue(prompt.contains("不是人类口述"))
        assertTrue(prompt.contains("说什么由你决定"))
        assertTrue(prompt.contains("不必复述来电原因"))
        assertTrue(prompt.contains("\"call_id\":\"call-1\""))
        assertFalse(prompt.startsWith(OrbisVoiceCallProtocol.CALL_MODE_PREFIX))
        assertThrows(IllegalArgumentException::class.java) { incomingVoiceOpeningForModel("../unsafe", "reason") }
        assertThrows(IllegalArgumentException::class.java) { incomingVoiceOpeningForModel("call-1", "a".repeat(2001)) }
    }

    @Test fun `direct archive preparation cannot silently use a best effort UI transcript`() {
        val damaged = record().copy(sourceNodesJson = "[{\"synthetic\":true}]")
        assertTrue(voiceCallTranscriptView(damaged).sourceUnavailable)
        assertTrue(voiceCallReadableTranscript(damaged).isNotEmpty())
        val failure = assertThrows(VoiceArchiveFailure::class.java) {
            prepareIsolatedVoiceArchive(damaged, conversation, settings())
        }
        assertEquals("archive_source_unreadable", failure.safeCode)
        assertNull(failure.cause)
    }
}
