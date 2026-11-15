package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.Serializable

/** Optional native background material. Old packages have no material and keep their exact fill. */
@Serializable internal data class ThemeBubbleMaterial(
    val type: String = "frosted",
    val blur: Float = 18f,
    val tintOpacity: Float = .35f,
    val refraction: Float = .65f,
    val displacement: Float = 12f,
    val depth: Float = .3f,
    val dispersion: Float = .25f,
    val highlight: Float = .45f,
    val rim: Float = .5f,
    val lightX: Float = -.6f,
    val lightY: Float = -.8f,
    val shimmer: Boolean = false,
    val shimmerDurationMs: Int = 6000,
    val flow: Float = 0f,
    val flowSpeed: Float = 1f,
    val metallic: Float = 0f,
    val fold: Float = 0f,
    val refractionHeight: Float = .25f,
    val edgeColors: List<String> = listOf("#8ECDF4", "#C5EAFE", "#D8C8F5", "#F5D4E5"),
    val edgeShadow: String = "#DDE3EB",
) {
    fun validate() {
        require(type in setOf("frosted", "liquid") && blur.isFinite() && blur in 0f..32f &&
            displacement.isFinite() && displacement in 0f..24f &&
            listOf(tintOpacity, refraction, depth, dispersion, highlight, rim).all { it.isFinite() && it in 0f..1f } &&
            listOf(lightX, lightY).all { it.isFinite() && it in -1f..1f } &&
            shimmerDurationMs in 2000..30000 && flow.isFinite() && flow in 0f..1f &&
            metallic.isFinite() && metallic in 0f..1f && flowSpeed.isFinite() && flowSpeed in .2f..2f &&
            fold.isFinite() && fold in 0f..1f && refractionHeight.isFinite() && refractionHeight in .1f.. .5f) { "theme_material_invalid" }
        require(edgeColors.size in 2..4) { "theme_material_invalid" }
        edgeColors.forEach(::themeArgb);themeArgb(edgeShadow)
    }
}

/** Sized in dp, never stretched with message length. Reserved bands keep decorations off text. */
@Serializable internal data class ThemeSticker(
    val asset: String? = null,
    val drawing: ThemeDrawing? = null,
    val anchor: String = "topEnd",
    val size: Int = 32,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
) {
    fun validate() {
        require((asset != null) xor (drawing != null)) { "theme_sticker_source_invalid" }
        require(anchor in setOf("topStart", "topCenter", "topEnd", "bottomStart", "bottomCenter", "bottomEnd") &&
            size in 16..48 && rotation.isFinite() && rotation in -45f..45f &&
            opacity.isFinite() && opacity in 0f..1f) { "theme_sticker_invalid" }
        drawing?.validate()
        require(drawing == null || drawing.layers.isNotEmpty()) { "theme_sticker_drawing_empty" }
    }
}

internal fun ThemeBubble.hasMotion(): Boolean = material?.shimmer == true || (material?.type == "liquid" && material.flow > 0f) ||
    drawing?.layers.orEmpty().any { it.motion != null } ||
    stickers.any { it.drawing?.layers.orEmpty().any { layer -> layer.motion != null } }

/** An optional surface texture, independent of the optical material beneath it. */
@Serializable internal data class ThemeBubbleTexture(
    val type: String = "ice",
    val asset: String? = null,
    val color: String = "#ECF8FF",
    val opacity: Float = .65f,
    val scale: Float = 1f,
    val density: Float = .6f,
    val seed: Int = 7,
    val edgeFade: Float = .75f,
) {
    fun validate() {
        require(type in setOf("ice", "image") && (if (type == "ice") asset == null else !asset.isNullOrBlank()) &&
            opacity.isFinite() && opacity in 0f..1f && scale.isFinite() && scale in .5f..2f &&
            density.isFinite() && density in .1f..1f && seed in 0..65535 &&
            edgeFade.isFinite() && edgeFade in 0f..1f) { "theme_texture_invalid" }
        themeArgb(color)
    }
}

@Serializable internal data class ThemeSurface(
    val material: ThemeBubbleMaterial? = null,
    val texture: ThemeBubbleTexture? = null,
    val opacity: Float = 1f,
) {
    fun validate() {
        material?.validate();texture?.validate()
        require(opacity.isFinite() && opacity in 0f..1f) { "theme_surface_invalid" }
    }
    fun hasMotion() = material?.shimmer == true || (material?.type == "liquid" && material.flow > 0f)
}
internal fun ThemeChrome.surfaceFor(role: String): ThemeSurface? = when(role) {
    "composer" -> composerSurface ?: surface
    "dock" -> dockSurface ?: surface
    "star" -> starSurface ?: dockSurface ?: surface
    else -> null
}
internal fun ThemePackage.hasMaterial() = bubbles.assistant.material != null || bubbles.user.material != null ||
    listOfNotNull(chrome.surface,chrome.composerSurface,chrome.dockSurface,chrome.starSurface).any { it.material != null }
internal fun ThemePackage.hasMotion() = bubbles.assistant.hasMotion() || bubbles.user.hasMotion() ||
    listOfNotNull(chrome.surface,chrome.composerSurface,chrome.dockSurface,chrome.starSurface).any { it.hasMotion() }
