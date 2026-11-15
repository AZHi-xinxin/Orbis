package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.data.orbis.market.MarketClient
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage

private val HallGold = Color(0xFFD8BA82)
private val HallInk = Color(0xFFF5E6C8)
private val HallMuted = Color(0xFFC1B9B5)

@Composable
internal fun OrbisBoardgamesPage(assistantId: String, conversation: String?, close: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val account = remember { MarketClient(context) }
    if (selected != null) {
        OrbisTabletopPage(assistantId, conversation, selected!!) { selected = null }
        return
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        TabletopEdgeToEdgeBars()
        OrbisInterfaceTheme {
            OrbisBoardgamesCatalog(onChoose = { selected = it }, onBack = close, scroll = scroll, showInvitationGuide = !account.hasSession())
        }
    }
}

/** This catalog never creates a room. Available cards retain the existing game routes. */
@Composable
internal fun OrbisBoardgamesCatalog(
    onChoose: (String) -> Unit,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    showInvitationGuide: Boolean = false,
) {
    Box(Modifier.fillMaxSize().background(Color(0xFF171824))) {
        HallStars(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).border(1.dp, HallGold.copy(alpha = .45f), CircleShape)
                        .clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = HallGold, fontSize = 32.sp) }
                Text("星夜桌游", color = HallInk, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("boardgames-scroll"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp)) {
                        Column(Modifier.padding(end = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("—  今夜 · 圆桌已备好", color = HallGold, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
                            Text("今晚，坐到一起。", color = HallInk, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif)
                            Text("和熟悉的助手，一起开启新的故事。", color = HallMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("✦", Modifier.align(Alignment.TopEnd), color = HallGold.copy(alpha = .65f), fontSize = 26.sp)
                    }
                    if (showInvitationGuide) Surface(color = OrbisTheme.colors.panel,
                        contentColor = OrbisTheme.colors.ink, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) { OrbisInvitationGuide() }
                    }
                    BoardgameRoomCard(
                        id = "werewolf", title = "狼人杀", hint = "推理 · 隐藏身份", seats = "10 席",
                        description = "灯火将熄，看看谁藏起了秘密。", modes = listOf("人机共玩", "纯 AI"),
                        action = "进入大厅", art = "green_cellar.png", tint = Color(0xFF1B302C),
                        onClick = { onChoose("werewolf") },
                    )
                    BoardgameRoomCard(
                        id = "drawguess", title = "你画我猜", hint = "传统接龙 · 快猜", seats = "10 席",
                        description = "一笔脑洞，一桌笑声。", modes = listOf("人机共玩", "纯 AI"),
                        action = "进入画室", art = "star_party.png", tint = Color(0xFF382A3D),
                        onClick = { onChoose("drawguess") },
                    )
                    BoardgameRoomCard(
                        id = "heartprison", title = "七席心牢", hint = "七席之间 · 人心如谜", seats = "7 席",
                        description = "七位原助手入席，人类全知观战。", modes = listOf("纯 AI"),
                        action = "进入心牢", art = "mist_manor.png", tint = Color(0xFF202D3D),
                        onClick = { onChoose("heartprison") },
                    )
                    Text("✦  熟悉的伙伴 · 新的故事  ✦", Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp, bottom = 28.dp),
                        color = HallMuted.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardgameRoomCard(
    id: String, title: String, hint: String, seats: String, description: String,
    modes: List<String>, action: String, art: String, tint: Color, onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(24.dp)
    val interaction = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.semantics(mergeDescendants = true) { disabled() }
    Box(Modifier.fillMaxWidth().clip(shape).background(tint)
        .border(BorderStroke(1.dp, HallGold.copy(alpha = .6f)), shape)
        .then(interaction).testTag("boardgame-$id")) {
        Column {
            Box(Modifier.fillMaxWidth().heightIn(min = 165.dp)) {
                AsyncImage(
                    model = "file:///android_asset/tabletop/assets/rooms/$art", contentDescription = null,
                    modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
                    alignment = BiasAlignment(0f, -.25f),
                )
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, tint.copy(alpha = .15f), tint))))
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(60.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(hint, Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = .9f)).padding(horizontal = 9.dp, vertical = 6.dp),
                            color = HallInk, style = MaterialTheme.typography.labelMedium)
                        Text(seats, Modifier.padding(start = 12.dp, top = 6.dp), color = HallInk, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(title, color = HallInk, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(description, color = HallMuted, style = MaterialTheme.typography.bodyMedium)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    modes.forEach { mode ->
                        Text(mode, Modifier.border(1.dp, HallGold.copy(alpha = .25f), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 7.dp),
                            color = HallInk.copy(alpha = .85f), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (onClick == null) "◌  $action" else "$action  ↗", Modifier.padding(vertical = 7.dp),
                        color = HallGold, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        Box(Modifier.matchParentSize().padding(5.dp).border(1.dp, HallGold.copy(alpha = .18f), RoundedCornerShape(19.dp)))
    }
}

@Composable
private fun HallStars(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF242031), Color(0xFF14232A), Color(0xFF211D2C))))
        repeat(38) { index ->
            val point = Offset(size.width * ((index * 37 + 11) % 101) / 101f, size.height * ((index * 59 + 7) % 103) / 103f)
            drawCircle(HallGold.copy(alpha = if (index % 3 == 0) .4f else .18f), (if (index % 3 == 0) 1.2f else .7f).dp.toPx(), point)
        }
        val center = Offset(size.width * .88f, size.height * .11f)
        drawCircle(HallGold.copy(alpha = .1f), 34.dp.toPx(), center, style = Stroke(1.dp.toPx()))
    }
}
