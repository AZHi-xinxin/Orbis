package me.rerere.rikkahub.data.orbis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class OrbisVoiceArchivePipelineTest {
    private val main = Model(modelId = "synthetic-archive")
    private val backup = Model(modelId = "synthetic-backup")
    private val chat = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
    private val call = OrbisVoiceCallRecord("call-1", chat.id.toString(), chat.assistantId.toString(), 1,
        connectedAtMs = 2, endedAtMs = 3, durationMs = 1, status = OrbisVoiceCallStatus.INTERRUPTED,
        transcript = listOf(OrbisVoiceTranscriptEntry("speech", "USER", "明天去公园。", 2)))
    private fun settings(fallback: Uuid? = backup.id) = Settings(orbisVoiceArchiveModelId = main.id,
        orbisVoiceArchiveFallbackModelId = fallback, providers = listOf(ProviderSetting.OpenAI(models = listOf(main, backup))))
    private fun response(text: String = """{"summary":"约好明天去公园。","transcript":"用户：明天去公园。"}""") =
        TextGenerationResult("synthetic", "synthetic", UIMessage.assistant(text))

    @Test fun `default and fallback alone never authorize any provider request`() = runTest {
        for (settings in listOf(Settings(), settings().copy(orbisVoiceArchiveModelId = null))) {
            var count = 0
            try { runIndependentVoiceArchive(call, chat, settings, { count++ }, { count++; response() }); fail() }
            catch (error: VoiceArchiveFailure) { assertEquals("model_not_configured", error.safeCode) }
            assertEquals(0, count)
        }
    }
    @Test fun `success uses only configured primary and never fallback`() = runTest {
        val calls = mutableListOf<Uuid>()
        val result = runIndependentVoiceArchive(call, chat, settings(), { calls += it }, { response() })
        assertEquals(main.id, result.modelId)
        assertEquals(listOf(main.id), calls)
    }
    @Test fun `primary failure permits exactly one configured fallback without repeating original request`() = runTest {
        val calls = mutableListOf<Uuid>()
        val result = runIndependentVoiceArchive(call, chat, settings(), { calls += it }, {
            if (it.params.model.id == main.id) throw IOException("private transport details") else response()
        })
        assertEquals(backup.id, result.modelId)
        assertEquals(listOf(main.id, backup.id), calls)
    }
    @Test fun `same model fallback is deduplicated and absent fallback never borrows fast model`() = runTest {
        for (fallback in listOf(null, main.id)) {
            var count = 0
            try { runIndependentVoiceArchive(call, chat, settings(fallback), { count++ }, { throw IOException("failure") }); fail() }
            catch (error: VoiceArchiveFailure) { assertEquals("request_failed", error.safeCode); assertFalse(error.toString().contains("failure")) }
            assertEquals(1, count)
        }
    }
    @Test fun `two failed attempts stop with a closed safe code and no provider body`() = runTest {
        var count = 0
        try { runIndependentVoiceArchive(call, chat, settings(), { count++ }, { throw IOException("PRIVATE_SECRET") }); fail() }
        catch (error: VoiceArchiveFailure) { assertEquals("request_failed", error.safeCode); assertFalse(error.toString().contains("PRIVATE_SECRET")) }
        assertEquals(2, count)
    }
    @Test fun `cancellation and failed durable intent never invoke fallback`() = runTest {
        var count = 0
        try { runIndependentVoiceArchive(call, chat, settings(), { count++ }, { throw CancellationException("cancel") }); fail() }
        catch (_: CancellationException) { }
        assertEquals(1, count)
        count = 0
        try { runIndependentVoiceArchive(call, chat, settings(), { throw IOException("disk failure") }, { count++; response() }); fail() }
        catch (_: IOException) { }
        assertEquals(0, count)
    }
    @Test fun `tool response is rejected without executing anything and may only use configured fallback`() = runTest {
        var count = 0
        val result = runIndependentVoiceArchive(call, chat, settings(), { count++ }, {
            if (it.params.model.id == main.id) response().copy(message = UIMessage.assistant("").copy(
                parts = listOf(UIMessagePart.Tool(toolCallId = "synthetic", toolName = "toy_bluetooth_set", input = "{}"))))
            else response()
        })
        assertEquals(2, count)
        assertEquals(backup.id, result.modelId)
    }
    @Test fun `truncated otherwise valid JSON is not falsely accepted as complete`() = runTest {
        var count = 0
        try { runIndependentVoiceArchive(call, chat, settings(null), { count++ }, { response().copy(finishReason = "length") }); fail() }
        catch (error: VoiceArchiveFailure) { assertEquals("invalid_archive_response", error.safeCode) }
        assertEquals(1, count)
    }

    @Test fun `unreadable source blocks primary fallback and durable request intent despite readable ASR`() = runTest {
        var intents = 0
        var requests = 0
        val damaged = call.copy(sourceNodesJson = "[{\"synthetic\":true}]")
        assertEquals(call.transcript, voiceCallTranscriptView(damaged).entries)
        try {
            runIndependentVoiceArchive(damaged, chat, settings(), { intents++ }, { requests++; response() })
            fail("partial display must not authorize an archive")
        } catch (error: VoiceArchiveFailure) { assertEquals("archive_source_unreadable", error.safeCode) }
        assertEquals(0, intents)
        assertEquals(0, requests)
        assertEquals("[{\"synthetic\":true}]", damaged.sourceNodesJson)
    }
}
