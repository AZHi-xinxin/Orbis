package me.rerere.rikkahub.ui.components.message

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.platform.LocalDensity
import me.rerere.rikkahub.data.orbis.theme.ThemeBubbleMaterial
import me.rerere.rikkahub.data.orbis.theme.themeArgb

/** Distorts the captured wallpaper material only. Never apply this modifier to message content. */
@Composable internal fun liquidFlowModifier(material: ThemeBubbleMaterial, radius: Int): Modifier {
    if (Build.VERSION.SDK_INT < 33 || material.type != "liquid" || (material.flow == 0f && material.metallic == 0f && material.dispersion == 0f)) return Modifier
    return liquidFlow33(material, radius)
}

@RequiresApi(33)
@Composable private fun liquidFlow33(material: ThemeBubbleMaterial, radius: Int): Modifier {
    val shader = remember { RuntimeShader(LIQUID_FLOW_SHADER) }
    val clock = LocalThemeMotionClock.current
    val density = LocalDensity.current.density
    val layer = rememberGraphicsLayer()
    var visible by remember { mutableStateOf<Rect?>(null) }
    // The message may span many screens. Keep one bounded layer for the visible slice,
    // rather than disabling its optical edge as expansion crosses a whole-message size cap.
    // Position/clock reads invalidate drawing only; text and layout are not animated here.
    return Modifier.onGloballyPositioned { coordinates ->
        val position = coordinates.positionInWindow()
        visible = coordinates.boundsInWindow().translate(-position)
    }.drawWithContent {
        val full = Rect(Offset.Zero, size)
        val shown = (visible ?: full).intersect(full)
        if (shown.isEmpty) return@drawWithContent
        val left = floor(shown.left); val top = floor(shown.top)
        val extent = IntSize(ceil(shown.right - left).toInt().coerceAtLeast(1),
            ceil(shown.bottom - top).toInt().coerceAtLeast(1))
        // This is the viewport, not the complete message. Avoid unsafe allocations on
        // exceptional external displays while preserving the underlying glass.
        if (extent.width > 4096 || extent.height > 4096 || extent.width.toLong()*extent.height > 16_777_216L) {
            drawContent()
            return@drawWithContent
        }
        shader.setFloatUniform("viewportOrigin",left,top)
        shader.setFloatUniform("viewportSize",extent.width.toFloat(),extent.height.toFloat())
        shader.setFloatUniform("resolution",size.width.coerceAtLeast(1f),size.height.coerceAtLeast(1f))
        shader.setFloatUniform("pixelDensity",density)
        shader.setFloatUniform("corner",radius*density)
        shader.setFloatUniform("time",if (material.flow > 0f || material.shimmer) (((clock?.value ?: 0L)/1000.0*material.flowSpeed)%(40*kotlin.math.PI)).toFloat() else 0f)
        shader.setFloatUniform("flow",material.flow)
        shader.setFloatUniform("metallic",material.metallic)
        shader.setFloatUniform("dispersion",material.dispersion)
        fun palette(name: String, hex: String) {
            val color=themeArgb(hex)
            shader.setFloatUniform(name,((color shr 16) and 255)/255f,((color shr 8) and 255)/255f,(color and 255)/255f)
        }
        repeat(4) { palette("edge${it}", material.edgeColors[it % material.edgeColors.size]) }
        palette("edgeShadow",material.edgeShadow)
        // Keep the same RenderNode throughout resize. Only its recording and shader uniforms
        // change; releasing a node that another optical pass still references can drop frames.
        layer.record(size = extent) {
            translate(-left, -top) { this@drawWithContent.drawContent() }
        }
        layer.renderEffect=RenderEffect.createRuntimeShaderEffect(shader,"content").asComposeRenderEffect()
        translate(left, top) { drawLayer(layer) }
    }
}

private const val LIQUID_FLOW_SHADER = """
uniform shader content;
uniform float2 resolution;
uniform float2 viewportOrigin;
uniform float2 viewportSize;
uniform float pixelDensity;
uniform float corner;
uniform float time;
uniform float flow;
uniform float metallic;
uniform float dispersion;
uniform float3 edge0;
uniform float3 edge1;
uniform float3 edge2;
uniform float3 edge3;
uniform float3 edgeShadow;
half4 main(float2 local) {
    float2 p = local + viewportOrigin;
    float2 halfSize = resolution * 0.5;
    float r = min(corner, min(halfSize.x, halfSize.y));
    float2 q = abs(p - halfSize) - halfSize + r;
    float depth = -(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r);
    float rim = 1.0 - smoothstep(0.0, max(12.0 * pixelDensity, min(halfSize.x, halfSize.y) * 0.38), depth);
    // Let curved ends carry most of the optical accent. Straight edges stay thin and clear.
    float curvedEnd = smoothstep(max(0.0, halfSize.x - r - 8.0 * pixelDensity), halfSize.x, abs(p.x - halfSize.x));
    float accent = rim * (0.22 + 0.78 * curvedEnd);
    float2 v = p / pixelDensity;
    float t = time;
    float2 wave = float2(sin(v.y * 0.055 + t * 1.4) + 0.55 * cos(v.x * 0.035 - t * 0.8),
                         cos(v.x * 0.045 + t * 1.15) + 0.5 * sin(v.y * 0.027 - t));
    float amplitude = flow * pixelDensity * (1.0 + 2.0 * rim + 6.0 * rim * curvedEnd) * smoothstep(0.0, 2.0 * pixelDensity, depth);
    float2 uv = clamp(p + wave * amplitude - viewportOrigin, float2(0.5), viewportSize - 0.5);
    half4 original = content.eval(local);
    half4 sample = content.eval(uv);
    if (sample.a < 0.01) sample = original;
    float3 rgb = float3(sample.rgb) / max(sample.a, 0.01);
    float angle = atan(p.y - halfSize.y, p.x - halfSize.x);
    float ribbon = 0.5 + 0.5 * sin(angle * 4.0 + v.y * 0.025 + sin(v.x * 0.02 + t) + t * 0.8);
    float chrome = smoothstep(0.25, 0.8, ribbon);
    float hue = fract(ribbon * 0.8 + t * 0.018) * 4.0;
    float3 pearl = hue < 1.0 ? mix(edge0, edge1, hue) :
                   hue < 2.0 ? mix(edge1, edge2, hue - 1.0) :
                   hue < 3.0 ? mix(edge2, edge3, hue - 2.0) : mix(edge3, edge0, hue - 3.0);
    float3 reflection = mix(edgeShadow, mix(pearl, float3(1.0), chrome * 0.55), chrome);
    rgb = mix(rgb, reflection, metallic * accent * 0.55);
    rgb = mix(rgb, pearl, dispersion * accent * 0.3);
    float edgeBlend = smoothstep(0.0, 3.0 * pixelDensity, depth);
    rgb = mix(float3(original.rgb) / max(original.a, 0.01), rgb, edgeBlend);
    return half4(clamp(rgb, 0.0, 1.0) * original.a, original.a);
}
"""
