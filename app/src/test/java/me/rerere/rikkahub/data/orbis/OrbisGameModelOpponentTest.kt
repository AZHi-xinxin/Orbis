package me.rerere.rikkahub.data.orbis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test

class OrbisGameModelOpponentTest {
    private val model = Model(modelId = "synthetic-chat")
    private val assistant = Assistant(chatModelId = model.id, systemPrompt = "synthetic persona", name = "测试AI")
    private fun settings(models: List<Model> = listOf(model), target: Assistant = assistant) = Settings(
        chatModelId = model.id,
        assistantId = target.id,
        assistants = listOf(target),
        providers = listOf(ProviderSetting.OpenAI(models = models)),
    )
    private fun ticket() = OrbisGameMatch("synthetic-match", 0, listOf(0), GomokuRules.MODEL_OPPONENT,
        "测试AI", assistant.id.toString(), modelCalls = 1, modelCallLimit = 10)

    @Test fun `configured exact auxiliary alias is preferred without inventing a model`() {
        val alias = Model(modelId = model.modelId + "--auxiliary-no-memory")
        assertEquals(alias.id, selectGomokuModel(model, settings(listOf(model, alias)).providers).id)
        assertEquals(alias.id, selectGomokuModel(alias, settings(listOf(model, alias)).providers).id)
    }

    @Test fun `alias on another provider never gets borrowed`() {
        val alias = Model(modelId = model.modelId + "--auxiliary-no-memory")
        val providers = listOf(ProviderSetting.OpenAI(models = listOf(model)),
            ProviderSetting.OpenAI(models = listOf(alias), baseUrl = "https://other.invalid/v1"))
        assertEquals(model.id, selectGomokuModel(model, providers).id)
    }

    @Test fun `provider advertising auxiliary protocol requires this exact base model alias`() {
        val unrelated = Model(modelId = "different-chat--auxiliary-no-memory")
        val error = assertThrows(IllegalArgumentException::class.java) {
            selectGomokuModel(model, settings(listOf(model, unrelated)).providers)
        }
        assertEquals("game_model_auxiliary_alias_missing", error.message)
    }

    @Test fun `ordinary vendor keeps original model id rather than adding an unknown suffix`() {
        val prepared = prepareGomokuModelRequest(ticket(), settings())
        assertEquals("synthetic-chat", prepared.params.model.modelId)
        assertNull(prepared.params.orbisConversationId)
        assertNull(prepared.params.sessionId)
    }

    @Test fun `auxiliary alias must not redirect a model override to another connection`() {
        val overridden = model.copy(providerOverwrite = ProviderSetting.OpenAI(baseUrl = "https://override.invalid/v1"))
        val alias = Model(modelId = model.modelId + "--auxiliary-no-memory")
        assertThrows(IllegalArgumentException::class.java) {
            selectGomokuModel(overridden, settings(listOf(overridden, alias)).providers)
        }
    }

