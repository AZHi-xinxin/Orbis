package me.rerere.rikkahub.data.orbis.theme

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThemeDiagnosticTest {
    @Test fun `invalid asset name undeclared reference and absent file give distinct repair evidence`() {
        val root = Files.createTempDirectory("theme-asset-evidence").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/synthetic"))
            val legal = "a".repeat(64) + ".png"
            val cases = listOf(
                Triple(ThemePackage(name="test", background=ThemeBackground("image","example.png"), assets=listOf("example.png")), "background.asset", "invalid_name"),
                Triple(ThemePackage(name="test", background=ThemeBackground("image",legal)), "background.asset", "not_declared"),
                Triple(ThemePackage(name="test", background=ThemeBackground("image",legal), assets=listOf(legal)), "background.asset", "missing_file"),
                Triple(ThemePackage(name="test", bubbles=ThemeBubbles(assistant=ThemeBubble(decorationAsset="sample.png")), assets=listOf("sample.png")), "bubbles.assistant.decorationAsset", "invalid_name"),
                Triple(ThemePackage(name="test", assets=listOf(legal,legal)), "assets[1]", "duplicate"),
                Triple(ThemePackage(name="test", background=ThemeBackground("solid",legal), assets=listOf(legal)), "background.asset", "solid_has_asset"),
            )
            cases.forEach { (body, field, issue) ->
                val error = assertThrows(ThemeAssetException::class.java) { store.write(themeJson.encodeToString(body)) }
                assertEquals(field,error.field); assertEquals(issue,error.issue)
                assertTrue(error.hint.isNotBlank()); assertTrue(store.snapshot().entries.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `corrupt store is not an argument failure and is preserved for diagnosis`() {
        val root = Files.createTempDirectory("theme-corrupt-evidence").toFile()
        try {
            val dir = root.resolve("orbis-themes/synthetic").also { it.mkdirs() }
            val store = ThemeRepository(dir)
            val file = dir.resolve("themes.json")
            listOf("{broken", """{"active":{"id":"missing","revision":2}}""").forEach { raw ->
                file.writeText(raw)
                listOf(
                    buildJsonObject { put("action","publish"); put("id","x"); put("expected_revision",1) },
                    buildJsonObject { put("action","write"); put("body","""{"name":"Synthetic"}""") },
                ).forEach { args ->
                    val error = assertThrows(Exception::class.java) { executeThemeTool(store,args) }
                    assertFalse(error is ThemeParameterException)
                    assertNotEquals("theme_invalid_parameters",error.message)
                    assertEquals(raw,file.readText())
                }
                listOf("read","publish").forEach { action ->
                    assertThrows(ThemeParameterException::class.java) { executeThemeTool(store,buildJsonObject { put("action",action) }) }
                    assertEquals(raw,file.readText())
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun `replacing missing draft image repairs its manifest and keeps published versions and files`() {
        val root = Files.createTempDirectory("theme-images").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/synthetic"))
            val old = store.importAsset(byteArrayOf(1,2,3), "png")
            val replacement = store.importAsset(byteArrayOf(4,5,6), "png")
            val draft = store.write(themeJson.encodeToString(ThemePackage(name="photo", background=ThemeBackground("image",old), assets=listOf(old))))
            val published = store.publish(draft.id,draft.revision)
            val originalVersion = published.versions.single()
            store.asset(old).delete() // External deletion/restore problem, not an app replace action.
            val changed = store.patch("""{"background":{"asset":"$replacement"}}""",published.id,published.revision)
            assertEquals(listOf(replacement),changed.draft!!.assets)
            assertEquals(originalVersion,changed.versions.single())
            assertEquals(emptyList<String>(),store.missingAssetFields(changed.draft!!))
            val next = store.publish(changed.id,changed.revision)
            val plain = store.patch("""{"background":{"type":"solid","asset":null}}""",next.id,next.revision)
            assertTrue(plain.draft!!.assets.isEmpty())
            assertTrue(store.asset(replacement).exists())
            assertEquals(replacement,plain.versions.last().body.background.asset)
            val bad = assertThrows(ThemeAssetException::class.java) {
                store.patch("""{"background":{"type":"image","asset":"$old"}}""",plain.id,plain.revision)
            }
            assertEquals("background.asset",bad.field)
            assertEquals(plain,store.snapshot().entries.single())
        } finally { root.deleteRecursively() }
    }
    @Test fun `write publish apply next calls use distinct revision fields and preview authorization`() {
        val root = Files.createTempDirectory("theme-next").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/synthetic"))
            val written = executeThemeTool(store, buildJsonObject { put("action", "write"); put("body", """{"name":"Synthetic"}""") })
            assertEquals("publish", written["next_call"]!!.jsonObject["action"]!!.jsonPrimitive.content)
            val before = store.snapshot()
            val failure = assertThrows(ThemeParameterException::class.java) {
                executeThemeTool(store, buildJsonObject { put("action", "publish"); put("id", written["id"]!!); put("revision", written["revision"]!!) })
            }
            assertTrue(failure.hint.contains("expected_revision"))
            assertEquals(before, store.snapshot())
            val published = executeThemeTool(store, written["next_call"]!!)
            assertEquals("published_not_applied", published["status"]!!.jsonPrimitive.content)
            assertFalse(store.snapshot().allowAiApply)
            val applied = executeThemeTool(store, published["next_call"]!!)
            assertEquals("awaiting_human_preview", applied["status"]!!.jsonPrimitive.content)
            assertNotNull(store.snapshot().pending)
        } finally { root.deleteRecursively() }
    }
    @Test fun `missing revision diagnosed before any publish and values not echoed`() {
        val root = Files.createTempDirectory("theme-errors").toFile()
        try {
            val store = ThemeRepository(root.resolve("orbis-themes/synthetic"))
            val e = store.write("""{"name":"Synthetic"}""")
            listOf(JsonNull, JsonPrimitive("SECRET"), buildJsonObject {}).forEach { invalid ->
                val failure = assertThrows(ThemeParameterException::class.java) {
                    executeThemeTool(store, buildJsonObject { put("action", "publish"); put("id", e.id); put("expected_revision", invalid) })
                }
                assertEquals("expected_revision", failure.field)
                assertFalse(failure.hint.contains("SECRET"))
                assertTrue(store.snapshot().entries.single().versions.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
