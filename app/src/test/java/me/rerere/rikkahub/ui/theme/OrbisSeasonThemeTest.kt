package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBackgroundStyle
import me.rerere.rikkahub.ui.pages.orbis.OrbisSeason
import me.rerere.rikkahub.ui.pages.orbis.orbisSeasonColors
import me.rerere.rikkahub.ui.pages.orbis.usesSeasonWallpaper
import me.rerere.rikkahub.ui.theme.presets.OrbisSeasonThemes
import me.rerere.rikkahub.ui.theme.presets.orbisSeasonForTheme
import org.junit.Assert.*
import org.junit.Test

class OrbisSeasonThemeTest {
    @Test fun fourNamedPresetsRoundTripAndKeepCustomThemePriority() {
        assertEquals(4, OrbisSeasonThemes.size)
        OrbisSeasonThemes.forEach { preset ->
            assertSame(preset, resolveThemeForAppearance(preset.id, emptyList(), true))
            assertEquals(preset.id, orbisSeasonForTheme(preset).themeId)
            assertEquals(OrbisSeason.NONE, orbisSeasonForTheme(preset.copy()))
            val custom = CustomTheme(id = preset.id, name = "Synthetic custom")
            assertEquals(OrbisSeason.NONE, orbisSeasonForTheme(resolveThemeForAppearance(custom.id, listOf(custom), true)))
        }
    }

    @Test fun wallpaperNeverReplacesAnExplicitImageOrAlternativeBackgroundStyle() {
        val original = OrbisAppearance()
        OrbisSeason.entries.filter { it != OrbisSeason.NONE }.forEach { season ->
            assertTrue(usesSeasonWallpaper(season, original))
            assertTrue(usesSeasonWallpaper(season, original.copy(backgroundEnabled = true)))
            assertFalse(usesSeasonWallpaper(season, original.copy(backgroundEnabled = true, backgroundImage = "content://synthetic/image")))
            assertFalse(usesSeasonWallpaper(season, original.copy(backgroundEnabled = true, backgroundStyle = OrbisBackgroundStyle.BLUSH)))
            assertFalse(usesSeasonWallpaper(season, original.copy(backgroundEnabled = true, backgroundStyle = OrbisBackgroundStyle.STARS)))
        }
        assertFalse(usesSeasonWallpaper(OrbisSeason.NONE, original))
        assertEquals(OrbisAppearance(), original)
    }

    @Test fun seasonalTextAndPrimaryButtonsKeepReadableLightAndDarkContrast() {
        OrbisSeason.entries.forEach { season -> listOf(false, true).forEach { dark ->
            val c = orbisSeasonColors(season, dark)
            listOf(c.ink to c.page, c.mutedInk to c.page, c.onAccent to c.accent).forEach { (foreground, background) ->
                val ratio = (maxOf(foreground.luminance(), background.luminance()) + .05f) /
                    (minOf(foreground.luminance(), background.luminance()) + .05f)
                assertTrue("$season dark=$dark contrast=$ratio", ratio >= 4.5f)
            }
        } }
    }
}
