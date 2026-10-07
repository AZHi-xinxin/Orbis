package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisPhotoWallLayoutTest {
    @Test fun emptyAlbumHasAnOpenSpreadWithPlacesToAdd() {
        assertEquals(listOf(listOf<String?>(null, null)), photoWallAlbumSpreads(emptyList()))
    }

    @Test fun albumKeepsEveryIdentityAndItsOrderWithNoFixedAlbumLimit() {
        for (count in listOf(1, 2, 3, 4, 17, 1000)) {
            val ids = (0 until count).map { "photo-$it" }
            val spreads = photoWallAlbumSpreads(ids)
            assertTrue(spreads.all { it.size == 2 })
            assertEquals(ids, spreads.flatten().filterNotNull())
            assertTrue(spreads.last().any { it == null })
            assertEquals(count / 2 + 1, spreads.size)
        }
    }

    @Test fun oddAlbumHasBlankRightPageAndEvenAlbumGetsNewSpread() {
        assertEquals(listOf(listOf("a", null)), photoWallAlbumSpreads(listOf("a")))
        assertEquals(listOf(listOf("a", "b"), listOf(null, null)), photoWallAlbumSpreads(listOf("a", "b")))
    }

    @Test fun normalImageRatioIsKeptAndExtremePaperSizesAreBoundedForFit() {
        assertEquals(.75f, photoWallFrameAspectRatio(300, 400), .0001f)
        assertEquals(1.5f, photoWallFrameAspectRatio(600, 400), .0001f)
        assertEquals(1f, photoWallFrameAspectRatio(400, 400), .0001f)
        assertEquals(.58f, photoWallFrameAspectRatio(1, 2000), .0001f)
        assertEquals(1.8f, photoWallFrameAspectRatio(2000, 1), .0001f)
        assertEquals(.8f, photoWallFrameAspectRatio(0, 0), .0001f)
    }

    @Test fun hangingAnchorsFollowSymmetricRopeAndStayOnCanvas() {
        for (columns in 1..4) {
            for (column in 0 until columns) {
                val y = photoWallRopeY(column, columns)
                assertTrue(y in 14f..37f)
                assertEquals(y, photoWallRopeY(columns - column - 1, columns), .0001f)
            }
        }
        assertTrue(photoWallRopeY(1, 3) > photoWallRopeY(0, 3))
    }

    @Test fun collageTiltIsStableAndSmallEnoughToPreserveVisibleEdges() {
        assertNotEquals(photoWallCollageTilt(0), photoWallCollageTilt(1))
        for (index in 0..1000) {
            assertTrue(kotlin.math.abs(photoWallCollageTilt(index)) <= 3f)
            assertEquals(photoWallCollageTilt(index), photoWallCollageTilt(index + 6), 0f)
        }
    }
}
