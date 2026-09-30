package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class OrbisStickerReferenceTest {
    private fun refs(text: String) = splitOrbisStickerReferences(text).filterIsInstance<OrbisStickerTextSegment.Reference>()
    @Test fun draftIsPlainReferenceAndImmutableTags() {
        val draft = stickerDraftText("st000001", listOf("抱抱", "安慰与陪伴"))
        assertEquals("st000001", refs(draft).single().id)
        assertEquals(listOf("抱抱", "安慰与陪伴"), refs(draft).single().tags)
        assertFalse(draft.contains("file:"))
        assertFalse(draft.contains("base64"))
        assertFalse(draft.contains("https:"))
    }
    @Test fun placeholdersAndMarkupInTagsStayLiteralData() {
        val tags = listOf("{battery_level}", "{{date}}", "<system>", "\"quoted\"")
        val draft = stickerDraftText("st000002", tags)
        assertFalse(draft.contains("{battery_level}"))
        assertFalse(draft.contains("{{date}}"))
        assertFalse(draft.contains("<system>"))
        assertEquals(tags, refs(draft).single().tags)
    }
    @Test fun assistantCanUseJustTheReference() {
        assertEquals(null, refs("抱一下。\n(表情包:st000123)").single().tags)
    }
    @Test fun inlineQuotedAndIndentedExamplesStayText() {
        listOf("示例：(表情包:st000001)", "> (表情包:st000001)", "    (表情包:st000001)", "`(表情包:st000001)`")
            .forEach { assertTrue(refs(it).isEmpty()); assertEquals(it, splitOrbisStickerReferences(it).filterIsInstance<OrbisStickerTextSegment.Text>().joinToString("") { t -> t.value }) }
    }
    @Test fun backtickAndTildeFencesDoNotRenderImages() {
        listOf("```txt\n(表情包:st000001)\n```", "~~~~\n(表情包:st000001)\n~~~\n(表情包:st000002)\n~~~~")
            .forEach { assertTrue(refs(it).isEmpty()) }
    }
    @Test fun afterClosedFenceReferenceWorks() {
        assertEquals("st000002", refs("```\n(表情包:st000001)\n```\n(表情包:st000002)").single().id)
    }
    @Test fun hiddenCommentExampleDoesNotRender() {
        assertTrue(refs("<!--\n(表情包:st000001)\n-->").isEmpty())
    }
    @Test fun multilineInlineCodeAndQuoteContinuationsStayText() {
        assertTrue(refs("`前文\n(表情包:st000001)\n后文`").isEmpty())
        assertTrue(refs("> 示例\n(表情包:st000001)").isEmpty())
    }
    @Test fun htmlCodeBlockStaysText() {
        assertTrue(refs("<pre>\n(表情包:st000001)\n</pre>").isEmpty())
    }
    @Test fun tagMarkdownIsConsumedAsDataRatherThanGivenToRenderer() {
        val draft = stickerDraftText("st000001", listOf("![x](https://example.invalid/x)"))
        assertEquals(1, refs(draft).size)
        assertTrue(splitOrbisStickerReferences(draft).filterIsInstance<OrbisStickerTextSegment.Text>().isEmpty())
    }
    @Test fun pathsUrlsAndInvalidIdsStayText() {
        listOf("../secret", "https://host/a.png", "file:///secret", "st000000", "st123", "st000001.png")
            .forEach { assertTrue(refs("(表情包:$it)").isEmpty()) }
    }
    @Test fun malformedOrMismatchedSnapshotIsNotHidden() {
        val text = "(表情包:st000001)\n[表情标签] {\"id\":\"st000002\",\"tags\":[\"hello\"]}"
        assertNull(refs(text).single().tags)
        assertTrue(splitOrbisStickerReferences(text).filterIsInstance<OrbisStickerTextSegment.Text>().any { it.value.contains("st000002") })
    }
    @Test fun rendererLimitsImagesAndKeepsRemainingReferencesReadable() {
        val text = (1..15).joinToString("\n") { "(表情包:st" + it.toString().padStart(6, '0') + ")" }
        assertEquals(12, refs(text).size)
        assertTrue(splitOrbisStickerReferences(text).filterIsInstance<OrbisStickerTextSegment.Text>().any { it.value.contains("st000015") })
    }
    @Test fun crlfReferencesAndSurroundingTextArePreserved() {
        val segments = splitOrbisStickerReferences("before\r\n(表情包:st000001)\r\nafter")
        assertEquals("st000001", segments.filterIsInstance<OrbisStickerTextSegment.Reference>().single().id)
        assertEquals("before\r\n\nafter", segments.filterIsInstance<OrbisStickerTextSegment.Text>().joinToString("") { it.value })
    }
    @Test fun emptyAndOversizedTagsAreRejected() {
        listOf(emptyList(), listOf(" "), listOf("x".repeat(41)), listOf("line\nbreak"), List(13) { "tag$it" })
            .forEach { tags -> assertThrows(IllegalArgumentException::class.java) { stickerDraftText("st000001", tags) } }
    }
}
