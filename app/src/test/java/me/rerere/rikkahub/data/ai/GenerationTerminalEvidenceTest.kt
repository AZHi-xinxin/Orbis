package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.ai.transformers.transformThinkTags
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.replaceRegexes
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Provider-shaped synthetic values only; never creates a GenerationLoop, network client or service. */
class GenerationTerminalEvidenceTest {
    private val done = UIMessagePart.Tool("tool-one", "synthetic_read", "{\"private\":true}",
        output = listOf(UIMessagePart.Text("synthetic private tool receipt")))
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun proof(raw: UIMessage, shown: UIMessage, transformed: UIMessage = raw, epoch: Long = 4) =
        createGenerationTerminalEvidence(raw, transformed, shown, epoch)

    @Test fun executedToolAndFinalResponseCanShareOneMessageWithoutLeakingEarlierParts() {
        val raw = assistant(UIMessagePart.Reasoning("synthetic final-step reasoning"), UIMessagePart.Text("final body"))
        val shown = assistant(UIMessagePart.Text("synthetic tool preamble"),
            UIMessagePart.Reasoning("synthetic earlier reasoning"), done,
            UIMessagePart.Reasoning("synthetic final-step reasoning"), UIMessagePart.Text("final body"))
        val evidence = requireNotNull(proof(raw, shown))
        assertEquals("final body", evidence.text)
        assertEquals(shown.id.toString(), evidence.messageId)
        assertTrue(matchesGenerationTerminalEvidence(evidence, shown, 4))
        assertFalse(evidence.toString().contains("final body"))
        assertFalse(evidence.toString().contains("synthetic"))
    }

    @Test fun streamingContinuationHasAnIndependentResponseAndRetainsTheOriginalBubbleIdentity() {
        val base = assistant(UIMessagePart.Text("private preamble"), done)
        var merged = listOf(base)
        var response = listOf(assistant())
        val uiHandler = StreamChunkHandler()
        val responseHandler = StreamChunkHandler()
        val chunks = listOf(StreamChunk.ReasoningStart("r"), StreamChunk.ReasoningDelta("r", "private reasoning"),
            StreamChunk.ReasoningEnd("r"), StreamChunk.TextStart("t"), StreamChunk.TextDelta("t", "final "),
            StreamChunk.TextDelta("t", "body"), StreamChunk.TextEnd("t"), StreamChunk.Finish("stop"))
        for (chunk in chunks) {
            merged = uiHandler.handle(merged, chunk)
            response = responseHandler.handle(response, chunk)
        }
        assertEquals(base.id, merged.single().id)
        assertTrue(merged.single().getTools().single().isExecuted)
        assertEquals("final body", requireNotNull(proof(response.single(), merged.single())).text)
    }

    @Test fun nonStreamingAdjacentTextMergeNeverIncludesThePreviousToolStepPostscript() {
        val base = assistant(UIMessagePart.Text("private preamble"), done, UIMessagePart.Text("private postscript:"))
        val raw = UIMessage.assistant("final body")
        val merged = listOf(base).handleTextGenerationResult(TextGenerationResult("synthetic", "synthetic", raw)).single()
        assertEquals("private postscript:final body", (merged.parts.last() as UIMessagePart.Text).text)
        val evidence = requireNotNull(proof(raw, merged))
        assertEquals("final body", evidence.text)
        assertTrue(matchesGenerationTerminalEvidence(evidence, merged, 4))
    }

    @Test fun originalToolCallResponseCannotBeLaunderedByRemovingItsToolDuringTransformation() {
        val raw = assistant(UIMessagePart.Text("private preamble"), done.copy(output = emptyList()),
            UIMessagePart.Text("private postscript"))
        val transformed = generationTerminalTextInput(raw)
        val shown = raw.copy(parts = raw.parts.map { if (it is UIMessagePart.Tool) done else it })
        assertNull(proof(raw, shown, transformed))
    }

    @Suppress("DEPRECATION")
    @Test fun everyUnsupportedToolRepresentationFailsClosedEvenWhenThereIsText() {
        val tools = listOf<UIMessagePart>(done,
            UIMessagePart.ToolCall("old-call", "old", "{}"),
            UIMessagePart.ToolResult("old-call", "old", JsonNull, JsonNull),
            UIMessagePart.ServerTool("server-call", "server", status = ServerToolStatus.COMPLETED),
            UIMessagePart.Search)
        for (tool in tools) {
            val raw = assistant(tool, UIMessagePart.Text("not proven"))
            assertTrue(generationResponseHasToolParts(raw))
            assertNull(proof(raw, raw, generationTerminalTextInput(raw)))
        }
    }

