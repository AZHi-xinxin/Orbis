package me.rerere.rikkahub.data.ai

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.core.Tool
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.compaction.AppliedCompaction
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.ai.compaction.isCompactionSummary
import me.rerere.rikkahub.data.ai.compaction.estimateCurrentContext
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/**
 * Real GenerationLoop -> ProviderManager -> OpenAIProvider -> on-device HTTP, with no mocks.
 * Use IsolatedGenerationLoopRunner; do not run the app's other instrumented persistence tests.
 * This fixture never obtains SettingsStore, Koin, a database, real tools, or a real API credential.
 */
@RunWith(AndroidJUnit4::class)
class GenerationLoopLoopbackTest {
    @Test(timeout = 30_000)
    fun streamingConsultationEmitsOneExactTerminalResponseAfterToolContinuations() = runBlocking<Unit> {
        terminalEvidenceChain(streaming = true)
    }

    @Test(timeout = 30_000)
    fun nonStreamingConsultationEmitsOneExactTerminalResponseAfterToolContinuations() = runBlocking<Unit> {
        terminalEvidenceChain(streaming = false)
    }

    private suspend fun terminalEvidenceChain(streaming: Boolean) {
        fixture(streaming, approval = false, expectedRequests = 4) { fixture ->
            val final = fixture.collect(emitTerminalEvidence = true, durableCheckpoints = true)
            assertEquals(listOf(1, 2, 3), fixture.executions.toList())
            assertEquals(1, fixture.terminalEvidence.size)
            val evidence = fixture.terminalEvidence.single()
            assertEquals("synthetic final answer", evidence.text)
            assertEquals(final.last().id.toString(), evidence.messageId)
            assertTrue(final.last().getTools().all { it.isExecuted })
            assertTrue(matchesGenerationTerminalEvidence(evidence, final.last(), 0))
            assertFalse(evidence.text.contains("LATE_TRIGGER"))
            assertFalse(evidence.text.contains("reasoning"))
            assertFalse(evidence.text.contains("tool result"))
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun stepLimitAfterAnExecutedToolNeverProducesTerminalEvidence() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 1) { fixture ->
                val final = fixture.collect(emitTerminalEvidence = true, maxSteps = 1, durableCheckpoints = true)
                assertEquals(listOf(1), fixture.executions.toList())
                assertTrue(final.last().getTools().single().isExecuted)
                assertTrue(fixture.terminalEvidence.isEmpty())
                assertEquals(0, fixture.context.privateStorageAccesses.get())
            }
        }
    }

    @Test(timeout = 30_000)
    fun ordinaryDefaultGenerationDoesNotEmitTerminalEvidence() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 4) { fixture ->
                val final = fixture.collect()
                assertEquals(listOf(1, 2, 3), fixture.executions.toList())
                assertTrue(final.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("synthetic final answer") })
                assertTrue(fixture.terminalEvidence.isEmpty())
            }
        }
    }

    @Test(timeout = 30_000)
    fun pendingApprovalDoesNotProduceTerminalEvidence() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = true, expectedRequests = 1) { fixture ->
                val final = fixture.collect(emitTerminalEvidence = true)
                assertTrue(final.last().getTools().single().isPending)
                assertTrue(fixture.executions.isEmpty())
                assertTrue(fixture.terminalEvidence.isEmpty())
            }
        }
    }

    @Test(timeout = 30_000)
    fun interruptedContinuationDoesNotProduceTerminalEvidenceOrReplayTools() = runBlocking<Unit> {
        fixture(streaming = true, approval = false, expectedRequests = 2, emptyContinuation = true) { fixture ->
            assertTrue(runCatching { fixture.collect(emitTerminalEvidence = true) }.isFailure)
            assertEquals(listOf(1), fixture.executions.toList())
            assertTrue(fixture.terminalEvidence.isEmpty())
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun terminalEvidenceUsesThinkTagConversionWithoutSendingPrivateReasoning() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 4,
                finalText = "<think>synthetic private final thought</think>synthetic final answer") { fixture ->
                val final = fixture.collect(emitTerminalEvidence = true, outputTransformers = listOf(ThinkTagTransformer))
                val evidence = fixture.terminalEvidence.single()
                assertEquals("synthetic final answer", evidence.text)
                assertTrue(matchesGenerationTerminalEvidence(evidence, final.last(), 0))
                assertFalse(evidence.text.contains("private"))
                assertFalse(evidence.text.contains("<think>"))
                assertEquals(0, fixture.context.privateStorageAccesses.get())
            }
        }
    }

    @Test(timeout = 30_000)
    fun consultationWaitsOnlyForProvenPreGenerationBusyWithoutRepeatingTools() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 5, busyFirst = true) { fixture ->
                val result = fixture.collect(consultationBusyWaitUntilMillis = System.currentTimeMillis() + 10_000)
                assertEquals(listOf(1, 2, 3), fixture.executions.toList())
                assertTrue(result.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("synthetic final answer") })
                assertEquals(fixture.server.requests[0].encodedMessages, fixture.server.requests[1].encodedMessages)
                assertEquals(1, fixture.transforms.get())
            }
        }
    }

    @Test(timeout = 30_000)
    fun ordinaryChatAndExpiredConsultationDoNotAutoReplayBusy() = runBlocking<Unit> {
        for (deadline in listOf<Long?>(null, 1L)) {
            fixture(streaming = true, approval = false, expectedRequests = 1, busyFirst = true) { fixture ->
                val failure = runCatching { fixture.collect(consultationBusyWaitUntilMillis = deadline) }.exceptionOrNull()
                assertTrue(failure is me.rerere.ai.util.HttpException)
                assertTrue(fixture.executions.isEmpty())
            }
        }
    }
    @Test(timeout = 30_000)
    fun failedBeforeToolCheckpointDoesNotExecuteOrRetry() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 1,
                batchWithEmptyResult = true, enableAutoRetry = true) { fixture ->
                val boundaries = mutableListOf<String>()
                val failure = runCatching {
                    fixture.collect(durableCheckpoints = true, onDurableBoundary = { chunk ->
                        assertEquals(false, chunk.toolCompleted)
                        assertEquals("synthetic-call-1", chunk.toolCallId)
                        assertFalse(chunk.messages.last().getTools().first().isExecuted)
                        boundaries += "start1"
                        delay(30)
                        assertTrue(fixture.executions.isEmpty())
                        chunk.result.completeExceptionally(IOException("synthetic checkpoint write failed"))
                    })
                }.exceptionOrNull()
                assertTrue(failure is GenerationDurabilityException)
                assertEquals(listOf("start1"), boundaries)
                assertTrue(fixture.executions.isEmpty())
                assertEquals(1, fixture.server.requests.size)
                assertEquals(0, fixture.context.privateStorageAccesses.get())
            }
        }
    }

    @Test(timeout = 30_000)
    fun failedFirstToolReceiptDoesNotExecuteSecondToolOrRetryFirst() = runBlocking<Unit> {
        for (streaming in listOf(true, false)) {
            fixture(streaming, approval = false, expectedRequests = 1,
                batchWithEmptyResult = true, enableAutoRetry = true) { fixture ->
                val boundaries = mutableListOf<String>()
                val failure = runCatching {
                    fixture.collect(durableCheckpoints = true, onDurableBoundary = { chunk ->
                        assertEquals("synthetic-call-1", chunk.toolCallId)
                        if (chunk.toolCompleted == true) {
                            boundaries += "receipt1"
                            assertTrue(chunk.messages.last().getTools().first().isExecuted)
                            delay(30)
                            assertEquals(listOf(1), fixture.executions.toList())
                            chunk.result.completeExceptionally(IOException("synthetic receipt write failed"))
                        } else {
                            boundaries += "start1"
                            assertTrue(fixture.executions.isEmpty())
                            chunk.result.complete(Unit)
                        }
                    })
                }.exceptionOrNull()
                assertTrue(failure is GenerationDurabilityException)
                assertEquals(listOf("start1", "receipt1"), boundaries)
                assertEquals(listOf(1), fixture.executions.toList())
                assertEquals(1, fixture.server.requests.size)
                assertEquals(0, fixture.context.privateStorageAccesses.get())
            }
        }
    }

    @Test(timeout = 30_000)
    fun streamingBatchWaitsForEveryDurableAcknowledgement() = runBlocking<Unit> {
        durableBatch(streaming = true)
    }

    @Test(timeout = 30_000)
    fun nonStreamingBatchWaitsForEveryDurableAcknowledgement() = runBlocking<Unit> {
        durableBatch(streaming = false)
    }

    private suspend fun durableBatch(streaming: Boolean) {
        fixture(streaming, approval = false, expectedRequests = 2,
            batchWithEmptyResult = true, enableAutoRetry = true) { fixture ->
            val events = Collections.synchronizedList(mutableListOf<String>())
            fixture.afterToolExecution = { step -> events += "execute$step" }
            val final = fixture.collect(durableCheckpoints = true, onDurableBoundary = { chunk ->
                val step = checkNotNull(chunk.toolCallId).substringAfterLast('-').toInt()
                assertEquals("synthetic_tool", chunk.toolName)
                assertEquals(0L, chunk.contextEpoch)
                val completed = checkNotNull(chunk.toolCompleted)
                events += if (completed) "receipt$step" else "start$step"
                val expectedExecuted = (1..if (completed) step else step - 1).toList()
                assertEquals(expectedExecuted, fixture.executions.toList())
                assertEquals(completed,
                    chunk.messages.last().getTools().single { it.toolCallId == chunk.toolCallId }.isExecuted)
                // Acceptance by collect is not an ACK: neither the next tool nor provider request
                // may get ahead while this simulated disk write is still outstanding.
                delay(30)
                assertEquals(expectedExecuted, fixture.executions.toList())
                assertEquals(1, fixture.server.requests.size)
                chunk.result.complete(Unit)
            })
            assertEquals(listOf("start1", "execute1", "receipt1", "start2", "execute2", "receipt2"), events.toList())
            assertEquals(listOf(1, 2), fixture.executions.toList())
            assertTrue(final.last().getTools().all { it.isExecuted })
            assertEquals(2, fixture.server.requests.size)
            assertEquals(2, fixture.server.requests.last().json.getValue("messages").jsonArray
                .count { it.jsonObject["role"] == JsonPrimitive("tool") })
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun cancelledBeforeToolAcknowledgementDoesNotExecuteOrRetry() = runBlocking<Unit> {
        fixture(streaming = true, approval = false, expectedRequests = 1,
            batchWithEmptyResult = true, enableAutoRetry = true) { fixture ->
            val boundaries = mutableListOf<String?>()
            val failure = runCatching {
                fixture.collect(durableCheckpoints = true, onDurableBoundary = { chunk ->
                    assertEquals(false, chunk.toolCompleted)
                    boundaries += chunk.toolCallId
                    chunk.result.cancel(CancellationException("synthetic cancellation before ACK"))
                })
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(listOf("synthetic-call-1"), boundaries)
            assertTrue(fixture.executions.isEmpty())
            assertEquals(1, fixture.server.requests.size)
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun cancellationAfterToolStartedLeavesUnknownWithoutReceiptContinuationOrRetry() = runBlocking<Unit> {
        interruptedToolResult(CancellationException("synthetic cancellation after external effect"))
    }

    @Test(timeout = 30_000)
    fun transportFailureAfterToolStartedLeavesUnknownWithoutReceiptContinuationOrRetry() = runBlocking<Unit> {
        interruptedToolResult(IOException("synthetic transport failed after external effect"))
    }

    private suspend fun interruptedToolResult(interruption: Exception) {
        fixture(streaming = true, approval = false, expectedRequests = 1,
            batchWithEmptyResult = true, enableAutoRetry = true) { fixture ->
            val boundaries = mutableListOf<Pair<String?, Boolean?>>()
            // The fake effect is the in-memory counter only. A failure after it happened must not
            // be advertised as a completed receipt: the recovery journal owns UNKNOWN handling.
            fixture.afterToolExecution = { throw interruption }
            val failure = runCatching {
                fixture.collect(durableCheckpoints = true, onDurableBoundary = { chunk ->
                    boundaries += chunk.toolCallId to chunk.toolCompleted
                    chunk.result.complete(Unit)
                })
            }.exceptionOrNull()
            assertTrue("A possibly executed tool must stop the generation", failure != null)
            if (interruption is CancellationException) assertTrue(failure is CancellationException)
            assertEquals(listOf("synthetic-call-1" to false), boundaries)
            assertEquals(listOf(1), fixture.executions.toList())
            assertEquals(1, fixture.server.requests.size)
            assertFalse(fixture.lastPublished.flatMap { it.getTools() }.any { it.isExecuted })
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun gatewayReasoningOnlyContinuationDoesNotReplayPreviouslyExecutedTool() = runBlocking<Unit> {
        fixture(streaming = true, approval = false, expectedRequests = 2, emptyContinuation = true, enableAutoRetry = true) { fixture ->
            val failure = runCatching { fixture.collect(collectorDelayMs = 20) }.exceptionOrNull()
            assertTrue(failure is KnownEmptyCompletionFailure)
            assertEquals(listOf(1), fixture.executions.toList())
            assertEquals(2, fixture.server.requests.size)
            val continued = fixture.server.requests.last().json.getValue("messages").jsonArray
            assertTrue(continued.any { it.jsonObject["role"] == JsonPrimitive("tool") })
            // The completed write's receipt remains in the last emitted state, even though the
            // next provider response was empty. ChatService must persist this on its failure path.
            assertTrue(fixture.lastPublished.flatMap { it.getTools() }.any {
                it.toolCallId == "synthetic-call-1" && it.isExecuted
            })
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun streamingToolUsageCrossesReminderLineWithoutCreatingAHumanTurn() = runBlocking<Unit> {
        reminderChain(streaming = true)
    }

    @Test(timeout = 30_000)
    fun nonStreamingToolUsageCrossesReminderLineWithoutCreatingAHumanTurn() = runBlocking<Unit> {
        reminderChain(streaming = false)
    }

    private suspend fun reminderChain(streaming: Boolean) {
        fixture(streaming, approval = false, expectedRequests = 4, reminderOnly = true) { fixture ->
            val final = fixture.collect()
            val requests = fixture.server.requests.toList()
            assertFalse(requests.first().encodedMessages.contains("Orbis 上下文用量参考"))
            assertEquals(listOf(1, 2, 3), fixture.executions.toList())
            requests.zipWithNext().forEach { (before, after) ->
                assertTrue("The actual previous request must remain an exact serialized prefix",
                    after.encodedMessages.startsWith(before.encodedMessages.dropLast(1) + ","))
            }
            requests.drop(1).forEach { request ->
                val messages = request.json.getValue("messages").jsonArray.map { it.jsonObject }
                val notice = messages.withIndex().filter {
                    it.value["content"].toString().contains("Orbis 上下文用量参考")
                }.single()
                assertEquals(JsonPrimitive("system"), notice.value["role"])
                assertEquals("synthetic current human", messages.last {
                    it["role"] == JsonPrimitive("user")
                }.getValue("content").jsonPrimitive.content)
                assertEquals(JsonPrimitive("tool"), messages.last()["role"])
                val callIndex = messages.indexOfFirst { it["tool_calls"] != null }
                assertTrue("The host notice must precede the complete assistant/tool unit", notice.index < callIndex)
                messages.withIndex().filter { it.value["tool_calls"] != null }.forEach { (index, call) ->
                    val expectedIds = call.getValue("tool_calls").jsonArray.map { it.jsonObject.getValue("id") }
                    val receipts = messages.drop(index + 1).take(expectedIds.size)
                    assertTrue(receipts.all { it["role"] == JsonPrimitive("tool") })
                    assertEquals(expectedIds, receipts.map { it["tool_call_id"] })
                }
            }
            assertEquals(1, fixture.transforms.get())
            assertEquals(0, fixture.compactionCommitAttempts.get())
            assertTrue(fixture.lastCompactionEstimate >= 315_000)
            assertFalse(final.any { it.toText().contains("Orbis 上下文用量参考") })
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun streamingCompactionSameBatchUsesLatestRawTextAndContinuesWithPairedReceipts() = runBlocking<Unit> {
        compactChain(streaming = true, mode = "ok")
    }

    @Test(timeout = 30_000)
    fun nonStreamingCompactionZeroKeepStillRetainsProtocolTailAndContinues() = runBlocking<Unit> {
        compactChain(streaming = false, mode = "zero")
    }

    @Test(timeout = 30_000)
    fun failedCompactionCommitKeepsOriginalContextAndSameBatchWriteResult() = runBlocking<Unit> {
        compactChain(streaming = true, mode = "commit_failure")
    }

    @Test(timeout = 30_000)
    fun compactionExplicitKeepOverflowReturnsCorrectableErrorWithoutCommit() = runBlocking<Unit> {
        compactChain(streaming = false, mode = "bad_keep")
    }

    private suspend fun compactChain(streaming: Boolean, mode: String) {
        fixture(streaming, approval = false, expectedRequests = 2, compactionMode = mode) { fixture ->
            val final = fixture.collect()
            val success = mode == "ok" || mode == "zero"
            assertEquals(listOf(1), fixture.executions.toList()) // Same-batch peer executes exactly once.
            assertEquals(1, fixture.transforms.get())
            assertEquals(if (mode == "bad_keep") 0 else 1, fixture.compactionCommitAttempts.get())
            assertEquals(success, final.first().isCompactionSummary())
            if (success) {
                assertEquals("RAW SUMMARY {battery_level}", final.first().toText())
                assertEquals(2, final.size)
                // Non-stream providers normalize omitted usage to zero; streaming leaves it null.
                // Neither may inherit the pre-compaction 350K measurement.
                assertEquals(0, final.last().usage?.promptTokens ?: 0)
                assertEquals(0, final.last().usage?.completionTokens ?: 0)
                assertTrue(estimateCurrentContext(final).tokens < 350_000)
                assertTrue(fixture.lastCompactionEstimate < 350_000)
                assertFalse(fixture.server.requests[1].encodedMessages.contains("synthetic earlier human"))
                assertFalse(fixture.server.requests[1].encodedMessages.contains("Orbis 上下文用量参考"))
            } else {
                assertEquals(fixture.initial, final.take(fixture.initial.size))
                assertTrue(fixture.server.requests[1].encodedMessages.contains("synthetic earlier human"))
            }
            val secondWire = fixture.server.requests[1].json.getValue("messages").jsonArray.map { it.jsonObject }
            val results = secondWire.filter { it["role"] == JsonPrimitive("tool") }
            assertEquals(listOf("synthetic-compact", "synthetic-call-1"), results.map { it.getValue("tool_call_id").jsonPrimitive.content })
            assertEquals("synthetic tool result 1", results[1].getValue("content").jsonPrimitive.content)
            val compactResult = Json.parseToJsonElement(results[0].getValue("content").jsonPrimitive.content).jsonObject
            assertEquals(if (success) "compacted" else "not_compacted", compactResult.getValue("status").jsonPrimitive.content)
            if (mode == "zero") assertEquals(1, compactResult.getValue("additional_protocol_messages").jsonPrimitive.int)
            assertTrue(final.last().toText().contains("synthetic final answer"))
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun streamingThreeToolsPreserveActualRequestPrefixAndMergedUi() = runBlocking<Unit> {
        automaticChain(streaming = true)
    }

    @Test(timeout = 30_000)
    fun nonStreamingThreeToolsPreserveActualRequestPrefixAndMergedUi() = runBlocking<Unit> {
        automaticChain(streaming = false)
    }

    @Test(timeout = 30_000)
    fun manualApprovalPausesWithoutExecutingToolOrSendingAnotherRequest() = runBlocking<Unit> {
        fixture(streaming = true, approval = true, expectedRequests = 1) { fixture ->
            val final = fixture.collect()
            assertEquals(1, fixture.server.requests.size)
            assertTrue(fixture.executions.isEmpty())
            val pending = final.last().getTools().single()
            assertTrue(pending.approvalState is ToolApprovalState.Pending)
            assertFalse(pending.isExecuted)
            assertEquals(1, fixture.transforms.get())
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun sameBatchEmptyResultRetainsBothToolReceiptsAndContinuesToText() = runBlocking<Unit> {
        fixture(streaming = true, approval = false, expectedRequests = 2, batchWithEmptyResult = true) { fixture ->
            val final = fixture.collect()
            assertEquals(listOf(1, 2), fixture.executions.toList())
            assertEquals(2, fixture.server.requests.size)
            val tools = final.last().getTools()
            assertEquals(listOf("synthetic-call-1", "synthetic-call-2"), tools.map { it.toolCallId })
            assertTrue(tools.all { it.isExecuted })
            assertEquals("synthetic tool result 1", (tools[0].output.single() as UIMessagePart.Text).text)
            val emptyReceipt = tools[1].output.single() as UIMessagePart.Text
            assertEquals("[Host receipt: tool invocation returned no content.]", emptyReceipt.text)
            assertEquals(JsonPrimitive(true), emptyReceipt.metadata?.get("host_empty_tool_result"))
            assertTrue(final.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("synthetic final answer") })
            assertEquals(fixture.initial, final.take(fixture.initial.size))
            assertEquals(fixture.initial.size + 1, final.size)
            val requests = fixture.server.requests.toList()
            assertTrue(requests[1].encodedMessages.startsWith(requests[0].encodedMessages.dropLast(1) + ","))
            val wireResults = requests[1].json.getValue("messages").jsonArray.map { it.jsonObject }
                .filter { it["role"] == JsonPrimitive("tool") }
            assertEquals(2, wireResults.size)
            assertEquals(listOf("synthetic-call-1", "synthetic-call-2"), wireResults.map { it.getValue("tool_call_id").jsonPrimitive.content })
            assertEquals("synthetic tool result 1", wireResults[0].getValue("content").jsonPrimitive.content)
            assertEquals(emptyReceipt.text, wireResults[1].getValue("content").jsonPrimitive.content)
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun mixedBatchResumeRunsAutoAndApprovedOnceButNeverDenied() = runBlocking<Unit> {
        fixture(streaming = false, approval = false, expectedRequests = 2, mixedApprovalBatch = true) { fixture ->
            val paused = fixture.collect()
            assertEquals(1, fixture.server.requests.size)
            assertTrue(fixture.executions.isEmpty())
            val calls = paused.last().getTools()
            assertTrue(calls[0].approvalState is ToolApprovalState.Auto)
            assertTrue(calls[1].isPending && calls[2].isPending)
            assertTrue(calls[1].hostApproval != null && calls[1].approvalInputFingerprint != null)
            val resumed = paused.dropLast(1) + paused.last().copy(parts = paused.last().parts.map { part ->
                if (part !is UIMessagePart.Tool) part else when (part.toolCallId) {
                    "synthetic-call-2" -> part.copy(approvalState = ToolApprovalState.Approved)
                    "synthetic-call-3" -> part.copy(approvalState = ToolApprovalState.Denied("synthetic no"))
                    else -> part
                }
            })
            val final = fixture.collect(resumed)
            assertEquals(listOf(1, 2), fixture.executions.toList())
            assertEquals(2, fixture.server.requests.size)
            assertTrue(final.last().getTools().all { it.isExecuted })
            val wireResults = fixture.server.requests[1].json.getValue("messages").jsonArray
                .map { it.jsonObject }.filter { it["role"] == JsonPrimitive("tool") }
            assertEquals(listOf("synthetic-call-1", "synthetic-call-2", "synthetic-call-3"),
                wireResults.map { it.getValue("tool_call_id").jsonPrimitive.content })
            assertTrue(wireResults[2].getValue("content").jsonPrimitive.content.contains("denied"))
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    @Test(timeout = 30_000)
    fun approvalRevokedBeforeExecutionPausesWithoutContinuation() = runBlocking<Unit> {
        fixture(streaming = false, approval = false, expectedRequests = 1, revokeBeforeExecution = true) { fixture ->
            val final = fixture.collect()
            assertEquals(1, fixture.server.requests.size)
            assertTrue(fixture.executions.isEmpty())
            assertTrue(final.last().getTools().single().isPending)
            assertEquals(0, fixture.context.privateStorageAccesses.get())
        }
    }

    private suspend fun automaticChain(streaming: Boolean) {
        fixture(streaming, approval = false, expectedRequests = 4) { fixture ->
            val final = fixture.collect()
            val requests = fixture.server.requests.toList()
            assertEquals(4, requests.size)
            assertEquals(listOf(1, 2, 3), fixture.executions.toList())
            assertEquals(1, fixture.transforms.get())
            assertEquals(1, fixture.schemas.get())
            assertEquals(1, fixture.systemPrompts.get())
            assertEquals(0, fixture.context.privateStorageAccesses.get())

            requests.zipWithNext().forEach { (previous, next) ->
                val before = previous.json.getValue("messages").jsonArray
                val after = next.json.getValue("messages").jsonArray
                assertEquals(before, JsonArray(after.take(before.size)))
                // Compare the original captured JSON substring too, not only a normalized parse.
                assertTrue(next.encodedMessages.startsWith(previous.encodedMessages.dropLast(1) + ","))
                assertEquals(
                    JsonObject(previous.json.filterKeys { it != "messages" }),
                    JsonObject(next.json.filterKeys { it != "messages" }),
                )
            }
            requests.forEachIndexed { index, request ->
                val messages = request.json.getValue("messages").jsonArray
                assertEquals(index, messages.count { it.jsonObject["role"] == JsonPrimitive("tool") })
                messages.map { it.jsonObject }.filter { "tool_calls" in it }.forEach {
                    assertEquals(1, it.getValue("tool_calls").jsonArray.size)
                }
                assertTrue(request.encodedMessages.contains("synthetic-dynamic-1"))
                assertFalse(request.encodedMessages.contains("SHOULD_ONLY_ACTIVATE_ON_A_NEW_HUMAN_WAKE"))
                assertEquals(128, request.json.getValue("max_tokens").jsonPrimitive.int)
            }

            // No rewritten/persisted authors, and the original one-bubble UI representation survives.
            assertEquals(fixture.initial, final.take(fixture.initial.size))
            assertEquals(fixture.initial.size + 1, final.size)
            assertEquals(MessageRole.ASSISTANT, final.last().role)
            assertEquals(listOf("synthetic-call-1", "synthetic-call-2", "synthetic-call-3"), final.last().getTools().map { it.toolCallId })
            assertTrue(final.last().getTools().all { it.isExecuted })
            assertTrue(final.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("synthetic final answer") })
        }
    }

    private suspend fun fixture(
        streaming: Boolean,
        approval: Boolean,
        expectedRequests: Int,
        batchWithEmptyResult: Boolean = false,
        mixedApprovalBatch: Boolean = false,
        revokeBeforeExecution: Boolean = false,
        compactionMode: String? = null,
        reminderOnly: Boolean = false,
        emptyContinuation: Boolean = false,
        enableAutoRetry: Boolean = false,
        busyFirst: Boolean = false,
        finalText: String = "synthetic final answer",
        block: suspend (Fixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner) { "Select the isolated generation-loop test runner." }
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java) {
            "Synthetic fixture must not start the real application."
        }
        val context = NoPrivateStorageContext(instrumentation.context)
        val server = LoopbackFixture(expectedRequests, batchWithEmptyResult, mixedApprovalBatch, compactionMode, reminderOnly, emptyContinuation, busyFirst, finalText)
        val requestBudget = AtomicInteger()
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .dns { name ->
                if (name != "127.0.0.1") throw IOException("Fixture disallows DNS/non-loopback destinations.")
                listOf(LOOPBACK)
            }
            .addInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != "http" || url.host != "127.0.0.1" || url.port != server.port ||
                    url.encodedPath != "/v1/chat/completions" || url.query != null ||
                    chain.request().method != "POST" || requestBudget.incrementAndGet() > expectedRequests
                ) throw IOException("Fixture endpoint or request budget violation.")
                chain.proceed(chain.request())
            }
            .build()
        try {
            val model = Model(modelId = "synthetic-cache-probe", displayName = "Synthetic only", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING))
            val lorebook = Lorebook(entries = listOf(PromptInjection.RegexInjection(
                keywords = listOf("LATE_TRIGGER"),
                content = "SHOULD_ONLY_ACTIVATE_ON_A_NEW_HUMAN_WAKE",
                scanDepth = 20,
            )))
            val assistant = Assistant(
                chatModelId = model.id,
                systemPrompt = "Synthetic local HTTP fixture. No real account or memory.",
                contextMessageLimit = 3,
                streamOutput = streaming,
                enableMemory = true,
                maxTokens = 128,
                localTools = emptyList(),
                lorebookIds = setOf(lorebook.id),
            )
            val provider = ProviderSetting.OpenAI(
                name = "Synthetic loopback only", models = listOf(model), apiKey = "",
                baseUrl = "http://127.0.0.1:${server.port}/v1", includeHistoryReasoning = true,
            )
            // Empty key makes LruKeyRoulette return before even requesting cacheDir. The context
            // below additionally throws AssertionError if any private-storage path is requested.
            val settings = Settings(
                providers = listOf(provider), assistants = listOf(assistant),
                chatModelId = model.id, assistantId = assistant.id,
                networkSetting = NetworkSetting(enableAutoRetry = enableAutoRetry),
                enableSuggestion = false, modeInjections = emptyList(), lorebooks = listOf(lorebook),
                searchServices = emptyList(), ttsProviders = emptyList(),
            )
            val fixture = Fixture(context, server, client, settings, assistant, model, approval, batchWithEmptyResult,
                mixedApprovalBatch, revokeBeforeExecution, compactionMode, reminderOnly)
            withTimeout(20_000) { block(fixture) }
            server.assertHealthy()
            assertEquals(expectedRequests, requestBudget.get())
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.close()
        }
    }

    private class Fixture(
        val context: NoPrivateStorageContext,
        val server: LoopbackFixture,
        client: OkHttpClient,
        private val settings: Settings,
        private val assistant: Assistant,
        private val model: Model,
        approval: Boolean,
        batchWithEmptyResult: Boolean,
        mixedApprovalBatch: Boolean,
        revokeBeforeExecution: Boolean,
        private val compactionMode: String?,
        private val reminderOnly: Boolean,
    ) {
        val executions = Collections.synchronizedList(mutableListOf<Int>())
        var afterToolExecution: suspend (Int) -> Unit = {}
        val transforms = AtomicInteger()
        val schemas = AtomicInteger()
        val systemPrompts = AtomicInteger()
        val compactionCommitAttempts = AtomicInteger()
        var lastCompactionEstimate: Long = 0
        val terminalEvidence = mutableListOf<GenerationTerminalEvidence>()
        private val approvalChecks = AtomicInteger()
        val initial = listOf(UIMessage.user("synthetic earlier human"), UIMessage.assistant("synthetic earlier assistant"), UIMessage.user("synthetic current human"))
        var lastPublished: List<UIMessage> = initial
            private set
        private val manager = ProviderManager(client, context)
        private val loop = GenerationLoop(context, manager, Json)
        private val tool = Tool(
            name = "synthetic_tool", description = "Synthetic local counter only.",
            parameters = {
                schemas.incrementAndGet()
                InputSchema.Obj(JsonObject(mapOf("step" to JsonObject(mapOf("type" to JsonPrimitive("integer"))))), listOf("step"))
            },
            systemPrompt = { _, _ -> "synthetic-tool-prompt-${systemPrompts.incrementAndGet()}" },
            needsApproval = { args ->
                if (revokeBeforeExecution) approvalChecks.incrementAndGet() > 1
                else approval || (mixedApprovalBatch && args.jsonObject.getValue("step").jsonPrimitive.int >= 2)
            },
            hostApproval = HostToolApproval("synthetic_tool", "fixture-v1", "Synthetic local counter"),
            execute = { args ->
                val step = args.jsonObject.getValue("step").jsonPrimitive.int
                check(step in 1..3 && step !in executions) { "Synthetic tool repeated or out of range." }
                executions.add(step)
                afterToolExecution(step)
                if (batchWithEmptyResult && step == 2) emptyList()
                else listOf(UIMessagePart.Text("synthetic tool result $step"))
            },
        )
        private val changingInput = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
                val marker = "synthetic-dynamic-${transforms.incrementAndGet()}"
                return messages.map { message ->
                    if (message.role == MessageRole.SYSTEM) message.copy(parts = message.parts + UIMessagePart.Text(marker)) else message
                }
            }
        }

        suspend fun collect(
            from: List<UIMessage> = initial,
            collectorDelayMs: Long = 0,
            durableCheckpoints: Boolean = false,
            consultationBusyWaitUntilMillis: Long? = null,
            emitTerminalEvidence: Boolean = false,
            maxSteps: Int = 4,
            outputTransformers: List<OutputMessageTransformer> = emptyList(),
            onDurableBoundary: suspend (GenerationChunk.DurableBoundary) -> Unit = { it.result.complete(Unit) },
        ): List<UIMessage> {
            terminalEvidence.clear() // Evidence belongs to this collection, never an approval retry.
            var final = from
            val compaction = if (compactionMode != null || reminderOnly) ConversationCompactionControl(
                thresholdTokens = if (reminderOnly) 350_000 else 1,
                readMessages = { final }, readHistory = { "[]" },
                commit = { before, replacement ->
                    compactionCommitAttempts.incrementAndGet()
                    // Delay in the collector exposes flowOn's buffer: the explicit ACK must wait.
                    assertEquals(final, before)
                    check(compactionMode != "commit_failure") { "synthetic disk full" }
                    final = replacement.messages
                    AppliedCompaction(replacement.messages, 1)
                },
            ) else null
            loop.generateText(
                settings = settings, model = model, assistant = assistant, messages = from,
                memories = listOf(AssistantMemory(1, "synthetic initial memory")),
                tools = listOf(tool) + compaction?.tools().orEmpty(), maxSteps = maxSteps,
                inputTransformers = listOf(changingInput, PromptInjectionTransformer),
                outputTransformers = outputTransformers, conversationId = Uuid.random(),
                compactionControl = compaction, includeCompactionReminder = compaction != null,
                durableCheckpoints = durableCheckpoints,
                consultationBusyWaitUntilMillis = consultationBusyWaitUntilMillis,
                emitTerminalEvidence = emitTerminalEvidence,
            ).collect { chunk ->
                if (collectorDelayMs > 0) delay(collectorDelayMs)
                if (compaction != null) delay(15)
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        final = chunk.messages
                        lastPublished = chunk.messages
                    }
                    is GenerationChunk.DurableBoundary -> {
                        check(durableCheckpoints) { "Unexpected durability boundary in legacy fixture." }
                        onDurableBoundary(chunk)
                    }
                    is GenerationChunk.CompactionCommit -> {
                        try { chunk.result.complete(checkNotNull(compaction).commit(chunk.before, chunk.replacement)) }
                        catch (failure: Exception) { chunk.result.completeExceptionally(failure) }
                    }
                    is GenerationChunk.TerminalResponse -> {
                        check(emitTerminalEvidence) { "Unexpected terminal evidence in an ordinary generation." }
                        check(matchesGenerationTerminalEvidence(chunk.evidence, final.last(), chunk.evidence.contextEpoch))
                        terminalEvidence += chunk.evidence
                    }
                }
            }
            lastCompactionEstimate = compaction?.lastInputEstimate ?: 0
            return final
        }
    }

    private class NoPrivateStorageContext(base: Context) : ContextWrapper(base) {
        val privateStorageAccesses = AtomicInteger()
        private fun forbidden(): Nothing {
            privateStorageAccesses.incrementAndGet()
            throw AssertionError("Synthetic GenerationLoop fixture attempted private storage access.")
        }
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = forbidden()
        override fun getFilesDir(): File = forbidden()
        override fun getNoBackupFilesDir(): File = forbidden()
        override fun getDatabasePath(name: String): File = forbidden()
        override fun getDir(name: String, mode: Int): File = forbidden()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbidden()
    }

    private data class CapturedRequest(val json: JsonObject, val encodedMessages: String)

    /** Single owned daemon thread, exact temporary port, 20s monotonic deadline, no disk or proxy. */
    private class LoopbackFixture(
        private val expectedRequests: Int,
        private val batchWithEmptyResult: Boolean,
        private val mixedApprovalBatch: Boolean,
        private val compactionMode: String?,
        private val reminderOnly: Boolean,
        private val emptyContinuation: Boolean,
        private val busyFirst: Boolean,
        private val finalText: String,
    ) : Closeable {
        private val listener = ServerSocket().apply {
            bind(InetSocketAddress(LOOPBACK, 0), 4)
            soTimeout = 250
        }
        val port: Int = listener.localPort
        val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
        private val closed = AtomicBoolean()
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        private val worker = thread(name = "synthetic-generation-http-$port", isDaemon = true) {
            try {
                while (!closed.get() && requests.size < expectedRequests) {
                    check(System.nanoTime() < deadline) { "Synthetic HTTP fixture exceeded its deadline." }
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    active.set(socket)
                    socket.use {
                        check(it.inetAddress.isLoopbackAddress)
                        it.soTimeout = 3_000
                        val input = BufferedInputStream(it.getInputStream())
                        check(readLine(input) == "POST /v1/chat/completions HTTP/1.1")
                        val headers = mutableMapOf<String, String>()
                        var headerBytes = 0
                        while (true) {
                            val line = readLine(input)
                            headerBytes += line.length + 2
                            check(headerBytes <= 16_384)
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            check(colon > 0)
                            val key = line.substring(0, colon).lowercase()
                            check(key !in headers)
                            headers[key] = line.substring(colon + 1).trim()
                        }
                        check(headers["host"] == "127.0.0.1:$port" && "transfer-encoding" !in headers)
                        val size = headers.getValue("content-length").toInt()
                        check(size in 1..131_072)
                        val body = ByteArray(size)
                        var read = 0
                        while (read < size) {
                            val count = input.read(body, read, size - read)
                            check(count > 0)
                            read += count
                        }
                        val raw = body.toString(Charsets.UTF_8)
                        val request = Json.parseToJsonElement(raw).jsonObject
                        requests.add(CapturedRequest(request, messagesArray(raw)))
                        val busy = busyFirst && requests.size == 1
                        val response = if (busy) "application/json" to """{"error":{"message":"synthetic busy","type":"stiller_gateway_error","code":"human_turn_in_progress","retry_class":"busy_before_generation","generation_started":false}}"""
                            else response(requests.size - if (busyFirst) 1 else 0, request["stream"] == JsonPrimitive(true))
                        val bytes = response.second.toByteArray(Charsets.UTF_8)
                        val header = "HTTP/1.1 ${if (busy) "409 Conflict" else "200 OK"}\r\nContent-Type: ${response.first}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        it.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    }
                    active.set(null)
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.set(error)
            } finally {
                runCatching { active.getAndSet(null)?.close() }
                runCatching { listener.close() }
            }
        }

        fun assertHealthy() {
            worker.join(1_000)
            check(!worker.isAlive) { "Synthetic HTTP worker did not finish." }
            failure.get()?.let { throw AssertionError("Synthetic HTTP fixture failed.", it) }
            assertEquals(expectedRequests, requests.size)
        }

        override fun close() {
            closed.set(true)
            runCatching { active.getAndSet(null)?.close() }
            runCatching { listener.close() }
            worker.join(1_000)
            check(!worker.isAlive) { "Synthetic HTTP worker did not close." }
        }

        private fun readLine(input: BufferedInputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                check(next >= 0 && bytes.size() < 8_192)
                if (next == 10) break
                bytes.write(next)
            }
            return bytes.toString("ISO-8859-1").removeSuffix("\r")
        }

        private fun response(step: Int, stream: Boolean): Pair<String, String> {
            if (emptyContinuation && step == 2) {
                check(stream) { "Empty-completion fixture is a streaming gateway response." }
                return "text/event-stream" to
                    "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"synthetic unfinished reasoning\"}}]}\n\n" +
                    "data: {\"error\":{\"message\":\"Only reasoning returned\",\"type\":\"stiller_gateway_error\",\"code\":\"upstream_empty_completion\"}}\n\n" +
                    "data: [DONE]\n\n"
            }
            val toolSteps = if (compactionMode != null) {
                if (step == 1) listOf(1) else emptyList()
            } else if (mixedApprovalBatch) {
                if (step == 1) listOf(1, 2, 3) else emptyList()
            } else if (batchWithEmptyResult) {
                if (step == 1) listOf(1, 2) else emptyList()
            } else if (step <= 3) listOf(step) else emptyList()
            val message = buildJsonObject {
                put("role", "assistant")
                if (toolSteps.isNotEmpty()) {
                    if (step == 1) put("content", if (compactionMode != null) "RAW SUMMARY {battery_level}" else "LATE_TRIGGER")
                    put("reasoning_content", "synthetic reasoning $step")
                    val compactCall = if (compactionMode != null) listOf(buildJsonObject {
                        put("index", 0); put("id", "synthetic-compact"); put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "compact")
                            val keep = when (compactionMode) { "zero" -> 0; "bad_keep" -> 999; else -> 1 }
                            put("arguments", "{\"use_last_message\":true,\"keep_recent\":$keep}")
                        })
                    }) else emptyList()
                    put("tool_calls", JsonArray(compactCall + toolSteps.mapIndexed { index, toolStep ->
                        buildJsonObject {
                            put("index", index + compactCall.size)
                            put("id", "synthetic-call-$toolStep")
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", "synthetic_tool")
                                put("arguments", "{\"step\":$toolStep}")
                            })
                        }
                    }))
                } else put("content", finalText)
            }
            val payload = buildJsonObject {
                put("id", "synthetic-response-$step")
                put("model", "synthetic-cache-probe")
                if ((compactionMode != null || reminderOnly) && step == 1) put("usage", buildJsonObject {
                    val prompt = if (reminderOnly) 315_000 else 350_000
                    put("prompt_tokens", prompt); put("completion_tokens", 40); put("total_tokens", prompt + 40)
                })
                put("choices", JsonArray(listOf(buildJsonObject {
                    put("index", 0)
                    put(if (stream) "delta" else "message", message)
                    put("finish_reason", if (toolSteps.isNotEmpty()) "tool_calls" else "stop")
                })))
            }.toString()
            return if (stream) "text/event-stream" to "data: $payload\n\ndata: [DONE]\n\n"
            else "application/json" to payload
        }

        /** Exact serialized array substring from our actual captured JSON; not a re-encoding. */
        private fun messagesArray(raw: String): String {
            val key = raw.indexOf("\"messages\":")
            check(key >= 0)
            val start = raw.indexOf('[', key)
            check(start >= 0)
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until raw.length) {
                val char = raw[index]
                if (quoted) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) return raw.substring(start, index + 1) }
                }
            }
            error("Synthetic request messages array is incomplete.")
        }
    }

    companion object {
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