    @Test fun `request uses fixed opponent assistant not current UI assistant and contains no chat history`() {
        val another = Assistant(systemPrompt = "do not borrow this persona")
        val configured = settings().copy(assistantId = another.id, assistants = listOf(assistant, another))
        val request = prepareGomokuModelRequest(ticket(), configured)
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), request.messages.map { it.role })
        assertTrue(request.messages.first().toText().startsWith("synthetic persona"))
        assertFalse(request.messages.joinToString { it.toText() }.contains(another.systemPrompt))
        assertTrue(request.messages.last().toText().contains("\"legal_cells\":[1,2,3"))
        assertTrue(request.messages.last().toText().contains("\"moves\":[0]"))
    }

    @Test fun `tools reasoning task override bodies and fake chat source are removed`() {
        val configuredModel = model.copy(
            tools = setOf(BuiltInTools.Search),
            customHeaders = listOf(CustomHeader("X-ST-Thread-ID", "private-chat"), CustomHeader("X-Vendor-Option", "ok")),
            customBodies = listOf(CustomBody("model", JsonPrimitive("other")),
                CustomBody("max_tokens", JsonPrimitive(9_999_999)),
                CustomBody("generationConfig", buildJsonObject { put("maxOutputTokens", 99_999) }),
                CustomBody("tools", JsonPrimitive("do something")),
                CustomBody("temperature", JsonPrimitive(0.4))),
        )
        val configuredAssistant = assistant.copy(
            customHeaders = listOf(CustomHeader("X-Session-ID", "private"), CustomHeader("Authorization", "synthetic auth")),
            customBodies = listOf(CustomBody("messages", JsonPrimitive("not game")),
                CustomBody("input", JsonPrimitive("not game")), CustomBody("stream", JsonPrimitive(true)),
                CustomBody("thinking", JsonPrimitive(true)), CustomBody("seed", JsonPrimitive(123))),
            reasoningLevel = ReasoningLevel.AUTO, maxTokens = 8_000,
        )
        val params = prepareGomokuModelRequest(ticket(), settings(listOf(configuredModel), configuredAssistant)).params
        assertEquals(256, params.maxTokens)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(0, params.maxAutomaticContinuations)
        assertTrue(params.tools.isEmpty())
        assertTrue(params.model.tools.isEmpty())
        assertTrue(params.model.customBodies.isEmpty())
        assertTrue(params.model.customHeaders.isEmpty())
        assertEquals(listOf("Authorization", "X-Vendor-Option"), params.customHeaders.map { it.name })
        assertEquals(listOf("seed", "temperature"), params.customBody.map { it.key })
        assertNull(params.sessionId)
        assertNull(params.orbisConversationId)
    }

    @Test fun `removed assistant missing model and unticketed calls fail before provider`() {
        assertThrows(IllegalStateException::class.java) {
            prepareGomokuModelRequest(ticket(), settings().copy(assistants = emptyList()))
        }
        assertThrows(IllegalStateException::class.java) { prepareGomokuModelRequest(ticket(), settings(emptyList())) }
        assertThrows(IllegalArgumentException::class.java) {
            prepareGomokuModelRequest(ticket().copy(modelCalls = 0), settings())
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepareGomokuModelRequest(ticket().copy(moves = emptyList()), settings())
        }
    }

    @Test fun `numeric legal cell and fenced json are parsed without executing text`() {
        assertEquals(1, parseGomokuModelMove("{\"cell\":1}", listOf(0)))
        assertEquals(80, parseGomokuModelMove("```json\n{\"cell\":80}\n```", listOf(0)))
    }

    @Test fun `malformed non integral occupied and out of range moves are rejected`() {
        for (response in listOf("go to 1", "[]", "{}", "{\"cell\":\"1\"}", "{\"cell\":1.5}",
                "{\"cell\":-1}", "{\"cell\":81}", "{\"cell\":0}", "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { parseGomokuModelMove(response, listOf(0)) }
        }
    }

    @Test fun `one successful provider call yields only a cell and never mutates a repository`() = runBlocking {
        var calls = 0
        val opponent = OrbisGameModelOpponent({ settings() }) { request ->
            calls++
            assertTrue(request.params.tools.isEmpty())
            UIMessage.assistant("{\"cell\":1}")
        }
        assertEquals(1, opponent.chooseMove(ticket()))
        assertEquals(1, calls)
        assertEquals(listOf(0), ticket().moves)
    }

    @Test fun `provider failure is not retried automatically`() {
        var calls = 0
        val opponent = OrbisGameModelOpponent({ settings() }) {
            calls++
            error("synthetic upstream failure")
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { opponent.chooseMove(ticket()) } }
        assertEquals(1, calls)
    }

    @Test fun `cancellation is propagated without retry or fallback move`() {
        var calls = 0
        val opponent = OrbisGameModelOpponent({ settings() }) {
            calls++
            throw CancellationException("synthetic cancellation")
        }
        assertThrows(CancellationException::class.java) { runBlocking { opponent.chooseMove(ticket()) } }
        assertEquals(1, calls)
    }

    @Test fun `unexpected generated tool is rejected and never executed`() {
        var calls = 0
        val opponent = OrbisGameModelOpponent({ settings() }) {
            calls++
            UIMessage.assistant("{\"cell\":1}").copy(parts = listOf(
                UIMessagePart.Text("{\"cell\":1}"), UIMessagePart.Tool("synthetic", "not_allowed", "{}", emptyList()),
            ))
        }
        val error = assertThrows(IllegalArgumentException::class.java) { runBlocking { opponent.chooseMove(ticket()) } }
        assertEquals("game_model_unexpected_tool", error.message)
        assertEquals(1, calls)
    }
}
