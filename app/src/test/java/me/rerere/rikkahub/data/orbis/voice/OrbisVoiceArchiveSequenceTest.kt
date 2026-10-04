package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
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
import java.io.IOException

class OrbisVoiceArchiveSequenceTest {
    private val main = Model(modelId = "original-call-model")
    private val fallback = Model(modelId = "explicit-archive-model")
    private val spare = Model(modelId = "explicit-spare-model")
    private val assistant = Assistant(chatModelId = main.id, systemPrompt = "synthetic persona")
    private val conversation = Conversation(assistantId = assistant.id, messageNodes = emptyList())
    private val record = OrbisVoiceCallRecord("synthetic-call", conversation.id.toString(), assistant.id.toString(), 1,
        modelId = main.id.toString(), connectedAtMs = 2, endedAtMs = 3, durationMs = 1,
        status = OrbisVoiceCallStatus.ENDED, transcript = listOf(OrbisVoiceTranscriptEntry("source", "USER", "明天去公园。", 2)))
    private fun settings() = Settings(assistants = listOf(assistant), chatModelId = main.id,
        orbisVoiceArchiveModelId = fallback.id, orbisVoiceArchiveFallbackModelId = spare.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(main, fallback, spare))))
    private fun response() = flowOf(StreamChunk.TextDelta("text", """{"summary":"约好散步。","transcript":"用户：明天去公园。"}"""),
        StreamChunk.Finish("stop"))

    @Test fun `normal call uses original assistant with no external model configuration`() = runTest {
        val attempts = mutableListOf<VoiceArchiveAttempt>()
        val result = runVoiceArchiveSequence(record, conversation,
            settings().copy(orbisVoiceArchiveModelId = null, orbisVoiceArchiveFallbackModelId = null),
            { attempts += it }, { _, _ -> fail("must not fail") }, { response() })
        assertEquals(listOf(main.id), attempts.map { it.modelId })
        assertEquals(OrbisVoiceArchiveAuthor.ASSISTANT, result.attempt.author)
        assertEquals(voiceArchiveSourceDigest(record), result.attempt.sourceDigest)
        assertNull(result.attempt.supersedesAttemptId)
    }

    @Test fun `abnormal first-content timeout retires assistant before exactly one fallback dispatch`() = runTest {
        val attempts = mutableListOf<VoiceArchiveAttempt>()
        val events = mutableListOf<String>()
        val result = runVoiceArchiveSequence(record.copy(status = OrbisVoiceCallStatus.INTERRUPTED), conversation, settings(),
            { attempts += it; events += "claim:${it.author}" },
            { attempt, code -> events += "fail:${attempt.author}:$code" }, { request ->
                events += "request:${request.params.model.id}"
                if (request.params.model.id == main.id) flow { delay(10_001); emit(StreamChunk.TextDelta("text", "late")) }
                else response()
            })
        assertEquals(listOf(main.id, fallback.id), attempts.map { it.modelId })
        assertEquals(attempts.first().attemptId, attempts.last().supersedesAttemptId)
        assertEquals(OrbisVoiceArchiveAuthor.FALLBACK, result.attempt.author)
        assertTrue(events.indexOf("fail:ASSISTANT:archive_first_reply_timeout") < events.indexOf("claim:FALLBACK"))
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test fun `one sequence is bounded and deduplicates same provider model aliases`() = runTest {
        val alias = main.copy(id = fallback.id)
        val selected = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(main, alias, spare))))
        val attempts = mutableListOf<VoiceArchiveAttempt>()
        val failure = runCatching { runVoiceArchiveSequence(record, conversation, selected,
            { attempts += it }, { _, _ -> }, { throw IOException("private provider detail") }) }.exceptionOrNull()
        assertEquals(listOf(main.id, spare.id), attempts.map { it.modelId })
        assertEquals("request_failed", (failure as VoiceArchiveFailure).safeCode)
        assertFalse(failure.toString().contains("private provider detail"))
    }

    @Test fun `failed intent failed retirement and cancellation never fan out to fallback`() = runTest {
        var requests = 0
        val local = IOException("synthetic local storage failure")
        val before = runCatching { runVoiceArchiveSequence(record, conversation, settings(),
            { throw local }, { _, _ -> fail() }, { requests++; response() }) }.exceptionOrNull()
        assertSame(local, before)
        assertEquals(0, requests)
        val failed = runCatching { runVoiceArchiveSequence(record, conversation, settings(),
            {}, { _, _ -> throw local }, { requests++; throw IOException("synthetic provider failure") }) }.exceptionOrNull()
        assertSame(local, failed)
        assertEquals(1, requests)
        val cancelled = CancellationException("synthetic cancellation")
        val cancellation = runCatching { runVoiceArchiveSequence(record, conversation, settings(),
            {}, { _, _ -> fail("user cancellation is not fallback permission") }, { requests++; throw cancelled }) }
        assertTrue(cancellation.isFailure)
        assertTrue(cancellation.exceptionOrNull() is CancellationException)
        assertEquals(cancelled.message, cancellation.exceptionOrNull()?.message)
        assertEquals(2, requests)
    }

    @Test fun `deleted original owner and mismatched source block all requests including external`() = runTest {
        for ((call, chat, selected) in listOf(
            Triple(record, conversation, settings().copy(assistants = emptyList())),
            Triple(record.copy(assistantId = Assistant().id.toString()), conversation, settings()),
            Triple(record.copy(sourceNodesJson = "not-json"), conversation, settings()),
            Triple(record.copy(connectedAtMs = null), conversation, settings()),
        )) {
            var requests = 0
            val failure = runCatching { runVoiceArchiveSequence(call, chat, selected,
                { requests++ }, { _, _ -> }, { requests++; response() }) }.exceptionOrNull()
            assertTrue(failure is VoiceArchiveFailure)
            assertEquals(0, requests)
        }
    }

    @Test fun `unavailable original model uses that owner's current model not selected foreign assistant`() = runTest {
        val foreign = Assistant(chatModelId = spare.id)
        val selected = settings().copy(assistantId = foreign.id, assistants = listOf(assistant.copy(chatModelId = fallback.id), foreign),
            providers = listOf(ProviderSetting.OpenAI(models = listOf(fallback, spare))))
        val attempts = mutableListOf<VoiceArchiveAttempt>()
        val result = runVoiceArchiveSequence(record, conversation, selected, { attempts += it }, { _, _ -> fail() }, { response() })
        assertEquals(listOf(fallback.id), attempts.map { it.modelId })
        assertEquals(OrbisVoiceArchiveAuthor.ASSISTANT, result.attempt.author)
    }

    @Test fun `owner with unavailable chat model can use only explicitly configured external model`() = runTest {
        val selected = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(fallback, spare))))
        val attempts = mutableListOf<VoiceArchiveAttempt>()
        val result = runVoiceArchiveSequence(record, conversation, selected, { attempts += it }, { _, _ -> fail() }, { response() })
        assertEquals(listOf(fallback.id), attempts.map { it.modelId })
        assertEquals(OrbisVoiceArchiveAuthor.FALLBACK, result.attempt.author)
    }
}
