package me.rerere.rikkahub.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbisComposerOpacityTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `old appearance settings keep an opaque composer and preserve custom bubble settings`() {
        val display = json.decodeFromString<DisplaySetting>("""{
            "userNickname":"Synthetic user",
            "orbisAppearance":{
                "bubbleStyle":"glass",
                "bubbleOpacity":0.73,
                "backgroundEnabled":true,
                "backgroundImage":"file:///synthetic/kept.png",
                "floatingStars":false
            }
        }""")
        assertEquals("Synthetic user", display.userNickname)
        assertEquals(1f, display.orbisAppearance.composerOpacity, 0f)
        assertEquals(.73f, display.orbisAppearance.bubbleOpacity, 0f)
        assertEquals(OrbisBubbleStyle.GLASS, display.orbisAppearance.bubbleStyle)
        assertEquals("file:///synthetic/kept.png", display.orbisAppearance.backgroundImage)
        assertEquals(false, display.orbisAppearance.floatingStars)
    }

    @Test
    fun `missing appearance settings also retain the old opaque composer`() {
        val display = json.decodeFromString<DisplaySetting>("{}")
        assertEquals(1f, display.orbisAppearance.composerOpacity, 0f)
        assertEquals(1f, OrbisAppearance().composerOpacity, 0f)
    }

    @Test
    fun `composer values clamp independently within fifteen to one hundred percent`() {
        listOf(-1f to .15f, 0f to .15f, .15f to .15f, .4f to .4f, 1f to 1f, 12f to 1f).forEach { (input, expected) ->
            val normalized = OrbisAppearance(composerOpacity = input, bubbleOpacity = .73f).normalized()
            assertEquals(expected, normalized.composerOpacity, 0f)
            assertEquals(.73f, normalized.bubbleOpacity, 0f)
        }
    }

    @Test
    fun `non finite composer values use the backwards compatible default`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { input ->
            assertEquals(1f, OrbisAppearance(composerOpacity = input).normalized().composerOpacity, 0f)
        }
    }

    @Test
    fun `independent bubble and composer settings survive serialization`() {
        val value = DisplaySetting(orbisAppearance = OrbisAppearance(
            bubbleOpacity = .8f,
            composerOpacity = .25f,
            backgroundEnabled = true,
            backgroundStyle = OrbisBackgroundStyle.STARS,
        ))
        val restored = json.decodeFromString<DisplaySetting>(json.encodeToString(value))
        assertEquals(value, restored)
        assertEquals(.8f, restored.orbisAppearance.bubbleOpacity, 0f)
        assertEquals(.25f, restored.orbisAppearance.composerOpacity, 0f)
    }

    @Test
    fun `normalizing invalid bubble opacity does not change a valid composer opacity`() {
        val original = OrbisAppearance(bubbleOpacity = -1f, composerOpacity = .35f,
            bubbleStyle = OrbisBubbleStyle.BOOK, backgroundEnabled = true,
            backgroundImage = "file:///synthetic/kept.png", reduceMotion = true)
        val normalized = original.normalized()
        assertEquals(original.copy(bubbleOpacity = 0f), normalized)
        assertEquals(normalized, normalized.normalized())
    }

    @Test
    fun `changing composer opacity preserves every other appearance preference`() {
        val original = OrbisAppearance(bubbleOpacity = .75f, composerOpacity = 1f,
            bubbleStyle = OrbisBubbleStyle.STARS, backgroundEnabled = true,
            backgroundStyle = OrbisBackgroundStyle.BLUSH, backgroundImage = "file:///synthetic/kept.png",
            floatingStars = false, reduceMotion = true)
        val updated = original.copy(composerOpacity = .4f).normalized()
        assertEquals(original, updated.copy(composerOpacity = original.composerOpacity))
    }
}
