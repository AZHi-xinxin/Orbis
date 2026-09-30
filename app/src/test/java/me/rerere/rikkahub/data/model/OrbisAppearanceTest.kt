package me.rerere.rikkahub.data.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbisAppearanceTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `old display preferences gain default appearance without replacing existing fields`() {
        val old = json.decodeFromString<DisplaySetting>("""{
            "userNickname":"Synthetic user",
            "userAvatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Emoji","content":"🌸"},
            "bubbleOpacity":0.4,
            "showAssistantBubble":false,
            "chatFontFamily":"serif",
            "chatCustomFontPath":"synthetic-font.ttf"
        }""")
        assertEquals("Synthetic user", old.userNickname)
        assertEquals(Avatar.Emoji("🌸"), old.userAvatar)
        assertEquals(.4f, old.bubbleOpacity, 0f)
        assertFalse(old.showAssistantBubble)
        assertEquals(ChatFontFamily.SERIF, old.chatFontFamily)
        assertEquals("synthetic-font.ttf", old.chatCustomFontPath)
        assertEquals(OrbisAppearance(), old.orbisAppearance)
        assertFalse(old.orbisAppearance.backgroundEnabled)
        assertNull(old.orbisAppearance.backgroundImage)
    }

    @Test
    fun `new defaults select silk and readable opacity without requiring an opt in flag`() {
        val value = OrbisAppearance()
        assertEquals(OrbisBubbleStyle.SILK, value.bubbleStyle)
        assertEquals(.92f, value.bubbleOpacity, 0f)
        assertEquals(OrbisBackgroundStyle.PAPER, value.backgroundStyle)
        assertTrue(value.floatingStars)
        assertFalse(value.reduceMotion)
        // Appearance itself still needs no opt-in flag; nested chat layout has its own toggle.
        assertFalse(json.parseToJsonElement(json.encodeToString(value)).jsonObject.containsKey("enabled"))
    }

    @Test
    fun `every style background and new field survives serialization`() {
        for (bubble in OrbisBubbleStyle.entries) for (background in OrbisBackgroundStyle.entries) {
            val value = OrbisAppearance(bubbleStyle = bubble, bubbleOpacity = .73f,
                backgroundEnabled = true, backgroundStyle = background, backgroundImage = "file:///synthetic/image.png",
                floatingStars = false, reduceMotion = true)
            assertEquals(value, json.decodeFromString<OrbisAppearance>(json.encodeToString(value)))
            val display = DisplaySetting(orbisAppearance = value, userNickname = "Synthetic")
            assertEquals(display, json.decodeFromString<DisplaySetting>(json.encodeToString(display)))
        }
    }

    @Test
    fun `serialized style names are the stable approved names`() {
        assertEquals(listOf("\"silk\"", "\"glass\"", "\"stars\"", "\"book\""),
            OrbisBubbleStyle.entries.map { json.encodeToString(it) })
        assertEquals(listOf("\"paper\"", "\"blush\"", "\"stars\""),
            OrbisBackgroundStyle.entries.map { json.encodeToString(it) })
    }

    @Test
    fun `opacity is clamped to the supported range and non finite values use default`() {
        assertEquals(0f, OrbisAppearance(bubbleOpacity = -1f).normalized().bubbleOpacity, 0f)
        assertEquals(1f, OrbisAppearance(bubbleOpacity = 12f).normalized().bubbleOpacity, 0f)
        assertEquals(.73f, OrbisAppearance(bubbleOpacity = .73f).normalized().bubbleOpacity, 0f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(.92f, OrbisAppearance(bubbleOpacity = it).normalized().bubbleOpacity, 0f)
        }
    }

    @Test
    fun `normalization only changes opacity`() {
        val value = OrbisAppearance(bubbleStyle = OrbisBubbleStyle.BOOK, bubbleOpacity = -10f,
            backgroundEnabled = true, backgroundStyle = OrbisBackgroundStyle.BLUSH,
            backgroundImage = "file:///synthetic/kept.png", floatingStars = false, reduceMotion = true)
        assertEquals(value.copy(bubbleOpacity = 0f), value.normalized())
    }

    @Test
    fun `all visibility motion power and lifecycle conditions are required to animate`() {
        val appearance = OrbisAppearance()
        assertTrue(shouldAnimateOrbisStars(appearance, true, true, false, true))
        assertFalse(shouldAnimateOrbisStars(appearance, false, true, false, true))
        assertFalse(shouldAnimateOrbisStars(appearance, true, false, false, true))
        assertFalse(shouldAnimateOrbisStars(appearance, true, true, true, true))
        assertFalse(shouldAnimateOrbisStars(appearance, true, true, false, false))
        assertFalse(shouldAnimateOrbisStars(appearance.copy(reduceMotion = true), true, true, false, true))
        assertFalse(shouldAnimateOrbisStars(appearance.copy(floatingStars = false), true, true, false, true))
        // Inheriting an existing background is not a reason to disable its star overlay.
        assertTrue(shouldAnimateOrbisStars(appearance.copy(backgroundEnabled = false), true, true, false, true))
    }

    @Test
    fun `appearance reset preserves unrelated display data`() {
        val old = DisplaySetting(userAvatar = Avatar.Emoji("☁"), userNickname = "Synthetic",
            chatFontFamily = ChatFontFamily.MONOSPACE, autoPlayTTSAfterGeneration = true,
            orbisAppearance = OrbisAppearance(bubbleStyle = OrbisBubbleStyle.BOOK, backgroundImage = "file:///synthetic/image.png"))
        val reset = old.copy(orbisAppearance = OrbisAppearance())
        assertEquals(old, reset.copy(orbisAppearance = old.orbisAppearance))
    }

    @Test
    fun `bounded image copy keeps bytes and accepts exact limit`() {
        val bytes = ByteArray(19000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyOrbisImage(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun `over limit image fails before oversized chunk is written`() {
        val output = ByteArrayOutputStream()
        try {
            copyOrbisImage(ByteArrayInputStream(ByteArray(10)), output, 9)
            throw AssertionError("oversized input accepted")
        } catch (expected: IOException) {
            assertEquals("image_too_large", expected.message)
            assertTrue(output.size() <= 9)
        }
    }

    @Test
    fun `empty image is not saved as successful`() {
        try {
            copyOrbisImage(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream())
            throw AssertionError("empty input accepted")
        } catch (expected: IOException) {
            assertEquals("image_empty", expected.message)
        }
    }
}
