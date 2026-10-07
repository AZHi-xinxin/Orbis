package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.presets.deepSeekColorScheme
import kotlin.math.cos
import kotlin.math.sin

/** Visual tokens only: these components neither fetch nor invent application state. */
@Immutable
data class OrbisColors(
    val page: Color,
    val pageTop: Color,
    val panel: Color,
    val raisedPanel: Color,
    val tintedPanel: Color,
    val ink: Color,
    val mutedInk: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
    val sand: Color,
    val onSand: Color,
    val indigo: Color,
    val onIndigo: Color,
    val dock: Color,
    val onDock: Color,
    val homeButton: Color,
    val star: Color,
)

object OrbisPalette {
    val Light = OrbisColors(
        page = Color(0xFFFBF6ED),
        pageTop = Color(0xFFF5E8D3),
        panel = Color(0xFFFFFBF4),
        raisedPanel = Color(0xFFFFFFFF),
        tintedPanel = Color(0xFFF0EFF5),
        ink = Color(0xFF24283B),
        mutedInk = Color(0xFF686276),
        border = Color(0xFFDDD6CD),
        accent = Color(0xFF65506D),
        onAccent = Color(0xFFFFFFFF),
        sand = Color(0xFFF4E2C2),
        onSand = Color(0xFF805438),
        indigo = Color(0xFF485D91),
        onIndigo = Color(0xFFFFFFFF),
        dock = Color(0xFF303448),
        onDock = Color(0xFFF4EBD8),
        homeButton = Color(0xFF485D91),
        star = Color(0xFFF9DFAA),
    )

    val Dark = OrbisColors(
        page = Color(0xFF171827),
        pageTop = Color(0xFF252337),
        panel = Color(0xFF242638),
        raisedPanel = Color(0xFF303248),
        tintedPanel = Color(0xFF292D40),
        ink = Color(0xFFF4EBDD),
        mutedInk = Color(0xFFC1BACD),
        border = Color(0xFF545165),
        accent = Color(0xFFD4BCD9),
        onAccent = Color(0xFF302137),
        sand = Color(0xFF4A3B2B),
        onSand = Color(0xFFEDCE9A),
        indigo = Color(0xFFBCCAFF),
        onIndigo = Color(0xFF202D51),
        dock = Color(0xFF25283D),
        onDock = Color(0xFFF2E7D0),
        homeButton = Color(0xFF3C4B73),
        star = Color(0xFFFFE5AD),
    )

    val DeepSeekLight = OrbisColors(
        page = Color.White,
        pageTop = Color.White,
        panel = Color.White,
        raisedPanel = Color(0xFFF7F7F8),
        tintedPanel = Color(0xFFEDF2FF),
        ink = Color(0xFF171717),
        mutedInk = Color(0xFF686B73),
        border = Color(0xFFE1E3E8),
        accent = Color(0xFF3B63D9),
        onAccent = Color.White,
        sand = Color(0xFFF0F1F3),
        onSand = Color(0xFF565B64),
        indigo = Color(0xFF3B63D9),
        onIndigo = Color.White,
        dock = Color(0xFFF5F6F8),
        onDock = Color(0xFF565B64),
        homeButton = Color(0xFFEDF2FF),
        star = Color(0xFF3B63D9),
    )

    val DeepSeekDark = OrbisColors(
        page = Color(0xFF1A1A1A),
        pageTop = Color(0xFF1A1A1A),
        panel = Color(0xFF232323),
        raisedPanel = Color(0xFF2B2B2B),
        tintedPanel = Color(0xFF2B344B),
        ink = Color(0xFFF0F0F0),
        mutedInk = Color(0xFFB0B0B0),
        border = Color(0xFF414141),
        accent = Color(0xFF89A7FF),
        onAccent = Color(0xFF102552),
        sand = Color(0xFF303030),
        onSand = Color(0xFFD0D0D0),
        indigo = Color(0xFF89A7FF),
        onIndigo = Color(0xFF102552),
        dock = Color(0xFF262626),
        onDock = Color(0xFFD2D2D2),
        homeButton = Color(0xFF2B344B),
        star = Color(0xFF89A7FF),
    )
}

private val LocalOrbisColors = staticCompositionLocalOf<OrbisColors?> { null }
/** Set once by the actual selected preset; nested native pages inherit it unchanged. */
val LocalOrbisDeepSeekStyle = staticCompositionLocalOf { false }