    @Test fun pendingOrUnexecutedUiToolsCannotMatchAClaimedFinalResponse() {
        val raw = UIMessage.assistant("final body")
        for (tool in listOf(done.copy(output = emptyList()),
            done.copy(output = emptyList(), approvalState = ToolApprovalState.Pending))) {
            assertNull(proof(raw, assistant(tool, UIMessagePart.Text("final body"))))
        }
    }

    @Test fun messageEpochAndTheEntireUiTextTailMustMatchAtCommit() {
        val raw = UIMessage.assistant("final body")
        val shown = assistant(done, UIMessagePart.Text("private postscript:final body"))
        val evidence = requireNotNull(proof(raw, shown))
        assertFalse(matchesGenerationTerminalEvidence(evidence, shown.copy(id = Uuid.random()), 4))
        assertFalse(matchesGenerationTerminalEvidence(evidence, shown, 5))
        assertFalse(matchesGenerationTerminalEvidence(evidence,
            shown.copy(parts = listOf(done, UIMessagePart.Text("changed prefix:final body"))), 4))
        assertFalse(matchesGenerationTerminalEvidence(evidence,
            shown.copy(parts = listOf(done, UIMessagePart.Text("private postscript:final body changed"))), 4))
        assertFalse(matchesGenerationTerminalEvidence(evidence,
            shown.copy(parts = listOf(done, UIMessagePart.Text("private postscript:final body"), done.copy(toolCallId = "later"))), 4))
    }

    @Test fun independentlyTransformedTextMustExactlyMatchTheDisplayedSuffix() {
        val raw = UIMessage.assistant("raw body")
        assertNull(proof(raw, UIMessage.assistant("different display")))
        val shown = UIMessage.assistant("converted body")
        assertEquals("converted body", requireNotNull(proof(raw, shown, UIMessage.assistant("converted body"))).text)
        assertNull(proof(raw, shown, UIMessage.assistant("converted body ")))
    }

    @Test fun thinkTagConversionKeepsItsReasoningOutOfTheEvidenceText() {
        val raw = UIMessage.assistant("<think>synthetic private thought</think>visible body")
        val transformed = listOf(generationTerminalTextInput(raw))
            .transformThinkTags(Instant.fromEpochMilliseconds(0), generationFinished = true).single()
        val evidence = requireNotNull(proof(raw, transformed, transformed))
        assertEquals("visible body", evidence.text)
        assertFalse(evidence.text.contains("private"))
        assertNull(proof(raw, transformed)) // Raw text may not bypass the conversion.
    }

    @Test fun configuredRegexOutputIsUsedRatherThanRawProviderText() {
        val assistant = Assistant(regexes = listOf(AssistantRegex(Uuid.random(), findRegex = "private",
            replaceString = "redacted", affectingScope = setOf(AssistantAffectScope.ASSISTANT))))
        val raw = UIMessage.assistant("private final body")
        val transformed = raw.copy(parts = raw.parts.map { part ->
            if (part is UIMessagePart.Text) part.copy(text = part.text.replaceRegexes(assistant, AssistantAffectScope.ASSISTANT)) else part
        })
        assertEquals("redacted final body", requireNotNull(proof(raw, transformed, transformed)).text)
        assertNull(proof(raw, transformed))
    }

    @Test fun terminalTextInputDropsAttachmentsWithoutFlatteningNestedToolReceipts() {
        val text = UIMessagePart.Text("final body")
        val reasoning = UIMessagePart.Reasoning("private reasoning")
        val input = generationTerminalTextInput(assistant(text, reasoning, done,
            UIMessagePart.Image("data:image/png;base64,synthetic")))
        assertEquals(listOf(text, reasoning), input.parts)
    }

    @Test fun compactionAndNonAssistantMessagesCannotSupplyTerminalEvidence() {
        val raw = UIMessage.assistant("final body")
        val summary = assistant(UIMessagePart.Text("final body", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) }))
        assertNull(proof(raw, summary))
        assertNull(proof(summary, raw))
        assertNull(proof(UIMessage.user("final body"), raw))
        assertNull(proof(raw, UIMessage.user("final body")))
        assertNull(proof(raw, raw, epoch = -1))
    }
}
