package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.activities.PetAssets
import me.rerere.rikkahub.data.orbis.activities.PetPose
import me.rerere.rikkahub.data.orbis.activities.canUseBundledPetPoses
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

private data class PoseAtlas(val bitmap: ImageBitmap, val regions: List<List<Int>>)

/** Each state uses a different illustration, with a crossfade between poses. No hit tint. */
@Composable
internal fun PetPoseSprite(appearance: String, pose: PetPose, imported: File?, animate: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val atlas by produceState<PoseAtlas?>(null, appearance, imported) {
        value = withContext(Dispatchers.IO) {
            // Reuse poses for byte-identical early official imports, never for a
            // different custom drawing merely bearing the same appearance ID.
            if(!canUseBundledPetPoses(context, appearance, imported)) null else runCatching {
                val base = "orbis-pets/poses/$appearance"
                val regions = context.assets.open("$base.json").bufferedReader().use {
                    val list = JSONObject(it.readText()).getJSONArray("regions")
                    require(list.length() == 6)
                    (0..5).map { i -> (0..3).map { j -> list.getJSONArray(i).getInt(j) } }
                }
                val bitmap = context.assets.open("$base.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                require(bitmap.width <= 2048 && bitmap.height <= 2048)
                regions.forEach { (x,y,w,h) -> require(x >= 0 && y >= 0 && w > 0 && h > 0 && x+w <= bitmap.width && y+h <= bitmap.height) }
                PoseAtlas(bitmap.asImageBitmap(), regions)
            }.getOrNull()
        }
    }
    val loaded = atlas
    if(loaded == null) {
        AsyncImage(PetAssets.model(appearance,"cat.png",imported),null,modifier,contentScale=ContentScale.Fit,
            alignment=Alignment.BottomCenter)
    } else Crossfade(pose, modifier.testTag("pet-pose-atlas"), animationSpec=if(animate) tween(240) else snap(), label="pet-pose-crossfade") { current ->
        Canvas(Modifier.fillMaxSize().testTag("pet-pose-${current.name.lowercase()}")) {
            val (x,y,w,h) = loaded.regions[current.frame]
            val scale = minOf(size.width / loaded.regions.maxOf { it[2] }, size.height / loaded.regions.maxOf { it[3] })
            val width = (w * scale).roundToInt().coerceAtLeast(1)
            val height = (h * scale).roundToInt().coerceAtLeast(1)
            drawImage(loaded.bitmap, IntOffset(x,y), IntSize(w,h),
                IntOffset(((size.width-width)/2).roundToInt(), (size.height-height).roundToInt()),
                IntSize(width,height), filterQuality=FilterQuality.High)
        }
    }
}
