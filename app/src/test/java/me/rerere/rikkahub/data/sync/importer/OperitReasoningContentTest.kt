package me.rerere.rikkahub.data.sync.importer

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OperitReasoningContentTest {
    private fun parse(text: String, sender: String = "ai") = splitOperitReasoningContent(text, sender)
    private fun roundTrip(value: OperitContentSplit) = value.slices.joinToString("") {
        if (it.reasoning) "<think>${it.text}</think>" else it.text
    }

    @Test fun singleAndMultipleEnvelopesPreserveAllWhitespaceAndOrdering() {
        val raw = " \n<think> first\r\nline </think>answer\n<think>second</think> tail "
        val value = parse(raw)
        assertEquals(listOf(" first\r\nline ", "second"), value.slices.filter { it.reasoning }.map { it.text })
        assertEquals(raw, roundTrip(value)); assertFalse(value.ambiguous)
    }

    @Test fun noEnvelopeAndHumanLiteralNeverBecomeReasoning() {
        listOf("", "plain answer", "The literal <think>example</think>", "```xml\n<think>x</think>\n```",
            "> <think>quoted</think>", "&lt;think&gt;escaped&lt;/think&gt;").forEach {
            assertEquals(listOf(OperitContentSlice(it)), parse(it).slices)
        }
        assertEquals(listOf(OperitContentSlice("<think>human</think>")), parse("<think>human</think>", "user").slices)
    }

    @Test fun fencesInlineEscapesQuotesAndToolPayloadsRemainOpaque() {
        val raw = "<think>real</think>\n```xml\n<think>code</think>\n```\n" +
            "~~~text\n<think>tilde</think>\n~~~\n> <think>quote</think>\n" +
            "    <think>indent</think>\n`<think>inline</think>`\n\\<think>escaped\\</think>\n" +
            "<!-- <think>comment</think> -->\n<tool_result_123><think>tool data</think></tool_result_123>\n" +
            "<think>next</think>body"
        val value = parse(raw)
        assertEquals(listOf("real", "next"), value.slices.filter { it.reasoning }.map { it.text })
        assertEquals(raw, roundTrip(value)); assertFalse(value.ambiguous)
    }

    @Test fun quotedClosingTagsInsideReasoningDoNotTruncateIt() {
        val raw = "<think>example `</think>`\n```xml\n</think>\n```\nfinish</think>body"
        val value = parse(raw)
        assertEquals(1, value.slices.count { it.reasoning }); assertEquals(raw, roundTrip(value))
        assertTrue(value.slices.first().text.endsWith("finish"))
    }

    @Test fun malformedOrNestedEnvelopesFailClosedWithoutLosingBody() {
        listOf("<think>unterminated", "<think>outer<think>inner</think>end</think>",
            "<think>first</think>body\n<think>unterminated", "<think>first</think>body</think>",
            "<think>first</think>\n`unclosed code <think>not sure</think>").forEach {
            val value = parse(it)
            assertTrue(value.ambiguous); assertEquals(listOf(OperitContentSlice(it)), value.slices)
        }
    }

    @Test fun emptyReasoningAndEmptyFinalBodyStayReconstructable() {
        listOf("<think></think>", "<think>only reasoning</think>", "\n<think>thinking</think>\n").forEach {
            assertEquals(it, roundTrip(parse(it)))
        }
    }

    @Test fun cancellationAndSegmentLimitNeverPartiallyDiscardSource() {
        assertThrows(CancellationException::class.java) {
            splitOperitReasoningContent("<think>data</think>", "ai") { throw CancellationException() }
        }
        val raw = "<think>x</think>\n".repeat(5000)
        assertTrue(parse(raw).ambiguous); assertEquals(raw, roundTrip(parse(raw)))
    }
}
