package me.rerere.rikkahub.data.orbis.activities

import android.app.Application
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Optional packs and disposable fixtures only; never adopts or changes a real pet. */
@RunWith(AndroidJUnit4::class)
class PetPoseAssetsDeviceTest {
    private fun context(): android.content.Context {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        return instrumentation.targetContext.also {
            assertEquals(Application::class.java, it.applicationContext.javaClass)
        }
    }

    @Test fun allFourOptionalAtlasFamiliesHaveSixDistinctDecodablePoses() {
        val context = context()
        assertEquals(4, BUNDLED_PET_POSE_APPEARANCES.size)
        BUNDLED_PET_POSE_APPEARANCES.forEach { appearance ->
            assertFalse(canUseBundledPetPoses(context, appearance, null))
            val base=PetArtDownloads.store(context).install(appearance,File(context.filesDir,"pet-art-fixtures/$appearance.zip"))
            assertTrue(canUseBundledPetPoses(context, appearance, base))
            val regions = File(base,"pose.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONArray("regions")
            }
            assertEquals(6, regions.length())
            val bitmap = File(base,"pose.webp").inputStream().use { BitmapFactory.decodeStream(it) }
            assertNotNull(bitmap)
            try {
                val fingerprints = (0 until regions.length()).map { index ->
                    val region = regions.getJSONArray(index)
                    val width = region.getInt(2)
                    val height = region.getInt(3)
                    val pixels = IntArray(width * height)
                    bitmap!!.getPixels(pixels, 0, width, region.getInt(0), region.getInt(1), width, height)
                    pixels.contentHashCode()
                }
                assertEquals("$appearance must not repeat one static drawing", 6, fingerprints.toSet().size)
            } finally { bitmap?.recycle() }
        }
        assertFalse(canUseBundledPetPoses(context, "cat-orange-v1", null))
    }

    @Test fun identicalEarlyImportGetsAnimationButCustomIdentityIsNeverReplaced() {
        val context = context()
        val fixture = File(context.cacheDir.canonicalFile, "pose-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            BUNDLED_PET_POSE_APPEARANCES.forEach { appearance ->
                PetArtDownloads.store(context).install(appearance,File(context.filesDir,"pet-art-fixtures/$appearance.zip"))
                File(context.filesDir,"pet-art-fixtures/$appearance.png").inputStream().use { input ->
                    File(fixture, "cat.png").outputStream().use { input.copyTo(it) }
                }
                assertTrue("$appearance official import should upgrade", canUseBundledPetPoses(context, appearance, fixture))
                File(fixture, "cat.png").appendBytes(byteArrayOf(0))
                assertFalse("$appearance different custom file must stay original", canUseBundledPetPoses(context, appearance, fixture))
            }
        } finally {
            check(fixture.parentFile == context.cacheDir.canonicalFile)
            fixture.deleteRecursively()
        }
    }
}
