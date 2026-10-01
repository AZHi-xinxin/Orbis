package me.rerere.asr

import org.junit.Assert.*
import org.junit.Test

class ASRCorrectionNoticeTest {
    private val first = ASRCorrectionResult("合成原识别", "合成纠正后")

    @Test fun dismissalIsForCaptureNotTextAndNeverDeletesTheAudit() {
        val shown = ASRCorrectionNotice().begin(1).accept(1, first)
        assertTrue(shown.visible)
        val closed = shown.dismiss(1)
        assertFalse(closed.visible)
        assertEquals(first, closed.review)
        val identicalNextUtterance = closed.begin(2).accept(2, first)
        assertTrue(identicalNextUtterance.visible)
        assertEquals(first.original, identicalNextUtterance.review?.original)
    }

    @Test fun sameCapturePartialAndFinalDoNotReopenConsumedNotice() {
        val closed = ASRCorrectionNotice().begin(3).accept(3, first).dismiss(3)
        val final = first.copy(original = "合成完整原识别", corrected = "合成完整纠正后")
        val updated = closed.accept(3, final)
        assertFalse(updated.visible)
        assertEquals(final, updated.review)
        assertEquals(3L, updated.eventId)
    }

    @Test fun staleResultOrDialogCloseCannotConsumeOrReplaceNextCapture() {
        val current = ASRCorrectionNotice().begin(2).accept(2, first)
        assertEquals(current, current.dismiss(1))
        assertEquals(current, current.accept(1, ASRCorrectionResult("旧原文", "旧纠正")))
        assertTrue(current.visible)
    }

    @Test fun unchangedOrEmptyResultHasNoNoticeButKeepsOriginal() {
        assertFalse(ASRCorrectionNotice().visible)
        val unchanged = ASRCorrectionNotice().begin(4).accept(4, ASRCorrectionResult("原文", "原文"))
        assertFalse(unchanged.visible)
        assertEquals("原文", unchanged.review?.original)
    }
}
