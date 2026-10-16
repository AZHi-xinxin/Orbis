package me.rerere.rikkahub.ui.widget

import android.graphics.Bitmap
import android.graphics.Color
import me.rerere.rikkahub.data.orbis.character.*
import kotlin.math.max
import kotlin.math.min

/** Display-only color customization for the opt-in bundled sprite. Source PNG/alpha are untouched. */
internal fun characterColoredSprite(source: Bitmap, asset: CharacterAsset, colors: CharacterColors, emotion: String = asset.emotion): Bitmap {
    if (asset.colorModel != BuiltinCharacter.COLOR_MODEL) return source
    val hair = characterPalettes.first { it.id == colors.hair }
    val eyes = characterEyePalette(colors, emotion)
    if (hair.id == "deepseek" && eyes.id == "deepseek") return source
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val hsv = FloatArray(3)
    val target = FloatArray(3)
    for (y in 0 until source.height) for (x in 0 until source.width) {
        val index = y * source.width + x
        val original = pixels[index]
        if (Color.alpha(original) == 0) continue
        val u = (x + .5f) / source.width
        val v = (y + .5f) / source.height
        val region = characterColorRegion(u, v)
        if (region == 0) continue
        Color.colorToHSV(original, hsv)
        val blue = hsv[0] in 198f..245f && hsv[1] >= .14f
        val red = (hsv[0] <= 25f || hsv[0] >= 330f) && hsv[1] >= .35f
        if (!blue && !(region == 2 && red)) continue
        val palette = if (region == 2) eyes else hair
        if (palette.id == "deepseek" && blue) continue
        val amount = if (palette.rgb == palette.endRgb) 0f else ((u - .2f) / .6f).coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val start = (palette.rgb shr shift) and 255
            return (start + (((palette.endRgb shr shift) and 255) - start) * amount).toInt()
        }
        Color.colorToHSV(Color.rgb(channel(16), channel(8), channel(0)), target)
        // Keep shadows and bright specular flecks, including eye highlights and metal-like hair sheen.
        val brightness = hsv[2]
        val saturation = hsv[1]
        target[1] = (target[1] * (saturation / .38f).coerceIn(.25f, 1.25f)).coerceIn(0f, 1f)
        target[2] = if (palette.id == "grok") (.07f + brightness * .25f)
            else (brightness * (.55f + target[2] * .65f)).coerceIn(0f, 1f)
        if (brightness > .87f && saturation < .3f) {
            target[2] = max(target[2], brightness * .94f)
            target[1] = min(target[1], saturation)
        }
        pixels[index] = Color.HSVToColor(Color.alpha(original), target)
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}
