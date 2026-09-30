package com.lover.connect.ui.components

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Normalized geometry, independent of Compose and Android for deterministic shape tests. */
internal data class StarVertex(val x: Float, val y: Float)

internal fun starSwitchPointCount(checked: Boolean): Int = if (checked) 5 else 4

internal fun starSwitchVertices(checked: Boolean): List<StarVertex> {
    val points = starSwitchPointCount(checked)
    val innerRadius = if (checked) 0.46 else 0.22
    return List(points * 2) { index ->
        val angle = -PI / 2 + index * PI / points
        val radius = if (index % 2 == 0) 1.0 else innerRadius
        StarVertex((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
    }
}
