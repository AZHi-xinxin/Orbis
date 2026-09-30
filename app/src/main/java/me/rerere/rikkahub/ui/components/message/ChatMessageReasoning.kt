package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalOrbisChatTextColor
import me.rerere.rikkahub.ui.context.orbisChatTextStyle
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.utils.extractThinkingTitle
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Stable
private class ReasoningState(
    val scrollState: ScrollState,
    initialDuration: Duration,
    private val collapseWhileLoading: Boolean = false,
) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var duration by mutableStateOf(initialDuration)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading && !collapseWhileLoading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

@Composable
private fun rememberReasoningState(reasoning: UIMessagePart.Reasoning): Pair<ReasoningState, Boolean> {
    val settings = LocalSettings.current
    val collapseByDefault = LocalOrbisDeepSeekStyle.current && settings.displaySetting.deepSeekCollapseThinking
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()

    val state = remember(reasoning.createdAt, collapseByDefault) {
        ReasoningState(
            scrollState = scrollState,
            collapseWhileLoading = collapseByDefault,
            initialDuration = reasoning.finishedAt?.let { it - reasoning.createdAt }
                ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!state.expandState.expanded && settings.displaySetting.showThinkingContent && !collapseByDefault)
                state.expandState = ReasoningCardState.Preview
            scrollState.animateScrollTo(scrollState.maxValue)
        } else {
            if (state.expandState.expanded) {
                state.expandState = if (settings.displaySetting.autoCloseThinking)
                    ReasoningCardState.Collapsed
                else
                    ReasoningCardState.Expanded
            }
        }
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                state.duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    return state to loading
}

@Composable
private fun ReasoningContent(
    reasoning: UIMessagePart.Reasoning,
    assistant: Assistant?,
    expandState: ReasoningCardState,
    scrollState: ScrollState,
    fadeHeight: Float,
    loading: Boolean,
) {
    val isPreview = expandState == ReasoningCardState.Preview
    val reasoningTextStyle = orbisChatTextStyle(MaterialTheme.typography.bodySmall.copy(
        fontFamily = LocalTextStyle.current.fontFamily,
    ))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { contentModifier ->
                if (isPreview) {
                    contentModifier
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val brush = Brush.verticalGradient(
                                startY = 0f,
                                endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fadeHeight / size.height) to Color.Black,
                                    (1 - fadeHeight / size.height) to Color.Black,
                                    1.0f to Color.Transparent
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(
                                    brush = brush,
                                    size = Size(size.width, size.height),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scrollState)
                } else {
                    contentModifier
                }
            }
    ) {
        val reasoningContent = @Composable {
            MarkdownBlock(
                content = reasoning.reasoning.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                ),
                style = reasoningTextStyle,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 流式生成期间不启用 SelectionContainer，避免 selectable 列表并发修改导致的
        // ConcurrentModificationException（详见 ChatMessage.kt 文本块同样处理）。
        if (loading) {
            reasoningContent()
        } else {
            SelectionContainer {
                reasoningContent()
            }
        }
    }
}

@Composable
fun ChainOfThoughtScope.ChatMessageReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    fadeHeight: Float = 64f,
    collapsedAdaptiveWidth: Boolean = false,
) {
    val (state, loading) = rememberReasoningState(reasoning)
    val thinkingTitle = reasoning.reasoning.extractThinkingTitle()
    val showThinkingTitle = loading && thinkingTitle != null
    val chatFontFamily = LocalTextStyle.current.fontFamily

    if (BuildConfig.ORBIS_ENABLED) {
        val expanded = state.expandState == ReasoningCardState.Expanded
        val ink = LocalOrbisChatTextColor.current ?: OrbisTheme.colors.mutedInk
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { state.onExpandedChange(!expanded, loading) }
                    .semantics {
                        stateDescription = when (state.expandState) {
                            ReasoningCardState.Collapsed -> "已折叠"
                            ReasoningCardState.Preview -> "预览中"
                            ReasoningCardState.Expanded -> "已展开"
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(HugeIcons.Idea01, null, modifier = Modifier.size(16.dp), tint = ink)
                Text(
                    text = if (showThinkingTitle) thinkingTitle!! else stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat(),
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = chatFontFamily),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).shimmer(isLoading = loading),
                )
                if (showThinkingTitle && state.duration > 0.seconds) {
                    Text(
                        text = state.duration.toString(DurationUnit.SECONDS, 1),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                        color = ink,
                    )
                }
                Icon(
                    if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    null, modifier = Modifier.size(16.dp), tint = ink,
                )
            }
            if (state.expandState != ReasoningCardState.Collapsed) {
                ReasoningContent(
                    reasoning = reasoning,
                    assistant = assistant,
                    expandState = state.expandState,
                    scrollState = state.scrollState,
                    fadeHeight = fadeHeight,
                    loading = loading,
                )
            }
        }
        return
    }

    ControlledChainOfThoughtStep(
        expanded = state.expandState == ReasoningCardState.Expanded,
        onExpandedChange = { state.onExpandedChange(it, loading) },
        icon = {
            Icon(
                imageVector = HugeIcons.Idea01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        },
        label = {
            if (showThinkingTitle) {
                ReasoningTitle(title = thinkingTitle!!)
            } else {
                Text(
                    text = stringResource(
                        R.string.deep_thinking_seconds,
                        state.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        extra = {
            if (showThinkingTitle && state.duration > 0.seconds) {
                Text(
                    text = state.duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = chatFontFamily),
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
            }
        },
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        contentVisible = state.expandState != ReasoningCardState.Collapsed,
        content = {
            ReasoningContent(
                reasoning = reasoning,
                assistant = assistant,
                expandState = state.expandState,
                scrollState = state.scrollState,
                fadeHeight = fadeHeight,
                loading = loading,
            )
        },
    )
}


@Composable
private fun ReasoningTitle(title: String) {
    val chatFontFamily = LocalTextStyle.current.fontFamily
    AnimatedContent(
        targetState = title,
        transitionSpec = {
            (slideInVertically { height -> height } + fadeIn()).togetherWith(
                slideOutVertically { height -> -height } + fadeOut()
            )
        }
    ) {
        Text(
            text = it,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .shimmer(true),
        )
    }
}
