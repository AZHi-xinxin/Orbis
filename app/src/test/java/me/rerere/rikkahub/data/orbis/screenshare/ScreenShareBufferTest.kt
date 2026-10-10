package me.rerere.rikkahub.data.orbis.screenshare

import org.junit.Assert.*
import org.junit.Test

class ScreenShareBufferTest {
    private fun frame(id: String, level: Int = 80, size: Int = 100) = ScreenShareFrame(id, 1000L,
        ByteArray(size) { 7 }, IntArray(1024) { level })

    @Test fun keepsEightFramesAndErasesDiscardedBytes() {
        val buffer = ScreenShareBuffer()
        val first = frame("0")
        buffer.add(first, true)
        repeat(8) { buffer.add(frame((it + 1).toString()), true) }
        assertEquals(8, buffer.size())
        assertNull(buffer.get("0"))
        assertTrue(first.jpeg.all { it == 0.toByte() })
        assertEquals("8", buffer.back(0)?.id)
        assertEquals("1", buffer.back(7)?.id)
        assertNull(buffer.back(8))
    }

    @Test fun byteBudgetCanEvictBeforeEightFrames() {
        val buffer = ScreenShareBuffer()
        repeat(8) { buffer.add(frame(it.toString(), size = 2_000_000), true) }
        assertEquals(4, buffer.size())
        assertNull(buffer.get("3"))
        assertNotNull(buffer.get("4"))
    }

    @Test fun deduplicatesSimilarFramesAndAcceptsChangedRegion() {
        val buffer = ScreenShareBuffer()
        assertTrue(buffer.add(frame("first")))
        assertFalse(buffer.add(frame("same")))
        assertFalse(buffer.add(frame("noise", 82)))
        val region = frame("region").let { it.copy(signature = it.signature.also { pixels -> repeat(24) { pixels[it] = 180 } }) }
        assertTrue(buffer.add(region))
        assertEquals(2, buffer.size())
    }

    @Test fun clearInvalidatesAndErasesAllFrames() {
        val buffer = ScreenShareBuffer()
        val first = frame("first")
        buffer.add(first)
        val copy = checkNotNull(buffer.get("first"))
        copy.jpeg.fill(3)
        assertEquals(7.toByte(), buffer.get("first")!!.jpeg.first())
        buffer.clear()
        assertNull(buffer.get("first"))
        assertTrue(first.jpeg.all { it == 0.toByte() })
    }

    @Test fun permissionBindsOwnerConversationSessionAndPictureSwitch() {
        assertTrue(screenSharePermitted("a", "c", "s", "a", "c", "s", true))
        assertFalse(screenSharePermitted("b", "c", "s", "a", "c", "s", true))
        assertFalse(screenSharePermitted("a", "other", "s", "a", "c", "s", true))
        assertFalse(screenSharePermitted("a", "c", "old", "a", "c", "s", true))
        assertFalse(screenSharePermitted("a", "c", "s", "a", "c", "s", false))
        assertFalse(screenSharePermitted("a", "c", "s", null, null, null, true))
    }

    @Test fun activeTurnKeepsStableFrameInsideSameEightFrameBudget() {
        val buffer = ScreenShareBuffer()
        buffer.add(frame("turn"), true)
        assertTrue(buffer.pin("turn"))
        repeat(30) { buffer.add(frame("tick-$it"), true) }
        assertEquals(8, buffer.size())
        assertEquals(7.toByte(), buffer.get("turn")!!.jpeg.first())
        buffer.clearUnpinned()
        assertEquals(1, buffer.size())
        buffer.unpin("turn")
        buffer.clearUnpinned()
        assertEquals(0, buffer.size())
    }

    @Test fun pinningDoesNotAllowMoreThanEightFramesAndPrivacyClearOverridesPins() {
        val buffer = ScreenShareBuffer()
        val frames = (0..7).map { frame("tool-$it") }
        frames.forEach { buffer.add(it, true); buffer.pin(it.id) }
        assertFalse(buffer.add(frame("ninth"), true))
        assertEquals(8, buffer.size())
        buffer.clear()
        assertEquals(0, buffer.size())
        assertTrue(frames.all { image -> image.jpeg.all { it == 0.toByte() } })
        assertFalse(buffer.pin("tool-0"))
    }
}
