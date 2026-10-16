package me.rerere.rikkahub.ui.pages.orbis.character

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.orbis.character.*

/** Two aligned images crossfade; no stretching and no background embedded in the person. */
@Composable
internal fun CharacterScene(store: CharacterStore, p: CharacterPresentation, night: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val images by produceState<Pair<android.graphics.Bitmap?, android.graphics.Bitmap?>?>(null, p.scene, p.assets) {
        value = withContext(Dispatchers.IO) {
            fun load(kind: CharacterAssetKind, suffix: String): android.graphics.Bitmap? = runCatching {
                val custom = p.assets.firstOrNull { it.kind == kind && it.tag == p.scene }
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                if (custom != null) {
                    val bytes = store.assetBytes(custom)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } else {
                    val scene = p.scene.takeIf { it in characterSceneNames } ?: "living"
                    context.assets.open("orbis-character/rooms/$scene-$suffix.png").use { BitmapFactory.decodeStream(it, null, opts) }
                }
            }.getOrNull()
            load(CharacterAssetKind.ROOM_DAY, "day") to load(CharacterAssetKind.ROOM_NIGHT, "night")
        }
    }
    val alpha by animateFloatAsState(night, tween(2500), label = "room daylight")
    Box(modifier) {
        images?.first?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        images?.second?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha) }
    }
}
