@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package me.rerere.rikkahub.ui.components.message

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import me.rerere.rikkahub.data.orbis.theme.ThemeBubble
import me.rerere.rikkahub.data.orbis.theme.ThemeBubbleTexture
import me.rerere.rikkahub.data.orbis.theme.themeArgb
import kotlin.math.*
import kotlin.random.Random

internal fun supportsIceOptics(size: Size) = size.width > 0 && size.height > 0 &&
    size.width <= 4096 && size.height <= 4096 && size.width * size.height <= 4_000_000

/** A second, sharp wallpaper capture over the frosted base. Crystal normals refract it locally.
 * This layer never contains text, buttons, private message images, or the chat list. */
@RequiresApi(33)
@Composable internal fun BoxScope.ThemeIceCrystalLayer(bubble: ThemeBubble, shape: Shape, input: HazeInput) {
    val texture = bubble.texture ?: return
    val material = bubble.material ?: return
    var extent by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val valid = supportsIceOptics(Size(extent.width.toFloat(), extent.height.toFloat()))
    // The bounded map is generated only for a new size/seed/geometry, never on animation frames.
    val map = remember(extent, density, texture.scale, texture.density, texture.seed) {
        if (valid) iceHeightMap(extent, density, texture) else null
    }
    val shader = remember { RuntimeShader(ICE_SHADER) }
    val clear = remember { HazeBlurStyle { blurRadius(0.dp); noiseFactor(0f) } }
    Box(Modifier.matchParentSize().onSizeChanged { extent = it }.clip(shape).graphicsLayer {
        val heightMap = map
        if (heightMap == null) { alpha = 0f; renderEffect = null; return@graphicsLayer }
        alpha = 1f
        shader.setInputShader("iceMap", BitmapShader(heightMap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        })
        shader.setFloatUniform("mapSize", heightMap.width.toFloat(), heightMap.height.toFloat())
        shader.setFloatUniform("resolution", size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f))
        shader.setFloatUniform("pixelDensity", density)
        shader.setFloatUniform("displacement", material.displacement * material.refraction)
        shader.setFloatUniform("strength", texture.opacity * bubble.opacity)
        shader.setFloatUniform("edgeFade", texture.edgeFade)
        val color = themeArgb(texture.color)
        shader.setFloatUniform("iceColor", ((color shr 16) and 255)/255f, ((color shr 8) and 255)/255f, (color and 255)/255f)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }.hazeBlur(input, clear))
}

