package me.rerere.rikkahub.data.orbis.character

import kotlinx.serialization.Serializable

@Serializable data class CharacterColors(val hair: String = "deepseek", val eyes: String = "deepseek")

/** User-requested palette names, not provider detection or affiliation. */
data class CharacterPalette(val id: String, val label: String, val rgb: Int, val endRgb: Int = rgb)

val characterPalettes = listOf(
    CharacterPalette("deepseek", "DeepSeek · 蓝", 0x6D92D0),
    CharacterPalette("gpt", "GPT / Codex · 白", 0xF1F3F7),
    CharacterPalette("claude", "Claude · 橙", 0xD98C55),
    CharacterPalette("gemini", "Gemini · 蓝粉", 0x6B9DE8, 0xE8A2CE),
    CharacterPalette("grok", "Grok · 黑", 0x17191D),
    CharacterPalette("doubao", "豆包 · 紫", 0xA085D9),
    CharacterPalette("kimi", "Kimi · 灰", 0x81868D),
    CharacterPalette("minimax", "MiniMax · 水红", 0xED8095),
    CharacterPalette("glm", "GLM · 银", 0xC3CBD4),
    CharacterPalette("mimo", "MiMo · 金", 0xE1B75F),
)

fun validateCharacterColors(colors: CharacterColors) {
    require(listOf(colors.hair, colors.eyes).all { id -> characterPalettes.any { it.id == id } }) { "character_colors" }
}

fun characterEyePalette(colors: CharacterColors, emotion: String): CharacterPalette =
    if (emotion in setOf("angry", "excited")) CharacterPalette("emotion-red", "情绪深红", 0x921C35)
    else characterPalettes.first { it.id == colors.eyes }

/** Normalized registration for the built-in, front-facing blue sprite, not arbitrary imported art. */
internal fun characterColorRegion(x: Float, y: Float): Int {
    if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return 0
    fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): Boolean =
        ((x - cx) / rx).let { a -> ((y - cy) / ry).let { b -> a * a + b * b <= 1f } }
    if (ellipse(.411f, .374f, .047f, .023f) || ellipse(.628f, .362f, .049f, .023f)) return 2
    if (y < .49f) return 1 // Only blue chroma is colored; skin, pupils, white highlights stay unchanged.
    if (y < .625f && (x in .33f.. .45f || x in .60f.. .70f)) return 1
    return 0 // Uniform, whale badge, neck light, metallic hands and card are never tinted.
}
