package me.rerere.rikkahub.data.orbis.voice

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Android JPEG/storage, but no camera, microphone, network, Koin or personal files. */
@RunWith(AndroidJUnit4::class)
class VideoFramePhotoLifecycleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temp = TemporaryFolder(instrumentation.targetContext.cacheDir)

    @Before fun isolatedOnly() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }

    @Test fun capturedJpegTenPhotoLimitAndRestartCleanupAreOneLifecycle() = runBlocking {
        val owner = UUID.randomUUID().toString()
        val call = UUID.randomUUID().toString()
        val frameRoot = temp.newFolder("temporary-camera-frames").canonicalFile
        val photosParent = temp.newFolder("orbis-companion-spaces").canonicalFile
        val photoRoot = File(photosParent, owner)
        val frames = OrbisVideoFrameStore(frameRoot)
        val photos = OrbisCompanionSpacesStore(photoRoot)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            bitmap.eraseColor(android.graphics.Color.rgb(56, 91, 150))
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)); output.toByteArray()
            }
        } finally { bitmap.recycle() }
        frames.begin(owner, UUID.randomUUID().toString(), call, 100)
        val captured = (1..11).map { frames.add(owner, call, jpeg, 200 + it.toLong()) }
        assertTrue(runCatching { frames.readJpeg(UUID.randomUUID().toString(), call, captured[0].id, 300) }.isFailure)
        for (frame in captured.take(10)) frames.retain(owner, call, frame.id, 300) {
            photos.importVideoFrame(it, "合成测试图", call, frame.id).id
        }
        assertTrue(runCatching { frames.retain(owner, call, captured[10].id, 300) {
            photos.importVideoFrame(it, "不得超过十张", call, captured[10].id).id
        } }.isFailure)
        val first = photos.snapshot().photos.first()
        assertEquals(32, BitmapFactory.decodeFile(photos.mediaFile(first.mediaId).path).let { image ->
            try { image.width } finally { image.recycle() }
        })
        photos.deletePhoto(first.id)
        assertTrue(runCatching { OrbisVideoFrameStore(frameRoot).retain(owner, call, captured[10].id, 350) {
            OrbisCompanionSpacesStore(photoRoot).importVideoFrame(it, "删除不返额度", call, captured[10].id).id
        } }.isFailure)
        frames.end(owner, call, 400)
        val restarted = OrbisVideoFrameStore(frameRoot)
        assertEquals(11, restarted.cleanup(400 + VIDEO_FRAME_TTL_MS, recoverInterrupted = true))
        assertTrue(restarted.list(owner, call, 400 + VIDEO_FRAME_TTL_MS).isEmpty())
        val preserved = OrbisCompanionSpacesStore(photoRoot)
        assertEquals(9, preserved.snapshot().photos.size)
        preserved.snapshot().photos.forEach { assertTrue(preserved.mediaFile(it.mediaId).isFile) }
    }
}
