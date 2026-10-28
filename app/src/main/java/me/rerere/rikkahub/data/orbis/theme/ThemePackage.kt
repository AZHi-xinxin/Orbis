package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

internal val themeJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable internal data class ThemeColors(
    val background: String = "#F5F3ED", val surface: String = "#FFFFFF",
    val primary: String = "#466A80", val onPrimary: String = "#FFFFFF",
    val text: String = "#263342", val textSecondary: String = "#596879",
)
@Serializable internal data class ThemeTypography(
    val fontFamily: String = "default", val fontSizeScale: Float = 1f, val lineHeight: Float = 1.6f,
    val weight: Int = 400, val italic: Boolean = false, val letterSpacing: Float = 0f,
) {
    fun validate() {
        require(fontFamily in setOf("default", "sans", "serif", "monospace", "NotoSerifSC") &&
            fontSizeScale.isFinite() && fontSizeScale in .85f..1.4f && lineHeight.isFinite() && lineHeight in 1.2f..2f &&
            weight in 100..900 && letterSpacing.isFinite() && letterSpacing in -.05f.. .2f) { "theme_typography_invalid" }
    }
}
@Serializable internal data class ThemeBubble(
    val bg: String = "#FFFFFF", val text: String = "#263342", val radius: Int = 20,
    val padding: Int = 12, val maxWidthRatio: Float = .92f, val tail: String = "none",
    val shape: String = "round", val pattern: String = "none", val decoration: String = "none",
    val decorationAsset: String? = null, val opacity: Float = 1f,
    val typography: ThemeTypography? = null, val drawing: ThemeDrawing? = null,
)
@Serializable internal data class ThemeChrome(
    val dock: String? = null, val onDock: String? = null, val star: String? = null,
    val composer: String? = null, val onComposer: String? = null,
)
@Serializable internal data class ThemeBubbles(
    val assistant: ThemeBubble = ThemeBubble(),
    val user: ThemeBubble = ThemeBubble(bg = "#DFEBEF"),
)
@Serializable internal data class ThemeBackground(
    val type: String = "solid", val asset: String? = null, val dim: Float = .15f, val scroll: String = "fixed",
)
/** Data only. Neither a global Settings replacement nor an executable skin. */
@Serializable internal data class ThemePackage(
    val schemaVersion: Int = 1, val name: String,
    val basedOn: String = "builtin:default", val colors: ThemeColors = ThemeColors(),
    val typography: ThemeTypography = ThemeTypography(), val bubbles: ThemeBubbles = ThemeBubbles(),
    val background: ThemeBackground = ThemeBackground(), val assets: List<String> = emptyList(),
    val chrome: ThemeChrome = ThemeChrome(),
) {
    fun validate() {
        require(schemaVersion == 1 && basedOn == "builtin:default") { "theme_schema_unsupported" }
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "theme_name_invalid" }
        listOf(colors.background, colors.surface, colors.primary, colors.onPrimary, colors.text, colors.textSecondary,
            bubbles.assistant.bg, bubbles.assistant.text, bubbles.user.bg, bubbles.user.text).forEach(::themeArgb)
        require(themeContrast(colors.text, colors.background) >= 4.5 && themeContrast(colors.text, colors.surface) >= 4.5 &&
            themeContrast(colors.onPrimary, colors.primary) >= 4.5 && themeContrast(colors.textSecondary, colors.background) >= 3) { "theme_contrast_too_low" }
        listOf(bubbles.assistant, bubbles.user).forEach {
            require(it.radius in 0..32 && it.padding in 6..24 && it.maxWidthRatio.isFinite() && it.maxWidthRatio in .55f..1f &&
                it.tail in setOf("none", "cat", "curl")) { "theme_bubble_invalid" }
            require(it.opacity.isFinite() && it.opacity in 0f..1f) { "theme_bubble_opacity_invalid" }
            require(themeContrast(it.text, it.bg) >= 4.5) { "theme_contrast_too_low" }
            require(it.shape in setOf("round", "cat", "rabbit", "ticket", "speech") &&
                it.pattern in setOf("none", "dots", "stars", "gingham") &&
                it.decoration in setOf("none", "paw", "heart", "star")) { "theme_bubble_invalid" }
            require(it.decorationAsset == null || it.decorationAsset in assets) { "theme_asset_missing" }
            it.typography?.validate(); it.drawing?.validate()
        }
        typography.validate()
        require(background.type in setOf("solid", "image") && background.scroll == "fixed" &&
            background.dim.isFinite() && background.dim in 0f.. .8f) { "theme_background_invalid" }
        require(assets.size <= 8 && assets.distinct().size == assets.size && assets.all { it.matches(Regex("[a-f0-9]{64}\\.(png|jpg|webp)")) }) { "theme_asset_invalid" }
        require(if (background.type == "image") background.asset in assets else background.asset == null) { "theme_asset_missing" }
        listOfNotNull(chrome.dock, chrome.onDock, chrome.star, chrome.composer, chrome.onComposer).forEach(::themeArgb)
        require(themeContrast(chrome.onDock ?: colors.text, chrome.dock ?: colors.surface) >= 4.5 &&
            themeContrast(chrome.onComposer ?: colors.text, chrome.composer ?: colors.surface) >= 4.5) { "theme_contrast_too_low" }
    }
}
internal fun themeArgb(value: String): Int {
    require(value.matches(Regex("#[A-Fa-f0-9]{6}"))) { "theme_color_invalid" }
    return value.substring(1).toLong(16).toInt() or 0xff000000.toInt()
}
private fun themeContrast(a: String, b: String): Double {
    fun luminance(value: String): Double {
        val color = themeArgb(value)
        fun part(shift: Int): Double { val v = ((color shr shift) and 255) / 255.0; return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4) }
        return .2126 * part(16) + .7152 * part(8) + .0722 * part(0)
    }
    val x = luminance(a); val y = luminance(b)
    return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
}
internal fun parseTheme(text: String): ThemePackage {
    require(text.toByteArray().size <= 32 * 1024) { "theme_package_too_large" }
    return themeJson.decodeFromString<ThemePackage>(text).also { it.validate() }
}
@Serializable internal data class ThemeVersion(val revision: Int, val body: ThemePackage)
@Serializable internal data class ThemeEntry(val id: String, val revision: Int = 0, val authorRole: String = "ai",
    val draft: ThemePackage? = null, val versions: List<ThemeVersion> = emptyList())
@Serializable internal data class ThemeRef(val id: String, val revision: Int)
@Serializable internal data class ThemeState(val schema: Int = 1, val entries: List<ThemeEntry> = emptyList(),
    val active: ThemeRef? = null, val previous: List<ThemeRef?> = emptyList(), val pending: ThemeRef? = null,
    val autoApply: Set<String> = emptySet(), val followHumanFont: Boolean = true, val allowAiApply: Boolean = false) {
    fun resolve(ref: ThemeRef? = active) = entries.firstOrNull { it.id == ref?.id }?.versions?.firstOrNull { it.revision == ref?.revision }?.body
}