internal fun resolveOrbisVisualColors(
    darkTheme: Boolean,
    deepSeekStyle: Boolean,
    inherited: OrbisColors? = null,
    inheritedDarkTheme: Boolean = darkTheme,
    season: OrbisSeason = OrbisSeason.NONE,
): OrbisColors = inherited?.takeIf { inheritedDarkTheme == darkTheme } ?: when {
    deepSeekStyle && darkTheme -> OrbisPalette.DeepSeekDark
    deepSeekStyle -> OrbisPalette.DeepSeekLight
    season != OrbisSeason.NONE -> orbisSeasonColors(season, darkTheme)
    darkTheme -> OrbisPalette.Dark
    else -> OrbisPalette.Light
}

object OrbisTheme {
    val colors: OrbisColors
        @Composable
        @ReadOnlyComposable
        get() = resolveOrbisVisualColors(LocalDarkMode.current, LocalOrbisDeepSeekStyle.current,
            inherited = LocalOrbisColors.current, season = LocalOrbisSeason.current)
}

/**
 * Scoped native theme, not a preference write or a replacement for the original Orbis HTML.
 * The host decides when this preset applies; set [useOrbisMaterialColors] to false to retain
 * its current Material color scheme. Existing typography, font scaling and shapes survive.
 */
@Composable
fun OrbisVisualTheme(
    darkTheme: Boolean = LocalDarkMode.current,
    colors: OrbisColors = resolveOrbisVisualColors(darkTheme, LocalOrbisDeepSeekStyle.current,
        inherited = LocalOrbisColors.current, inheritedDarkTheme = LocalDarkMode.current,
        season = LocalOrbisSeason.current),
    useOrbisMaterialColors: Boolean = true,
    content: @Composable () -> Unit,
) {
    val inheritedScheme = MaterialTheme.colorScheme
    val scheme = if (!useOrbisMaterialColors) inheritedScheme
    else if (LocalOrbisDeepSeekStyle.current) deepSeekColorScheme(inheritedScheme, colors, darkTheme)
    else inheritedScheme.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.tintedPanel,
        onPrimaryContainer = colors.ink,
        secondary = colors.onSand,
        onSecondary = colors.sand,
        secondaryContainer = colors.sand,
        onSecondaryContainer = colors.onSand,
        tertiary = colors.indigo,
        onTertiary = colors.onIndigo,
        tertiaryContainer = colors.tintedPanel,
        onTertiaryContainer = colors.ink,
        background = colors.page,
        onBackground = colors.ink,
        surface = colors.panel,
        onSurface = colors.ink,
        surfaceVariant = colors.tintedPanel,
        onSurfaceVariant = colors.mutedInk,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = colors.page,
        surfaceContainerLow = colors.panel,
        surfaceContainer = colors.panel,
        surfaceContainerHigh = colors.raisedPanel,
        surfaceContainerHighest = colors.tintedPanel,
        outline = colors.mutedInk,
        outlineVariant = colors.border,
    )
    CompositionLocalProvider(LocalOrbisColors provides colors, LocalDarkMode provides darkTheme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}

/** In a Scaffold, pass its padding separately and leave this inset argument at zero. */
@Composable
fun OrbisPageSurface(
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = OrbisTheme.colors
    Box(
        modifier = modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.pageTop, colors.page, colors.page)))
            .windowInsetsPadding(windowInsets),
        content = content,
    )
}

/**
 * A bounded page/sheet viewport. Do not nest it inside another vertical scroll container.
 * A ModalBottomSheet/Scaffold already handling insets should pass WindowInsets(0, 0, 0, 0).
 * No fixed content height or hidden overflow: large text can reach every action by scrolling.
 */
@Composable
fun OrbisScrollablePanel(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().windowInsetsPadding(windowInsets)
            .verticalScroll(scrollState).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

/** Compact real title/avatar slots; no demonstration user, provider or status is supplied. */
@Composable
fun OrbisPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = OrbisTheme.colors
    val deepSeekStyle = LocalOrbisDeepSeekStyle.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Match the prototype's mobile media rule without shrinking touch targets.
        val narrow = maxWidth <= 540.dp
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .background(colors.page.copy(alpha = if (deepSeekStyle) 1f else .86f))
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(colors.border.copy(alpha = .65f),
                        Offset(0f, size.height - stroke / 2),
                        Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
                }
                .padding(start = if (narrow) 12.dp else 16.dp,
                    end = if (narrow) 10.dp else 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 7.dp else 11.dp),
        ) {
            navigationIcon?.invoke()
            if (avatar != null) {
                Surface(shape = RoundedCornerShape(15.dp), color = Color.Transparent,
                    contentColor = if (deepSeekStyle) colors.accent else Color.White,
                    shadowElevation = if (deepSeekStyle) 0.dp else 7.dp,
                    modifier = Modifier.size(42.dp)) {
                    Box(Modifier.background(Brush.linearGradient(
                        if (deepSeekStyle) listOf(colors.tintedPanel, colors.tintedPanel)
                        else listOf(Color(0xFF23335B), Color(0xFF5B78B6)))),
                        contentAlignment = Alignment.Center) { avatar() }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = colors.ink, fontWeight = FontWeight(790),
                    maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = .17.sp),
                    modifier = Modifier.semantics { heading() })
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, color = colors.mutedInk,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            actions()
        }
    }
}

