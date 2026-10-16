package me.rerere.rikkahub.ui.pages.orbis.character

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import me.rerere.rikkahub.data.orbis.character.RoomLight

/** Original vector room: no game artwork, downloaded assets, network, sensor or location access. */
@Composable
fun CharacterRoom(light: RoomLight, motion: Boolean, modifier: Modifier = Modifier) {
    val drift = if (motion) {
        val transition = rememberInfiniteTransition(label = "room-light")
        transition.animateFloat(.92f, 1f, infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "soft-light").value
    } else 1f
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val night = light == RoomLight.NIGHT
        val dusk = light == RoomLight.DUSK
        val wall = when (light) {
            RoomLight.MORNING -> Color(0xFFF4E9D8)
            RoomLight.DAY -> Color(0xFFE6EDE8)
            RoomLight.DUSK -> Color(0xFFE0BFA7)
            RoomLight.NIGHT -> Color(0xFF242D3B)
        }
        val wood = if (night) Color(0xFF4B3F40) else Color(0xFFC9AD90)
        drawRect(Brush.verticalGradient(listOf(wall, wall.copy(alpha = .94f))))
        drawRect(wood, Offset(0f, h * .77f), Size(w, h * .23f))
        for (i in 0..6) drawLine(wood.copy(red = (wood.red * .85f)), Offset(w * i / 6, h * .77f), Offset(w * (i - 1) / 5, h), 1f)
        // Large side window with a sill and a soft linen curtain.
        val sky = if (night) Color(0xFF172A43) else if (dusk) Color(0xFFECAF81) else Color(0xFFBBD7DE)
        drawRoundRect(if (night) Color(0xFF7B7572) else Color(0xFFF6F1E7), Offset(w * .06f, h * .12f), Size(w * .38f, h * .45f), CornerRadius(8f))
        drawRect(Brush.verticalGradient(listOf(sky, sky.copy(alpha = .65f))), Offset(w * .08f, h * .135f), Size(w * .34f, h * .41f))
        drawCircle(if (night) Color(0xFFF0EAD1) else Color(0xFFFFF2CF), w * .025f, Offset(w * .33f, h * .22f))
        val frame = if (night) Color(0xFF9B9890) else Color(0xFFFFFCF4)
        drawLine(frame, Offset(w * .25f, h * .135f), Offset(w * .25f, h * .55f), w * .012f)
        drawLine(frame, Offset(w * .08f, h * .35f), Offset(w * .42f, h * .35f), w * .012f)
        drawRect(frame, Offset(w * .045f, h * .555f), Size(w * .41f, h * .015f))
        drawRect(frame.copy(alpha = .35f), Offset(w * .07f, h * .13f), Size(w * .07f, h * .44f))
        if (!night) {
            val ray = Path().apply { moveTo(w * .08f, h * .56f); lineTo(w * .39f, h * .56f); lineTo(w * .81f, h); lineTo(w * .24f, h); close() }
            drawPath(ray, Color(0xFFFFEFBB).copy(alpha = .16f * drift))
        }
        // Sofa, cushion and rug stay low, leaving space for the portrait.
        drawOval(if (night) Color(0xFF49454D) else Color(0xFFD9D0BE), Offset(w * .09f, h * .86f), Size(w * .83f, h * .1f))
        drawRoundRect(if (night) Color(0xFF58636B) else Color(0xFFA5B7B2), Offset(w * .05f, h * .64f), Size(w * .74f, h * .19f), CornerRadius(w * .035f))
        drawRoundRect(if (night) Color(0xFF737776) else Color(0xFFDCDCCB), Offset(w * .09f, h * .68f), Size(w * .16f, h * .10f), CornerRadius(w * .025f))
        drawLine(wood, Offset(w * .10f, h * .81f), Offset(w * .10f, h * .86f), w * .02f)
        drawLine(wood, Offset(w * .73f, h * .81f), Offset(w * .73f, h * .86f), w * .02f)
        // Floor lamp; the night halo is deliberately faint, not a flashing animation.
        if (night || dusk) drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC26C).copy(alpha = .25f * drift), Color.Transparent), Offset(w * .86f, h * .46f), w * .34f), w * .34f, Offset(w * .86f, h * .46f))
        drawLine(wood, Offset(w * .86f, h * .43f), Offset(w * .86f, h * .81f), w * .008f)
        drawOval(wood, Offset(w * .79f, h * .81f), Size(w * .14f, h * .015f))
        val shade = Path().apply { moveTo(w * .80f, h * .37f); lineTo(w * .92f, h * .37f); lineTo(w * .96f, h * .47f); lineTo(w * .76f, h * .47f); close() }
        drawPath(shade, if (night || dusk) Color(0xFFEBC392) else Color(0xFFE2D8C6))
        // Potted plant, deliberately offset from the figure's face and hands.
        drawRoundRect(if (night) Color(0xFF6B5A4C) else Color(0xFFB79578), Offset(w * .83f, h * .76f), Size(w * .1f, h * .09f), CornerRadius(8f))
        drawLine(Color(0xFF69806B), Offset(w * .88f, h * .78f), Offset(w * .88f, h * .60f), 3f)
        for (i in 0..3) drawOval(if (night) Color(0xFF40574E) else Color(0xFF6B9477), Offset(w * (if (i % 2 == 0) .81f else .88f), h * (.61f + i * .033f)), Size(w * .09f, h * .04f))
    }
}
