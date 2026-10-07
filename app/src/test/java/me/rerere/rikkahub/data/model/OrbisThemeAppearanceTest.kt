package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test

class OrbisThemeAppearanceTest {
    @Test fun legacyPreferencesKeepOriginalWallpaperAndDefaults() {
        val value = Json.decodeFromString<DisplaySetting>("{}")
        assertEquals(OrbisAppearance(), value.orbisAppearance)
        assertTrue(value.appearanceForStyle(true).backgroundEnabled)
        assertNull(value.appearanceForStyle(true).backgroundImage)
        assertFalse(value.appearanceForStyle(true).floatingStars)
        assertFalse(value.deepSeekShowAvatars)
        assertTrue(value.deepSeekCollapseThinking)
    }

    @Test fun profilesCanBeEditedAndRoundTrippedWithoutOverwritingOneAnother() {
        val original = DisplaySetting(orbisAppearance = OrbisAppearance(
            backgroundImage = "file:///synthetic/original.png", composerOpacity = .55f))
        val changed = original.withAppearanceForStyle(true) {
            it.copy(backgroundImage = "file:///synthetic/ds.png", composerOpacity = .3f, chatTextColor = 0xFF123456.toInt())
        }
        assertEquals(original.orbisAppearance, changed.orbisAppearance)
        assertEquals(.3f, changed.appearanceForStyle(true).composerOpacity, 0f)
        assertEquals(.55f, changed.appearanceForStyle(false).composerOpacity, 0f)
        assertEquals(changed, Json.decodeFromString<DisplaySetting>(Json.encodeToString(changed)))
        val reset = changed.withAppearanceForStyle(true) { deepSeekDefaultAppearance() }
        assertEquals(original.orbisAppearance, reset.orbisAppearance)
        assertEquals(original.userAvatar, reset.userAvatar)
    }

    @Test fun switchingSkinDoesNotChangeSplitBubblesOrActionRecognition() {
        val flow = OrbisChatFlowSettings(enabled = true, distinguishActions = true,
            markers = setOf(OrbisActionMarker.FULLWIDTH_ROUND), collapseActions = true)
        val display = DisplaySetting(orbisAppearance = OrbisAppearance(chatFlow = flow))
        assertEquals(flow, display.appearanceForStyle(true).chatFlow)
        assertEquals(flow, display.appearanceForStyle(false).chatFlow)
    }

    @Test fun headerOpacityPersistsIndependentlyAndResetOnlyChangesTheSelectedProfile() {
        val original = DisplaySetting(userNickname = "Synthetic", orbisAppearance = OrbisAppearance(
            headerOpacity = .25f, composerOpacity = .6f, bubbleOpacity = .8f,
            backgroundImage = "file:///synthetic/original.png"))
        val changed = original.withAppearanceForStyle(true) { it.copy(headerOpacity = 0f) }
        val restored = Json.decodeFromString<DisplaySetting>(Json.encodeToString(changed))
        assertEquals(original.orbisAppearance, restored.orbisAppearance)
        assertEquals(0f, restored.appearanceForStyle(true).headerOpacity, 0f)
        assertEquals(.25f, restored.appearanceForStyle(false).headerOpacity, 0f)
        val deepSeekReset = restored.withAppearanceForStyle(true) { deepSeekDefaultAppearance() }
        assertEquals(.75f, deepSeekReset.appearanceForStyle(true).headerOpacity, 0f)
        assertEquals(original.orbisAppearance, deepSeekReset.orbisAppearance)
        val orbisReset = restored.withAppearanceForStyle(false) { OrbisAppearance() }
        assertEquals(.75f, orbisReset.appearanceForStyle(false).headerOpacity, 0f)
        assertEquals(restored.deepSeekAppearance, orbisReset.deepSeekAppearance)
        assertEquals("Synthetic", orbisReset.userNickname)
    }

    @Test fun themeOverridesAreNormalized() {
        val value = DisplaySetting().withAppearanceForStyle(true) { it.copy(composerOpacity = -2f, bubbleOpacity = 8f) }
        assertEquals(.15f, value.appearanceForStyle(true).composerOpacity, 0f)
        assertEquals(1f, value.appearanceForStyle(true).bubbleOpacity, 0f)
    }

    @Test fun greetingUsesOnlyTheChosenDisplayName() {
        assertEquals("___，欢迎回家", deepSeekWelcomeText("  "))
        assertEquals("Test，欢迎回家", deepSeekWelcomeText(" Test "))
    }
}
