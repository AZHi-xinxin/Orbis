package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.ui.pages.orbis.OrbisPalette
import me.rerere.rikkahub.ui.pages.orbis.resolveOrbisVisualColors
import me.rerere.rikkahub.ui.theme.presets.DeepSeekThemeId
import me.rerere.rikkahub.ui.theme.presets.DeepSeekThemePreset
import me.rerere.rikkahub.ui.theme.presets.OrbisThemePreset
import me.rerere.rikkahub.ui.theme.presets.deepSeekColorScheme
import org.junit.Assert.*
import org.junit.Test

/** Theme selection and tokens only; no device, provider, chat data or preference writes. */
class DeepSeekThemeTest {
    @Test fun deepSeekPresetRequiresItsExplicitSavedId() {
        assertEquals("deepseek", DeepSeekThemeId)
        val selected = resolveThemeForAppearance(DeepSeekThemeId, emptyList(), orbis = true)
        assertSame(DeepSeekThemePreset, selected)
        assertTrue(isDeepSeekAppearance(selected))
        listOf("", "orbis", "sakura", "DeepSeek", "unknown").forEach { id ->
            val other = resolveThemeForAppearance(id, emptyList(), orbis = true)
            assertSame(OrbisThemePreset, other)
            assertFalse(isDeepSeekAppearance(other))
        }
    }

    @Test fun activeCatalogKeepsExistingChoicesBeforeFourSeasons() {
        assertEquals(listOf("orbis", DeepSeekThemeId, "orbis-spring", "orbis-summer", "orbis-autumn", "orbis-winter"),
            selectablePresetThemes(orbis = true).map { it.id })
        assertSame(DeepSeekThemePreset, findPresetTheme(DeepSeekThemeId))
        assertSame(DeepSeekThemePreset, findThemeById(DeepSeekThemeId, emptyList()))
    }

    @Test fun customThemeWithSameIdKeepsPriorityAndNeverActivatesDeepSeekStyle() {
        val custom = CustomTheme(id = DeepSeekThemeId, name = "Synthetic custom",
            primaryColorArgb = 0xFF456745, secondaryColorArgb = 0xFF987654)
        val before = Json.encodeToString(custom)
        val resolved = resolveThemeForAppearance(custom.id, listOf(custom), orbis = true)
        assertFalse(isDeepSeekAppearance(resolved))
        listOf(false, true).forEach { dark ->
            assertEquals(custom.generateColorScheme(dark).primary, resolved.getColorScheme(dark).primary)
            assertEquals(custom.generateColorScheme(dark).background, resolved.getColorScheme(dark).background)
        }
        assertEquals(before, Json.encodeToString(custom))
    }

    @Test fun builtInStyleMarkerRequiresActualPresetRatherThanMatchingIdOrColors() {
        assertTrue(isDeepSeekAppearance(DeepSeekThemePreset))
        assertFalse(isDeepSeekAppearance(DeepSeekThemePreset.copy()))
        assertFalse(isDeepSeekAppearance(OrbisThemePreset))
    }

    @Test fun shallowBackgroundsAreNeutralAndFlatInBothModes() {
        val light = OrbisPalette.DeepSeekLight
        val dark = OrbisPalette.DeepSeekDark
        assertEquals(Color.White, light.page)
        assertEquals(Color(0xFF1A1A1A), dark.page)
        listOf(light, dark).forEach { palette ->
            assertEquals(palette.page, palette.pageTop)
            listOf(palette.page, palette.panel).forEach { neutral ->
                assertEquals(neutral.red, neutral.green, .0001f)
                assertEquals(neutral.green, neutral.blue, .0001f)
            }
            assertTrue(palette.accent.blue > palette.accent.red)
            assertTrue(palette.accent.blue > palette.accent.green)
            assertEquals(palette.accent, palette.star)
        }
    }

    @Test fun primaryAndContainerTextHaveReadableContrastInBothModes() {
        listOf(OrbisPalette.DeepSeekLight, OrbisPalette.DeepSeekDark).forEach { c ->
            listOf(c.ink to c.page, c.ink to c.panel,
                c.mutedInk to c.raisedPanel, c.onDock to c.dock,
                c.onAccent to c.accent, c.accent to c.tintedPanel).forEach { (text, background) ->
                val lighter = maxOf(text.luminance(), background.luminance())
                val darker = minOf(text.luminance(), background.luminance())
                assertTrue("Text contrast must be at least 4.5:1", (lighter + .05f) / (darker + .05f) >= 4.5f)
            }
        }
    }

