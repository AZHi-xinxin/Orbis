package me.rerere.rikkahub.data.orbis.character

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.widget.characterColoredSprite
import me.rerere.rikkahub.ui.widget.characterFaceComposite
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BuiltinCharacterDeviceTest {
    private fun context(): android.content.Context {
        val i = InstrumentationRegistry.getInstrumentation()
        check(i is IsolatedGenerationLoopRunner)
        val fd = i.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        check(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText().trim() } == "1")
        return i.targetContext
    }
    private fun decode(bytes: ByteArray): Bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = 4; inMutable = true }))
    private fun sheet(name: String, labels: List<String>, columns: Int, render: (Int) -> Bitmap) {
        val first = render(0); val w = first.width; val h = first.height; first.recycle()
        val output = Bitmap.createBitmap(w * columns, (h + 32) * ((labels.size + columns - 1) / columns), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output); canvas.drawColor(Color.rgb(236, 238, 243))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 19f }
        labels.forEachIndexed { index, label ->
            val bitmap = render(index); val x = index % columns * w; val y = index / columns * (h + 32)
            canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)
            canvas.drawText(label, x + 8f, y + h + 23f, paint); bitmap.recycle()
        }
        val dir = File(context().getExternalFilesDir(null), "builtin-character-output").apply { check(mkdirs() || isDirectory) }
        File(dir, name).outputStream().use { assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        output.recycle()
    }
    @Test fun completePublicSetDecodesAndDefaultInstallationIsIdempotent() {
        val draft = loadBuiltinCharacter(context()); val p = draft.presentation!!
        assertEquals(33, p.assets.size)
        assertEquals(9, p.assets.count { it.kind == CharacterAssetKind.POSE })
        characterEmotions.forEach { mood -> assertEquals(mood, 3, p.assets.count { it.kind == CharacterAssetKind.EXPRESSION && it.emotion == mood }) }
        p.assets.forEach { asset ->
            assertEquals(BuiltinCharacter.COLOR_MODEL, asset.colorModel); assertNotNull(asset.faceAnchor)
            val bytes = draft.assets.getValue(asset.sha256); assertEquals(asset.sha256, characterSha(bytes))
            val bitmap = decode(bytes)
            assertEquals(0, Color.alpha(bitmap.getPixel(0,0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width - 1,0)))
            assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200)
            bitmap.recycle()
        }
        val store = openCharacterStore(context(), UUID.randomUUID().toString())
        val first = ensureBuiltinCharacter(context(), store)
        assertEquals(first, ensureBuiltinCharacter(context(), store)); assertEquals(draft.pack, first.pack)
    }
    @Test fun colorsNeverAlterSourcePrivateArtClothesOrAlphaAndEyesAreIndependent() {
        val d = loadBuiltinCharacter(context()); val a = d.presentation!!.assets.first { it.id == "pose-neutral" }
        val source = decode(d.assets.getValue(a.sha256))
        val original = IntArray(source.width * source.height)
        source.getPixels(original, 0, source.width, 0, 0, source.width, source.height)
        assertSame(source, characterColoredSprite(source, a.copy(colorModel = null), CharacterColors("mimo","gpt"),"angry"))
        val colored = characterColoredSprite(source, a, CharacterColors("mimo","gpt"))
        val eyes = characterColoredSprite(source, a, CharacterColors("deepseek","mimo"))
        val angry = characterColoredSprite(source, a, CharacterColors("deepseek","mimo"),"angry")
        val excited = characterColoredSprite(source, a, CharacterColors("deepseek","mimo"),"excited")
        var hairChanged = 0; var eyesChanged = 0; var emotionChanged = 0
        for (y in 0 until source.height) for (x in 0 until source.width) {
            val before = original[y * source.width + x]; val after = colored.getPixel(x,y)
            val region = characterColorRegion((x + .5f) / source.width, (y + .5f) / source.height)
            assertEquals(before, source.getPixel(x,y)); assertEquals(Color.alpha(before),Color.alpha(after))
            if (region == 0) assertEquals(before,after)
            if (region != 2) assertEquals(before,eyes.getPixel(x,y))
            if (before != after && region == 1) hairChanged++
            if (before != eyes.getPixel(x,y) && region == 2) eyesChanged++
            assertEquals(angry.getPixel(x,y),excited.getPixel(x,y))
            if (eyes.getPixel(x,y) != angry.getPixel(x,y)) emotionChanged++
        }
        assertTrue(hairChanged > 100); assertTrue(eyesChanged > 10); assertTrue(emotionChanged > 10)
        assertEquals(a.sha256,characterSha(d.assets.getValue(a.sha256)))
        listOf(source,colored,eyes,angry,excited).distinct().forEach(Bitmap::recycle)
    }
    @Test fun actualRendererProducesPalettePoseAndExpressionReviewSheets() {
        val d = loadBuiltinCharacter(context()); val p = d.presentation!!
        val a = p.assets.first { it.id == "pose-neutral" }
        sheet("ten-palettes.png", characterPalettes.map { it.id }, 5) { index ->
            val source = decode(d.assets.getValue(a.sha256)); val id = characterPalettes[index].id
            characterColoredSprite(source,a,CharacterColors(id,id)).also { if (it !== source) source.recycle() }
        }
        val poses = p.assets.filter { it.kind == CharacterAssetKind.POSE }
        sheet("all-poses.png", poses.map { it.tag }, 3) { decode(d.assets.getValue(poses[it].sha256)) }
        val faces = p.assets.filter { it.kind == CharacterAssetKind.EXPRESSION }
        sheet("all-expressions.png", faces.map { it.id.removePrefix("expression-") }, 6) { index ->
            val source = decode(d.assets.getValue(a.sha256)); val face = faces[index]
            val f = decode(d.assets.getValue(face.sha256))
            val combined = characterFaceComposite(source,f,a.faceAnchor!!,face.faceAnchor!!)
            source.recycle(); f.recycle()
            characterColoredSprite(combined,a,CharacterColors("gpt","mimo"),face.emotion).also { if (it !== combined) combined.recycle() }
        }
    }
}

