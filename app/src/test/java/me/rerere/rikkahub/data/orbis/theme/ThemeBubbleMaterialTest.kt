package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThemeBubbleMaterialTest {
    @Test fun chromeSurfacesShareDefaultsAndKeepIndependentOverrides() {
        val liquid=ThemeSurface(material=ThemeBubbleMaterial(type="liquid",flow=.8f,metallic=.6f,fold=.4f))
        val frost=ThemeSurface(material=ThemeBubbleMaterial(),texture=ThemeBubbleTexture())
        val body=ThemePackage(name="整套",chrome=ThemeChrome(surface=liquid,composerSurface=frost,dockSurface=ThemeSurface()))
        assertEquals(body,parseTheme(themeJson.encodeToString(body)))
        assertEquals(frost,body.chrome.surfaceFor("composer"))
        assertNull(body.chrome.surfaceFor("dock")!!.material)
        assertNull(body.chrome.surfaceFor("star")!!.material)
        assertTrue(body.hasMaterial());assertTrue(body.hasMotion())
        assertFalse(ThemePackage(name="旧").hasMaterial());assertFalse(ThemePackage(name="旧").hasMotion())
        listOf(ThemeBubbleMaterial(flow=Float.NaN),ThemeBubbleMaterial(flowSpeed=3f),
            ThemeBubbleMaterial(metallic=-1f),ThemeBubbleMaterial(fold=2f),ThemeBubbleMaterial(refractionHeight=0f)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { liquid.copy(opacity=2f).validate() }
    }
    @Test fun iceAndImageTexturesValidateAndKeepAssetOwnership() {
        val frost=ThemeBubbleTexture()
        listOf(frost.copy(type="shader"),frost.copy(opacity=Float.NaN),frost.copy(scale=3f),
            frost.copy(density=0f),frost.copy(seed=-1),frost.copy(edgeFade=2f),
            frost.copy(type="image"),frost.copy(asset="forbidden.png")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        val asset="b".repeat(64)+".webp"
        val body=ThemePackage(name="冰窗",assets=listOf(asset),bubbles=ThemeBubbles(
            assistant=ThemeBubble(texture=frost),user=ThemeBubble(texture=ThemeBubbleTexture(type="image",asset=asset))))
        assertEquals(body,parseTheme(themeJson.encodeToString(body)))
        assertEquals(asset,body.assetReferences()["bubbles.user.texture.asset"])
        assertThrows(ThemeAssetException::class.java) { body.copy(assets=emptyList()).validate() }
    }

    @Test fun legacyAndNullMaterialKeepOldBubbles() {
        val old = parseTheme("""{"name":"原样","bubbles":{"assistant":{"shape":"cat","tail":"curl","opacity":0.4}}}""")
        assertNull(old.bubbles.assistant.material)
        assertTrue(old.bubbles.assistant.stickers.isEmpty())
        assertEquals("curl", old.bubbles.assistant.tail)
        assertEquals(old, parseTheme(themeJson.encodeToString(old)))
    }

    @Test fun invalidMaterialsAndGeometryAreRejected() {
        val base = ThemeBubbleMaterial(type = "liquid")
        listOf(base.copy(type="script"), base.copy(blur=Float.NaN), base.copy(displacement=25f),
            base.copy(tintOpacity=-.1f), base.copy(refraction=Float.POSITIVE_INFINITY),
            base.copy(depth=2f), base.copy(dispersion=2f), base.copy(highlight=2f),
            base.copy(rim=Float.NaN), base.copy(lightX=2f), base.copy(lightY=Float.NaN),
            base.copy(shimmerDurationMs=0),base.copy(edgeColors=emptyList()),base.copy(edgeShadow="black")).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        listOf(ThemeBubble(shape="cat",material=base), ThemeBubble(tail="curl",material=base),
            ThemeBubble(material=base,drawing=ThemeDrawing(outline="M0 0 L100 0 L50 100 Z"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { ThemePackage(name="bad",bubbles=ThemeBubbles(user=it)).validate() }
        }
        ThemePackage(name="frosted cat", bubbles=ThemeBubbles(user=ThemeBubble(shape="cat",material=base.copy(type="frosted")))).validate()
    }

    @Test fun guidePatchPersistsThroughPublishAndRevertWithoutChangingPermissions() {
        val root=Files.createTempDirectory("material-theme").toFile()
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            val old=store.write("""{"name":"旧主题"}"""); val published=store.publish(old.id,old.revision)
            store.confirm(ThemeRef(published.id,published.revision))
            val guide=executeThemeTool(store,buildJsonObject { put("action","guide") })
            val next=store.patch(guide["material_patch_example"]!!.jsonPrimitive.content,published.id,published.revision)
            val v=store.publish(next.id,next.revision)
            assertFalse(store.requestApply(ThemeRef(v.id,v.revision)))
            assertNull(store.snapshot().resolve()!!.bubbles.user.material)
            store.confirm(ThemeRef(v.id,v.revision))
            val reopened=ThemeRepository(root.resolve("orbis-themes/owner"))
            assertEquals("liquid",reopened.snapshot().resolve()!!.bubbles.user.material!!.type)
            assertFalse(reopened.snapshot().allowAiApply)
            reopened.revert()
            assertNull(reopened.snapshot().resolve()!!.bubbles.user.material)
        } finally { root.deleteRecursively() }
    }

    @Test fun stickersMustBeBoundedAndDeclareImportedAssets() {
        val asset="a".repeat(64)+".png"
        val drawing=ThemeDrawing(layers=listOf(ThemeVectorLayer("M5 5 L95 5 L50 95 Z",fill="#AA3388")))
        val bubble=ThemeBubble(stickers=listOf(ThemeSticker(asset=asset), ThemeSticker(drawing=drawing,anchor="bottomStart")))
        val body=ThemePackage(name="贴纸",bubbles=ThemeBubbles(user=bubble),assets=listOf(asset))
        assertEquals(body,parseTheme(themeJson.encodeToString(body)))
        assertEquals(asset,body.assetReferences()["bubbles.user.stickers[0].asset"])
        assertThrows(ThemeAssetException::class.java) { body.copy(assets=emptyList()).validate() }
        listOf(ThemeSticker(),ThemeSticker(asset=asset,drawing=drawing),ThemeSticker(asset=asset,size=100),
            ThemeSticker(asset=asset,anchor="anywhere"),ThemeSticker(asset=asset,rotation=Float.NaN),
            ThemeSticker(asset=asset,opacity=-1f),ThemeSticker(drawing=ThemeDrawing())).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            body.copy(bubbles=ThemeBubbles(user=bubble.copy(stickers=List(5) { ThemeSticker(asset=asset) }))).validate()
        }
    }

    @Test fun rejectedPatchLeavesDraftAndActiveThemeUntouched() {
        val root=Files.createTempDirectory("material-atomic").toFile()
        try {
            val store=ThemeRepository(root.resolve("orbis-themes/owner"))
            val old=store.write("""{"name":"原样"}"""); val before=store.snapshot()
            listOf("""{"bubbles":{"user":{"material":{"type":"liquid","blur":999}}}}""",
                """{"bubbles":{"user":{"material":{"shader":"exec"}}}}""",
                """{"bubbles":{"user":{"stickers":[{"asset":"https://example.com/a.png"}]}}}""").forEach {
                assertThrows(Exception::class.java) { store.patch(it,old.id,old.revision) }
                assertEquals(before,store.snapshot())
            }
        } finally { root.deleteRecursively() }
    }
}
