package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.orbis.OrbisStickerLimits
import org.junit.Assert.*
import org.junit.Test

class OrbisStickerPanelLogicTest {
    @Test fun commaAndNewlineNormalizeWithoutReordering() {
        assertEquals(listOf("抱抱", "安慰", "想你时"), parseStickerEditorTags(" 抱抱，安慰\r\n想你时,抱抱 "))
    }

    @Test fun semicolonAndInternalSpacesRemainPartOfAUserTag() {
        assertEquals(listOf("happy when tired; gentle"), parseStickerEditorTags("happy when tired; gentle"))
    }

    @Test fun emptyTagsCannotBeSaved() {
        assertNotNull(stickerEditorError(parseStickerEditorTags(" ,，\r\n ")))
    }

    @Test fun validTagsHaveNoError() {
        assertNull(stickerEditorError(parseStickerEditorTags("抱抱，安慰，想你时")))
    }

    @Test fun tooManyTagsAreRejectedNotTruncated() {
        val tags = (1..OrbisStickerLimits.MAX_TAGS + 1).map { "标签$it" }
        val parsed = parseStickerEditorTags(tags.joinToString(","))
        assertEquals(tags, parsed)
        assertNotNull(stickerEditorError(parsed))
    }

    @Test fun overlongTagIsRejectedNotTruncated() {
        val raw = "长".repeat(OrbisStickerLimits.MAX_TAG_LENGTH + 1)
        assertEquals(listOf(raw), parseStickerEditorTags(raw))
        assertNotNull(stickerEditorError(parseStickerEditorTags(raw)))
    }

    @Test fun totalLengthLimitIsEnforced() {
        val tags = List(7) { "长".repeat(OrbisStickerLimits.MAX_TAG_LENGTH - 1) + it }
        assertTrue(tags.size <= OrbisStickerLimits.MAX_TAGS)
        assertTrue(tags.sumOf(String::length) > OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH)
        assertNotNull(stickerEditorError(tags))
    }

    @Test fun exactBoundariesAreAccepted() {
        val tags = List(6) { "长".repeat(OrbisStickerLimits.MAX_TAG_LENGTH - 1) + it }
        assertEquals(OrbisStickerLimits.MAX_TAG_TOTAL_LENGTH, tags.sumOf(String::length))
        assertNull(stickerEditorError(tags))
    }
}
