package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.ui.pages.orbis.OrbisPageSurface
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme

/** Presentation-only wrapper: existing editors, state and save actions remain untouched. */
@Composable
internal fun OrbisSettingsScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val body: @Composable () -> Unit = {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = if (BuildConfig.ORBIS_ENABLED) Color.Transparent else containerColor,
            contentColor = if (BuildConfig.ORBIS_ENABLED) OrbisTheme.colors.ink else contentColor,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
    if (BuildConfig.ORBIS_ENABLED) OrbisVisualTheme { OrbisPageSurface { body() } } else body()
}

/** Compact Orbis header, including action slots from the original editor. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun OrbisSettingsTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: (@Composable () -> Unit)? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    if (!BuildConfig.ORBIS_ENABLED) {
        LargeFlexibleTopAppBar(title = title, modifier = modifier,
            navigationIcon = navigationIcon, actions = actions,
            colors = colors, scrollBehavior = scrollBehavior)
        return
    }
    // Pages may still install this behavior's nested-scroll connection. A Material collapsing
    // bar normally sets its measured collapse range; our fixed header never does. Leaving the
    // initial -Float.MAX_VALUE limit makes the parent consume upward drags indefinitely.
    SideEffect {
        scrollBehavior?.state?.let { state ->
            state.heightOffsetLimit = 0f
            state.heightOffset = 0f
            state.contentOffset = 0f
        }
    }
    val palette = OrbisTheme.colors
    Column(modifier.fillMaxWidth().background(palette.page.copy(alpha = .94f)).statusBarsPadding()
        .drawBehind {
            val stroke = 1.dp.toPx()
            drawLine(palette.border.copy(alpha = .65f), Offset(0f, size.height - stroke / 2),
                Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
        }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            navigationIcon()
            Column(Modifier.weight(1f).semantics { heading() }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp,
                    lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)) { title() }
                if (subtitle != null) ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp, lineHeight = 16.sp, color = palette.mutedInk)) { subtitle() }
            }
            actions()
        }
    }
}
