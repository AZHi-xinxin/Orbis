package me.rerere.rikkahub.data.orbis.screenshare

import org.junit.Assert.*
import org.junit.Test

class ScreenShareOverlayLayoutTest {
    private val portrait = ScreenShareOverlayArea(8, 32, 392, 820)

    @Test fun defaultWindowIsCompactAndBodyHeightCannotGrowWithReply() {
        val size = screenShareOverlaySize(1f, portrait, false)
        assertEquals(204, size.width); assertEquals(204, size.height); assertEquals(88, size.replyHeight)
        assertEquals(size, screenShareOverlaySize(1f, portrait, false))
        assertEquals(20_000, screenShareOverlayReply("x".repeat(20_000)).length)
    }

    @Test fun minimizedTabIsSmallButFullyVisible() {
        val size = screenShareOverlaySize(1f, portrait, true)
        assertEquals(ScreenShareOverlaySize(62, 32, 0), size)
        assertEquals(ScreenShareOverlayPosition(330, 788),
            clampScreenShareOverlay(ScreenShareOverlayPosition(900, 900), size, portrait))
    }

    @Test fun wholeExpandedWindowClampsNotOnlyItsHeader() {
        val size = screenShareOverlaySize(1f, portrait, false)
        assertEquals(ScreenShareOverlayPosition(188, 616),
            clampScreenShareOverlay(ScreenShareOverlayPosition(1000, 1000), size, portrait))
        assertEquals(ScreenShareOverlayPosition(8, 32),
            clampScreenShareOverlay(ScreenShareOverlayPosition(-999, -999), size, portrait))
    }

    @Test fun rotationKeepsBothExpandedAndMinimizedWindowsInsideNewInsets() {
        val landscape = ScreenShareOverlayArea(24, 8, 820, 360)
        for (collapsed in listOf(false, true)) {
            val size = screenShareOverlaySize(1f, landscape, collapsed)
            val position = clampScreenShareOverlay(ScreenShareOverlayPosition(300, 780), size, landscape)
            assertTrue(position.x >= landscape.left && position.y >= landscape.top)
            assertTrue(position.x + size.width <= landscape.right)
            assertTrue(position.y + size.height <= landscape.bottom)
        }
    }

    @Test fun collapsedDragSnapsToNearestVisibleEdge() {
        val size = screenShareOverlaySize(1f, portrait, true)
        assertEquals(8, snapScreenShareOverlayToEdge(ScreenShareOverlayPosition(45, 70), size, portrait).x)
        assertEquals(330, snapScreenShareOverlayToEdge(ScreenShareOverlayPosition(280, 70), size, portrait).x)
    }

    @Test fun keyboardAndNarrowScreenReduceBoundsInsteadOfLeakingPastBottom() {
        val area = ScreenShareOverlayArea(10, 20, 185, 200)
        val size = screenShareOverlaySize(1f, area, false)
        assertEquals(175, size.width); assertEquals(180, size.height); assertEquals(64, size.replyHeight)
        assertEquals(ScreenShareOverlayPosition(10, 20),
            clampScreenShareOverlay(ScreenShareOverlayPosition(900, 900), size, area))
    }

    @Test fun densityScalesGeometryButInvalidDensityDoesNotBreakIt() {
        val area = ScreenShareOverlayArea(0, 0, 1500, 3000)
        assertEquals(ScreenShareOverlaySize(612, 612, 264), screenShareOverlaySize(3f, area, false))
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(ScreenShareOverlaySize(204, 204, 88), screenShareOverlaySize(bad, area, false))
        }
    }

    @Test fun largeAccessibilityFontCannotInflateCompactWindow() {
        assertEquals(1.15f, screenShareOverlayFontScale(3f), .001f)
        assertEquals(1f, screenShareOverlayFontScale(.7f), .001f)
        assertEquals(1f, screenShareOverlayFontScale(Float.NaN), .001f)
    }

    @Test fun replyProjectionDoesNotInventAnAssistantResponse() {
        assertEquals("回复会显示在这里", screenShareOverlayReply(""))
        assertEquals("真实 AI 回复", screenShareOverlayReply("真实 AI 回复"))
    }
}
