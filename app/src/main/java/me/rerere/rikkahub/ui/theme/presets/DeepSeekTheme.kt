package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.pages.orbis.OrbisColors
import me.rerere.rikkahub.ui.pages.orbis.OrbisPalette
import me.rerere.rikkahub.ui.theme.PresetTheme

const val DeepSeekThemeId = "deepseek"

/** Opt-in appearance only: no model/provider association or preference migration. */
val DeepSeekThemePreset by lazy {
    PresetTheme(
        id = DeepSeekThemeId,
        name = { Text("DeepSeek · 简约蓝") },
        standardLight = deepSeekColorScheme(lightColorScheme(), OrbisPalette.DeepSeekLight, false),
        standardDark = deepSeekColorScheme(darkColorScheme(), OrbisPalette.DeepSeekDark, true),
    )
}

/** Shared by the global preset and nested native scopes, including explicit light/dark overrides. */
internal fun deepSeekColorScheme(base: ColorScheme, c: OrbisColors, dark: Boolean) = base.copy(
    primary = c.accent, onPrimary = c.onAccent,
    primaryContainer = c.tintedPanel, onPrimaryContainer = c.accent,
    secondary = c.mutedInk, onSecondary = c.page,
    secondaryContainer = c.sand, onSecondaryContainer = c.onSand,
    tertiary = c.indigo, onTertiary = c.onIndigo,
    tertiaryContainer = c.tintedPanel, onTertiaryContainer = c.indigo,
    background = c.page, onBackground = c.ink,
    surface = c.panel, onSurface = c.ink,
    surfaceVariant = c.raisedPanel, onSurfaceVariant = c.mutedInk,
    surfaceTint = Color.Transparent,
    surfaceDim = c.page, surfaceBright = c.raisedPanel,
    surfaceContainerLowest = c.page, surfaceContainerLow = c.panel,
    surfaceContainer = c.panel, surfaceContainerHigh = c.raisedPanel,
    surfaceContainerHighest = c.sand,
    outline = c.mutedInk, outlineVariant = c.border,
    inverseSurface = if (dark) OrbisPalette.DeepSeekLight.sand else OrbisPalette.DeepSeekDark.panel,
    inverseOnSurface = if (dark) OrbisPalette.DeepSeekLight.ink else OrbisPalette.DeepSeekDark.ink,
    inversePrimary = if (dark) OrbisPalette.DeepSeekLight.accent else OrbisPalette.DeepSeekDark.accent,
    scrim = Color.Black,
)
