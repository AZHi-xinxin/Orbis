package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MaterialThemePresetTest {
    private fun fixture(block: (ThemeRepository,java.io.File)->Unit) {
        val root=Files.createTempDirectory("material-presets").toFile()
        try { block(ThemeRepository(root.resolve("orbis-themes/owner")),root) } finally { root.deleteRecursively() }
    }
    @Test fun everyPresetActuallyEnablesWallpaperMaterialAndTheLiquidClock() = fixture { s,_ ->
        val asset=s.importAsset(byteArrayOf(1),"png")
        MaterialThemePreset.entries.forEach { preset ->
            val b=materialThemePreset(preset,asset)
            parseTheme(themeJson.encodeToString(b))
            assertNotNull(b.bubbles.assistant.material);assertNotNull(b.bubbles.user.material)
            assertNotNull(b.chrome.surface);assertNotNull(b.chrome.composerSurface)
            assertEquals(1f,b.bubbles.assistant.opacity,0f)
            if(preset==MaterialThemePreset.LIQUID) assertTrue(b.bubbles.assistant.material!!.flow>0f && b.hasMotion())
            if(preset==MaterialThemePreset.LIQUID_STILL) {
                assertFalse(b.hasMotion())
                assertEquals("liquid",b.bubbles.assistant.material!!.type)
                assertTrue(b.bubbles.assistant.material!!.refraction>0f && b.bubbles.assistant.material!!.metallic>0f)
            }
            if(preset==MaterialThemePreset.ICE) {assertEquals("ice",b.bubbles.assistant.texture!!.type);assertTrue(b.bubbles.assistant.material!!.blur>10f)}
        }
    }
    @Test fun firstStaticVariantKeepsWallpaperAndHumanInkWithoutChangingTheMovingOriginal() = fixture { s,_ ->
        val image=s.importAsset(byteArrayOf(1),"png")
        val moving=s.prepareMaterialPreset(MaterialThemePreset.LIQUID,image);s.confirm(moving)
        s.setMaterialTextColor(moving,MaterialTextTarget.ASSISTANT,"#FFFFFF")
        s.setMaterialTextColor(moving,MaterialTextTarget.CHROME,"#FFEEDD")
        val before=s.snapshot()
        val still=s.prepareMaterialPreset(MaterialThemePreset.LIQUID_STILL,image)
        assertEquals(moving,s.snapshot().active)
        assertEquals(before.entries,s.snapshot().entries.take(before.entries.size))
        s.confirm(still)
        assertFalse(s.snapshot().resolve()!!.hasMotion())
        assertEquals(image,s.snapshot().resolve()!!.background.asset)
        assertEquals("#FFFFFF",s.snapshot().displayBody()!!.bubbles.assistant.text)
        assertEquals("#FFEEDD",s.snapshot().displayBody()!!.chrome.onComposer)
        s.setMaterialTextColor(still,MaterialTextTarget.CHROME,null)
        s.confirm(moving);s.prepareMaterialPreset(MaterialThemePreset.LIQUID_STILL,image)
        assertNull(s.snapshot().materialTextColors[still.id]!!.chrome)
    }
    @Test fun builtinPreviewPreservesAiThemesPermissionsAndCurrentWallpaper() = fixture { s,root ->
        val own=s.importAsset(byteArrayOf(1),"png");val fallback=s.importAsset(byteArrayOf(2),"png")
        val d=s.write(themeJson.encodeToString(ThemePackage(name="原设计",assets=listOf(own),background=ThemeBackground("image",own))))
        val p=s.publish(d.id,d.revision);val original=ThemeRef(p.id,p.revision);s.confirm(original);s.allowAiApply(true)
        val before=s.snapshot()
        val ref=s.prepareMaterialPreset(MaterialThemePreset.LIQUID,fallback)
        assertEquals(original,s.snapshot().active);assertEquals(before.entries.single(),s.snapshot().entries.first())
        assertEquals(own,s.snapshot().resolve(ref)!!.background.asset);assertTrue(s.snapshot().allowAiApply)
        repeat(3) { assertEquals(ref,s.prepareMaterialPreset(MaterialThemePreset.LIQUID,fallback)) }
        assertEquals(2,s.snapshot().entries.size)
        assertThrows(IllegalStateException::class.java) { s.patch("{}",ref.id,ref.revision) }
        s.confirm(ref);s.revert();assertEquals(original,s.snapshot().active)
        assertEquals(before.entries.single(),ThemeRepository(root.resolve("orbis-themes/owner")).snapshot().entries.first())
    }
    @Test fun changingImagePreservesMaterialAndCanBeUndoneIncludingAfterRevert() = fixture { s,_ ->
        val a=s.importAsset(byteArrayOf(1),"png");val b=s.importAsset(byteArrayOf(2),"png")
        val ref=s.prepareMaterialPreset(MaterialThemePreset.ICE,a);s.confirm(ref)
        val original=s.snapshot().resolve()!!
        val changed=s.setMaterialBackground(ref,b)
        assertEquals(original.bubbles,s.snapshot().resolve()!!.bubbles)
        assertEquals(original.chrome,s.snapshot().resolve()!!.chrome)
        assertEquals(b,s.snapshot().resolve()!!.background.asset)
        s.revert();assertEquals(ref,s.snapshot().active)
        val again=s.setMaterialBackground(ref,b)
        assertTrue(again.revision>changed.revision)
        repeat(25) { s.setMaterialBackground(s.snapshot().active!!,if(it%2==0)a else b) }
        s.snapshot() // all remaining undo refs resolve after bounded version retention
        assertTrue(s.snapshot().entries.single().versions.size<=20)
    }
    @Test fun stalePickerAndMissingImageLeaveTheActiveThemeUntouched() = fixture { s,_ ->
        val a=s.importAsset(byteArrayOf(1),"png")
        val first=s.prepareMaterialPreset(MaterialThemePreset.FROSTED,a);s.confirm(first)
        val second=s.prepareMaterialPreset(MaterialThemePreset.LIQUID,a);s.confirm(second)
        val before=s.snapshot()
        assertThrows(IllegalStateException::class.java) { s.setMaterialBackground(first,a) }
        assertThrows(ThemeAssetException::class.java) { s.setMaterialBackground(second,"a".repeat(64)+".png") }
        assertEquals(before,s.snapshot())
    }
    @Test fun lightTextOverridesSurviveWallpaperChangesReopenAndThemeSwitchWithoutMutatingMaterials() = fixture { s,root ->
        val image=s.importAsset(byteArrayOf(1),"png");val other=s.importAsset(byteArrayOf(2),"png")
        val ref=s.prepareMaterialPreset(MaterialThemePreset.LIQUID,image);s.confirm(ref)
        val original=s.snapshot().resolve()!!
        s.setMaterialTextColor(ref,MaterialTextTarget.ASSISTANT,"#FFFFFF")
        s.setMaterialTextColor(ref,MaterialTextTarget.USER,"#FFE0F0")
        s.setMaterialTextColor(ref,MaterialTextTarget.CHROME,"#DBEEFF")
        assertEquals(original,s.snapshot().resolve()) // Published package stays valid, including its contrast rules.
        assertEquals("#FFFFFF",s.snapshot().displayBody()!!.bubbles.assistant.text)
        assertEquals("#FFE0F0",s.snapshot().displayBody()!!.bubbles.user.text)
        assertEquals("#DBEEFF",s.snapshot().displayBody()!!.chrome.onComposer)
        assertEquals("#DBEEFF",s.snapshot().displayBody()!!.chrome.onDock)
        assertEquals(original.bubbles.assistant.material,s.snapshot().displayBody()!!.bubbles.assistant.material)
        val updated=s.setMaterialBackground(ref,other)
        val reopened=ThemeRepository(root.resolve("orbis-themes/owner"))
        assertEquals("#FFFFFF",reopened.snapshot().displayBody()!!.bubbles.assistant.text)
        assertEquals(other,reopened.snapshot().displayBody()!!.background.asset)
        val ice=s.prepareMaterialPreset(MaterialThemePreset.ICE,image);s.confirm(ice)
        assertEquals(s.snapshot().resolve(),s.snapshot().displayBody()) // Another preset has independent colors.
        s.confirm(updated)
        s.setMaterialTextColor(updated,MaterialTextTarget.ASSISTANT,null)
        assertEquals(original.bubbles.assistant.text,s.snapshot().displayBody()!!.bubbles.assistant.text)
        assertEquals("#FFE0F0",s.snapshot().displayBody()!!.bubbles.user.text)
        assertFalse(s.snapshot().allowAiApply)
    }
    @Test fun invalidOrStaleColorWritesAreAtomicAndOldStoresNeedNoMigration() = fixture { s,_ ->
        assertEquals(emptyMap<String,MaterialTextColors>(),themeJson.decodeFromString<ThemeState>("{}").materialTextColors)
        val image=s.importAsset(byteArrayOf(1),"png")
        val first=s.prepareMaterialPreset(MaterialThemePreset.FROSTED,image);s.confirm(first)
        val second=s.prepareMaterialPreset(MaterialThemePreset.ICE,image);s.confirm(second)
        val before=s.snapshot()
        assertThrows(IllegalStateException::class.java) { s.setMaterialTextColor(first,MaterialTextTarget.USER,"#FFFFFF") }
        assertThrows(IllegalArgumentException::class.java) { s.setMaterialTextColor(second,MaterialTextTarget.USER,"#00FFFFFF") }
        assertEquals(before,s.snapshot())
        s.setMaterialTextColor(second,MaterialTextTarget.USER,"#FFFFFF")
        s.setMaterialTextColor(second,MaterialTextTarget.USER,null)
        assertTrue(s.snapshot().materialTextColors.isEmpty())
    }
}
