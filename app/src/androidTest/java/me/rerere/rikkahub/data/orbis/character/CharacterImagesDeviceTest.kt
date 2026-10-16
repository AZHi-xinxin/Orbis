package me.rerere.rikkahub.data.orbis.character

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Synthetic pixels only. Run with -PorbisIsolatedTests=true; no user's assistants or files. */
@RunWith(AndroidJUnit4::class)
class CharacterImagesDeviceTest {
    @Before fun isolated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
    }
    private fun image(format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val b = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
        try { b.eraseColor(Color.TRANSPARENT); b.setPixel(4, 4, Color.RED); return ByteArrayOutputStream().also { b.compress(format, 100, it) }.toByteArray() }
        finally { b.recycle() }
    }
    private fun draft(bytes: ByteArray) = CharacterDraft(CharacterPack(sha256=characterSha(bytes),width=8,height=16),CharacterInteractions(),bytes)
    @Test fun transparentPngPassesAndRetainsTransparency() {
        val bytes=image(); verifyCharacterDraft(draft(bytes))
        val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try { assertEquals(0,Color.alpha(decoded.getPixel(0,0))); assertEquals(255,Color.alpha(decoded.getPixel(4,4))) } finally { decoded.recycle() }
    }
    @Test fun mislabeledJpegCannotBecomeCharacterPng() { assertThrows(IllegalArgumentException::class.java) { verifyCharacterDraft(draft(image(Bitmap.CompressFormat.JPEG))) } }
    @Test fun lyingDimensionsRejectBeforeInstalling() { val d=draft(image()); assertThrows(IllegalArgumentException::class.java) { verifyCharacterDraft(d.copy(pack=d.pack.copy(width=99))) } }
    @Test fun hashFailureRejectsEvenDecodablePng() { val d=draft(image()); assertThrows(IllegalArgumentException::class.java) { verifyCharacterDraft(d.copy(pack=d.pack.copy(sha256="0".repeat(64)))) } }
    @Test fun archiveKeepsTransparentPixels() { val out=ByteArrayOutputStream(); CharacterArchive.write(out,draft(image()),false); verifyCharacterDraft(CharacterArchive.read(out.toByteArray().inputStream())) }
    @Test fun expandedArchiveVerifiesEveryImage() {
        val b = image(); val a = CharacterAsset("pose", CharacterAssetKind.POSE, "calm", characterSha(b), 8, 16)
        val d = draft(b).copy(presentation = CharacterPresentation(assets = listOf(a)), assets = mapOf(a.sha256 to b))
        verifyCharacterDraft(d)
        assertThrows(IllegalArgumentException::class.java) { verifyCharacterDraft(d.copy(presentation = CharacterPresentation(assets = listOf(a.copy(width = 9))))) }
    }
    @Test fun roomAndCardBuiltinsAreDecodable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = characterSceneNames.flatMap { scene -> listOf("rooms/$scene-day.png", "rooms/$scene-night.png") } + characterCardNames.map { "cards/$it.png" }
        files.forEach { name -> context.assets.open("orbis-character/$name").use {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(it, null, bounds)
            assertEquals("image/png", bounds.outMimeType); assertTrue(bounds.outWidth > 0); assertTrue(bounds.outHeight > 0)
        } }
    }
    @Test fun horizontalWidgetInflatesWithSystemClocksAndSeparateHitTargets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = android.widget.RemoteViews(context.packageName, me.rerere.rikkahub.R.layout.orbis_character_widget).apply(context, null)
            assertTrue(view.findViewById<android.view.View>(me.rerere.rikkahub.R.id.character_widget_clock) is android.widget.TextClock)
            assertNotNull(view.findViewById<android.view.View>(me.rerere.rikkahub.R.id.character_widget_head))
            assertNotNull(view.findViewById<android.view.View>(me.rerere.rikkahub.R.id.character_widget_hand))
            // A launcher can hand us a portrait-shaped host. The actual card stays horizontal.
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1300, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 900, 1300)
            val canvas = view.findViewById<android.view.View>(me.rerere.rikkahub.R.id.character_widget_canvas)
            assertTrue(kotlin.math.abs((180 * context.resources.displayMetrics.density).toInt() - canvas.height) <= 1)
            assertTrue(canvas.height < view.height)
        }
    }
    @Test fun bustWaistStopsAtCardEdgeWithoutChangingSourceOrOtherPoses() {
        val source = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            assertSame(source, me.rerere.rikkahub.ui.widget.characterWidgetSprite(source, 180, false))
            for (height in listOf(150, 180)) {
                val framed = me.rerere.rikkahub.ui.widget.characterWidgetSprite(source, height, true)
                try {
                    assertEquals(296, framed.width); assertEquals(height * 2, framed.height)
                    assertEquals(Color.RED, framed.getPixel(148, (height - 16) * 2 - 1))
                    assertEquals(0, Color.alpha(framed.getPixel(148, (height - 16) * 2)))
                    assertEquals(0, Color.alpha(framed.getPixel(148, height * 2 - 1)))
                    assertEquals(0, Color.alpha(framed.getPixel(0, 0)))
                    assertEquals(Color.RED, source.getPixel(20, 59))
                } finally { framed.recycle() }
            }
        } finally { source.recycle() }
    }
}