/** Soft, filled leaf facets produce thickness, with fine ribs embedded in the height field. */
private fun iceHeightMap(size: IntSize, density: Float, texture: ThemeBubbleTexture): Bitmap {
    val ratio = min(1f, 768f / max(size.width, size.height))
    val bitmap = Bitmap.createBitmap(max(1,(size.width*ratio).roundToInt()), max(1,(size.height*ratio).roundToInt()), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.scale(ratio, ratio)
    val random = Random(texture.seed)
    val unit = texture.scale * density
    val stride = (116f - 48f * texture.density) * unit
    val rows = ceil(size.height / stride).toInt().coerceIn(1, 24)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun gray(v: Int) = android.graphics.Color.rgb(v,v,v)
    for (side in 0..1) for (row in 0..rows) {
        val origin = Offset(if (side == 0) -12f*unit else size.width+12f*unit,
            row * max(stride, size.height.toFloat()/rows) + random.nextFloat()*stride*.4f)
        val length = (90f + random.nextFloat()*70f)*unit
        val angle = if (side == 0) -.85f + random.nextFloat()*.45f else -2.3f - random.nextFloat()*.45f
        val along = Offset(cos(angle),sin(angle)); val across = Offset(-along.y,along.x)
        val bend = (random.nextFloat()-.5f)*length*.45f
        fun point(t: Float, w: Float = 0f) = origin + along*(t*length) + across*(sin(t*PI.toFloat())*bend+w)
        val width = length*(.16f+random.nextFloat()*.08f)
        for (i in 1..23) for (sign in listOf(-1f,1f)) {
            val t = i/25f
            val spread = sin(t*PI.toFloat()).pow(.7f)*width*sign
            val root = point(t)
            val tip = point((t+.20f).coerceAtMost(1.04f),spread)
            val end = point((t+.055f).coerceAtMost(1f))
            val mid = root+(tip-root)*.5f
            val path = android.graphics.Path().apply {
                moveTo(root.x,root.y)
                val c1 = point(t+.035f,spread*.65f)
                quadTo(c1.x,c1.y,tip.x,tip.y)
                val c2 = point(t+.10f,spread*.40f)
                quadTo(c2.x,c2.y,end.x,end.y)
                close()
            }
            // Each thin facet rises from a soft edge into a clear icy ridge.
            val normal = Offset(-(tip-root).y,(tip-root).x).let { it / it.getDistance().coerceAtLeast(1f) }
            val half = length*.022f
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(mid.x-normal.x*half,mid.y-normal.y*half,mid.x+normal.x*half,mid.y+normal.y*half,
                intArrayOf(gray(18),gray(126+random.nextInt(44)),gray(44)),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            canvas.drawPath(path,paint)
            paint.shader = null
            // Fine striations sit inside the ice, not as opaque white wires above the text.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .4f*unit
            paint.color = gray(165)
            val rib = android.graphics.Path().apply { moveTo(root.x,root.y); quadTo(mid.x,mid.y,tip.x,tip.y) }
            canvas.drawPath(rib,paint)
        }
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.25f*unit
        paint.color = gray(155); paint.strokeCap = Paint.Cap.ROUND
        val stem = android.graphics.Path().apply { val p=point(0f); moveTo(p.x,p.y)
            for (i in 1..32) { val q=point(i/32f); lineTo(q.x,q.y) } }
        canvas.drawPath(stem,paint)
    }
    return bitmap
}

private const val ICE_SHADER = """
uniform shader content;
uniform shader iceMap;
uniform float2 resolution;
uniform float2 mapSize;
uniform float pixelDensity;
uniform float displacement;
uniform float strength;
uniform float edgeFade;
uniform float3 iceColor;
float heightAt(float2 p) { return float(iceMap.eval(p / resolution * mapSize).r); }
half4 main(float2 p) {
    float h = heightAt(p);
    float stepSize = max(0.7 * pixelDensity, max(resolution.x / mapSize.x, resolution.y / mapSize.y));
    float2 gradient = float2(heightAt(p + float2(stepSize,0.0)) - heightAt(p - float2(stepSize,0.0)),
                             heightAt(p + float2(0.0,stepSize)) - heightAt(p - float2(0.0,stepSize)));
    float3 normal = normalize(float3(-gradient * 3.5, 1.0));
    float2 uv = clamp(p + normal.xy * displacement * pixelDensity, float2(0.5), resolution - 0.5);
    half4 sample = content.eval(uv);
    float3 rgb = float3(sample.rgb) / max(float(sample.a), 0.01);
    float light = dot(normal.xy, float2(-0.65,-0.76));
    rgb = mix(rgb, iceColor, 0.06 + 0.12 * h);
    rgb = mix(rgb, float3(0.93,0.98,1.0), max(light,0.0) * 0.65);
    rgb = mix(rgb, float3(0.72,0.82,0.90), max(-light,0.0) * 0.16);
    float edge = abs(p.x / resolution.x * 2.0 - 1.0);
    float fade = mix(1.0, 0.28 + 0.72 * smoothstep(0.05,0.85,edge), edgeFade);
    float mask = smoothstep(0.025,0.30,h) * strength * fade * 0.93;
    mask *= float(sample.a);
    return half4(clamp(rgb,0.0,1.0) * mask, mask);
}
"""
