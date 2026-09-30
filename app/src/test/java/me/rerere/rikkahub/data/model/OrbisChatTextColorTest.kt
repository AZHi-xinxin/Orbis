package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.*
import org.junit.Test

class OrbisChatTextColorTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun oldSettingsKeepThemeTextAndExistingAppearance() {
        val display = json.decodeFromString<DisplaySetting>("""{
            "orbisAppearance":{"bubbleOpacity":0.73,"composerOpacity":0.35,"bubbleStyle":"book"}
        }""")
        assertNull(display.orbisAppearance.chatTextColor)
        assertEquals(.73f, display.orbisAppearance.normalized().bubbleOpacity, 0f)
        assertEquals(.35f, display.orbisAppearance.composerOpacity, 0f)
        assertEquals(OrbisBubbleStyle.BOOK, display.orbisAppearance.bubbleStyle)
    }

    @Test fun opaqueArgbAndTransparentBubbleSurviveSerialization() {
        val original = DisplaySetting(orbisAppearance = OrbisAppearance(
            chatTextColor = 0xFFF1D8A2.toInt(), bubbleOpacity = 0f, composerOpacity = .35f,
            backgroundEnabled = true, backgroundImage = "file:///synthetic/kept.image",
        ))
        val restored = json.decodeFromString<DisplaySetting>(json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(original.orbisAppearance, restored.orbisAppearance.normalized())
    }

    @Test fun colorNormalizationForcesOpaqueAlphaWithoutChangingRgb() {
        listOf(0x00123456, 0x7F123456, 0xFF123456.toInt()).forEach { color ->
            val normalized = OrbisAppearance(chatTextColor = color).normalized()
            assertEquals(0xFF123456.toInt(), normalized.chatTextColor)
            assertEquals(normalized, normalized.normalized())
        }
        assertEquals(0xFF000000.toInt(), OrbisAppearance(chatTextColor = 0).normalized().chatTextColor)
        assertNull(OrbisAppearance().normalized().chatTextColor)
    }

    @Test fun bubbleOpacitySupportsTheEntireZeroToOneRange() {
        listOf(-1f to 0f, 0f to 0f, .01f to .01f, .4f to .4f, 1f to 1f, 2f to 1f).forEach { (input, expected) ->
            val value = OrbisAppearance(bubbleOpacity = input, composerOpacity = .35f,
                chatTextColor = 0xFF123456.toInt()).normalized()
            assertEquals(expected, value.bubbleOpacity, 0f)
            assertEquals(.35f, value.composerOpacity, 0f)
            assertEquals(0xFF123456.toInt(), value.chatTextColor)
        }
    }

    @Test fun resettingTextColorPreservesAllOtherPreferences() {
        val original = OrbisAppearance(chatTextColor = 0xFFFFFFFF.toInt(), bubbleOpacity = 0f,
            composerOpacity = .35f, bubbleStyle = OrbisBubbleStyle.STARS,
            backgroundEnabled = true, backgroundImage = "file:///synthetic/kept.image",
            floatingStars = false, reduceMotion = true)
        val reset = original.copy(chatTextColor = null).normalized()
        assertEquals(original, reset.copy(chatTextColor = original.chatTextColor))
    }
}
