package me.rerere.rikkahub.data.model

import org.junit.Assert.*
import org.junit.Test

class OrbisStickerDraftPresentationTest {
    private val sticker = stickerDraftText("st000001", listOf("抱抱", "安慰"))

    @Test fun completeSnapshotHasExactSourceRange() {
        val raw = "前文🙂\n$sticker\n后文"
        val span = orbisStickerDraftSpans(raw).single()
        assertEquals(sticker, raw.substring(span.start, span.end))
        assertEquals("[表情：抱抱、安慰]", span.label)
    }

    @Test fun adjacentStickersAndCrLfRemainLossless() {
        val second = stickerDraftText("st000002", listOf("开心"))
        val raw = "$sticker\n$second\n".replace("\n", "\r\n")
        val spans = orbisStickerDraftSpans(raw)
        assertEquals(2, spans.size)
        assertEquals(sticker.replace("\n", "\r\n") + "\r", raw.substring(spans[0].start, spans[0].end))
        assertEquals(second.replace("\n", "\r\n") + "\r", raw.substring(spans[1].start, spans[1].end))
    }

    @Test fun invalidOrMissingSnapshotIsNeverHidden() {
        for (raw in listOf("(表情包:st000001)", "$sticker broken", sticker.replace("\"tags\"", "\"other\""))) {
            assertTrue(orbisStickerDraftSpans(raw).isEmpty())
        }
    }

    @Test fun codeAndInlineExamplesAreNeverHidden() {
        assertTrue(orbisStickerDraftSpans("```text\n$sticker\n```").isEmpty())
        assertTrue(orbisStickerDraftSpans("示例：$sticker").isEmpty())
        assertTrue(orbisStickerDraftSpans("> $sticker").isEmpty())
    }

    @Test fun longEmojiLabelDoesNotBreakSurrogatePairs() {
        val raw = stickerDraftText("st000001", listOf("🙂".repeat(20), "🙂".repeat(20)))
        val label = orbisStickerDraftSpans(raw).single().label
        assertTrue(label.endsWith("…]"))
        assertFalse(label.contains('\uFFFD'))
        assertEquals(0, label.windowed(2).count { it[0].isHighSurrogate() && !it[1].isLowSurrogate() })
    }

    @Test fun partialBackspaceExpandsWholeTokenButNotNewlinesOrNeighbours() {
        val raw = "前文\n$sticker\n后文"
        val span = orbisStickerDraftSpans(raw).single()
        val deletion = orbisStickerAtomicDeletion(raw, span.end - 1, span.end, 0, 1)!!
        assertEquals(span.start, deletion.start)
        assertEquals(span.end, deletion.end)
        assertEquals("前文\n\n后文", raw.removeRange(deletion.start, deletion.end))
    }

    @Test fun forwardDeleteExpandsWholeToken() {
        val span = orbisStickerDraftSpans(sticker).single()
        assertEquals(OrbisStickerDraftDeletion(span.start, span.end), orbisStickerAtomicDeletion(sticker, 0, 1, 0, 1))
    }

    @Test fun boundaryPasteAndWholeReplacementRemainUnchanged() {
        val raw = "\n$sticker\n"
        val span = orbisStickerDraftSpans(raw).single()
        assertNull(orbisStickerAtomicDeletion(raw, span.start, span.start, 8, 1))
        assertNull(orbisStickerAtomicDeletion(raw, span.end, span.end, 8, 1))
        assertNull(orbisStickerAtomicDeletion(raw, 0, raw.length, 5, 1))
        assertNull(orbisStickerAtomicDeletion(raw, span.start + 1, span.end - 1, 2, 1))
    }

    @Test fun multiChangeAndAlreadyWholeDeletionRemainUnchanged() {
        assertNull(orbisStickerAtomicDeletion(sticker, 0, 1, 0, 2))
        assertNull(orbisStickerAtomicDeletion(sticker, 0, sticker.length, 0, 1))
    }

    @Test fun deletingNewlineImmediatelyNextToTokenDoesNotRemoveToken() {
        val raw = "\n$sticker\n"
        assertNull(orbisStickerAtomicDeletion(raw, 0, 1, 0, 1))
        assertNull(orbisStickerAtomicDeletion(raw, raw.length - 1, raw.length, 0, 1))
    }

    @Test fun deletionAcrossTwoTokensPreservesOutsideText() {
        val second = stickerDraftText("st000002", listOf("开心"))
        val raw = "before\n$sticker\n$second\nafter"
        val spans = orbisStickerDraftSpans(raw)
        val deletion = orbisStickerAtomicDeletion(raw, spans[0].end - 1, spans[1].start + 1, 0, 1)!!
        assertEquals("before\n\nafter", raw.removeRange(deletion.start, deletion.end))
    }

    @Test fun ordinaryTextAndInvalidRangesAreNotRewritten() {
        assertTrue(orbisStickerDraftSpans("普通文字").isEmpty())
        assertNull(orbisStickerAtomicDeletion("普通文字", 0, 1, 0, 1))
        assertNull(orbisStickerAtomicDeletion(sticker, -1, 1, 0, 1))
        assertNull(orbisStickerAtomicDeletion(sticker, 0, sticker.length + 1, 0, 1))
    }
}
