package me.rerere.rikkahub.data.ai.compaction

import kotlinx.datetime.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.orbisCompactionSummaryHash
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CompactionPolicyBoundaryTest {
    private fun args(text: String = "{}") = Json.parseToJsonElement(text)
    private fun assistant(text: String) = UIMessage.assistant(text)
    private fun completedCall(id: String = "synthetic-compact") = UIMessagePart.Tool(id, "compact", "{}", listOf(UIMessagePart.Text("pending")))

    @Test fun `350K begins at 315K and zero never reminds`() {
        assertNull(compactionReminder(314_999, 350_000))
        assertNotNull(compactionReminder(315_000, 350_000))
        assertNull(compactionReminder(1_500_000, 0))
        assertTrue(compactionReminder(350_001, 350_000)!!.toText().contains("超过"))
    }

    @Test fun `reminder uses host system transport with neutral nonhuman provenance`() {
        val reminder = compactionReminder(315_000, 350_000)!!
        assertEquals(MessageRole.SYSTEM, reminder.role)
        assertTrue(reminder.isSynthetic)
        assertTrue(reminder.isCompactionReminder())
        assertEquals(JsonPrimitive(false), reminder.parts.single().metadata?.get("human_authored"))
        assertEquals(JsonPrimitive("none"), reminder.parts.single().metadata?.get("instruction_authority"))
        assertTrue(reminder.toText().contains("非人类发言"))
        assertTrue(reminder.toText().contains("35000"))
        assertTrue(reminder.toText().contains("不会自动压缩"))
        assertTrue(reminder.toText().contains("不是整理前置条件"))
    }

    @Test fun `near threshold reminder shows current usage threshold ratio and remaining tokens`() {
        val text = compactionReminder(315_000, 350_000)!!.toText()
        assertTrue(text.contains("当前上下文估算 315000 / 提醒阈值 350000 token（90%）"))
        assertTrue(text.contains("距阈值还有 35000 token"))
        assertFalse(text.contains("已超过阈值"))
        assertFalse(text.contains("负数"))
    }

    @Test fun `over threshold reminder shows positive excess and unclamped percentage`() {
        val text = compactionReminder(420_000, 350_000)!!.toText()
        assertTrue(text.contains("当前上下文估算 420000 / 提醒阈值 350000 token（120%）"))
        assertTrue(text.contains("已超过阈值 70000 token"))
        assertFalse(text.contains("-70000"))
        assertFalse(text.contains("距阈值还有"))
        assertFalse(text.contains("负数"))
    }

    @Test fun `exact threshold reminder reports reached rather than negative or excess tokens`() {
        val text = compactionReminder(350_000, 350_000)!!.toText()
        assertTrue(text.contains("当前上下文估算 350000 / 提醒阈值 350000 token（100%）"))
        assertTrue(text.contains("已达到提醒阈值。"))
        assertFalse(text.contains("已超过阈值 0"))
        assertFalse(text.contains("负数"))
    }

    @Test fun `reminder actions keep archival querying and human approval optional`() {
        listOf(315_000L, 350_000L, 420_000L).forEach { tokens ->
            val text = compactionReminder(tokens, 350_000)!!.toText()
            assertTrue(text.contains("你可先自行记忆存档，再写自己的摘要并调用compact"))
            assertTrue(text.contains("也可直接写摘要并调用compact，或继续对话"))
            assertTrue(text.contains("记忆存档不是整理前置条件"))
            assertTrue(text.contains("不要求先查询状态或由人类确认"))
            assertTrue(text.contains("宿主不会自动压缩"))
        }
    }

    @Test fun `readable reminder stays absent below ninety percent or when disabled`() {
        listOf(0L, 1L, 100_000L, 314_999L).forEach { tokens ->
            assertNull(compactionReminder(tokens, 350_000))
        }
        listOf(0L, 315_000L, 350_000L, 420_000L).forEach { tokens ->
            assertNull(compactionReminder(tokens, 0))
        }
    }

    @Test fun `Chinese text is not halved as ascii`() {
        assertTrue(estimateCompactionTextTokens("中文".repeat(1000)) >= 2000)
        assertEquals(500L, estimateCompactionTextTokens("ab".repeat(1000)))
    }

    @Test fun `current authored text is preferred and no summary format imposed`() {
        val previous = assistant("previous unrelated answer")
        val raw = assistant("我想怎么写，就怎么写。")
        val prepared = prepareCompaction(args(), listOf(UIMessage.user("question"), previous), raw)
        assertEquals(raw.toText(), prepared.summary.toText())
        assertEquals(MessageRole.ASSISTANT, prepared.summary.role)
        assertTrue(prepared.summary.isCompactionSummary())
        assertEquals(raw.id, prepared.sourceMessageId)
        assertEquals(2, prepared.keepRecent)
    }

    @Test fun `reasoning and tool outputs are not mistaken for the summary`() {
        val raw = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = "private synthetic thought"),
                completedCall().copy(output = listOf(UIMessagePart.Text("untrusted tool output")))))
        val previous = assistant("the AI-authored summary")
        val prepared = prepareCompaction(args(), listOf(previous, raw), raw)
        assertEquals(previous.toText(), prepared.summary.toText())
    }

    @Test fun `missing summary and invalid retain counts leave input unchanged`() {
        val input = listOf(UIMessage.user("synthetic source"))
        assertTrue(runCatching { prepareCompaction(args(), input, null) }.isFailure)
        val source = listOf(assistant("my summary"))
        listOf(-1, 2).forEach { keep ->
            assertTrue(runCatching { prepareCompaction(args("{\"keep_recent\":$keep}"), source, null) }.isFailure)
        }
        assertEquals("synthetic source", input.single().toText())
        assertEquals(0, prepareCompaction(args("{\"keep_recent\":0}"), source, null).keepRecent)
    }

    @Test fun `explicit summary preserves exact text and role`() {
        val prepared = prepareCompaction(args("{\"use_last_message\":false,\"summary\":\"I am myself.\",\"keep_recent\":0}"),
            listOf(UIMessage.user("synthetic question")), null)
        assertEquals("I am myself.", prepared.summary.toText())
        assertEquals(MessageRole.ASSISTANT, prepared.summary.role)
    }

    @Test fun `keep zero retains complete compact receipt and no earlier turns`() {
        val tool = completedCall()
        val current = assistant("my summary").copy(parts = listOf(UIMessagePart.Text("my summary"), tool))
        val input = listOf(UIMessage.user("old question"), assistant("old answer"), current)
        val prepared = prepareCompaction(args("{\"keep_recent\":0}"), input, current)
        val result = buildCompactionReplacement(prepared, input, tool.toolCallId)
        assertEquals(2, result.messages.size)
        assertEquals(1, result.additionalProtocolMessages)
        assertEquals(prepared.summary.id, result.messages.first().id)
        val receipt = Json.parseToJsonElement((result.messages.last().getTools().single().output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("compacted", receipt.getValue("status").jsonPrimitive.content)
        assertEquals(result.eventId.toString(), receipt.getValue("event_id").jsonPrimitive.content)
        assertEquals(orbisCompactionSummaryHash("my summary"), receipt.getValue("summary_sha256").jsonPrimitive.content)
        assertEquals(result.summary.id.toString(), receipt.getValue("new_start_message_id").jsonPrimitive.content)
        assertTrue(receipt.getValue("created_at_ms").jsonPrimitive.long > 0)
    }

    @Test fun `post compact ring ignores pre compact provider usage`() {
        val model = Uuid.random()
        val earlier = assistant("old retained reply").copy(modelId = model,
            finishedAt = LocalDateTime(2026, 9, 23, 8, 0), usage = TokenUsage(promptTokens = 350_000, completionTokens = 20))
        val summary = prepareCompaction(args("{\"summary\":\"my compacted summary\"}"), listOf(earlier), null).summary
            .copy(createdAt = LocalDateTime(2026, 9, 23, 8, 1))
        assertTrue(estimateCurrentContext(listOf(summary, earlier), model).tokens < 1000)
        val latest = assistant("new reply").copy(modelId = model,
            finishedAt = LocalDateTime(2026, 9, 23, 8, 2), usage = TokenUsage(promptTokens = 5000, completionTokens = 100))
        assertEquals(5100L, estimateCurrentContext(listOf(summary, earlier, latest), model).tokens)
    }

    @Test fun `disabled reminder never removes compact or introduces approvals`() {
        val control = ConversationCompactionControl(0, { listOf(assistant("summary")) }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 1) })
        assertEquals(setOf("compact", "context_compaction_status", "context_compaction_history"), control.tools().map { it.name }.toSet())
        assertTrue(control.tools().all { !it.needsApproval(args()) && !it.requiresFreshApproval(args()) })
    }

    @Test fun `compaction tools inject no system status at any budget stage`() {
        val model = Model(modelId = "synthetic-model")
        val messages = listOf(UIMessage.user("synthetic question"), assistant("synthetic answer"))
        listOf(0 to 10_000L, 0 to 400_000L, 350_000 to 10_000L,
            350_000 to 315_000L, 350_000 to 400_000L).forEach { (threshold, tokens) ->
            val measured = messages.dropLast(1) + messages.last().copy(modelId = model.id,
                finishedAt = LocalDateTime(2026, 9, 23, 8, 0),
                usage = TokenUsage(promptTokens = tokens.toInt(), completionTokens = 1))
            val control = ConversationCompactionControl(threshold, { measured }, { "[]" },
                { _, replacement -> AppliedCompaction(replacement.messages, 1) })
            control.observeInput(measured, modelId = model.id)
            control.tools().forEach { tool ->
                assertEquals("${tool.name}, threshold=$threshold, tokens=$tokens",
                    "", tool.systemPrompt(model, measured))
            }
        }
    }

    @Test fun `status query remains opt in and reads live counts without side effects`() = runBlocking {
        listOf(0, 350_000).forEach { threshold ->
            var messages = listOf(UIMessage.user("synthetic question"), assistant("synthetic answer"))
            var messageReads = 0
            var historyReads = 0
            var commits = 0
            val control = ConversationCompactionControl(threshold, { messageReads++; messages },
                { historyReads++; "[]" }, { _, replacement ->
                    commits++
                    AppliedCompaction(replacement.messages, 1)
                })
            val tools = control.tools()
            tools.forEach { assertEquals("", it.systemPrompt(Model(), messages)) }
            assertEquals(0, messageReads)
            assertEquals(0, historyReads)
            assertEquals(0, commits)

            val status = tools.single { it.name == "context_compaction_status" }
            val first = Json.parseToJsonElement((status.execute(args()).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(2, first.getValue("active_messages").jsonPrimitive.int)
            assertEquals(threshold, first.getValue("reminder_threshold_tokens").jsonPrimitive.int)
            assertFalse(first.getValue("automatic_compaction").jsonPrimitive.boolean)

            messages = messages + UIMessage.user("synthetic next question")
            val second = Json.parseToJsonElement((status.execute(args()).single() as UIMessagePart.Text).text).jsonObject
            assertEquals(3, second.getValue("active_messages").jsonPrimitive.int)
            assertEquals(threshold, second.getValue("reminder_threshold_tokens").jsonPrimitive.int)
            assertTrue(messageReads > 0)
            assertEquals(0, historyReads)
            assertEquals(0, commits)
            assertTrue(tools.all { !it.needsApproval(args()) && !it.requiresFreshApproval(args()) })
        }
    }

    @Test fun `preparing compaction has no preceding status query requirement`() {
        val summary = assistant("My own synthetic summary, with no prescribed format.")
        val messages = listOf(UIMessage.user("synthetic question"), summary)
        var statusReads = 0
        val control = ConversationCompactionControl(350_000, { statusReads++; messages }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 1) })
        val compact = control.tools().single { it.name == COMPACT_TOOL_NAME }
        assertFalse(compact.needsApproval(args()))
        assertFalse(compact.requiresFreshApproval(args()))
        val prepared = prepareCompaction(args("{\"use_last_message\":true,\"keep_recent\":0}"), messages, summary)
        assertEquals(summary.toText(), prepared.summary.toText())
        assertEquals(0, prepared.keepRecent)
        assertEquals(0, statusReads)
    }

    @Test fun `ninety percent reminder boundary remains separate from ordinary tool prompts`() {
        listOf(100, 350_000, 1_000_000).forEach { threshold ->
            val boundary = threshold.toLong() * 9 / 10
            assertNull(compactionReminder(boundary - 1, threshold))
            assertTrue(compactionReminder(boundary, threshold)!!.toText().contains("已接近"))
            assertTrue(compactionReminder(threshold.toLong(), threshold)!!.toText().contains("已达到或超过"))
            assertTrue(compactionReminder(threshold.toLong() + 1, threshold)!!.toText().contains("不会自动压缩"))
        }
    }

    @Test fun `compaction below warning boundary stays quiet in continuation and next wake`() {
        val model = Model(modelId = "synthetic-model")
        val call = completedCall()
        val current = assistant("My short synthetic summary").copy(
            modelId = model.id, finishedAt = LocalDateTime(2026, 9, 23, 8, 0),
            usage = TokenUsage(promptTokens = 370_000, completionTokens = 20),
            parts = listOf(UIMessagePart.Text("My short synthetic summary"), call))
        var messages = listOf(UIMessage.user("synthetic old question"), assistant("synthetic old answer"), current)
        val control = ConversationCompactionControl(350_000, { messages }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 1) })
        assertNotNull(compactionReminder(control.observeInput(messages, modelId = model.id), control.thresholdTokens))

        val prepared = prepareCompaction(args("{\"keep_recent\":0}"), messages, current)
        messages = buildCompactionReplacement(prepared, messages, call.toolCallId).messages
        val continuationEstimate = control.observeCompactedInput(messages, "synthetic tool schema")
        assertTrue(continuationEstimate < 315_000)
        assertNull(compactionReminder(continuationEstimate, control.thresholdTokens))
        assertTrue(control.tools().all { it.systemPrompt(model, messages).isEmpty() })

        messages = messages + UIMessage.user("synthetic next wake")
        val nextControl = ConversationCompactionControl(350_000, { messages }, { "[]" },
            { _, replacement -> AppliedCompaction(replacement.messages, 2) })
        val nextEstimate = nextControl.observeInput(messages, "synthetic tool schema", model.id)
        assertTrue(nextEstimate < 315_000)
        assertNull(compactionReminder(nextEstimate, nextControl.thresholdTokens))
        assertTrue(nextControl.tools().all { it.systemPrompt(model, messages).isEmpty() })
    }
}
