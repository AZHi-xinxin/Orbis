package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import me.rerere.rikkahub.data.orbis.theme.ThemeBubbleTexture
import me.rerere.rikkahub.data.orbis.theme.themeArgb
import me.rerere.rikkahub.ui.pages.orbis.ChatTheme
import kotlin.math.*
import kotlin.random.Random

/** Cached vector crystals or a bounded, aspect-preserving image tile; always below the text. */
@Composable internal fun themeTextureModifier(texture: ThemeBubbleTexture?, theme: ChatTheme?, bg: String, opacity: Float, optical: Boolean = false): Modifier {
    if (texture == null || texture.opacity * opacity == 0f) return Modifier
    val context = LocalContext.current
    val asset = texture.asset?.let { theme?.assets?.get(it) }
    val image = if (texture.type == "image" && asset != null) {
        val request = remember(asset, context) { ImageRequest.Builder(context).data(asset).size(512).build() }
        val async = rememberAsyncImagePainter(request, onError = { theme?.onImageError?.invoke() })
        val state by async.state.collectAsState()
        (state as? AsyncImagePainter.State.Success)?.painter
    } else null
    return Modifier.drawWithCache {
        val alpha = texture.opacity * opacity
        val frost = Color(themeArgb(texture.color))
        val brush = Brush.horizontalGradient(listOf(frost.copy(alpha=alpha),
            frost.copy(alpha=alpha*(1-texture.edgeFade)), frost.copy(alpha=alpha)))
        val shadow = Brush.horizontalGradient(listOf(Color(0xFF4F7F9F).copy(alpha=alpha*.3f),
            Color(0xFF4F7F9F).copy(alpha=alpha*.3f*(1-texture.edgeFade)), Color(0xFF4F7F9F).copy(alpha=alpha*.3f)))
        val fine = Path(); val stems = Path()
        if (texture.type == "ice" && !(optical && supportsIceOptics(size))) {
            val random = Random(texture.seed)
            val unit = texture.scale * density
            val stride = (100f - 55f * texture.density) * unit
            // Two banks grow inwards. A global cap also bounds work for very long code/messages.
            val rows = ceil(size.height / stride).toInt().coerceIn(1, 28)
            for (side in 0..1) for (row in 0..rows) {
                val origin = Offset(if(side==0) -8f*unit else size.width+8f*unit,
                    row*maxOf(stride, size.height/rows) + random.nextFloat()*stride*.5f)
                val length = (72f+random.nextFloat()*65f)*unit
                val angle = if(side==0) (-.9f+random.nextFloat()*.55f) else (-2.25f-random.nextFloat()*.5f)
                val along = Offset(cos(angle),sin(angle)); val across=Offset(-along.y,along.x)
                val bend=(random.nextFloat()-.5f)*length*.45f
                fun point(t: Float, w: Float=0f) = origin + along*(t*length) + across*(sin(t*PI.toFloat())*bend+w)
                fun Path.move(p: Offset) = moveTo(p.x,p.y)
                fun Path.line(p: Offset) = lineTo(p.x,p.y)
                fun Path.curve(c: Offset,p: Offset) = quadraticTo(c.x,c.y,p.x,p.y)
                stems.move(point(0f)); for(i in 1..32) stems.line(point(i/32f))
                val width = length*(.14f+random.nextFloat()*.09f)
                for(i in 1..34) {
                    val t=i/36f
                    for(sign in listOf(-1f,1f)) {
                        val spread=sin(t*PI.toFloat()).pow(.7f)*width*sign
                        val start=point(t)
                        val tip=point((t+.20f).coerceAtMost(1.03f),spread)
                        fine.move(start);fine.curve(point(t+.07f,spread*.65f),tip)
                        // Fine parallel forks, like the barbs of a feather, with brighter crystal ribs.
                        for(j in 1..3) {
                            val f=j/4f
                            val root=start+(tip-start)*f
                            fine.move(root)
                            fine.curve(root+along*(length*.025f)+across*(spread*.12f),
                                root+along*(length*(.04f+.012f*j))+across*(spread*.22f))
                        }
                    }
                }
            }
        }
        val wash = Brush.horizontalGradient(listOf(Color.Transparent,
            Color(themeArgb(bg)).copy(alpha=alpha*texture.edgeFade*.65f), Color.Transparent))
        onDrawWithContent {
            if (texture.type == "ice" && !(optical && supportsIceOptics(size))) {
                translate(.65.dp.toPx(), .65.dp.toPx()) { drawPath(fine,shadow,style=Stroke(.8.dp.toPx())) }
                drawPath(fine,brush,style=Stroke(.48.dp.toPx()))
                drawPath(stems,brush,style=Stroke(1.dp.toPx()))
            } else if (image != null) {
                val intrinsic=image.intrinsicSize
                val ratio=if(intrinsic.width.isFinite() && intrinsic.height.isFinite() && intrinsic.width>0) intrinsic.height/intrinsic.width else 1f
                val width=(180.dp.toPx()*texture.scale).coerceAtLeast(1f)
                val height=(width*ratio).coerceAtLeast(1f)
                // Decoded at <=512px; repeat instead of stretching a photo along a long message.
                val columns=ceil(size.width/width).toInt().coerceIn(1,8)
                val rows=ceil(size.height/height).toInt().coerceIn(1,64)
                for(y in 0 until rows) for(x in 0 until columns) translate(x*width,y*height) {
                    with(image) { draw(Size(width,height),alpha=alpha) }
                }
                drawRect(wash)
            }
            drawContent()
        }
    }
}
