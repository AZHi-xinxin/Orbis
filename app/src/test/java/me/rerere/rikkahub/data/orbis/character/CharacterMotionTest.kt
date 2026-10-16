package me.rerere.rikkahub.data.orbis.character

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CharacterMotionTest {
    private val head = CharacterRegion("head", "Head", .3f, .02f, .4f, .25f)
    @Test fun disabledAndImageEdgesAreFixed() {
        val p = CharacterPoint(.5f, .2f)
        assertEquals(p, characterMotionPoint(p, head, CharacterMotion()))
        for (edge in listOf(CharacterPoint(0f,.5f), CharacterPoint(1f,.5f), CharacterPoint(.5f,0f), CharacterPoint(.5f,1f))) {
            assertEquals(edge, characterMotionPoint(edge, head, CharacterMotion(3f,1f,1f,1f,head,true)))
        }
    }
    @Test fun trackingIsBoundedAndHitTestingMapsBack() {
        val m = CharacterMotion(3f,1f,-1f,.8f,head,true)
        for (x in 0..30) for (y in 0..40) {
            val p = CharacterPoint(x / 30f, y / 40f)
            val moved = characterMotionPoint(p, head, m)
            assertTrue(abs(moved.x - p.x) <= .0181f && abs(moved.y - p.y) <= .0181f)
            val recovered = characterMotionSource(moved, head, m)
            assertEquals(p.x, recovered.x, .0002f); assertEquals(p.y, recovered.y, .0002f)
        }
    }
    @Test fun invalidClockAndInputCannotPoisonCanvas() {
        val p = characterMotionPoint(CharacterPoint(.5f,.2f), head, CharacterMotion(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN,head,true))
        assertTrue(p.x.isFinite() && p.y.isFinite())
    }
}
