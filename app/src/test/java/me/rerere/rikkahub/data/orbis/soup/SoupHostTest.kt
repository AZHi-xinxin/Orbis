package me.rerere.rikkahub.data.orbis.soup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class SoupHostTest {
    private val model = Model(modelId = "deepseek-chat", displayName = "Synthetic host",
        customHeaders = listOf(CustomHeader("X-ST-Identity", "private-header-sentinel")),
        customBodies = listOf(CustomBody("messages", JsonPrimitive("private-body-sentinel"))))
    private fun settings() = Settings(providers = listOf(ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", apiKey = "synthetic-key", models = listOf(model))),
        assistants = listOf(Assistant(systemPrompt = "private-assistant-sentinel", enableMemory = true)))
    private fun rejected(block: () -> Unit) { try { block(); fail("must reject") } catch (_: Exception) { } }

    @Test fun directSelectionRejectsMemoryGatewaysOverridesAndUnsafeEndpoints() {
        listOf("https://memory.example/v1", "http://api.deepseek.com/v1", "https://api.deepseek.com/v1?identity=x", "https://api.deepseek.com/v1#memory", "https://user:pass@api.deepseek.com/v1", "https://api.deepseek.com:8443/v1", "https://api.deepseek.com/%76%31", "https://api.deepseek.com/v1/../v1").forEach { endpoint ->
            rejected { soupDirectEndpoint(ProviderSetting.OpenAI(baseUrl = endpoint)) }
        }
        rejected { soupDirectEndpoint(ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", chatCompletionsPath = "/memory/chat")) }
        val overridden = model.copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://memory.example/v1", apiKey = "synthetic"))
        rejected { soupModelSelection(settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(overridden)))), overridden.id.toString()) }
        assertEquals("https://api.siliconflow.cn/v1", soupDirectEndpoint(ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn/v1")))
    }

    @Test fun isolatedRequestHasOnlyHostRulesAndCurrentGameNoAssistantMemoryHeadersOrTools() {
        val config = settings(); val selected = soupModelSelection(config, model.id.toString())
        val session = SoupSession("00000000-0000-0000-0000-000000000001", "soup_sample_005", SoupMode.NORMAL, 1,
            questions = listOf(SoupQuestion(SoupPlayer.HUMAN, "合成问题吗？", "是", 2)))
        val request = soupHostRequest(session, SoupAction.ASK, "这是真的吗？", selected)
        val text = request.messages.joinToString { it.toText() }
        assertEquals(2, request.messages.size)
        assertTrue(text.contains("secret_solution")); assertTrue(text.contains("合成问题吗？"))
        listOf("private-assistant-sentinel", "private-header-sentinel", "private-body-sentinel", "synthetic-key").forEach { assertFalse(text.contains(it)) }
        assertTrue(request.params.tools.isEmpty()); assertTrue(request.params.customHeaders.isEmpty()); assertTrue(request.params.customBody.isEmpty())
        assertTrue(request.params.model.customHeaders.isEmpty()); assertTrue(request.params.model.customBodies.isEmpty()); assertTrue(request.params.model.tools.isEmpty())
        assertNull(request.params.model.providerOverwrite); assertNull(request.params.sessionId); assertNull(request.params.orbisConversationId)
        assertEquals(0, request.params.maxAutomaticContinuations)
    }

    @Test fun preparationDoesNotCallModelAndExecutionRequiresDurableRunningTicket() = runBlocking {
        val storage = SoupMemoryStorage(); val repository = SoupRepository(storage, { 1000L })
        repository.selectHost(model.id.toString()); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var calls = 0
        val config = settings()
        val host = SoupHost({ config }) {
            calls++
            assertEquals(SoupAttemptState.RUNNING, repository.snapshot().active!!.pending!!.state)
            assertTrue(requireNotNull(storage.value).contains("RUNNING"))
            UIMessage.assistant("""{"answer":"是也不是"}""")
        }
        val controller = SoupController(repository, host)
        val prepared = controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？")
        assertEquals(0, calls); assertTrue(repository.snapshot().active!!.attempts.isEmpty())
        controller.execute(prepared)
        assertEquals(1, calls); assertEquals("是也不是", repository.snapshot().active!!.questions.single().answer)
    }

    @Test fun failureIsUnknownNeverAutomaticallyRetriedAndFreshConfirmationCanRetry() = runBlocking {
        val storage = SoupMemoryStorage(); val repository = SoupRepository(storage, { 1000L })
        repository.selectHost(model.id.toString()); val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var calls = 0
        val config = settings()
        val controller = SoupController(repository, SoupHost({ config }) { calls++; error("private-transport-details") })
        val prepared = controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？")
        try { controller.execute(prepared); fail("must fail") } catch (error: IllegalStateException) { assertEquals("soup_request_unknown", error.message) }
        assertEquals(1, calls); assertEquals(SoupAttemptState.UNKNOWN, repository.snapshot().active!!.pending!!.state)
        assertTrue(repository.snapshot().active!!.questions.isEmpty()); assertFalse(storage.value!!.contains("private-transport-details"))
        rejected { controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？") }
        val again = controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？", retry = true)
        assertEquals(1, calls)
        try { controller.execute(again); fail("must fail") } catch (_: IllegalStateException) { }
        assertEquals(2, calls); assertEquals(2, repository.snapshot().active!!.attempts.size)
    }

    @Test fun cancellationIsPersistedAsUnknownAndRethrownWithoutRetry() = runBlocking {
        val repository = SoupRepository(SoupMemoryStorage(), { 1000L }); repository.selectHost(model.id.toString())
        val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var calls = 0
        val config = settings()
        val controller = SoupController(repository, SoupHost({ config }) { calls++; throw CancellationException("synthetic") })
        try { controller.execute(controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？")); fail("cancelled") }
        catch (_: CancellationException) { }
        assertEquals(1, calls); assertEquals(SoupAttemptState.UNKNOWN, repository.snapshot().active!!.pending!!.state)
    }

    @Test fun changedProviderBeforeConfirmationCannotMakePaidCall() = runBlocking {
        val repository = SoupRepository(SoupMemoryStorage(), { 1000L }); repository.selectHost(model.id.toString())
        val session = repository.start("soup_sample_001", SoupMode.NORMAL)
        var currentSettings = settings(); var calls = 0
        val controller = SoupController(repository, SoupHost({ currentSettings }) { calls++; UIMessage.assistant("""{"answer":"是"}""") })
        val prepared = controller.prepare(session.id, SoupAction.ASK, SoupPlayer.HUMAN, "成立吗？")
        currentSettings = currentSettings.copy(providers = listOf((currentSettings.providers.single() as ProviderSetting.OpenAI).copy(apiKey = "changed-synthetic-key")))
        try { controller.execute(prepared); fail("changed") } catch (_: IllegalArgumentException) { }
        assertEquals(0, calls); assertTrue(repository.snapshot().active!!.attempts.isEmpty())
    }
}
