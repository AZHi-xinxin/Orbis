package me.rerere.rikkahub.ui.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.presets.OrbisThemePreset
import me.rerere.rikkahub.ui.theme.presets.SakuraThemePreset
import org.junit.Assert.*
import org.junit.Test

/** Synthetic values only. Display policy must never rewrite saved appearance preferences. */
class OrbisThemePolicyTest {
    @Test fun onlyActiveOrbisAndDeepSeekPresetsAreSelectableButHistoricalCatalogRemainsReadable() {
        assertEquals(listOf("orbis", "deepseek"), selectablePresetThemes(orbis = true).map { it.id })
        assertSame(PresetThemes, selectablePresetThemes(orbis = false))
        val legacy = PresetThemes.filter { it.id !in setOf("orbis", "deepseek") }
        assertTrue(legacy.isNotEmpty())
        legacy.forEach {
            assertSame(it, findPresetTheme(it.id))
            assertSame(it, findThemeById(it.id, emptyList()))
        }
    }

    @Test fun retiredStoredPresetFallsBackToOrbisWithoutChangingRawSettings() {
        val saved = Settings(themeId = "sakura", dynamicColor = true,
            customThemes = listOf(CustomTheme(id = "synthetic-custom", name = "Kept")))
        val before = saved.copy()
        PresetThemes.filter { it.id !in setOf("orbis", "deepseek") }.forEach {
            assertSame(OrbisThemePreset, resolveThemeForAppearance(it.id, saved.customThemes, orbis = true))
        }
        assertSame(OrbisThemePreset, resolveThemeForAppearance(saved.themeId, saved.customThemes, orbis = true))
        assertEquals(before, saved)
        assertEquals("sakura", saved.themeId)
        assertTrue(saved.dynamicColor)
    }

    @Test fun unknownAndOrbisStoredIdsResolveToOrbis() {
        assertSame(OrbisThemePreset, resolveThemeForAppearance("unknown-synthetic-id", emptyList(), orbis = true))
        assertSame(OrbisThemePreset, resolveThemeForAppearance("orbis", emptyList(), orbis = true))
        // Non-Orbis fallback remains the original compatibility behavior.
        assertSame(SakuraThemePreset, resolveThemeForAppearance("unknown-synthetic-id", emptyList(), orbis = false))
    }

    @Test fun selectedCustomThemeKeepsItsIdColorsAndSerialization() {
        val saved = CustomTheme(id = "synthetic-custom", name = "Synthetic custom",
            primaryColorArgb = 0xFF335577, secondaryColorArgb = 0xFFAACCEE)
        val serialized = Json.encodeToString(saved)
        val resolved = resolveThemeForAppearance(saved.id, listOf(saved), orbis = true)
        assertEquals(saved.id, resolved.id)
        listOf(false, true).forEach { dark ->
            val expected = saved.generateColorScheme(dark)
            val actual = resolved.getColorScheme(dark)
            assertEquals(expected.primary, actual.primary)
            assertEquals(expected.secondary, actual.secondary)
            assertEquals(expected.tertiary, actual.tertiary)
            assertEquals(expected.background, actual.background)
        }
        assertEquals(serialized, Json.encodeToString(saved))
        assertEquals(saved, Json.decodeFromString<CustomTheme>(serialized))
    }

    @Test fun legacyDynamicAndAmoledValuesAreIgnoredOnlyInOrbis() {
        listOf(false, true).forEach { saved ->
            assertFalse(legacyThemeColorOptionEnabled(saved, orbis = true))
            assertEquals(saved, legacyThemeColorOptionEnabled(saved, orbis = false))
        }
    }

    @Test fun ordinaryLightAndDarkPalettesRemainAvailable() {
        val theme = resolveThemeForAppearance("orbis", emptyList(), orbis = true)
        assertSame(theme.standardLight, theme.getColorScheme(false))
        assertSame(theme.standardDark, theme.getColorScheme(true))
        assertNotEquals(theme.standardLight.background, theme.standardDark.background)
        assertEquals(listOf(ColorMode.SYSTEM, ColorMode.LIGHT, ColorMode.DARK), ColorMode.entries.toList())
    }
}
