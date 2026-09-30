package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisStickerSendTest {
    @Test fun `fast second tap is blocked even if the first request finished instantly`() {
        assertFalse(isOrbisStickerTapDebounced(1000L, null))
        assertTrue(isOrbisStickerTapDebounced(1000L, 1000L))
        assertTrue(isOrbisStickerTapDebounced(1599L, 1000L))
        assertFalse(isOrbisStickerTapDebounced(1600L, 1000L))
        assertFalse(isOrbisStickerTapDebounced(10L, 1000L))
    }

    @Test fun `image send contains real image part not a textual reference`() {
        val parts = orbisStickerImageParts("file:///synthetic-upload/image.png")
        assertEquals(listOf(UIMessagePart.Image("file:///synthetic-upload/image.png")), parts)
        assertTrue(parts.none { it is UIMessagePart.Text })
    }

    @Test fun `remote and text reference payloads are not accepted as local attachment urls`() {
        listOf("https://example.invalid/image.png", "(表情包:st000001)", "../private", "content://source").forEach {
            assertThrows(IllegalArgumentException::class.java) { orbisStickerImageParts(it) }
        }
    }

    @Test fun `only fully idle session accepts immediate send including tool approval and queued work`() {
        for (mask in 0 until 16) {
            assertEquals("busy mask $mask", mask == 0,
                canSendOrbisStickerNow(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0,
                    mask and 8 != 0))
        }
    }
}