/** A 39 dp visual control inside an explicit 48 dp accessible hit area. */
@Composable
fun OrbisHeaderIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundOpacity: Float = .75f,
    content: @Composable () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        OrbisHeaderControlSurface(backgroundOpacity, modifier = Modifier.size(39.dp),
            shape = RoundedCornerShape(13.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { content() }
            }
        }
    }
}

/** Fade only the backdrop; preserve the selected theme, outline and content contrast. */
@Composable
internal fun OrbisHeaderControlSurface(
    backgroundOpacity: Float,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable () -> Unit,
) {
    val colors = OrbisTheme.colors
    val opacity = backgroundOpacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .75f
    Surface(modifier = modifier, shape = shape,
        color = colors.raisedPanel.copy(alpha = opacity), contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .65f))) {
        content()
    }
}

/** Reusable cream/night panel for real controls, not a clickable imitation of a setting. */
@Composable
fun OrbisPanelCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    supportingText: String? = null,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = OrbisTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.panel,
        contentColor = colors.ink,
        border = BorderStroke(1.dp, colors.border.copy(alpha = .7f)),
        shadowElevation = if (LocalOrbisDeepSeekStyle.current) 0.dp else 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null || supportingText != null || headerAction != null) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        if (supportingText != null) Text(supportingText,
                            style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                    }
                    headerAction?.invoke()
                }
            }
            content()
        }
    }
}

/** The host supplies genuine routing/availability; there is deliberately no default list. */
@Immutable
data class OrbisNavigationItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val unavailableReason: String? = null,
)

/**
 * Approved layout: first (chat) entry spans the row, followed by two columns.
 * Falls back to one column on narrow screens/large font scales; parent owns scrolling.
 */
@Composable
fun OrbisNavigationGrid(
    items: List<OrbisNavigationItem>,
    modifier: Modifier = Modifier,
    prominentFirst: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 320.dp && fontScale <= 1.3f) 2 else 1
        val first = if (prominentFirst) items.firstOrNull() else null
        val remaining = if (first != null) items.drop(1) else items
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (first != null) OrbisNavigationCard(first, Modifier.fillMaxWidth())
            remaining.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item ->
                        OrbisNavigationCard(item, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun OrbisNavigationCard(item: OrbisNavigationItem, modifier: Modifier = Modifier) {
    val colors = OrbisTheme.colors
    Card(
        onClick = item.onClick,
        enabled = item.enabled,
        modifier = modifier.heightIn(min = 92.dp).semantics(mergeDescendants = true) {
            if (!item.enabled) stateDescription = item.unavailableReason ?: "暂不可用"
        },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, colors.border.copy(alpha = .65f)),
        colors = CardDefaults.cardColors(
            containerColor = colors.raisedPanel,
            contentColor = colors.ink,
            disabledContainerColor = colors.panel,
            disabledContentColor = colors.mutedInk,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(12.dp),
                color = if (item.enabled) colors.accent else colors.tintedPanel,
                contentColor = if (item.enabled) colors.onAccent else colors.mutedInk,
                modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold)
                if (item.subtitle.isNotBlank()) Text(item.subtitle,
                    style = MaterialTheme.typography.bodySmall, color = colors.mutedInk)
                if (!item.enabled && !item.unavailableReason.isNullOrBlank()
                    && item.unavailableReason != item.subtitle) {
                    Text(item.unavailableReason, style = MaterialTheme.typography.labelSmall,
                        color = colors.mutedInk)
                }
            }
        }
    }
}

