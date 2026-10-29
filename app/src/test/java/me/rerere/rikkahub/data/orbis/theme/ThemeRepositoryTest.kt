package me.rerere.rikkahub.data.orbis.theme

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ThemeRepositoryTest {
    private fun fixture(block: (ThemeRepository, java.io.File) -> Unit) {
        val temp = Files.createTempDirectory("themes-test").toFile()
        try { block(ThemeRepository(temp.resolve("orbis-themes/owner")), temp) } finally { temp.deleteRecursively() }
    }
    private fun json(name: String) = themeJson.encodeToString(ThemePackage(name = name))
    private fun published(s: ThemeRepository, name: String): ThemeRef {
        val d = s.write(json(name)); val e = s.publish(d.id, d.revision); return ThemeRef(e.id, e.revision)
    }
    @Test fun draftPublishConfirmationAndRevertSurviveReopenWithoutChangingOtherOwners() = fixture { store, root ->
        val a = published(store, "雾蓝"); assertFalse(store.requestApply(a)); assertNull(store.snapshot().active)
        assertEquals(a, store.snapshot().pending); store.confirm(a)
        val b = published(store, "月光"); store.confirm(b)
        val reopened = ThemeRepository(root.resolve("orbis-themes/owner"))
        assertEquals("月光", reopened.snapshot().resolve()?.name); reopened.revert()
        assertEquals("雾蓝", reopened.snapshot().resolve()?.name); reopened.revert()
        assertNull(reopened.snapshot().active)
        assertTrue(ThemeRepository(root.resolve("orbis-themes/other")).snapshot().entries.isEmpty())
    }
    @Test fun staleDraftAndBadPackagesNeverOverwritePublishedVersion() = fixture { s, _ ->
        val a = published(s, "好主题"); s.confirm(a)
        val edit = s.write(json("新草稿"), a.id, a.revision)
        assertThrows(IllegalStateException::class.java) { s.write(json("旧写入"), a.id, a.revision) }
        assertThrows(Exception::class.java) { s.write("{bad", a.id, edit.revision) }
        assertThrows(Exception::class.java) { s.write("{\"name\":\"bad\",\"script\":\"alert(1)\"}") }
        assertThrows(Exception::class.java) { s.write(themeJson.encodeToString(ThemePackage(name="low", colors=ThemeColors(text="#F5F3ED")))) }
        assertEquals("好主题", s.snapshot().resolve()?.name)
        assertEquals("新草稿", s.snapshot().entries.single().draft?.name)
    }
    @Test fun humanAutoGrantIsNotWritableViaToolAndImagesMustExistBeforePublish() = fixture { s, _ ->
        val a = published(s, "夜航"); s.allowAuto(a.id, true)
        assertTrue(s.requestApply(a)); assertEquals(a, s.snapshot().active)
        assertThrows(Exception::class.java) { executeThemeTool(s, buildJsonObject { put("action", "apply"); put("id", a.id); put("revision", a.revision); put("autoApply", true) }) }
        val name = "a".repeat(64) + ".png"
        assertEquals("background.asset", assertThrows(ThemeAssetException::class.java) {
            s.write(themeJson.encodeToString(ThemePackage(name="missing",background=ThemeBackground("image",name),assets=listOf(name))))
        }.field)
        val existing = s.importAsset(byteArrayOf(1,2,3), "png")
        val d = s.write(themeJson.encodeToString(ThemePackage(name="removed later",background=ThemeBackground("image",existing),assets=listOf(existing))))
        s.asset(existing).delete()
        assertEquals("background.asset", assertThrows(ThemeAssetException::class.java) { s.publish(d.id, d.revision) }.field)
        assertEquals(a, s.snapshot().active)
        assertThrows(Exception::class.java) { s.asset("../../secret.png") }
    }
    @Test fun humanEntriesCannotBeOverwrittenAndCorruptStoresArePreserved() = fixture { s, root ->
        val folder = root.resolve("orbis-themes/owner"); folder.mkdirs()
        val f = folder.resolve("themes.json")
        f.writeText(themeJson.encodeToString(ThemeState(entries=listOf(ThemeEntry("human",authorRole="human")))))
        assertThrows(IllegalStateException::class.java) { s.write(json("overwrite"), "human", 0) }
        assertThrows(IllegalStateException::class.java) { s.delete("human", 0) }
        f.writeText("{corrupt")
        assertThrows(Exception::class.java) { s.write(json("replacement")) }
        assertEquals("{corrupt", f.readText())
    }
    @Test fun explicitBackupRestoresThemeButNeverItsAutoApplicationPermission() = fixture { s, root ->
        val a = published(s, "可迁移"); s.allowAuto(a.id, true); s.confirm(a)
        val staging = root.resolve("snapshot")
        val files = ThemeBackup.stageSnapshot(root, staging)
        assertEquals(1, files.size)
        val payload = root.resolve("payload")
        staging.copyRecursively(payload.resolve("files"))
        val live = root.resolve("new-install")
        ThemeBackup.prepareBeforeJournal(payload, live)
        ThemeBackup.validateStaged(payload)
        val restored = ThemeRepository(payload.resolve("files/orbis-themes/owner")).snapshot()
        assertEquals("可迁移", restored.resolve()?.name); assertTrue(restored.autoApply.isEmpty())
        assertNull(restored.pending)
        val different = ThemeRepository(live.resolve("orbis-themes/owner"))
        val edited = themeJson.decodeFromString<ThemeState>(files.single().second.readText()).let {
            it.copy(entries=it.entries.map { e -> e.copy(revision=e.revision+1) })
        }
        live.resolve("orbis-themes/owner").mkdirs()
        live.resolve("orbis-themes/owner/themes.json").writeText(themeJson.encodeToString(edited))
        assertThrows(IllegalArgumentException::class.java) { ThemeBackup.prepareBeforeJournal(payload,live) }
        assertEquals(edited,different.snapshot())
    }
    @Test fun failedImageRenderDisablesAutomaticReapplicationAndKeepsPackage() = fixture { s, _ ->
        val ref=published(s,"图片候选"); s.allowAuto(ref.id,true);s.confirm(ref)
        s.renderFailed(ref)
        assertNull(s.snapshot().active);assertTrue(s.snapshot().autoApply.isEmpty())
        assertEquals("图片候选",s.snapshot().resolve(ref)?.name)
    }

    @Test fun humanGrantCoversNewThemesAndRevocationImmediatelyStopsAiApplication() = fixture { s, root ->
        val first=published(s,"猫的信"); assertFalse(s.requestApply(first))
        s.allowAiApply(true)
        val second=published(s,"兔的信")
        assertTrue(s.requestApply(second))
        assertEquals(second,ThemeRepository(root.resolve("orbis-themes/owner")).snapshot().active)
        assertThrows(Exception::class.java) { executeThemeTool(s, buildJsonObject { put("action","allowAiApply");put("enabled",true) }) }
        assertThrows(Exception::class.java) { s.write("{\"name\":\"bad\",\"allowAiApply\":true}") }
        s.allowAuto(first.id,true);s.allowAiApply(false)
        assertFalse(s.requestApply(first));assertEquals(second,s.snapshot().active)
        s.allowAiApply(true);s.renderFailed(second)
        assertFalse(s.snapshot().allowAiApply)
    }

    @Test fun globalHumanPermissionIsNeverExportedOrRestored() = fixture { s, root ->
        val theme=published(s,"可迁移");s.confirm(theme);s.allowAiApply(true)
        val staging=root.resolve("snapshot-global")
        val files=ThemeBackup.stageSnapshot(root,staging)
        val copy=themeJson.decodeFromString<ThemeState>(files.single().second.readText())
        assertFalse(copy.allowAiApply);assertTrue(copy.autoApply.isEmpty())
        assertTrue(s.snapshot().allowAiApply)
    }

    @Test fun shapesPatternsAndChromeAreValidatedAndLegacyDefaultsRemainReadable() = fixture { s, _ ->
        for(shape in listOf("round","cat","rabbit","ticket","speech")) {
            val p=ThemePackage(name=shape,bubbles=ThemeBubbles(assistant=ThemeBubble(shape=shape,pattern="stars",decoration="heart")),chrome=ThemeChrome(dock="#E7EEF5",onDock="#233449"))
            assertEquals(shape,parseTheme(themeJson.encodeToString(p)).bubbles.assistant.shape)
        }
        assertEquals("round",parseTheme("{\"name\":\"旧主题\"}").bubbles.assistant.shape)
        assertThrows(Exception::class.java){s.write(themeJson.encodeToString(ThemePackage(name="bad",bubbles=ThemeBubbles(assistant=ThemeBubble(shape="script")))))}
        assertThrows(Exception::class.java){s.write(themeJson.encodeToString(ThemePackage(name="bad",chrome=ThemeChrome(dock="#FFFFFF",onDock="#FFFFFF"))))}
        assertThrows(Exception::class.java){s.write(themeJson.encodeToString(ThemePackage(name="bad",bubbles=ThemeBubbles(assistant=ThemeBubble(decorationAsset="a".repeat(64)+".png")))))}
    }

}
