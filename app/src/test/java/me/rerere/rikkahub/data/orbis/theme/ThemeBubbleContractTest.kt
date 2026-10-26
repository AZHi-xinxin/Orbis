package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThemeBubbleContractTest {
    @Test fun legacyPackagesKeepOpaqueBubblesWithoutTails() {
        val old = parseTheme("""{"name":"旧主题","bubbles":{"assistant":{"shape":"cat"},"user":{"bg":"#DFEBEF"}}}""")
        assertEquals(1f, old.bubbles.assistant.opacity, 0f)
        assertEquals(1f, old.bubbles.user.opacity, 0f)
        assertEquals("none", old.bubbles.assistant.tail)
        assertEquals("cat", old.bubbles.assistant.shape)
    }

    @Test fun publishedAndReopenedThemeKeepsIndependentOpacityAndTails() {
        val root = Files.createTempDirectory("theme-opacity").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/owner"))
            val body = """{"name":"猫的玻璃信","bubbles":{"assistant":{"opacity":0.55,"shape":"cat","tail":"cat","pattern":"stars"},"user":{"opacity":0,"tail":"curl"}}}"""
            val written = executeThemeTool(store, buildJsonObject { put("action", "write"); put("body", body) })
            val published = executeThemeTool(store, buildJsonObject {
                put("action", "publish"); put("id", written["id"]!!); put("expected_revision", written["revision"]!!)
            })
            val ref = ThemeRef(published["id"]!!.jsonPrimitive.content, published["revision"]!!.jsonPrimitive.int)
            assertFalse(store.requestApply(ref))
            assertNull(store.snapshot().active)
            store.confirm(ref)
            val reopened = ThemeRepository(root.resolve("orbis-themes/owner")).snapshot().resolve()!!
            assertEquals(.55f, reopened.bubbles.assistant.opacity, 0f)
            assertEquals("cat", reopened.bubbles.assistant.tail)
            assertEquals(0f, reopened.bubbles.user.opacity, 0f)
            assertEquals("curl", reopened.bubbles.user.tail)
            assertEquals("stars", reopened.bubbles.assistant.pattern)
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidOpacityCannotReplaceAnExistingDraft() {
        val root = Files.createTempDirectory("theme-invalid-opacity").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/owner"))
            val original = store.write("""{"name":"保留的主题"}""")
            for (value in listOf(-.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val invalid = ThemePackage(name = "越界", bubbles = ThemeBubbles(assistant = ThemeBubble(opacity = value)))
                assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
            }
            for (value in listOf("-0.01", "1.01", "null", "\"mist\"")) {
                assertThrows(Exception::class.java) {
                    store.write("""{"name":"不能写入","bubbles":{"user":{"opacity":$value}}}""", original.id, original.revision)
                }
                assertEquals(original, store.snapshot().entries.single())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun onlySupportedTailsAndSixDigitColorsAreAccepted() {
        for (tail in listOf("none", "cat", "curl")) for (opacity in listOf(0f, .55f, 1f)) {
            val theme = ThemePackage(name = tail, bubbles = ThemeBubbles(user = ThemeBubble(tail = tail, opacity = opacity)))
            assertEquals(theme, parseTheme(themeJson.encodeToString(theme)))
        }
        assertThrows(IllegalArgumentException::class.java) { ThemePackage(name = "bad", bubbles = ThemeBubbles(user = ThemeBubble(tail = "script"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { ThemePackage(name = "bad", bubbles = ThemeBubbles(user = ThemeBubble(bg = "#FFFFFF88"))).validate() }
    }
}
