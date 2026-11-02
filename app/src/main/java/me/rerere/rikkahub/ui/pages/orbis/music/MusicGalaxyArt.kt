package me.rerere.rikkahub.ui.pages.orbis.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.orbis.music.MusicPlaylist
import me.rerere.rikkahub.data.orbis.music.MusicRuntime
import kotlin.math.cos
import kotlin.math.sin

/** Native, deterministic artwork: no network image or additional bitmap payload. */
@Composable internal fun MusicLibraryBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val light = LocalMusicLight.current
    val gold = MusicGold
    Box(modifier.background(if(light) Color(0xFFF0F5F9) else Color(0xFF07131F))) {
        Canvas(Modifier.matchParentSize()) {
            val mist = if(light) Color(0xFF9CB8D2) else Color(0xFF466C98)
            drawCircle(Brush.radialGradient(listOf(mist.copy(alpha = .24f), Color.Transparent),
                Offset(size.width * .95f, size.height * .24f), size.width * .85f), size.width * .85f,
                Offset(size.width * .95f, size.height * .24f))
            drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .08f), Color.Transparent),
                Offset(0f, size.height * .92f), size.width), size.width, Offset(0f, size.height * .92f))
            repeat(55) { n ->
                val x = ((n * 47 + 11) % 101) / 101f * size.width
                val y = ((n * 31 + 7) % 103) / 103f * size.height
                drawCircle(gold.copy(alpha = if(n % 6 == 0) .32f else .13f), if(n % 6 == 0) 1.dp.toPx() else .5.dp.toPx(), Offset(x,y))
            }
            constellation(gold.copy(alpha = .22f), Offset(size.width * .52f, size.height * .06f), size.width * .43f)
        }
        content()
    }
}

@Composable internal fun MusicPlaylistCover(playlist: MusicPlaylist?, runtime: MusicRuntime?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    val gold = MusicGold
    Box(modifier.clip(shape).border(1.dp, gold.copy(alpha = .3f), shape)) {
        if(playlist?.cover == true && runtime != null) {
            AsyncImage(runtime.store.playlistCoverFile(playlist.id), "歌单封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            // Stable by playlist identity, including after edits and restarts.
            val variant = Math.floorMod((playlist?.id ?: "new-playlist").hashCode(), 3)
            Canvas(Modifier.fillMaxSize()) { drawGalaxyCover(variant) }
        }
    }
}

private fun DrawScope.constellation(color: Color, origin: Offset, width: Float) {
    val points = listOf(0f to .12f, .22f to .02f, .43f to .18f, .58f to .4f, .95f to .34f, .87f to .69f, .6f to .65f)
        .map { origin + Offset(it.first * width, it.second * width) }
    (points.zipWithNext() + (points.last() to points[3])).forEach { (a,b) -> drawLine(color.copy(alpha = color.alpha * .5f), a,b,.7.dp.toPx()) }
    points.forEach { star(it, width * .018f, color) }
}

private fun DrawScope.star(at: Offset, radius: Float, color: Color) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = color.alpha * .45f), Color.Transparent), at, radius * 4), radius * 4, at)
    drawCircle(color, radius * .35f, at)
    val p = Path().apply {
        moveTo(at.x, at.y-radius); lineTo(at.x+radius*.22f, at.y-radius*.22f)
        lineTo(at.x+radius, at.y); lineTo(at.x+radius*.22f, at.y+radius*.22f)
        lineTo(at.x, at.y+radius); lineTo(at.x-radius*.22f, at.y+radius*.22f)
        lineTo(at.x-radius, at.y); lineTo(at.x-radius*.22f, at.y-radius*.22f); close()
    }
    drawPath(p,color)
}

private fun DrawScope.drawGalaxyCover(variant: Int) {
    val gold = Color(0xFFF0D4A0)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF091629), if(variant == 2) Color(0xFF304059) else Color(0xFF203A54), Color(0xFF091825))))
    val center = Offset(size.width * .53f, size.height * .48f)
    rotate(-28f,center) {
        repeat(5) { i ->
            val radius = size.width * (.42f - i*.055f)
            drawOval(Brush.radialGradient(listOf(Color(0xFF94A6C4).copy(alpha = .03f + i*.015f), Color.Transparent), center, radius),
                center - Offset(radius, radius*.4f), Size(radius*2,radius*.8f))
        }
        repeat(3) { i ->
            val radius = size.width * (.22f+i*.11f)
            drawOval(gold.copy(alpha = .22f-i*.04f), center - Offset(radius,radius*.43f), Size(radius*2,radius*.86f), style = Stroke(.65.dp.toPx()))
        }
    }
    repeat(150) { n ->
        val at = Offset(((n*47+variant*11+3)%157)/157f*size.width, ((n*79+13)%163)/163f*size.height)
        drawCircle(if(n%5==0) gold.copy(alpha=.65f) else Color(0xFFB7D2EC).copy(alpha=.38f), size.width*(if(n%11==0) .0045f else .0018f), at)
    }
    if(variant == 1) {
        repeat(110) { n ->
            val a=n*.31f; val r=size.width*(.02f+n*.0033f)
            val x=cos(a)*r; val y=sin(a)*r*.48f
            val at=center+Offset(x*.88f+y*.48f,-x*.48f+y*.88f)
            drawCircle(gold.copy(alpha=.25f+(n%3)*.12f),size.width*.003f,at)
        }
        star(center,size.width*.06f,gold)
    } else {
        val planet=Offset(size.width*(if(variant == 2) .3f else .72f),size.height*(if(variant == 2) .7f else .57f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF1B354D),Color(0xFF0B1829)),planet,size.width*.21f),size.width*.21f,planet)
        drawArc(gold.copy(alpha=.6f),-100f,165f,false,planet-Offset(size.width*.21f,size.width*.21f),Size(size.width*.42f,size.width*.42f),style=Stroke(.8.dp.toPx()))
        if(variant == 2) {
            star(Offset(size.width*.67f,size.height*.27f),size.width*.05f,gold)
            drawArc(gold.copy(alpha=.4f),-150f,210f,false,Offset(size.width*.08f,size.height*.09f),Size(size.width*.82f,size.height*.7f),style=Stroke(.65.dp.toPx()))
        } else constellation(gold.copy(alpha=.8f),Offset(size.width*.09f,size.height*.17f),size.width*.65f)
        val mountain=Path().apply {
            moveTo(0f,size.height*.87f)
            listOf(.12f to .77f,.25f to .88f,.4f to .8f,.52f to .93f,.74f to .78f,1f to .9f).forEach { lineTo(it.first*size.width,it.second*size.height) }
            lineTo(size.width,size.height); lineTo(0f,size.height); close()
        }
        drawPath(mountain,Color(0xFF07121F))
    }
}
