package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.Serializable

/** A small vector language, not a menu of effects: the author owns every curve and keyframe. */
@Serializable internal data class ThemeGradient(
    val colors: List<String>, val x1: Float = 0f, val y1: Float = 0f,
    val x2: Float = 100f, val y2: Float = 100f,
) {
    fun validate() {
        require(colors.size in 2..6 && listOf(x1,y1,x2,y2).all { it.isFinite() && it in -100f..200f } &&
            (x1 != x2 || y1 != y2)) { "theme_gradient_invalid" }
        colors.forEach(::themeArgb)
    }
}
@Serializable internal data class ThemeKeyframe(
    val at: Float, val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f,
    val rotation: Float = 0f, val opacity: Float = 1f,
)
@Serializable internal data class ThemeMotion(
    val durationMs: Int = 4000, val alternate: Boolean = true, val frames: List<ThemeKeyframe>,
) {
    fun validate() {
        require(durationMs in 1000..60000 && frames.size in 2..12 && frames.first().at == 0f && frames.last().at == 1f &&
            frames.zipWithNext().all { (a,b) -> a.at < b.at } && frames.all {
                it.at.isFinite() && it.at in 0f..1f && it.x.isFinite() && it.x in -100f..100f &&
                    it.y.isFinite() && it.y in -100f..100f && it.scale.isFinite() && it.scale in .1f..3f &&
                    it.rotation.isFinite() && it.rotation in -720f..720f && it.opacity.isFinite() && it.opacity in 0f..1f
            }) { "theme_motion_invalid" }
    }
    fun at(elapsedMs: Long): ThemeKeyframe {
        val cycle = (elapsedMs.coerceAtLeast(0) % (durationMs.toLong() * if (alternate) 2 else 1)).toFloat() / durationMs
        val phase = if (cycle > 1) 2 - cycle else cycle
        val b = frames.indexOfFirst { it.at >= phase }.coerceAtLeast(1)
        val from = frames[b-1]; val to = frames[b]; val t = (phase-from.at)/(to.at-from.at)
        fun mix(a: Float, b: Float) = a+(b-a)*t
        return ThemeKeyframe(phase,mix(from.x,to.x),mix(from.y,to.y),mix(from.scale,to.scale),
            mix(from.rotation,to.rotation),mix(from.opacity,to.opacity))
    }
}
@Serializable internal data class ThemeVectorLayer(
    val path: String, val fill: String? = null, val gradient: ThemeGradient? = null,
    val stroke: String? = null, val strokeWidth: Float = 1f, val opacity: Float = 1f,
    val motion: ThemeMotion? = null,
)
@Serializable internal data class ThemeDrawing(
    val outline: String? = null, val gradient: ThemeGradient? = null,
    val stroke: String? = null, val strokeWidth: Float = 1f,
    val layers: List<ThemeVectorLayer> = emptyList(),
) {
    fun validate() {
        outline?.let { require(parseThemePath(it).last().op == 'Z') { "theme_outline_not_closed" } }
        gradient?.validate(); stroke?.let(::themeArgb)
        require(strokeWidth.isFinite() && strokeWidth in 0f..8f && layers.size <= 12) { "theme_drawing_invalid" }
        layers.forEach {
            parseThemePath(it.path); it.fill?.let(::themeArgb); it.gradient?.validate(); it.stroke?.let(::themeArgb)
            require((it.fill != null || it.gradient != null || it.stroke != null) && it.strokeWidth.isFinite() &&
                it.strokeWidth in 0f..8f && it.opacity.isFinite() && it.opacity in 0f..1f) { "theme_layer_invalid" }
            it.motion?.validate()
        }
    }
}
internal data class ThemePathNode(val op: Char, val values: List<Float>)

/** SVG M/L/H/V/Q/C/Z, including lowercase relative coordinates and repeated segments. */
internal fun parseThemePath(path: String): List<ThemePathNode> {
    require(path.isNotBlank() && path.length <= 4096) { "theme_path_invalid" }
    val token = Regex("[MLHVQCZmlhvqcz]|[-+]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")
    var end = 0
    val parts = token.findAll(path).map {
        require(path.substring(end,it.range.first).all { c -> c.isWhitespace() || c == ',' }) { "theme_path_invalid" }
        end = it.range.last+1; it.value
    }.toList()
    require(path.substring(end).all { it.isWhitespace() || it == ',' }) { "theme_path_invalid" }
    var i=0; var command=' '; var x=0f; var y=0f; var startX=0f; var startY=0f
    val out = mutableListOf<ThemePathNode>()
    fun isCommand(s: String) = s.length == 1 && s[0] in "MLHVQCZmlhvqcz"
    while (i < parts.size) {
        if (isCommand(parts[i])) command=parts[i++][0]
        val op=command.uppercaseChar(); val relative=command.isLowerCase()
        require(op in "MLHVQCZ" && (out.isNotEmpty() || op=='M')) { "theme_path_invalid" }
        if (op=='Z') {
            out += ThemePathNode('Z',emptyList()); x=startX;y=startY;command=' '
        } else {
            val count=when(op) { 'M','L' -> 2; 'H','V' -> 1; 'Q' -> 4; 'C' -> 6; else -> error("theme_path_invalid") }
            require(i+count <= parts.size && parts.subList(i,i+count).none(::isCommand)) { "theme_path_invalid" }
            val values=parts.subList(i,i+count).map { it.toFloatOrNull() ?: error("theme_path_invalid") };i+=count
            require(values.all { it.isFinite() && it in -200f..200f }) { "theme_path_invalid" }
            val normalized=when(op) {
                'H' -> listOf(values[0]+if(relative)x else 0f,y)
                'V' -> listOf(x,values[0]+if(relative)y else 0f)
                else -> values.mapIndexed { n,v -> v+if(relative) { if(n%2==0)x else y } else 0f }
            }
            require(normalized.all { it in -200f..200f }) { "theme_path_invalid" }
            x=normalized[normalized.size-2];y=normalized.last()
            out+=ThemePathNode(if(op=='H'||op=='V')'L' else op,normalized)
            if(op=='M') { startX=x;startY=y;command=if(relative)'l' else 'L' }
        }
        require(out.size <= 160) { "theme_path_too_complex" }
    }
    require(out.isNotEmpty()) { "theme_path_invalid" }
    return out
}
