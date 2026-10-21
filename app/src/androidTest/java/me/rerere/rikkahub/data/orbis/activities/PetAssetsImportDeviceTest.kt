package me.rerere.rikkahub.data.orbis.activities

import android.app.Application
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real distributed packs, isolated storage only. Never reads an assistant or adopts a pet. */
@RunWith(AndroidJUnit4::class)
class PetAssetsImportDeviceTest {
    @Test fun distributedPacksImportAndRemainReadyAfterReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val target = instrumentation.targetContext
        assertEquals(Application::class.java, target.applicationContext.javaClass)
        val root = File(target.cacheDir, "pet-import-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) { override fun getFilesDir() = root }
        val fixtures = File(checkNotNull(target.getExternalFilesDir(null)), "pet-import-fixtures")
        val packs = fixtures.listFiles()?.filter { it.extension == "zip" }.orEmpty()
        assertEquals("Push all forty official packs into the isolated emulator", 40, packs.size)
        try {
            packs.forEach { pack ->
                val appearance = appearance(pack)
                assertFalse(PetAssets.ready(context, appearance))
                pack.inputStream().use { PetAssets.import(context, it, appearance) }
                assertTrue("Imported $appearance", PetAssets.ready(context, appearance))
                PetAssets.names.forEach { name ->
                    val bitmap = android.graphics.BitmapFactory.decodeFile(File(PetAssets.directory(context, appearance), name).path)
                    assertNotNull("Android can decode $appearance/$name", bitmap)
                    bitmap!!.recycle()
                }
                pack.inputStream().use { PetAssets.import(context, it, appearance) }
                val reopened = object : ContextWrapper(target) { override fun getFilesDir() = root }
                assertTrue(PetAssets.ready(reopened, appearance))
            }
        } finally {
            check(root.parentFile.canonicalFile == target.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    private fun appearance(pack: File): String = ZipInputStream(pack.inputStream()).use { input ->
        while (true) {
            val entry = input.nextEntry ?: error("Missing manifest")
            if (entry.name == "manifest.json") return@use Json.parseToJsonElement(input.readBytes().toString(Charsets.UTF_8))
                .jsonObject.getValue("id").jsonPrimitive.content
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun fixture(test: (android.content.Context, File, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val target = instrumentation.targetContext
        assertEquals(Application::class.java, target.applicationContext.javaClass)
        val root = File(target.cacheDir.canonicalFile, "pet-path-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val real = File(root, "real").apply { mkdirs() }
        val alias = File(root, "alias")
        Os.symlink(real.path, alias.path)
        val context = object : ContextWrapper(target) { override fun getFilesDir() = alias }
        val packs = File(checkNotNull(target.getExternalFilesDir(null)), "pet-import-fixtures").listFiles().orEmpty()
        val pack = packs.first { it.extension == "zip" && appearance(it) == "cat-black-v1" }
        try { test(context, root, pack) }
        finally { alias.delete(); check(root.parentFile == target.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun trustedFilesDirAliasCanImportWithoutChangingPetIdentity() = fixture { context, _, pack ->
        pack.inputStream().use { PetAssets.import(context, it, "cat-black-v1") }
        assertTrue(PetAssets.ready(context, "cat-black-v1"))
        assertFalse(File(context.filesDir, "orbis-activities").exists())
    }

    @Test fun untrustedChildSymlinkStillCannotRedirectWrites() = fixture { context, root, pack ->
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        val link = File(context.filesDir, "orbis-pet-assets")
        Os.symlink(elsewhere.path, link.path)
        try {
            assertThrows(IllegalArgumentException::class.java) { pack.inputStream().use { PetAssets.import(context, it, "cat-black-v1") } }
            assertFalse(PetAssets.ready(context, "cat-black-v1"))
            assertTrue(elsewhere.listFiles().orEmpty().isEmpty())
        } finally { link.delete() }
    }

    @Test fun wrongSpeciesAndBrokenZipLeaveTheExistingImagesUntouched() = fixture { context, _, pack ->
        pack.inputStream().use { PetAssets.import(context, it, "cat-black-v1") }
        val root = PetAssets.directory(context, "cat-black-v1")
        val before = PetAssets.names.associateWith { me.rerere.rikkahub.data.orbis.OrbisStickerImages.sha256(File(root, it).readBytes()) }
        assertThrows(IllegalArgumentException::class.java) { pack.inputStream().use { PetAssets.import(context, it, "cat-tabby-v1") } }
        assertThrows(Exception::class.java) { PetAssets.import(context, byteArrayOf(1, 2, 3).inputStream(), "cat-black-v1") }
        assertEquals(before, PetAssets.names.associateWith { me.rerere.rikkahub.data.orbis.OrbisStickerImages.sha256(File(root, it).readBytes()) })
        assertFalse(PetAssets.ready(context, "cat-tabby-v1"))
    }
}
