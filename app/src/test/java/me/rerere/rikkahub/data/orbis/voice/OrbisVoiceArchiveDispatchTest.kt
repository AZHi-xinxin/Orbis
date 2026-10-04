package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisVoiceArchiveDispatchTest {
    private val main = Model(modelId = "synthetic-current")
    private val fallback = Model(modelId = "synthetic-external")
    private val spare = Model(modelId = "synthetic-backup")
    private val assistant = Assistant(chatModelId = main.id, systemPrompt = "synthetic persona")
    private val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList())
    private val record = OrbisVoiceCallRecord("synthetic-call", conversation.id.toString(), assistant.id.toString(), 1,
        modelId = main.id.toString(), connectedAtMs = 2, endedAtMs = 3, durationMs = 1,
        status = OrbisVoiceCallStatus.ENDED, transcript = listOf(OrbisVoiceTranscriptEntry("source", "USER", "Synthetic source.", 2)))
    private val provider = ProviderSetting.OpenAI(models = listOf(main, fallback, spare),
        baseUrl = "https://planned.invalid/v1", apiKey = "synthetic-credential-not-real")
    private val planned = Settings(assistants = listOf(assistant), chatModelId = main.id,
        orbisVoiceArchiveModelId = fallback.id, orbisVoiceArchiveFallbackModelId = spare.id, providers = listOf(provider))
    private fun attempt(author: OrbisVoiceArchiveAuthor = OrbisVoiceArchiveAuthor.ASSISTANT,
        model: Uuid = if (author == OrbisVoiceArchiveAuthor.ASSISTANT) main.id else fallback.id) =
        VoiceArchiveAttempt("synthetic-attempt", model, author, voiceArchiveSourceDigest(record))
    private fun rejected(live: Settings, selected: VoiceArchiveAttempt = attempt(),
        call: OrbisVoiceCallRecord = record, chat: Conversation = conversation) {
        val error = runCatching { requireVoiceArchiveDispatchStillAllowed(call, chat, planned, live, selected) }.exceptionOrNull()
        assertTrue(error is VoiceArchiveFailure)
        assertEquals("archive_configuration_changed", (error as VoiceArchiveFailure).safeCode)
        assertFalse(error.toString().contains("credential"))
        assertFalse(error.toString().contains("planned.invalid"))
    }

    @Test fun `unchanged assistant and explicitly configured external targets remain allowed`() {
        for (selected in listOf(attempt(), attempt(OrbisVoiceArchiveAuthor.FALLBACK),
            attempt(OrbisVoiceArchiveAuthor.FALLBACK, spare.id))) {
            requireVoiceArchiveDispatchStillAllowed(record, conversation, planned, planned, selected)
            requireVoiceArchiveDispatchStillAllowed(record, conversation, planned,
                planned.copy(providers = listOf(provider.copy())), selected)
        }
        // Merely selecting a different UI assistant is not a transfer of this call's ownership.
        requireVoiceArchiveDispatchStillAllowed(record, conversation, planned,
            planned.copy(assistantId = Uuid.random()), attempt())
    }

    @Test fun `removed owner or changed conversation ownership blocks all authors`() {
        for (selected in listOf(attempt(), attempt(OrbisVoiceArchiveAuthor.FALLBACK))) {
            rejected(planned.copy(assistants = emptyList()), selected)
            rejected(planned, selected, call = record.copy(assistantId = Uuid.random().toString()))
            rejected(planned, selected, chat = conversation.copy(id = Uuid.random()))
        }
    }

    @Test fun `revoked external primary or backup cannot be dispatched from old plan`() {
        rejected(planned.copy(orbisVoiceArchiveModelId = null), attempt(OrbisVoiceArchiveAuthor.FALLBACK))
        rejected(planned.copy(orbisVoiceArchiveModelId = null), attempt(OrbisVoiceArchiveAuthor.FALLBACK, spare.id))
        rejected(planned.copy(orbisVoiceArchiveModelId = spare.id, orbisVoiceArchiveFallbackModelId = null),
            attempt(OrbisVoiceArchiveAuthor.FALLBACK))
        rejected(planned.copy(orbisVoiceArchiveFallbackModelId = null), attempt(OrbisVoiceArchiveAuthor.FALLBACK, spare.id))
    }

    @Test fun `disabled or removed provider and removed model block stale dispatch`() {
        for (selected in listOf(attempt(), attempt(OrbisVoiceArchiveAuthor.FALLBACK))) {
            rejected(planned.copy(providers = listOf(provider.copy(enabled = false))), selected)
            rejected(planned.copy(providers = emptyList()), selected)
            rejected(planned.copy(providers = listOf(provider.copy(models = provider.models.filterNot { it.id == selected.modelId }))), selected)
        }
    }

    @Test fun `endpoint credentials transport and model config changes block without disclosing values`() {
        for (changedProvider in listOf(provider.copy(baseUrl = "https://changed.invalid/v1"),
            provider.copy(apiKey = "synthetic-replacement-secret"), provider.copy(useResponseApi = true),
            provider.copy(chatCompletionsPath = "/other"), provider.copy(models = listOf(main.copy(modelId = "other-model"), fallback, spare)))) {
            rejected(planned.copy(providers = listOf(changedProvider)))
            rejected(planned.copy(providers = listOf(changedProvider)), attempt(OrbisVoiceArchiveAuthor.FALLBACK))
        }
    }

    @Test fun `changed original owner's model or persona stops even when recorded call model remains usable`() {
        rejected(planned.copy(assistants = listOf(assistant.copy(chatModelId = spare.id))))
        rejected(planned.copy(assistants = listOf(assistant.copy(systemPrompt = "new synthetic persona"))))
        val usingDefault = planned.copy(assistants = listOf(assistant.copy(chatModelId = null)))
        val error = runCatching { requireVoiceArchiveDispatchStillAllowed(record, conversation, usingDefault,
            usingDefault.copy(chatModelId = spare.id), attempt()) }.exceptionOrNull()
        assertEquals("archive_configuration_changed", (error as VoiceArchiveFailure).safeCode)
    }

    @Test fun `unplanned model or tool author cannot be repurposed as dispatch permission`() {
        rejected(planned, attempt(OrbisVoiceArchiveAuthor.ASSISTANT, fallback.id))
        rejected(planned, attempt(OrbisVoiceArchiveAuthor.FALLBACK, main.id))
        rejected(planned, attempt(OrbisVoiceArchiveAuthor.ASSISTANT_TOOL, main.id))
    }

    @Test fun `configuration change in request closure terminates sequence without external fanout`() = runTest {
        var live = planned
        var claimed: VoiceArchiveAttempt? = null
        var dispatches = 0
        var claims = 0
        val error = runCatching { runVoiceArchiveSequence(record, conversation, planned,
            beforeRequest = { selected ->
                requireVoiceArchiveDispatchStillAllowed(record, conversation, planned, live, selected)
                claimed = selected
                claims++
                // Simulate settings changing while the durable claim is suspended.
                live = planned.copy(providers = listOf(provider.copy(apiKey = "synthetic-replacement-secret")))
            },
            onAttemptFailed = { _, _ -> fail("a consent/configuration change must stop the sequence, not retire into fallback") },
            request = {
                requireVoiceArchiveDispatchStillAllowed(record, conversation, planned, live, checkNotNull(claimed))
                dispatches++
                flowOf(StreamChunk.TextDelta("text", """{"summary":"Synthetic summary.","transcript":"Synthetic source."}"""),
                    StreamChunk.Finish("stop"))
            }) }.exceptionOrNull()
        assertEquals("archive_configuration_changed", (error as VoiceArchiveFailure).safeCode)
        assertEquals(1, claims)
        assertEquals(0, dispatches)
    }

    @Test fun `configuration change at intent boundary also prevents all requests`() = runTest {
        var dispatches = 0
        val error = runCatching { runVoiceArchiveSequence(record, conversation, planned,
            beforeRequest = { requireVoiceArchiveDispatchStillAllowed(record, conversation, planned,
                planned.copy(assistants = emptyList()), it) },
            onAttemptFailed = { _, _ -> fail("no fallback after revoked ownership") },
            request = { dispatches++; flowOf(StreamChunk.Finish("stop")) }) }.exceptionOrNull()
        assertEquals("archive_configuration_changed", (error as VoiceArchiveFailure).safeCode)
        assertEquals(0, dispatches)
    }

    @Test fun `source and persistence failures at request boundary do not become fallback authorization`() = runTest {
        for (code in listOf("archive_configuration_changed", "archive_source_changed", "archive_source_unreadable",
            "archive_persistence_failed")) {
            var requests = 0
            var claims = 0
            val error = runCatching { runVoiceArchiveSequence(record, conversation, planned,
                beforeRequest = { claims++ },
                onAttemptFailed = { _, _ -> fail("boundary failure must stop, not fan out") },
                request = { requests++; throw VoiceArchiveFailure(code) }) }.exceptionOrNull()
            assertEquals(code, (error as VoiceArchiveFailure).safeCode)
            assertEquals(1, requests)
            assertEquals(1, claims)
        }
    }
}
