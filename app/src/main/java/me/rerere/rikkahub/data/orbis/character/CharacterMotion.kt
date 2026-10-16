package me.rerere.rikkahub.data.orbis.character

import kotlin.math.exp
import kotlin.math.sin

/** Small, local, reversible 2D deformations; never translates the room or claims hidden 3D anatomy. */
data class CharacterMotion(val seconds: Float = 0f, val lookX: Float = 0f, val lookY: Float = 0f,
    val reaction: Float = 0f, val region: CharacterRegion? = null, val enabled: Boolean = false)

fun characterMotionPoint(p: CharacterPoint, head: CharacterRegion?, m: CharacterMotion): CharacterPoint {
    if (!m.enabled || head == null) return p
    fun weight(cx: Float, cy: Float, rx: Float, ry: Float): Float {
        val x = (p.x - cx) / rx; val y = (p.y - cy) / ry
        return exp(-(x * x + y * y) * 2f)
    }
    val t = m.seconds.takeIf { it.isFinite() } ?: 0f
    val lx = m.lookX.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    val ly = m.lookY.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
    val cx = head.x + head.width / 2; val cy = head.y + head.height / 2
    val hw = weight(cx, cy, head.width * .75f, head.height * .8f)
    val hair = weight(cx, head.y + head.height * .18f, head.width * .6f, head.height * .28f)
    val chest = weight(.5f, head.y + head.height * 1.8f, .38f, .25f)
    var dx = hw * (lx * .006f + sin(t * .62f) * .0016f) + hair * sin(t * 1.6f) * .0022f
    var dy = hw * ly * .003f - chest * sin(t * 1.45f) * .0017f
    for (eyeX in listOf(cx - head.width * .18f, cx + head.width * .18f)) {
        val eye = weight(eyeX, head.y + head.height * .62f, head.width * .14f, head.height * .10f)
        dx += eye * lx * .0012f; dy += eye * ly * .0008f
    }
    m.region?.let { r ->
        val touch = m.reaction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val w = weight(r.x + r.width / 2, r.y + r.height / 2, maxOf(.10f, r.width * .75f), maxOf(.08f, r.height * .8f))
        dy -= w * touch * .009f; dx += w * touch * .003f
    }
    // Fix image edges so cut-off wrists/torso never reveal a gap while breathing.
    val edge = (minOf(p.x, 1 - p.x, p.y, 1 - p.y) / .035f).coerceIn(0f, 1f)
    return CharacterPoint(p.x + dx.coerceIn(-.018f, .018f) * edge, p.y + dy.coerceIn(-.018f, .018f) * edge)
}

/** Inverse of the bounded displacement for alpha and region hit-testing. */
fun characterMotionSource(p: CharacterPoint, head: CharacterRegion?, m: CharacterMotion): CharacterPoint {
    var source = p
    repeat(8) { val drawn = characterMotionPoint(source, head, m); source = CharacterPoint(source.x + p.x - drawn.x, source.y + p.y - drawn.y) }
    return source
}
