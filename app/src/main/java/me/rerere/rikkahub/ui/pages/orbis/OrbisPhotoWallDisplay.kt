package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import me.rerere.rikkahub.data.orbis.spaces.SpacePhoto

private val PhotoPaper = Color(0xFFFFFCF4)
private val PhotoInk = Color(0xFF514849)
private val PhotoMuted = Color(0xFF8A7C75)

/** Presentation only: keeps store order, ids and notes intact; all destructive actions stay in edit. */
@Composable
internal fun OrbisPhotoWallDisplay(
    photos: List<SpacePhoto>, style: String, store: OrbisCompanionSpacesStore,
    enabled: Boolean, onAdd: () -> Unit, onImage: (String) -> Unit,
) {
    when (style) {
        "album" -> PhotoAlbum(photos, store, enabled, onAdd, onImage)
        "hanging" -> HangingPhotos(photos, store, enabled, onAdd, onImage)
        "collage" -> FeltPhotoWall(photos, store, enabled, onAdd, onImage)
        else -> PolaroidPhotos(photos, store, enabled, onAdd, onImage)
    }
}

/** Fit is also shared by the preserved editor, so narrow portraits and panoramas never crop. */
@Composable
internal fun PhotoWallImage(
    photo: SpacePhoto, store: OrbisCompanionSpacesStore, modifier: Modifier = Modifier,
    description: String = "查看照片原图", frameRatio: Float? = null,
) {
    var ratio by remember(photo.mediaId) { mutableFloatStateOf(.8f) }
    var failed by remember(photo.mediaId) { mutableStateOf(false) }
    val file = remember(store, photo.mediaId) { runCatching { store.mediaFile(photo.mediaId) }.getOrNull() }
    Box(modifier.aspectRatio(frameRatio ?: ratio).background(Color(0xFFF3EEE5)), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = file, contentDescription = description, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().testTag("space-photo-image-${photo.id}"),
            onSuccess = { result ->
                ratio = photoWallFrameAspectRatio(result.result.image.width, result.result.image.height)
                failed = false
            },
            onError = { failed = true },
        )
        if (failed || file == null) Text("照片暂时无法读取", color = PhotoMuted,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GalleryPhoto(
    photo: SpacePhoto, store: OrbisCompanionSpacesStore, onImage: (String) -> Unit,
    modifier: Modifier = Modifier, frameRatio: Float? = null, number: Int? = null,
) {
    Surface(modifier, color = PhotoPaper, contentColor = PhotoInk, shape = RoundedCornerShape(2.dp), shadowElevation = 5.dp) {
        Column(Modifier.padding(9.dp)) {
            PhotoWallImage(photo, store, Modifier.fillMaxWidth().clickable { onImage(photo.mediaId) }, frameRatio = frameRatio)
            Text(photo.note.ifBlank { "留住这一刻" }, Modifier.padding(top = 10.dp, bottom = 7.dp),
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            number?.let { Text(it.toString().padStart(2, '0'), color = PhotoMuted,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End)) }
        }
    }
}

@Composable
private fun AddPhotoPlace(enabled: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(enabled = enabled, onClick = onAdd)
        .border(1.dp, PhotoMuted.copy(alpha = .25f), RoundedCornerShape(3.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("＋", fontSize = 30.sp, color = PhotoMuted)
        Text("待添加", color = PhotoInk, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Text("下一段日常\n留在这里", color = PhotoMuted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PagingControls(state: PagerState, photoCount: Int, album: Boolean) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = state.currentPage > 0, onClick = { scope.launch { state.animateScrollToPage(state.currentPage - 1) } }) {
            Text(if (album) "‹ 上一页" else "‹ 上一张")
        }
        Text(if (album) "${state.currentPage + 1} / ${state.pageCount} 页"
            else if (state.currentPage < photoCount) "${state.currentPage + 1} / $photoCount" else "留一个位置",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(enabled = state.currentPage < state.pageCount - 1,
            onClick = { scope.launch { state.animateScrollToPage(state.currentPage + 1) } }) {
            Text(if (album) "下一页 ›" else "下一张 ›")
        }
    }
}

@Composable
private fun PolaroidPhotos(photos: List<SpacePhoto>, store: OrbisCompanionSpacesStore,
    enabled: Boolean, onAdd: () -> Unit, onImage: (String) -> Unit) {
    val pager = rememberPagerState { photos.size + 1 }
    Column(Modifier.fillMaxSize().testTag("space-photo-display-polaroid")
        .verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperCamera(Modifier.size(80.dp, 58.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text("把瞬间，慢慢收藏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("左右轻翻 · 每一张都完整保留", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalPager(state = pager, key = { photos.getOrNull(it)?.id ?: "add-photo" },
            modifier = Modifier.fillMaxWidth().testTag("space-photo-pager"), verticalAlignment = Alignment.CenterVertically) { index ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
                // Empty paper layers sit behind the current frame, never over its image.
                Box(Modifier.matchParentSize().padding(5.dp).rotate(-4f).background(Color(0xFFE7DDD0), RoundedCornerShape(3.dp)))
                Box(Modifier.matchParentSize().padding(3.dp).rotate(3f).background(Color(0xFFF3EADC), RoundedCornerShape(3.dp)))
                val photo = photos.getOrNull(index)
                if (photo == null) Surface(color = PhotoPaper, shape = RoundedCornerShape(2.dp), shadowElevation = 6.dp) {
                    AddPhotoPlace(enabled, onAdd, Modifier.fillMaxWidth().height(310.dp).padding(18.dp))
                } else GalleryPhoto(photo, store, onImage, Modifier.fillMaxWidth(), number = index + 1)
            }
        }
        PagingControls(pager, photos.size, false)
    }
}

@Composable
private fun PaperCamera(modifier: Modifier) {
    Canvas(modifier) {
        val ink = Color(0xFF978D86)
        val pink = Color(0xFFD8BFBA)
        drawRoundRect(Color(0xFFB7AAA3), Offset(size.width * .07f, size.height * .15f), Size(size.width * .85f, size.height * .8f), CornerRadius(9.dp.toPx()))
        drawRoundRect(PhotoPaper, Offset(size.width * .04f, size.height * .09f), Size(size.width * .84f, size.height * .78f), CornerRadius(9.dp.toPx()))
        drawRoundRect(pink, Offset(size.width * .04f, size.height * .38f), Size(size.width * .84f, size.height * .33f), CornerRadius(2.dp.toPx()))
        drawCircle(ink, size.height * .26f, Offset(size.width * .49f, size.height * .52f))
        drawCircle(PhotoPaper, size.height * .21f, Offset(size.width * .49f, size.height * .52f))
        drawCircle(Color(0xFF728A8D), size.height * .15f, Offset(size.width * .49f, size.height * .52f))
        drawCircle(Color.White.copy(alpha = .8f), size.height * .045f, Offset(size.width * .46f, size.height * .45f))
        drawRoundRect(ink, Offset(size.width * .13f, size.height * .035f), Size(size.width * .15f, size.height * .08f), CornerRadius(2.dp.toPx()))
        drawRoundRect(pink, Offset(size.width * .67f, size.height * .2f), Size(size.width * .13f, size.height * .09f), CornerRadius(2.dp.toPx()))
    }
}

@Composable
private fun PhotoAlbum(photos: List<SpacePhoto>, store: OrbisCompanionSpacesStore,
    enabled: Boolean, onAdd: () -> Unit, onImage: (String) -> Unit) {
    val spreads = remember(photos.map { it.id }) { photoWallAlbumSpreads(photos.map { it.id }) }
    val byId = remember(photos) { photos.associateBy { it.id } }
    val pager = rememberPagerState { spreads.size }
    Column(Modifier.fillMaxSize().testTag("space-photo-display-album").verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("我们的日常，装订成册", Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        HorizontalPager(pager, modifier = Modifier.fillMaxWidth().testTag("space-photo-pager")) { spreadIndex ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                .graphicsLayer {
                    rotationY = ((pager.currentPage - spreadIndex) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f) * 8f
                    cameraDistance = 24 * density
                }) {
                Surface(color = Color(0xFF8D9386), shape = RoundedCornerShape(7.dp), shadowElevation = 6.dp) {
                    Row(Modifier.fillMaxWidth().padding(5.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        spreads[spreadIndex].forEachIndexed { side, id ->
                            Column(Modifier.weight(1f).background(Brush.horizontalGradient(if (side == 0)
                                listOf(PhotoPaper, PhotoPaper, Color(0xFFE0D8C9)) else
                                listOf(Color(0xFFDBD1BF), PhotoPaper, PhotoPaper)))
                                .padding(horizontal = 10.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val photo = id?.let(byId::get)
                                if (photo != null) {
                                    PhotoWallImage(photo, store, Modifier.fillMaxWidth().clickable { onImage(photo.mediaId) }, frameRatio = .7f)
                                    Text(photo.note.ifBlank { "在这一页，留住你我" },
                                        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(top = 14.dp),
                                        color = PhotoInk, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 4, overflow = TextOverflow.Ellipsis)
                                } else {
                                    AddPhotoPlace(enabled, onAdd, Modifier.fillMaxWidth().aspectRatio(.7f))
                                    Spacer(Modifier.height(72.dp))
                                }
                                Text("— ${spreadIndex * 2 + side + 1} —", color = PhotoMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).width(2.dp).height(20.dp).background(Color(0xFFB09C82)))
            }
        }
        PagingControls(pager, photos.size, true)
        Text("左右翻页 · 空白页也等着新的故事", Modifier.fillMaxWidth().padding(10.dp),
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HangingPhotos(photos: List<SpacePhoto>, store: OrbisCompanionSpacesStore,
    enabled: Boolean, onAdd: () -> Unit, onImage: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .2f
    BoxWithConstraints(Modifier.fillMaxSize().testTag("space-photo-display-hanging")
        .background(if (dark) Color(0xFF343B37) else Color(0xFFF1F0E8))) {
        val columns = if (maxWidth >= 360.dp) 3 else 2
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("风把日常挂起来", Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, color = if (dark) PhotoPaper else PhotoInk)
                Canvas(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 22.dp)) {
                    drawLine(Color(0xFF8C7254), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 12.dp.toPx(), StrokeCap.Round)
                    drawLine(Color(0xFFC0A17B), Offset(3.dp.toPx(), size.height / 2 - 2.dp.toPx()), Offset(size.width - 3.dp.toPx(), size.height / 2 - 2.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            items(photos.chunked(columns), key = { row -> row.joinToString { it.id } }) { row ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    Canvas(Modifier.matchParentSize()) {
                        val rope = Path().apply { moveTo(0f, 14.dp.toPx()); quadraticTo(size.width / 2, 60.dp.toPx(), size.width, 14.dp.toPx()) }
                        drawPath(rope, Color(0xFFB8A07B), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                        row.indices.forEach { column ->
                            val x = size.width * (column + .5f) / columns
                            val y = photoWallRopeY(column, columns).dp.toPx()
                            drawLine(Color(0xFFB8A07B), Offset(x, y), Offset(x, y + 26.dp.toPx()), 1.5.dp.toPx())
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { index, photo ->
                            Box(Modifier.weight(1f).padding(top = (photoWallRopeY(index, columns) + 18).dp)) {
                                GalleryPhoto(photo, store, onImage, Modifier.fillMaxWidth(), frameRatio = .75f)
                                Clothespin(Modifier.align(Alignment.TopCenter).offset(y = (-11).dp).size(9.dp, 24.dp))
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp), color = PhotoPaper.copy(alpha = .94f), shape = RoundedCornerShape(3.dp)) {
                    AddPhotoPlace(enabled, onAdd, Modifier.fillMaxWidth().height(150.dp))
                }
            }
        }
    }
}

@Composable
private fun Clothespin(modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(Color(0xFF9F8059), cornerRadius = CornerRadius(2.dp.toPx()))
        drawRoundRect(Color(0xFFD8BE91), size = Size(size.width * .7f, size.height), cornerRadius = CornerRadius(2.dp.toPx()))
        drawLine(Color(0xFF88765E), Offset(size.width * .15f, size.height * .46f), Offset(size.width * .9f, size.height * .46f), 1.dp.toPx())
    }
}

@Composable
private fun FeltPhotoWall(photos: List<SpacePhoto>, store: OrbisCompanionSpacesStore,
    enabled: Boolean, onAdd: () -> Unit, onImage: (String) -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .2f
    val felt = if (dark) Color(0xFF414A46) else Color(0xFFD8D4C5)
    Box(Modifier.fillMaxSize().testTag("space-photo-display-collage").background(felt).drawWithCache {
        val fibers = Path()
        val step = 9.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else step / 2
            while (x < size.width) { fibers.moveTo(x, y); fibers.lineTo(x + 2.dp.toPx(), y + 1.dp.toPx()); x += step }
            y += step; row++
        }
        onDrawBehind { drawPath(fibers, Color.White.copy(alpha = .18f), style = Stroke(.6.dp.toPx())) }
    }) {
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp), verticalItemSpacing = 28.dp) {
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                Box(Modifier.padding(top = if (index == 1) 34.dp else 10.dp).rotate(photoWallCollageTilt(index))) {
                    GalleryPhoto(photo, store, onImage, Modifier.fillMaxWidth(), number = index + 1)
                    Box(Modifier.align(Alignment.TopCenter).offset(y = (-6).dp).rotate(-4f).size(38.dp, 15.dp)
                        .background(Color(0xFFC6B6A0).copy(alpha = .82f)))
                }
            }
            item {
                Surface(color = PhotoPaper, shape = RoundedCornerShape(2.dp), shadowElevation = 3.dp,
                    modifier = Modifier.padding(top = 8.dp).rotate(-1.5f)) {
                    AddPhotoPlace(enabled, onAdd, Modifier.fillMaxWidth().height(200.dp).padding(8.dp))
                }
            }
        }
    }
}
