package me.rerere.rikkahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.theme.presets.AutumnThemePreset
import me.rerere.rikkahub.ui.theme.presets.BlackThemePreset
import me.rerere.rikkahub.ui.theme.presets.ClaudeThemePreset
import me.rerere.rikkahub.ui.theme.presets.DeepSeekThemePreset
import me.rerere.rikkahub.ui.theme.presets.MinimalThemePreset
import me.rerere.rikkahub.ui.theme.presets.OceanThemePreset
import me.rerere.rikkahub.ui.theme.presets.SakuraThemePreset
import me.rerere.rikkahub.ui.theme.presets.SpringThemePreset
import me.rerere.rikkahub.ui.theme.presets.OrbisThemePreset
import me.rerere.rikkahub.ui.theme.presets.OrbisSeasonThemes
import me.rerere.rikkahub.BuildConfig

data class PresetTheme(
    val id: String,
    val name: @Composable () -> Unit,
    val standardLight: ColorScheme,
    val standardDark: ColorScheme,
) {
    fun getColorScheme(dark: Boolean): ColorScheme {
        return if (dark) standardDark else standardLight
    }
}

val PresetThemes by lazy {
    (if (BuildConfig.ORBIS_ENABLED) listOf(OrbisThemePreset) + OrbisSeasonThemes else emptyList()) + listOf(
        DeepSeekThemePreset,
        SakuraThemePreset,
        OceanThemePreset,
        SpringThemePreset,
        AutumnThemePreset,
        BlackThemePreset,
        MinimalThemePreset,
        ClaudeThemePreset,
    )
}

/** Presentation catalog only. Keep every historical ID above readable for backups and release. */
internal fun selectablePresetThemes(orbis: Boolean = BuildConfig.ORBIS_ENABLED): List<PresetTheme> =
    if (orbis) listOf(OrbisThemePreset, DeepSeekThemePreset) + OrbisSeasonThemes else PresetThemes

/** The stored preference remains intact, but legacy color overrides are inactive in Orbis. */
internal fun legacyThemeColorOptionEnabled(saved: Boolean, orbis: Boolean = BuildConfig.ORBIS_ENABLED): Boolean =
    !orbis && saved

/** Active native presets are selectable; explicit custom themes keep priority. */
internal fun resolveThemeForAppearance(id: String, customThemes: List<CustomTheme>,
    orbis: Boolean = BuildConfig.ORBIS_ENABLED): PresetTheme = if (orbis) {
    customThemes.find { it.id == id }?.asPresetTheme()
        ?: selectablePresetThemes(orbis = true).find { it.id == id } ?: OrbisThemePreset
} else {
    findThemeById(id, customThemes) ?: findPresetTheme(id)
}

/** Object identity avoids treating a custom theme with the same ID as the built-in style. */
internal fun isDeepSeekAppearance(theme: PresetTheme): Boolean = theme === DeepSeekThemePreset

fun findPresetTheme(id: String): PresetTheme {
    return PresetThemes.find { it.id == id } ?: SakuraThemePreset
}

fun findThemeById(id: String, customThemes: List<CustomTheme>): PresetTheme? {
    PresetThemes.find { it.id == id }?.let { return it }
    val custom = customThemes.find { it.id == id } ?: return null
    return custom.asPresetTheme()
}

private fun CustomTheme.asPresetTheme(): PresetTheme = PresetTheme(
    id = id,
    name = { androidx.compose.material3.Text(name) },
    standardLight = generateColorScheme(dark = false),
    standardDark = generateColorScheme(dark = true),
)
