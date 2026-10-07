package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.pages.orbis.OrbisColors
import me.rerere.rikkahub.ui.pages.orbis.OrbisPalette
import me.rerere.rikkahub.ui.theme.PresetTheme

/** Selectable native preset. Does not overwrite an existing theme or wallpaper preference. */
val OrbisThemePreset by lazy {
    PresetTheme(
        id = "orbis",
        name = { Text("Orbis · 星夜与奶油") },
        standardLight = orbisScheme(lightColorScheme(), OrbisPalette.Light),
        standardDark = orbisScheme(darkColorScheme(), OrbisPalette.Dark),
    )
}

internal fun orbisScheme(base: ColorScheme, c: OrbisColors) = base.copy(
    primary = c.accent, onPrimary = c.onAccent,
    primaryContainer = c.tintedPanel, onPrimaryContainer = c.ink,
    secondary = c.onSand, onSecondary = c.sand,
    secondaryContainer = c.sand, onSecondaryContainer = c.onSand,
    tertiary = c.indigo, onTertiary = c.onIndigo,
    tertiaryContainer = c.tintedPanel, onTertiaryContainer = c.ink,
    background = c.page, onBackground = c.ink,
    surface = c.panel, onSurface = c.ink,
    surfaceVariant = c.tintedPanel, onSurfaceVariant = c.mutedInk,
    surfaceTint = Color.Transparent,
    surfaceDim = c.page, surfaceBright = c.raisedPanel,
    surfaceContainerLowest = c.page, surfaceContainerLow = c.panel,
    surfaceContainer = c.panel, surfaceContainerHigh = c.raisedPanel,
    surfaceContainerHighest = c.tintedPanel,
    outline = c.mutedInk, outlineVariant = c.border,
)
