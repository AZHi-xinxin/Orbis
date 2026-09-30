package com.lover.connect.ui.components

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarSwitchGeometryTest {
    @Test fun offIsFourPointStar() {
        assertEquals(4, starSwitchPointCount(false))
        assertEquals(8, starSwitchVertices(false).size)
    }

    @Test fun onIsFivePointStar() {
        assertEquals(5, starSwitchPointCount(true))
        assertEquals(10, starSwitchVertices(true).size)
    }

    @Test fun stateDifferenceDoesNotDependOnColor() {
        assertFalse(starSwitchVertices(false) == starSwitchVertices(true))
    }

    @Test fun bothStarsHaveAnUprightFirstTip() {
        listOf(false, true).forEach { checked ->
            val tip = starSwitchVertices(checked).first()
            assertEquals(0f, tip.x, 0.0001f)
            assertEquals(-1f, tip.y, 0.0001f)
        }
    }

    @Test fun everyCoordinateIsFiniteAndFitsItsCanvas() {
        listOf(false, true).forEach { checked ->
            starSwitchVertices(checked).forEach { vertex ->
                assertTrue(vertex.x.isFinite() && vertex.y.isFinite())
                assertTrue(abs(vertex.x) <= 1f && abs(vertex.y) <= 1f)
            }
        }
    }

    @Test fun outerTipsAlternateWithDistinctInnerValleys() {
        listOf(false, true).forEach { checked ->
            starSwitchVertices(checked).forEachIndexed { index, vertex ->
                val radius = sqrt(vertex.x * vertex.x + vertex.y * vertex.y)
                val expected = if (index % 2 == 0) 1f else if (checked) 0.46f else 0.22f
                assertEquals(expected, radius, 0.0001f)
            }
        }
    }
}