    @Test fun materialSchemeUsesDeepSeekTokensForEveryMainSurfaceRole() {
        listOf(false to OrbisPalette.DeepSeekLight, true to OrbisPalette.DeepSeekDark).forEach { (dark, c) ->
            val scheme = DeepSeekThemePreset.getColorScheme(dark)
            assertEquals(c.accent, scheme.primary)
            assertEquals(c.onAccent, scheme.onPrimary)
            assertEquals(c.page, scheme.background)
            assertEquals(c.ink, scheme.onBackground)
            assertEquals(c.panel, scheme.surface)
            assertEquals(c.raisedPanel, scheme.surfaceContainerHigh)
            assertEquals(c.sand, scheme.surfaceContainerHighest)
            assertEquals(c.border, scheme.outlineVariant)
            assertEquals(Color.Transparent, scheme.surfaceTint)
        }
        assertNotEquals(DeepSeekThemePreset.standardLight, DeepSeekThemePreset.standardDark)
    }

    @Test fun nativePagesUseDeepSeekTokensEvenWithoutAnExplicitPalette() {
        assertSame(OrbisPalette.DeepSeekLight,
            resolveOrbisVisualColors(darkTheme = false, deepSeekStyle = true))
        assertSame(OrbisPalette.DeepSeekDark,
            resolveOrbisVisualColors(darkTheme = true, deepSeekStyle = true))
    }

    @Test fun originalOrbisTokensRemainTheFallbackWhenDeepSeekIsNotSelected() {
        assertSame(OrbisPalette.Light,
            resolveOrbisVisualColors(darkTheme = false, deepSeekStyle = false))
        assertSame(OrbisPalette.Dark,
            resolveOrbisVisualColors(darkTheme = true, deepSeekStyle = false))
        assertEquals(Color(0xFFFBF6ED), OrbisPalette.Light.page)
        assertEquals(Color(0xFF171827), OrbisPalette.Dark.page)
        assertEquals(Color(0xFFF9DFAA), OrbisPalette.Light.star)
        assertEquals(Color(0xFFFFE5AD), OrbisPalette.Dark.star)
    }

    @Test fun nestedPagesInheritTheirCurrentPaletteInsteadOfResettingToOrbis() {
        listOf(false to OrbisPalette.DeepSeekLight, true to OrbisPalette.DeepSeekDark).forEach { (dark, c) ->
            assertSame(c, resolveOrbisVisualColors(dark, deepSeekStyle = true,
                inherited = c, inheritedDarkTheme = dark))
        }
    }

    @Test fun explicitScopedPaletteSurvivesNestedPages() {
        val custom = OrbisPalette.DeepSeekLight.copy(accent = Color(0xFF256025))
        listOf(false, true).forEach { deepSeek ->
            assertSame(custom, resolveOrbisVisualColors(darkTheme = false, deepSeekStyle = deepSeek,
                inherited = custom, inheritedDarkTheme = false))
        }
    }

    @Test fun changingLocalLightDarkModeUsesCorrespondingDeepSeekTokens() {
        assertSame(OrbisPalette.DeepSeekDark, resolveOrbisVisualColors(darkTheme = true,
            deepSeekStyle = true, inherited = OrbisPalette.DeepSeekLight, inheritedDarkTheme = false))
        assertSame(OrbisPalette.DeepSeekLight, resolveOrbisVisualColors(darkTheme = false,
            deepSeekStyle = true, inherited = OrbisPalette.DeepSeekDark, inheritedDarkTheme = true))
        assertSame(OrbisPalette.Dark, resolveOrbisVisualColors(darkTheme = true,
            deepSeekStyle = false, inherited = OrbisPalette.Light, inheritedDarkTheme = false))
    }

    @Test fun nestedMaterialScopeReplacesOppositeModeSurfacesAndKeepsSemanticErrors() {
        listOf(false to OrbisPalette.DeepSeekLight, true to OrbisPalette.DeepSeekDark).forEach { (dark, c) ->
            val inherited = OrbisThemePreset.getColorScheme(!dark)
            val scoped = deepSeekColorScheme(inherited, c, dark)
            assertEquals(c.page, scoped.surfaceDim)
            assertEquals(c.raisedPanel, scoped.surfaceBright)
            assertEquals(c.sand, scoped.surfaceContainerHighest)
            assertEquals(c.accent, scoped.onPrimaryContainer)
            assertEquals(inherited.error, scoped.error)
            assertEquals(inherited.onError, scoped.onError)
        }
    }
}
