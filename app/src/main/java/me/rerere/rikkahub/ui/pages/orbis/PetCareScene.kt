package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Nine complete care pictures: sleep/food/water rows, sage/rose/sky columns.
 * Kept as an atlas on disk; only this adopted animal is decoded. No moving actor
 * is drawn over the picture, and Fit keeps the face and bowl visible on short screens.
 */
@Composable internal fun PetCareScene(file: File, row: Int, palette: String, modifier: Modifier = Modifier) {
    require(row in 0..2)
    val column = when(palette) { "rose" -> 1; "sky" -> 2; else -> 0 }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    val image by produceState<ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth in 9..4096 && bounds.outHeight in 9..4096)
                checkNotNull(BitmapFactory.decodeFile(file.path)).asImageBitmap()
            }.getOrNull()
        }
        failed = value == null
    }
    val bitmap = image
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    if(bitmap == null) {
        if(failed) Text("画面暂时无法读取，可在布置中重新获取原画") else CircularProgressIndicator()
    }
    Canvas(Modifier.fillMaxSize().then(if(bitmap != null) Modifier.testTag("pet-care-frame-$row-$column") else Modifier)) {
        if(bitmap != null) {
            // Integer boundaries cover non-divisible atlas dimensions without sampling neighbours.
            val x = bitmap.width * column / 3 + 1
            val y = bitmap.height * row / 3 + 1
            val w = bitmap.width * (column + 1) / 3 - x - 1
            val h = bitmap.height * (row + 1) / 3 - y - 1
            val scale = minOf(size.width / w, size.height / h)
            val dw = (w * scale).roundToInt().coerceAtLeast(1)
            val dh = (h * scale).roundToInt().coerceAtLeast(1)
            drawImage(bitmap, IntOffset(x,y), IntSize(w,h),
                IntOffset(((size.width-dw)/2).roundToInt(),((size.height-dh)/2).roundToInt()),
                IntSize(dw,dh), filterQuality=FilterQuality.High)
        }
    }
    }
}
