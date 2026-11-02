package me.rerere.ai.provider.providers.openai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ImageEditParams
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.math.*

internal data class NovelAiReference(val base64: String, val size: String)

/** Keep original aspect ratio near normal output area, with dimensions accepted by NovelAI. */
internal fun novelAiReferenceSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "workshop_image_invalid" }
    val factor = minOf(1.0, sqrt(1_048_576.0 / (width.toDouble() * height)), 2048.0 / max(width, height))
    val w = (floor(width * factor / 64).toInt() * 64).coerceIn(64, 2048)
    val h = (floor(height * factor / 64).toInt() * 64).coerceIn(64, 2048)
    return w to h
}

internal fun prepareNovelAiReference(params: ImageEditParams): NovelAiReference {
    require(params.images.size == 1) { "workshop_single_reference_only" }
    val file = File(params.images.single())
    require(file.isFile && file.length() in 1..24L*1024*1024) { "workshop_reference" }
    val bytes = file.inputStream().use { input ->
        val out=ByteArrayOutputStream();val block=ByteArray(8192)
        while(true) { val n=input.read(block);if(n<0)break
            require(out.size()+n <= 24*1024*1024) { "workshop_image_large" };out.write(block,0,n) }
        out.toByteArray()
    }
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192 && bounds.outWidth.toLong()*bounds.outHeight <= 32_000_000 &&
        bounds.outMimeType in setOf("image/png","image/jpeg","image/webp")) { "workshop_image_invalid" }
    val custom=params.customBody.associate { it.key to it.value }
    val size = if(params.size.isBlank() || params.size=="auto") novelAiReferenceSize(bounds.outWidth,bounds.outHeight)
        else params.size.split('x').let { it[0].toInt() to it[1].toInt() }
    val outputW=(custom["width"] as? JsonPrimitive)?.intOrNull ?: size.first
    val outputH=(custom["height"] as? JsonPrimitive)?.intOrNull ?: size.second
    val precise=(custom["reference_mode"] as? JsonPrimitive)?.content=="character&style"
    val (w,h)=if(!precise) outputW to outputH else when {
        bounds.outWidth > bounds.outHeight*1.15 -> 1536 to 1024
        bounds.outHeight > bounds.outWidth*1.15 -> 1024 to 1536
        else -> 1472 to 1472
    }
    // Downsample before decoding to bound native bitmap memory, then fit with padding.
    var sample=1
    while(bounds.outWidth/(sample*2)>=w && bounds.outHeight/(sample*2)>=h)sample*=2
    val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
        ?: error("workshop_image_invalid")
    val target=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    try {
        val canvas=Canvas(target);canvas.drawColor(Color.BLACK)
        val scale=min(w.toFloat()/source.width,h.toFloat()/source.height)
        val dw=source.width*scale;val dh=source.height*scale
        canvas.drawBitmap(source,null,RectF((w-dw)/2,(h-dh)/2,(w+dw)/2,(h+dh)/2),Paint(Paint.FILTER_BITMAP_FLAG))
        val out=ByteArrayOutputStream();require(target.compress(Bitmap.CompressFormat.PNG,100,out)) { "workshop_image_format" }
        return NovelAiReference(Base64.getEncoder().encodeToString(out.toByteArray()),"${size.first}x${size.second}")
    } finally {source.recycle();target.recycle()}
}
