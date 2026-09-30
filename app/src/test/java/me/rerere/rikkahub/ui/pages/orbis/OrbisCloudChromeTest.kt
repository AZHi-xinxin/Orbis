package me.rerere.rikkahub.ui.pages.orbis

import org.junit.Assert.*
import org.junit.Test

class OrbisCloudChromeTest {
    @Test fun chromeOnlyChangesKnownGradientAndHasNoNativeOrNetworkBridge() {
        val script = OrbisCloudChrome(OrbisCloudWebPolicy.from("https://home.example.org/")!!).script
        assertTrue(script.contains("window !== window.top"))
        assertTrue(script.contains("location.origin !== 'https://home.example.org'"))
        assertTrue(script.contains("rgb(201, 138, 118)"))
        assertTrue(script.contains("rgb(16, 19, 42)"))
        assertTrue(script.contains("background-image: none"))
        listOf("fetch(", "XMLHttpRequest", "localStorage", "window.Android", "padding:", "height:").forEach {
            assertFalse("Chrome must remain presentation-only: $it", script.contains(it))
        }
    }
}
