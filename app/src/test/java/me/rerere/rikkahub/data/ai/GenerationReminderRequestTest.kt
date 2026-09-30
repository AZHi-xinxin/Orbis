package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.compaction.AppliedCompaction
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.ai.compaction.buildCompactionReplacement
import me.rerere.rikkahub.data.ai.compaction.isCompactionReminder
import me.rerere.rikkahub.data.ai.compaction.prepareCompaction
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic request construction only: no HTTP call, private history, or Android storage. */
class GenerationReminderRequestTest {
    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val requestMethod = ChatCompletionsAPI::class.java.getDeclaredMethod(
        "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
        ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }

    private fun initial() = listOf(
        UIMessage.system("stable synthetic identity"),
        UIMessage.user("synthetic human question"),
    )

    private fun call(id: String, name: String = "synthetic_read") = UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = "{}",
    )

    private fun completed(call: UIMessagePart.Tool, text: String = "synthetic result") = call.copy(
        output = listOf(UIMessagePart.Text(text)),
    )

    private fun response(call: UIMessagePart.Tool, usage: TokenUsage? = null) = UIMessage(
        role = MessageRole.ASSISTANT, parts = listOf(call), usage = usage,
    )

    private fun wire(messages: List<UIMessage>): JsonObject = requestMethod.invoke(
        api, messages,
        TextGenerationParams(
            model = Model(modelId = "deepseek-chat", abilities = listOf(ModelAbility.REASONING)),
            maxTokens = 4096, reasoningLevel = ReasoningLevel.HIGH,
        ),
        ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:1/v1", includeHistoryReasoning = true),
        false,
    ) as JsonObject

    private fun assertWirePrefix(before: JsonObject, after: JsonObject) {
        val previous = before.getValue("messages").jsonArray
        val next = after.getValue("messages").jsonArray
        assertTrue(next.size > previous.size)
        assertEquals(previous.toString(), JsonArray(next.take(previous.size)).toString())
        assertEquals(before.filterKeys { it != "messages" }, after.filterKeys { it != "messages" })
    }

    private fun assertOneOriginalSystem(body: JsonObject) {
        val systems = body.getValue("messages").jsonArray.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "system" }
        assertEquals("stable synthetic identity", systems.first()["content"]?.jsonPrimitive?.content)
        assertEquals(1, systems.count { it["content"]?.jsonPrimitive?.content == "stable synthetic identity" })
    }

    // Small semantic projections of ST's server.py _source_frame/_is_tool_continuation and
    // _bound_continuation_tool_call_batch (source-transition 2026-09-21). These exercise the
    // real serialized client wire only, not a service, database, signed receipt or model call.
    private fun gatewayQuery(body: JsonObject): String = body.getValue("messages").jsonArray
        .map { it.jsonObject }.last { it["role"]?.jsonPrimitive?.content == "user" }
        .getValue("content").jsonPrimitive.content.trim().take(4000)

    private fun gatewayIsToolContinuation(body: JsonObject): Boolean = body.getValue("messages").jsonArray
        .asReversed().map { it.jsonObject.getValue("role").jsonPrimitive.content }
        .firstOrNull { it !in setOf("system", "developer") } in setOf("tool", "function")

    private fun gatewayTerminalToolIds(body: JsonObject): Set<String> {
        val frames = body.getValue("messages").jsonArray.map { it.jsonObject }
        var cursor = frames.size
        val allIds = mutableSetOf<String>()
        while (cursor > 0) {
            var resultStart = cursor
            while (resultStart > 0 && frames[resultStart - 1]["role"]?.jsonPrimitive?.content == "tool") {
                resultStart--
            }
            if (resultStart == cursor || resultStart == 0) break
            val declaration = frames[resultStart - 1]
            val calls = declaration["tool_calls"] as? JsonArray ?: break
            if (declaration["role"]?.jsonPrimitive?.content != "assistant" || calls.isEmpty()) break
            val declared = calls.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val returned = frames.subList(resultStart, cursor).map { it.getValue("tool_call_id").jsonPrimitive.content }
            check(declared.toSet().size == declared.size && returned.toSet().size == returned.size)
            check(declared.toSet() == returned.toSet())
            check(declared.none { it in allIds })
            allIds.addAll(declared)
            cursor = resultStart - 1
        }
        check(allIds.isNotEmpty()) { "tail_not_tool_results" }
        return allIds
    }

    @Test
    fun belowNinetyPercentDoesNotChangeFrozenMessagesOrSerializedRequest() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val frozen = snapshot.input { initial() }
        val request = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 314_999,
        )

        assertEquals(frozen, request.messages)
        assertFalse(request.messages.any { it.isCompactionReminder() })
        assertEquals(wire(frozen), wire(request.messages))
        assertTrue(request.estimatedTokens >= 314_999)
    }

    @Test
    fun exactBoundaryAppendsOneHostSystemWithoutChangingHumanQueryOrOriginalSystem() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val frozen = snapshot.input { initial() }
        val request = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 315_000,
        )

        assertEquals(frozen, request.messages.dropLast(1))
        val reminder = request.messages.last()
        assertEquals(MessageRole.SYSTEM, reminder.role)
        assertTrue(reminder.isSynthetic)
        assertTrue(reminder.isCompactionReminder())
        assertEquals(1, request.messages.count { it.isCompactionReminder() })
        assertTrue(reminder.toText().contains("不是用户发言"))
        assertTrue(reminder.toText().contains("不会自动压缩"))
        assertWirePrefix(wire(frozen), wire(request.messages))
        assertOneOriginalSystem(wire(request.messages))
        assertEquals(gatewayQuery(wire(frozen)), gatewayQuery(wire(request.messages)))
        assertFalse(gatewayIsToolContinuation(wire(request.messages)))
        assertEquals(1, request.messages.count { it.role == MessageRole.USER })
        assertEquals(2, request.messages.count { it.role == MessageRole.SYSTEM })
    }

    @Test
    fun zeroThresholdAndIneligibleWakeStayQuietEvenAtHighEstimate() = runBlocking {
        for ((threshold, include) in listOf(0 to true, 350_000 to false)) {
            val snapshot = GenerationInputSnapshot()
            val frozen = snapshot.input { initial() }
            val request = snapshot.prepareRequest(
                thresholdTokens = threshold, includeCompactionReminder = include,
                initialEstimate = 500_000,
            )
            assertEquals(frozen, request.messages)
            assertFalse(request.messages.any { it.isCompactionReminder() })
            assertEquals(wire(frozen), wire(request.messages))
        }
    }

    @Test
    fun repeatedPreparationAndToolContinuationKeepOnlyTheOriginalNotice() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { initial() }
        val first = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 315_000,
        )
        val again = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        assertEquals(first.messages, again.messages)
        assertEquals(wire(first.messages), wire(again.messages))

        val tool = call("after-notice")
        snapshot.appendCompletedResponse(
            response(tool, TokenUsage(promptTokens = 360_000, completionTokens = 10)),
            listOf(completed(tool)),
        )
        val continued = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        assertEquals(1, continued.messages.count { it.isCompactionReminder() })
        assertEquals(first.messages.last().id, continued.messages.single { it.isCompactionReminder() }.id)
        assertWirePrefix(wire(first.messages), wire(continued.messages))
        assertOneOriginalSystem(wire(continued.messages))
    }

    @Test
    fun eachFreshHumanWakeCanRemindWithoutPersistingThePriorNotice() = runBlocking {
        val authoredHistory = initial()
        val notices = (1..2).map {
            val snapshot = GenerationInputSnapshot()
            snapshot.input { authoredHistory }
            val request = snapshot.prepareRequest(
                thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 360_000,
            )
            assertEquals(authoredHistory, request.messages.dropLast(1))
            assertEquals(1, request.messages.count { it.isCompactionReminder() })
            request.messages.single { it.isCompactionReminder() }
        }
        assertNotEquals(notices[0].id, notices[1].id)
        assertFalse(authoredHistory.any { it.isCompactionReminder() })
    }

    @Test
    fun independentProviderUsagePlusNewToolOutputCanFirstCrossTheBoundary() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { initial() }
        val before = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 310_000,
        )
        assertFalse(before.messages.any { it.isCompactionReminder() })
        val tool = call("large-result")
        snapshot.appendCompletedResponse(
            response(tool, TokenUsage(promptTokens = 310_000, completionTokens = 10)),
            listOf(completed(tool, "量".repeat(6_000))),
        )
        val crossed = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)

        assertTrue(crossed.estimatedTokens >= 316_010)
        assertEquals(1, crossed.messages.count { it.isCompactionReminder() })
        assertTrue(crossed.messages[crossed.messages.lastIndex - 1].isCompactionReminder())
        assertEquals("large-result", crossed.messages.last().getTools().single().toolCallId)
        assertWirePrefix(wire(before.messages), wire(crossed.messages))
        assertOneOriginalSystem(wire(crossed.messages))
        val frames = wire(crossed.messages).getValue("messages").jsonArray
        assertEquals("system", frames[frames.lastIndex - 2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", frames[frames.lastIndex - 1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("tool", frames.last().jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(gatewayQuery(wire(before.messages)), gatewayQuery(wire(crossed.messages)))
        assertTrue(gatewayIsToolContinuation(wire(crossed.messages)))
        assertEquals(setOf("large-result"), gatewayTerminalToolIds(wire(crossed.messages)))
    }

    @Test
    fun crossingAfterAnEarlierToolStepPreservesSentPrefixAndTheEntireNewParallelBatch() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { initial() }
        snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 300_000)
        val earlier = call("earlier-settled")
        snapshot.appendCompletedResponse(
            response(earlier, TokenUsage(promptTokens = 300_000, completionTokens = 10)), listOf(completed(earlier)),
        )
        val sent = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        val first = call("parallel-one")
        val second = call("parallel-two")
        val raw = response(first, TokenUsage(promptTokens = 316_000, completionTokens = 20))
            .copy(parts = listOf(first, second))
        val finished = raw.copy(parts = listOf(completed(first), completed(second)))
        snapshot.appendCompletedResponse(raw, finished.getTools())
        val next = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        val nextWire = wire(next.messages)
        val frames = nextWire.getValue("messages").jsonArray
        val oldFrames = wire(sent.messages).getValue("messages").jsonArray

        assertWirePrefix(wire(sent.messages), nextWire)
        assertTrue(next.messages[sent.messages.size].isCompactionReminder())
        assertEquals(wire(listOf(finished)).getValue("messages"), JsonArray(frames.drop(oldFrames.size + 1)))
        assertTrue(gatewayIsToolContinuation(nextWire))
        assertEquals(setOf("parallel-one", "parallel-two"), gatewayTerminalToolIds(nextWire))
        assertEquals(gatewayQuery(wire(sent.messages)), gatewayQuery(nextWire))
        // anchored-v1 rejects new USER frames after its original prefix; there are none.
        assertTrue(frames.drop(oldFrames.size).none { it.jsonObject["role"]?.jsonPrimitive?.content == "user" })
    }

    @Test
    fun freshSnapshotWithCompletedToolTailPlacesNoticeBeforeItsCallNotAfterItsResult() = runBlocking {
        val tool = call("resumed-complete")
        val completedResponse = response(tool).copy(parts = listOf(completed(tool)))
        val authored = initial() + completedResponse
        val snapshot = GenerationInputSnapshot()
        snapshot.input { authored }
        val next = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 315_000,
        )
        assertEquals(authored, next.messages.filterNot { it.isCompactionReminder() })
        assertTrue(next.messages[next.messages.lastIndex - 1].isCompactionReminder())
        assertEquals(completedResponse, next.messages.last())
        assertTrue(gatewayIsToolContinuation(wire(next.messages)))
        assertEquals(setOf("resumed-complete"), gatewayTerminalToolIds(wire(next.messages)))
        assertEquals(gatewayQuery(wire(authored)), gatewayQuery(wire(next.messages)))
        // This checks wire shape only; approval/restart ST wake recovery is not claimed.
    }

    @Test
    fun noticePrecedesAllAdjacentToolGroupsWithinOneNewResponse() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { initial() }
        val sent = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 310_000,
        )
        val first = call("split-group-one")
        val second = call("split-group-two")
        val bridgeText = UIMessagePart.Text("synthetic authored text before the second tool group")
        val raw = response(first, TokenUsage(promptTokens = 316_000, completionTokens = 20))
            .copy(parts = listOf(first, bridgeText, second))
        val finished = raw.copy(parts = listOf(completed(first), bridgeText, completed(second)))
        snapshot.appendCompletedResponse(raw, finished.getTools())
        val next = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        val nextWire = wire(next.messages)
        val suffix = nextWire.getValue("messages").jsonArray.drop(wire(sent.messages).getValue("messages").jsonArray.size)

        assertWirePrefix(wire(sent.messages), nextWire)
        assertEquals("system", suffix.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(wire(listOf(finished)).getValue("messages"), JsonArray(suffix.drop(1)))
        assertTrue(gatewayIsToolContinuation(nextWire))
        assertEquals(setOf("split-group-one", "split-group-two"), gatewayTerminalToolIds(nextWire))
    }

    @Test
    fun gatewayProjectionRejectsBothOldUserTailAndNaiveSystemAfterToolResult() = runBlocking {
        val tool = call("synthetic-tail-check")
        val frames = initial() + response(tool).copy(parts = listOf(completed(tool)))
        val userTail = wire(frames + UIMessage.user("synthetic host notice"))
        assertEquals("synthetic host notice", gatewayQuery(userTail))
        assertFalse(gatewayIsToolContinuation(userTail))
        assertTrue(runCatching { gatewayTerminalToolIds(userTail) }.isFailure)

        val systemTail = wire(frames + UIMessage.system("synthetic host notice"))
        assertEquals("synthetic human question", gatewayQuery(systemTail))
        assertTrue(gatewayIsToolContinuation(systemTail))
        assertTrue(runCatching { gatewayTerminalToolIds(systemTail) }.isFailure)
    }

    @Test
    fun noUsageContinuationAddsPayloadToPriorEstimateInsteadOfResettingToTinyHistory() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        snapshot.input { initial() }
        val before = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 310_000,
        )
        val tool = call("without-usage")
        snapshot.appendCompletedResponse(response(tool), listOf(completed(tool, "量".repeat(6_000))))
        val crossed = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)

        assertTrue(crossed.estimatedTokens >= before.estimatedTokens + 6_000)
        assertEquals(1, crossed.messages.count { it.isCompactionReminder() })
        assertWirePrefix(wire(before.messages), wire(crossed.messages))
    }

    @Test
    fun explicitStatusReadsTheLatestPreparedRequestEstimateWithoutCommittingAnything() = runBlocking {
        val authoredHistory = initial()
        var commits = 0
        var historyReads = 0
        val control = ConversationCompactionControl(
            thresholdTokens = 350_000,
            readMessages = { authoredHistory },
            readHistory = { historyReads++; "[]" },
            commit = { _, replacement -> commits++; AppliedCompaction(replacement.messages, 1) },
        )
        val snapshot = GenerationInputSnapshot()
        snapshot.input { authoredHistory }
        val before = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 310_000,
        )
        control.observeRequestEstimate(before.estimatedTokens)
        val status = control.tools().single { it.name == "context_compaction_status" }
        suspend fun statusEstimate(): Long {
            val output = status.execute(JsonObject(emptyMap())).single() as UIMessagePart.Text
            return Json.parseToJsonElement(output.text).jsonObject.getValue("estimated_tokens").jsonPrimitive.long
        }
        assertEquals(before.estimatedTokens, statusEstimate())

        val tool = call("status-large-result")
        snapshot.appendCompletedResponse(
            response(tool, TokenUsage(promptTokens = 310_000, completionTokens = 10)),
            listOf(completed(tool, "量".repeat(6_000))),
        )
        val next = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        control.observeRequestEstimate(next.estimatedTokens)
        assertTrue(next.estimatedTokens > before.estimatedTokens)
        assertEquals(next.estimatedTokens, statusEstimate())
        assertEquals(0, commits)
        assertEquals(0, historyReads)
    }

    @Test
    fun acceptedCompactionDropsOldAnchorAndNoticeWithoutRearmingTheSameWake() = runBlocking {
        val snapshot = GenerationInputSnapshot()
        val authoredHistory = initial()
        snapshot.input { authoredHistory }
        val warned = snapshot.prepareRequest(
            thresholdTokens = 350_000, includeCompactionReminder = true, initialEstimate = 360_000,
        )
        assertEquals(1, warned.messages.count { it.isCompactionReminder() })

        val compactCall = call("compact-now", "compact")
        val rawResponse = response(compactCall, TokenUsage(promptTokens = 360_000, completionTokens = 10))
            .copy(parts = listOf(UIMessagePart.Text("short synthetic summary"), compactCall))
        val completedResponse = rawResponse.copy(parts = listOf(
            UIMessagePart.Text("short synthetic summary"), completed(compactCall),
        ))
        val visible = authoredHistory.drop(1) + completedResponse
        val prepared = prepareCompaction(
            Json.parseToJsonElement("{\"use_last_message\":true,\"keep_recent\":0}"), visible, rawResponse,
        )
        val replacement = buildCompactionReplacement(prepared, visible, compactCall.toolCallId)
        val results = replacement.messages.last().getTools()
        val rebased = snapshot.prepareCompactionInput(replacement.messages, rawResponse, results, rawResponse.id)
        snapshot.acceptCompactionInput(rebased, rawResponse, results, rawResponse.id)
        val after = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)

        assertTrue(after.estimatedTokens < 315_000)
        assertFalse(after.messages.any { it.isCompactionReminder() })
        assertOneOriginalSystem(wire(after.messages))

        // A later large response in this same wake must not issue a second notice after rebase.
        val later = call("post-compact-crossing")
        snapshot.appendCompletedResponse(
            response(later, TokenUsage(promptTokens = 320_000, completionTokens = 10)),
            listOf(completed(later)),
        )
        val crossedAgain = snapshot.prepareRequest(thresholdTokens = 350_000, includeCompactionReminder = true)
        assertTrue(crossedAgain.estimatedTokens >= 320_010)
        assertFalse(crossedAgain.messages.any { it.isCompactionReminder() })
    }
}
