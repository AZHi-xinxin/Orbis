package me.rerere.rikkahub.data.orbis

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Real Android filesystem aliases and symlinks; all data lives in a fresh synthetic cache fixture. */
@RunWith(AndroidJUnit4::class)
class CompanionSpacesPathDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    private val owner = "1c66025d-e84f-4c0e-9f08-b2ca044be81a"
    private val other = "dcc605c7-b98a-440b-bc57-a7c56f4a7908"

    @Before fun requireIsolatedRunner() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    private fun shelf(base: File, id: String = owner) = File(base, "orbis-companion-spaces/$id")
    private fun syntheticContext(base: File) = object : ContextWrapper(instrumentation.targetContext) {
        override fun getFilesDir(): File = base
        override fun getApplicationContext(): Context = this
    }
    private fun unlink(file: File) {
        if (Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
    }

    @Test fun androidRootAliasesShareOwnerDataRevisionSignalAndPublicFactory() {
        // Keep the original Android cache spelling: this covers /data/data versus /data/user/0.
        val base = temporary.newFolder("trusted-files")
        val alias = File(temporary.root, "trusted-root-alias")
        Files.createSymbolicLink(alias.toPath(), base.canonicalFile.toPath())
        try {
            val original = OrbisCompanionSpacesStore(shelf(base))
            val canonical = OrbisCompanionSpacesStore(shelf(base.canonicalFile))
            val throughAlias = OrbisCompanionSpacesStore(shelf(alias))
            assertSame(original.revisions, canonical.revisions)
            assertSame(original.revisions, throughAlias.revisions)
            assertTrue(original.snapshot().stories.isEmpty())
            val story = original.saveHumanStory("合成番外", "原指令保持", "letter")
            assertEquals(story, canonical.snapshot().stories.single())
            throughAlias.writeStoryBody(story.id, "真实 Android 文件系统写入", story.revision)
            val viaPublicFactory = OrbisCompanionSpacesStore.open(syntheticContext(alias), owner)
            assertEquals("真实 Android 文件系统写入", viaPublicFactory.snapshot().stories.single().body)
            assertEquals("原指令保持", original.snapshot().stories.single().prompt)
            assertTrue(OrbisCompanionSpacesStore.open(syntheticContext(alias), other).snapshot().stories.isEmpty())
            assertThrows(IllegalStateException::class.java) {
                OrbisCompanionSpacesStore(shelf(alias, other)).deleteStory(story.id, 2)
            }
        } finally { unlink(alias) }
    }

    @Test fun namespaceAndOwnerSymlinksCannotRedirectToAnotherAssistant() {
        val base = temporary.newFolder("directory-links")
        val otherStore = OrbisCompanionSpacesStore(shelf(base, other))
        otherStore.saveHumanStory("其他助手", "不能读改", "letter")
        val otherIndex = File(shelf(base, other), "index.json")
        val originalBytes = otherIndex.readBytes()
        val ownerLink = shelf(base)
        val alternativeBase = temporary.newFolder("alternative-files")
        val namespaceLink = File(alternativeBase, "orbis-companion-spaces")
        Files.createSymbolicLink(ownerLink.toPath(), shelf(base, other).canonicalFile.toPath())
        Files.createSymbolicLink(namespaceLink.toPath(), File(base, "orbis-companion-spaces").canonicalFile.toPath())
        try {
            assertThrows(IllegalArgumentException::class.java) { OrbisCompanionSpacesStore(ownerLink).snapshot() }
            assertThrows(IllegalArgumentException::class.java) { OrbisCompanionSpacesStore(shelf(alternativeBase, other)).snapshot() }
            assertThrows(IllegalArgumentException::class.java) {
                OrbisCompanionSpacesStore(File(base, "orbis-companion-spaces/$owner/../$other")).snapshot()
            }
            assertArrayEquals(originalBytes, otherIndex.readBytes())
        } finally { unlink(ownerLink); unlink(namespaceLink) }
    }

    @Test fun indexAndMediaChildLinksAreRejectedAfterStoreWasOpened() {
        val base = temporary.newFolder("file-links")
        val store = OrbisCompanionSpacesStore(shelf(base))
        val story = store.saveHumanStory("原故事", "保持原样", "letter")
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)
        val media = store.addMedia(bytes, "image/jpeg")
        store.addPhoto(media.id)
        val externalImage = temporary.newFile("unrelated-image").apply { writeBytes(bytes) }
        val mediaLink = File(shelf(base), media.filename)
        val indexLink = File(shelf(base), "index.json")
        val externalIndex = temporary.newFile("unrelated-index").apply { writeBytes(indexLink.readBytes()) }
        val originalIndex = externalIndex.readBytes()
        assertTrue(mediaLink.delete())
        Files.createSymbolicLink(mediaLink.toPath(), externalImage.canonicalFile.toPath())
        try {
            assertThrows(IllegalArgumentException::class.java) { store.mediaFile(media.id) }
            assertThrows(IllegalArgumentException::class.java) { store.imageBytes(media.id) }
            assertTrue(indexLink.delete())
            Files.createSymbolicLink(indexLink.toPath(), externalIndex.canonicalFile.toPath())
            assertThrows(IllegalArgumentException::class.java) { store.snapshot() }
            assertThrows(IllegalArgumentException::class.java) { store.writeStoryBody(story.id, "不得写入", story.revision) }
            assertArrayEquals(bytes, externalImage.readBytes())
            assertArrayEquals(originalIndex, externalIndex.readBytes())
        } finally { unlink(mediaLink); unlink(indexLink) }
    }
}
