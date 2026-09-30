package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisEventAppearanceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun oldAppearanceGetsLightEventDefaultWithoutChangingExistingPreferences() {
        val restored = json.decodeFromString<OrbisAppearance>("""{"bubbleOpacity":0.6,"composerOpacity":0.8,"chatTextColor":-1}""")
        assertEquals(.22f, restored.eventOpacity, 0f)
        assertEquals(.6f, restored.bubbleOpacity, 0f)
        assertEquals(.8f, restored.composerOpacity, 0f)
        assertEquals(-1, restored.chatTextColor)
    }

    @Test fun eventOpacityRoundTripsInSharedDisplayPreferencesIncludingFullTransparency() {
        for (opacity in listOf(0f, .22f, .63f, 1f)) {
            val value = DisplaySetting(userNickname = "Synthetic", orbisAppearance = OrbisAppearance(eventOpacity = opacity))
            assertEquals(value, json.decodeFromString<DisplaySetting>(json.encodeToString(value)))
        }
    }

    @Test fun eventOpacityIsClampedIndependently() {
        assertEquals(0f, OrbisAppearance(eventOpacity = -1f).normalized().eventOpacity, 0f)
        assertEquals(1f, OrbisAppearance(eventOpacity = 2f).normalized().eventOpacity, 0f)
        assertEquals(.57f, OrbisAppearance(eventOpacity = .57f).normalized().eventOpacity, 0f)
    }

    @Test fun nonFiniteOpacityUsesLightDefault() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(.22f, OrbisAppearance(eventOpacity = value).normalized().eventOpacity, 0f)
        }
    }

    @Test fun eventOpacityDoesNotChangeChatBubbleComposerOrWallpaper() {
        val before = OrbisAppearance(bubbleOpacity = .7f, composerOpacity = .8f,
            backgroundEnabled = true, backgroundImage = "file:///synthetic/kept.png", chatTextColor = -1)
        assertEquals(before.copy(eventOpacity = 0f), before.copy(eventOpacity = -1f).normalized())
    }

    @Test fun transparentEventBackgroundStillNormalizesCustomTextToOpaque() {
        val value = OrbisAppearance(eventOpacity = 0f, chatTextColor = 0x00336699).normalized()
        assertEquals(0f, value.eventOpacity, 0f)
        assertEquals(0xFF336699.toInt(), value.chatTextColor)
    }
}
