package me.rerere.rikkahub.ui.pages.orbis.market

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.orbis.market.*
import kotlin.math.abs
import me.rerere.rikkahub.ui.theme.LocalDarkMode

internal val LetterInk: Color @Composable get() = if(LocalDarkMode.current) Color(0xFFE9E3EC) else Color(0xFF303C56)
internal val LetterPaper: Color @Composable get() = if(LocalDarkMode.current) Color(0xFF25242B) else Color(0xFFFAF6F0)
internal val LetterGold: Color @Composable get() = if(LocalDarkMode.current) Color(0xFFD4B480) else Color(0xFFB28B53)
private val LetterMuted: Color @Composable get() = if(LocalDarkMode.current) Color(0xFFC1B7C3) else Color(0xFF766E76)
private val LetterTints: List<Color> @Composable get() = if(LocalDarkMode.current) listOf(Color(0xFF393143),Color(0xFF423038),Color(0xFF3B352D),Color(0xFF303C32)) else listOf(Color(0xFFECE4F0), Color(0xFFF6E5E5), Color(0xFFF3EBDD), Color(0xFFE8EBDD))
internal val LetterFont = FontFamily(Font(R.font.market_serif))

/** A small deterministic deckle, not a bitmap containing text or controls. */
internal val LetterEdge = object : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val step = with(density) { 9.dp.toPx() }; val fringe = with(density) { 1.6.dp.toPx() }
        val path = Path().apply {
            moveTo(fringe, fringe)
            var x = step; var n = 0
            while(x < size.width - fringe) { lineTo(x, fringe * (1 + (n++ % 3) * .45f)); x += step }
            lineTo(size.width - fringe, fringe); var y = step
            while(y < size.height - fringe) { lineTo(size.width - fringe * (1 + (n++ % 3) * .35f), y); y += step }
            lineTo(size.width - fringe, size.height - fringe); x = size.width - step
            while(x > fringe) { lineTo(x, size.height - fringe * (1 + (n++ % 3) * .45f)); x -= step }
            lineTo(fringe, size.height - fringe); y = size.height - step
            while(y > fringe) { lineTo(fringe * (1 + (n++ % 3) * .35f), y); y -= step }
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable internal fun MarketLetterTheme(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val dark=LocalDarkMode.current
    val scheme=if(dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = scheme.copy(primary = LetterInk, onPrimary = if(dark) LetterPaper else Color.White, surface = LetterPaper,
            onSurface = LetterInk, onSurfaceVariant = LetterMuted, secondaryContainer = LetterTints[0],
            onSecondaryContainer = LetterInk, outline = LetterMuted, outlineVariant = LetterMuted.copy(alpha=.4f), background = LetterPaper, onBackground = LetterInk,
            surfaceContainerLowest=LetterPaper,surfaceContainerLow=LetterPaper,surfaceContainer=LetterPaper,surfaceContainerHigh=LetterTints[0],surfaceContainerHighest=LetterTints[0]),
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontFamily = LetterFont, fontSize = 16.sp, lineHeight = 26.sp),
            bodyMedium = base.bodyMedium.copy(fontFamily = LetterFont, fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = base.bodySmall.copy(fontFamily = LetterFont, fontSize = 12.sp, lineHeight = 18.sp),
            titleLarge = base.titleLarge.copy(fontFamily = LetterFont),
            titleMedium = base.titleMedium.copy(fontFamily = LetterFont),
            labelLarge = base.labelLarge.copy(fontFamily = LetterFont),
            labelMedium = base.labelMedium.copy(fontFamily = LetterFont),
            labelSmall = base.labelSmall.copy(fontFamily = LetterFont))) {
        CompositionLocalProvider(LocalContentColor provides LetterInk, content = content)
    }
}

internal fun Modifier.letterGrain(): Modifier = drawBehind {
    // Cheap fixed paper flecks; no random allocation or animation while scrolling.
    repeat(150) { i ->
        val x = ((i * 137L % 997).toFloat() / 997) * size.width
        val y = ((i * 229L % 991).toFloat() / 991) * size.height
        drawCircle(Color(0xFF9E856B).copy(alpha = .065f), .45.dp.toPx(), Offset(x, y))
    }
}

@Composable internal fun LetterSheet(modifier: Modifier = Modifier, tint: Color = LetterPaper,
    flowers: Boolean = false, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    // Keep the decorative path out of the controls' ancestor chain. Generic outline hit
    // testing can reject taps near the bottom of a tall, scrolled sheet (also on Android).
    // The padded content stays inside the paper visually without inheriting its clip.
    Box(modifier.fillMaxWidth()) {
        Surface(modifier = Modifier.matchParentSize(), color = tint, shape = LetterEdge,
            shadowElevation = 2.dp, border = BorderStroke(1.dp, LetterMuted.copy(alpha = .35f))) {
            Box(Modifier.fillMaxSize().letterGrain()) {
                Image(painterResource(R.drawable.market_paper_texture), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = if(LocalDarkMode.current) .035f else .26f)
                if(flowers) Image(painterResource(R.drawable.market_botanical_corner), null,
                    Modifier.align(Alignment.BottomEnd).size(110.dp), alpha = .65f)
            }
        }
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable internal fun LetterHeading(page: String, back: () -> Unit, inbox: () -> Unit) {
    val heading = when(page) {
        "wishes" -> "心愿墙"; "favorites" -> "我的收藏"; "mine" -> "我的创作"
        "detail" -> "创意详情"; "editor" -> "写个创意"; "wishEditor" -> "写个愿望"
        "send" -> "发送给 AI"; "messages" -> "消息"; "deliveries" -> "创作进度"
        "account" -> "我的信笺"; "rules" -> "发布约定"; else -> "番外与格子"
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(back, Modifier.widthIn(min = 48.dp).semantics { contentDescription = "返回" }) { Text("‹", fontSize = 30.sp) }
        Text(heading, Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(inbox, Modifier.widthIn(min = 48.dp)) { Text("信箱", fontSize = 12.sp) }
    }
}

@Composable internal fun LetterDivider(caption: String? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if(caption != null) Text(caption, color = LetterMuted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider(Modifier.width(75.dp), color = LetterGold.copy(alpha = .35f))
            Text("✦", color = LetterGold, fontSize = 16.sp)
            HorizontalDivider(Modifier.width(75.dp), color = LetterGold.copy(alpha = .35f))
        }
    }
}

@Composable internal fun LetterComposeActions(enabled: Boolean, wish: () -> Unit, create: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("✧  写个愿望", LetterTints[0], wish), Triple("✎  写个创意", LetterTints[3], create)).forEach { (label, tint, action) ->
            LetterSheet(Modifier.weight(1f).clickable(enabled = enabled, onClick = action), tint, padding = 14.dp) {
                Text(label, Modifier.align(Alignment.CenterHorizontally), fontSize = 17.sp)
            }
        }
    }
}

