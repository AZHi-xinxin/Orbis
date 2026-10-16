package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class PreparedContextUsageTest {
    private val assistant = Assistant(compactionThresholdTokens = 360_000)
    private val model = Model(modelId = "test-model")
    private val time = LocalDateTime(2026, 10, 13, 15, 30)
    private val history = listOf(UIMessage.user("test question"),
        UIMessage.assistant("test answer").copy(modelId = model.id, finishedAt = time))
    private fun config(a: Assistant = assistant, m: Model = model) = preparedContextConfiguration(
        a, m, null, OrbisConversationPrompt(), emptySet(), emptySet(), null)
    private fun recorded(tokens: Long = 329_019) = attachPreparedContextUsage(history, tokens, 1000,
        2, assistant.id, model.id, config())
    private fun valid(messages: List<UIMessage>, epoch: Long = 2, a: Uuid = assistant.id,
        m: Uuid? = model.id, configuration: String = config()) =
        validPreparedContextUsage(messages, epoch, a, m, configuration)

    @Test fun `34 percent body and 91 percent request use request as primary UI and tool`() = runBlocking {
        val receipt = valid(recorded())!!
        val budget = deriveOrbisContextBudget(recorded(), model.id, null, false, 360_000,
            receipt.tokens, "prepared_request_estimate", requirePreparedRequest = true, bodyUsageTokens = 123_863)
        assertEquals("91%", budget.percentLabel)
        assertEquals(123_863L, budget.bodyUsageTokens)
        val control = ConversationCompactionControl(360_000, { history }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 3) })
        control.observeRequestEstimate(receipt.tokens)
        val tool = control.tools().single { it.name == "context_compaction_status" }
        val fields = Json.parseToJsonElement((tool.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text).text).jsonObject
        assertEquals(receipt.tokens, fields.getValue("estimated_tokens").jsonPrimitive.long)
        assertEquals(receipt.tokens, fields.getValue("prepared_request_estimated_tokens").jsonPrimitive.long)
        assertTrue(fields.getValue("same_reference_as_chat_ring").jsonPrimitive.boolean)
        assertTrue(compactionReminder(receipt.tokens, 360_000)!!.toText().contains("91%"))
    }

    @Test fun `receipt survives serialization and is not counted as new context`() {
        val messages = recorded()
        val codec = Json { encodeDefaults = true }
        val encoded = codec.encodeToString(ListSerializer(UIMessage.serializer()), messages)
        val decoded = codec.decodeFromString(ListSerializer(UIMessage.serializer()), encoded)
        assertEquals(329_019L, valid(decoded)!!.tokens)
        assertEquals(estimateCompactionTokens(history), estimateCompactionTokens(messages))
        assertEquals(estimateActiveMessageBodies(history), estimateActiveMessageBodies(messages))
    }

    @Test fun `legacy messages load without a receipt and UI does not substitute body or old usage`() {
        val codec = Json { encodeDefaults = true }
        val encoded = codec.encodeToString(UIMessage.serializer(), history.last())
        assertFalse(encoded.contains("orbisPreparedContextUsage"))
        assertNull(codec.decodeFromString(UIMessage.serializer(), encoded).orbisPreparedContextUsage)
        val budget = deriveOrbisContextBudget(history, model.id, null, false, 360_000,
            requirePreparedRequest = true, bodyUsageTokens = 123_863)
        assertEquals("—", budget.percentLabel)
        assertEquals(OrbisBudgetReason.REQUEST_ESTIMATE_UNAVAILABLE, budget.reason)
    }

    @Test fun `scope changes and same id model configuration changes invalidate`() {
        val messages = recorded()
        assertNull(valid(messages, epoch = 3))
        assertNull(valid(messages, a = Uuid.random()))
        assertNull(valid(messages, m = Uuid.random()))
        assertNull(valid(messages, m = null))
        assertNull(valid(messages, configuration = config(assistant.copy(systemPrompt = "changed"))))
        assertNull(valid(messages, configuration = config(m = model.copy(modelId = "other-model"))))
        assertNotNull(valid(messages, configuration = config(assistant.copy(compactionThresholdTokens = 500_000))))
    }

    @Test fun `history edits appended input branches and incomplete replies do not reuse an old receipt`() {
        val messages = recorded()
        assertNull(valid(messages.drop(1)))
        assertNull(valid(messages + UIMessage.user("new input")))
        assertNull(valid(messages + UIMessage.assistant("new reply").copy(finishedAt = time)))
        assertNull(valid(listOf(messages.first().copy(parts = listOf(UIMessagePart.Text("edit"))), messages.last())))
        assertNull(valid(messages.dropLast(1) + messages.last().copy(parts = listOf(UIMessagePart.Text("edit")))))
        assertNull(valid(messages.dropLast(1) + messages.last().copy(id = Uuid.random())))
        assertNull(valid(messages.dropLast(1) + messages.last().copy(finishedAt = null)))
        assertNull(valid(messages.dropLast(1) + messages.last().copy(usageContextInvalidated = true)))
    }

    @Test fun `display only edits do not affect source identity`() {
        val messages = recorded()
        assertNotNull(valid(messages.dropLast(1) + messages.last().copy(translation = "translated",
            privateRoomContentHidden = true)))
    }

    @Test fun `compression epoch resets high receipt and fresh request installs lower one`() {
        val messages = recorded()
        assertNull(valid(messages, epoch = 3))
        val next = attachPreparedContextUsage(history, 36_000, 2000, 3, assistant.id, model.id, config())
        val receipt = valid(next, epoch = 3)!!
        assertEquals(36_000L, receipt.tokens)
        assertEquals("10%", deriveOrbisContextBudget(next, model.id, null, false, 360_000,
            receipt.tokens, requirePreparedRequest = true).percentLabel)
    }

    @Test fun `generating and pending never flash old high ratio and over limit is not clamped`() {
        assertEquals("—", deriveOrbisContextBudget(recorded(), model.id, null, true, 360_000,
            329_019, requirePreparedRequest = true).percentLabel)
        assertEquals("—", deriveOrbisContextBudget(recorded(), model.id, null, false, 360_000,
            currentEstimatePending = true, requirePreparedRequest = true).percentLabel)
        assertEquals("120%", deriveOrbisContextBudget(recorded(), model.id, null, false, 360_000,
            432_000, requirePreparedRequest = true).percentLabel)
        assertEquals("—", deriveOrbisContextBudget(recorded(), model.id, null, false, 0,
            329_019, requirePreparedRequest = true).percentLabel)
    }

    @Test fun `status without a prepared request uses null rather than misleading body primary`() = runBlocking {
        val control = ConversationCompactionControl(360_000, { history }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 3) })
        val output = control.tools().single { it.name == "context_compaction_status" }
            .execute(JsonObject(emptyMap())).single() as UIMessagePart.Text
        val fields = Json.parseToJsonElement(output.text).jsonObject
        assertEquals(JsonNull, fields["estimated_tokens"])
        assertFalse(fields.getValue("same_reference_as_chat_ring").jsonPrimitive.boolean)
        assertTrue(fields.getValue("body_estimated_tokens").jsonPrimitive.long > 0)
    }
}
