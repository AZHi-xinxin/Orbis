package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThemeHumanFontTest {
    @Test fun oldThemesFollowHumanFontUntilHumanExplicitlyOptsOut() {
        val root=Files.createTempDirectory("theme-human-font").toFile()
        try {
            val ownerRoot=root.resolve("orbis-themes/owner")
            val store=ThemeRepository(ownerRoot)
            val old=themeJson.decodeFromString<ThemeState>("""{"schema":1}""")
            assertTrue(old.followHumanFont)
            val d=store.write(themeJson.encodeToString(ThemePackage(name="old serif",typography=ThemeTypography(fontFamily="serif"))))
            val v=store.publish(d.id,d.revision);store.confirm(ThemeRef(v.id,v.revision))
            assertTrue(store.snapshot().followHumanFont)
            store.followHumanFont(false);assertFalse(ThemeRepository(ownerRoot).snapshot().followHumanFont)
            store.followHumanFont(true)
            assertEquals("serif",store.snapshot().resolve()!!.typography.fontFamily) // preserve design data
            assertTrue(ThemeRepository(ownerRoot).snapshot().followHumanFont)
        } finally { root.deleteRecursively() }
    }
    @Test fun tailGuideExampleCanBePatchedAndPublishedAndCannotAlterHumanFontChoice() {
        val root=Files.createTempDirectory("theme-tail-guide").toFile()
        try {
            val ownerRoot=root.resolve("orbis-themes/owner")
            val store=ThemeRepository(ownerRoot)
            val d=store.write(themeJson.encodeToString(themeDrawingExample()))
            val guide=executeThemeTool(store,buildJsonObject { put("action","guide") })
            val patch=guide["tail_patch_example"]!!.jsonPrimitive.content
            val edited=store.patch(patch,d.id,d.revision)
            val version=store.publish(d.id,edited.revision)
            assertEquals("cat",version.versions.last().body.bubbles.assistant.tail)
            assertEquals("curl",version.versions.last().body.bubbles.user.tail)
            assertNull(version.versions.last().body.bubbles.assistant.drawing?.outline)
            assertTrue(store.snapshot().followHumanFont)
            assertTrue(runCatching { executeThemeTool(store,buildJsonObject{put("action","followHumanFont");put("enabled",false)}) }.isFailure)
        } finally { root.deleteRecursively() }
    }
}
