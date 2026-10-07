package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisSeason
import me.rerere.rikkahub.ui.pages.orbis.orbisSeasonColors
import me.rerere.rikkahub.ui.theme.PresetTheme

val OrbisSeasonThemes: List<PresetTheme> by lazy {
    OrbisSeason.entries.filter { it != OrbisSeason.NONE }.map { season ->
        PresetTheme(season.themeId, { Text("Orbis · ${season.title}") },
            orbisScheme(lightColorScheme(), orbisSeasonColors(season, false)),
            orbisScheme(darkColorScheme(), orbisSeasonColors(season, true)))
    }
}

/** Identity protects an explicit custom theme that happens to reuse a built-in ID. */
fun orbisSeasonForTheme(theme: PresetTheme): OrbisSeason =
    if (OrbisSeasonThemes.any { it === theme }) OrbisSeason.entries.first { it.themeId == theme.id }
    else OrbisSeason.NONE
