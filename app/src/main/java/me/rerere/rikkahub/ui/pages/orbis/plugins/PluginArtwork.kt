package me.rerere.rikkahub.ui.pages.orbis.plugins

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal fun pluginTint(name: String)=when(name) {
    "blue" -> Color(0xFFB6D6EF)
    "rose" -> Color(0xFFF0C8CE)
    "mint" -> Color(0xFFBCDCD0)
    "gold" -> Color(0xFFECD8AA)
    else -> Color(0xFFCEC0E8)
}

/** Native, scalable stationery: no remote assets, scripts or fixed-size screenshot backgrounds. */
@Composable internal fun PluginArtwork(icon: String, tint: Color, modifier: Modifier=Modifier) {
    Canvas(modifier.fillMaxWidth().height(90.dp)) {
        val w=size.width; val h=size.height; val c=Offset(w*.5f,h*.5f)
        val ink=Color(0xFF6A647D); val paper=Color(0xFFFFFAEE); val gold=Color(0xFFEBCB88)
        fun star(x: Float,y: Float,r: Float,color: Color=paper) {
            val p=Path().apply { moveTo(x,y-r); lineTo(x+r*.25f,y-r*.25f); lineTo(x+r,y); lineTo(x+r*.25f,y+r*.25f)
                lineTo(x,y+r); lineTo(x-r*.25f,y+r*.25f); lineTo(x-r,y); lineTo(x-r*.25f,y-r*.25f); close() }
            drawPath(p,color)
        }
        listOf(.13f to .24f,.84f to .2f,.22f to .69f,.78f to .7f,.37f to .12f).forEachIndexed { i,(x,y) ->
            star(w*x,h*y,if(i%2==0) 4.dp.toPx() else 2.5.dp.toPx(),paper.copy(alpha=.9f))
        }
        drawOval(tint.copy(alpha=.32f),Offset(w*.25f,h*.74f),Size(w*.52f,h*.12f))
        when(icon) {
            "book" -> {
                val left=Path().apply { moveTo(c.x,c.y-h*.2f); cubicTo(c.x-w*.1f,c.y-h*.34f,c.x-w*.23f,c.y-h*.28f,c.x-w*.28f,c.y-h*.22f)
                    lineTo(c.x-w*.28f,c.y+h*.27f); quadraticTo(c.x-w*.1f,c.y+h*.17f,c.x,c.y+h*.3f); close() }
                val right=Path().apply { moveTo(c.x,c.y-h*.2f); quadraticTo(c.x+w*.15f,c.y-h*.32f,c.x+w*.28f,c.y-h*.22f)
                    lineTo(c.x+w*.28f,c.y+h*.27f); quadraticTo(c.x+w*.12f,c.y+h*.17f,c.x,c.y+h*.3f); close() }
                drawPath(left,paper); drawPath(right,Color.White); drawPath(left,ink.copy(alpha=.35f),style=Stroke(1.dp.toPx())); drawPath(right,ink.copy(alpha=.35f),style=Stroke(1.dp.toPx()))
                repeat(3) { n -> drawLine(ink.copy(alpha=.2f),Offset(c.x-w*.23f,c.y+h*(-.08f+n*.1f)),Offset(c.x-w*.06f,c.y+h*(-.04f+n*.1f)),1.dp.toPx()) }
                drawLine(gold,Offset(c.x+w*.09f,c.y-h*.18f),Offset(c.x+w*.09f,c.y+h*.31f),4.dp.toPx())
            }
            "letter" -> {
                val top=Offset(w*.23f,h*.27f); val s=Size(w*.54f,h*.46f)
                drawRoundRect(paper,top,s,CornerRadius(6.dp.toPx()))
                drawRoundRect(ink.copy(alpha=.3f),top,s,CornerRadius(6.dp.toPx()),style=Stroke(1.dp.toPx()))
                val fold=Path().apply { moveTo(top.x,top.y); lineTo(c.x,c.y+h*.08f); lineTo(top.x+s.width,top.y) }
                drawPath(fold,ink.copy(alpha=.25f),style=Stroke(1.dp.toPx()))
                drawCircle(Color(0xFFE3A7B6),7.dp.toPx(),Offset(c.x,c.y+h*.08f)); star(c.x,c.y+h*.08f,3.dp.toPx())
            }
            "leaf" -> {
                drawLine(ink.copy(alpha=.5f),Offset(c.x-w*.09f,h*.8f),Offset(c.x+w*.08f,h*.2f),2.dp.toPx())
                repeat(5) { n ->
                    val x=c.x+w*(-.07f+n*.035f); val y=h*(.72f-n*.11f); val sign=if(n%2==0) 1 else -1
                    val leaf=Path().apply { moveTo(x,y); quadraticTo(x+sign*w*.23f,y+h*.04f,x+sign*w*.18f,y-h*.19f); quadraticTo(x+sign*w*.05f,y-h*.18f,x,y); close() }
                    drawPath(leaf,Color(0xFF7CA995).copy(alpha=.65f))
                }
            }
            "music" -> {
                drawLine(ink,Offset(c.x+w*.06f,h*.23f),Offset(c.x+w*.06f,h*.67f),3.dp.toPx())
                drawOval(ink,Offset(c.x-w*.1f,h*.6f),Size(w*.17f,h*.16f))
                drawLine(ink,Offset(c.x+w*.06f,h*.23f),Offset(c.x+w*.22f,h*.32f),4.dp.toPx())
            }
            "star", "sparkles" -> { star(c.x,c.y,26.dp.toPx(),gold); star(c.x+w*.2f,c.y-h*.14f,9.dp.toPx()) }
            else -> {
                val moon=Path().apply { moveTo(c.x+14.dp.toPx(),c.y-29.dp.toPx())
                    cubicTo(c.x-28.dp.toPx(),c.y-25.dp.toPx(),c.x-32.dp.toPx(),c.y+25.dp.toPx(),c.x+7.dp.toPx(),c.y+29.dp.toPx())
                    quadraticTo(c.x+27.dp.toPx(),c.y+28.dp.toPx(),c.x+32.dp.toPx(),c.y+10.dp.toPx())
                    cubicTo(c.x+2.dp.toPx(),c.y+21.dp.toPx(),c.x-7.dp.toPx(),c.y-5.dp.toPx(),c.x+14.dp.toPx(),c.y-29.dp.toPx()); close() }
                drawPath(moon,gold); drawPath(moon,paper.copy(alpha=.6f),style=Stroke(1.dp.toPx()))
                drawOval(paper.copy(alpha=.85f),Offset(c.x-w*.29f,h*.7f),Size(w*.39f,h*.12f))
                drawCircle(paper.copy(alpha=.9f),9.dp.toPx(),Offset(c.x-w*.18f,h*.68f))
            }
        }
    }
}