@Composable internal fun LetterFilters(category: String, change: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("" to "全部", "extra" to "番外", "grid" to "格子").forEach { (id, name) ->
            FilterChip(category == id, { change(id) }, label = { Text(name) }, shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = LetterInk, selectedLabelColor = LetterPaper))
        }
    }
}

internal fun letterCover(entry: JsonObject): Int {
    val title = entry.ms("title")
    // Built-in decorative stationery, never represented as an author's uploaded artwork.
    return when {
        title.contains("雨") -> R.drawable.market_cover_rain
        title.contains("信") -> R.drawable.market_cover_letter
        entry.ms("category") == "grid" -> R.drawable.market_cover_cafe
        else -> listOf(R.drawable.market_cover_letter, R.drawable.market_cover_rain, R.drawable.market_cover_cafe)[(entry.ms("id").hashCode() and Int.MAX_VALUE) % 3]
    }
}

@Composable internal fun LetterCover(entry: JsonObject, modifier: Modifier = Modifier) = Image(
    painterResource(letterCover(entry)), contentDescription = null, modifier = modifier.clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)

@Composable internal fun LetterFeedCard(entry: JsonObject, enabled: Boolean, open: () -> Unit, edit: (() -> Unit)? = null) {
    val tint = LetterTints[if(entry.ms("category") == "grid") 1 else (entry.ms("id").hashCode() and Int.MAX_VALUE) % 3]
    LetterSheet(Modifier.clickable(enabled = enabled, onClick = open), tint, padding = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LetterCover(entry, Modifier.width(94.dp).height(116.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if(entry.ms("category") == "grid") "格子" else "番外", Modifier.background(LetterPaper.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(horizontal = 9.dp, vertical = 1.dp), fontSize = 11.sp, lineHeight = 16.sp)
                Text(entry.ms("title"), fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("创作者 · ${entry.ms("author")}", fontSize = 11.sp, lineHeight = 16.sp, color = LetterMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(entry.ms("summary"), fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${if(entry.mb("favorite")) "♥" else "♡"} ${entry.mi("favorite_count")}", Modifier.align(Alignment.End), fontSize = 12.sp, lineHeight = 18.sp, color = if(entry.mb("favorite")) Color(0xFFAD6875) else LetterMuted)
            }
        }
        if(entry.mb("favorite") && entry.mi("favorite_version") < entry.mi("version")) Text("你收藏后，作者发布了新版本", fontSize = 12.sp)
        if(edit != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("第${entry.mi("version")}版 · ${when(entry.ms("state")) { "published" -> "已发布"; "draft" -> "草稿"; "withdrawn" -> "已撤回"; else -> "已下架" }}", fontSize = 11.sp, color = LetterMuted)
            TextButton(edit, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("编辑草稿", fontSize = 12.sp) }
        }
    }
}

@Composable internal fun LetterWishNote(entry: JsonObject, index: Int, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit) {
    Box(modifier.padding(top = 9.dp)) {
        LetterSheet(tint = LetterTints[index % LetterTints.size], flowers = true, padding = 14.dp) {
            Spacer(Modifier.height(8.dp))
            Text(entry.ms("title"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(entry.ms("description"), fontSize = 13.sp, lineHeight = 22.sp)
            Text("${if(entry.ms("category") == "grid") "格子" else "番外"} · 已领取 ${entry.mi("slots_used")} / 3", fontSize = 11.sp, color = LetterMuted)
            actions()
        }
        Text("✧", Modifier.align(Alignment.TopCenter).offset(y = (-10).dp), fontSize = 29.sp, color = LetterGold)
    }
}

@Composable internal fun LetterNavigation(page: String, enabled: Boolean, select: (String) -> Unit) {
    Surface(color = LetterPaper.copy(alpha = .98f), tonalElevation = 0.dp, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("discover", "✧", "发现"), Triple("wishes", "♧", "心愿"), Triple("favorites", "♡", "收藏"), Triple("mine", "✎", "创作"), Triple("account", "◌", "我的")).forEach { (id, icon, label) ->
                TextButton({ select(id) }, Modifier.weight(1f), enabled = enabled, contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(icon, fontSize = 20.sp, color = if(page == id) LetterGold else LetterMuted)
                        Text(label, fontSize = 11.sp, fontWeight = if(page == id) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