/** Insets stay outside the rounded dock; every action has at least a 48 dp touch target. */
@Composable
fun OrbisBottomDock(
    currentLabel: String,
    onHome: () -> Unit,
    onOpenNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    homeDescription: String = "返回 Orbis 主页",
    navigationDescription: String = "打开北斗导航",
) {
    val colors = OrbisTheme.colors
    val deepSeekStyle = LocalOrbisDeepSeekStyle.current
    val themedDock = deepSeekStyle || LocalOrbisSeason.current != OrbisSeason.NONE
    val starColor = if (themedDock) colors.star else Color(0xFFFFE3A7)
    Box(modifier.fillMaxWidth().windowInsetsPadding(windowInsets)
        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        // Reserve the protruding star inside layout bounds, so all 66 dp remain tappable.
        Box(Modifier.fillMaxWidth().height(72.dp)) {
            Surface(modifier = Modifier.fillMaxWidth().height(58.dp).align(Alignment.BottomCenter),
                shape = RoundedCornerShape(22.dp),
                color = if (themedDock || LocalDarkMode.current) colors.dock else Color(0xE81F283D),
                contentColor = if (themedDock) colors.onDock else Color.White.copy(alpha = .76f),
                shadowElevation = if (deepSeekStyle) 0.dp else 10.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(currentLabel, style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 10.sp, lineHeight = 14.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                    Spacer(Modifier.width(80.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        IconButton(onClick = onOpenNavigation,
                            modifier = Modifier.size(68.dp, 48.dp)
                                .semantics { contentDescription = navigationDescription }) {
                            OrbisConstellation(Modifier.size(62.dp, 42.dp),
                                color = if (themedDock) colors.star else Color(0xFFFFE0A0))
                        }
                    }
                }
            }
            Surface(onClick = onHome, shape = CircleShape,
                color = Color.Transparent, contentColor = starColor,
                border = BorderStroke(5.dp, colors.page),
                shadowElevation = if (deepSeekStyle) 0.dp else 10.dp,
                modifier = Modifier.align(Alignment.TopCenter).size(66.dp)
                    .semantics { contentDescription = homeDescription }) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                    if (themedDock) listOf(colors.homeButton, colors.homeButton)
                    else listOf(Color(0xFF6982BA), Color(0xFF344B7C)))),
                    contentAlignment = Alignment.Center) {
                    OrbisStar(Modifier.size(36.dp), color = starColor)
                }
            }
        }
    }
}

/** Decorative when description is null; the enclosing button owns the accessible label. */
@Composable
fun OrbisConstellation(
    modifier: Modifier = Modifier,
    color: Color = OrbisTheme.colors.star,
    description: String? = null,
) {
    val deepSeekStyle = LocalOrbisDeepSeekStyle.current
    val semantics = if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
    }
    Canvas(modifier.then(semantics)) {
        // Exact geometry of index.html's 72 x 44 constellation SVG, preserving its aspect ratio.
        val scale = minOf(size.width / 72f, size.height / 44f)
        val origin = Offset((size.width - 72f * scale) / 2, (size.height - 44f * scale) / 2)
        val points = listOf(8f to 13f, 25f to 11f, 31f to 27f, 14f to 31f,
            41f to 18f, 54f to 13f, 66f to 21f)
            .map { Offset(origin.x + it.first * scale, origin.y + it.second * scale) }
        val radii = listOf(2.7f, 3.1f, 2.5f, 2.8f, 2.6f, 3f, 2.7f)
        listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 1 to 4, 4 to 5, 5 to 6).forEach { (a, b) ->
            drawLine(color.copy(alpha = .55f), points[a], points[b],
                strokeWidth = 1.8f * scale, cap = StrokeCap.Round)
        }
        points.forEachIndexed { index, point ->
            val radius = radii[index] * scale
            if (!deepSeekStyle) drawCircle(color.copy(alpha = .12f), radius * 2.1f, point)
            drawCircle(color, radius, point)
            drawCircle(if (deepSeekStyle) color else Color.White.copy(alpha = .72f), radius, point,
                style = Stroke(width = scale))
        }
    }
}

@Composable
fun OrbisStar(
    modifier: Modifier = Modifier,
    color: Color = OrbisTheme.colors.star,
    description: String? = null,
) {
    val deepSeekStyle = LocalOrbisDeepSeekStyle.current
    val semantics = if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
    }
    Canvas(modifier.then(semantics)) {
        val star = Path()
        val outer = size.minDimension * .45f
        repeat(10) { i ->
            val angle = -Math.PI / 2 + i * Math.PI / 5
            val radius = if (i % 2 == 0) outer else outer * .44f
            val x = center.x + cos(angle).toFloat() * radius
            val y = center.y + sin(angle).toFloat() * radius
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        if (!deepSeekStyle) drawCircle(color.copy(alpha = .09f), outer, center)
        drawPath(star, color)
    }
}
