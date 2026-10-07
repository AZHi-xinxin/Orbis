package me.rerere.rikkahub.ui.components.message

import me.rerere.rikkahub.data.model.isOrbisVoiceNote
import me.rerere.rikkahub.data.model.voiceNotePlaybackKey
import me.rerere.rikkahub.data.model.OrbisVoiceNotePlayedEdit

import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.ui.components.richtext.LocalImportedHistory
import me.rerere.rikkahub.ui.components.richtext.isDeepSeekHistory
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.hostToolFailure
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.orbis.privateroom.hasPrivateRoomToolContent
import me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolPart
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomSafePresentation
import me.rerere.rikkahub.data.orbis.privateroom.privateRoomPublicParts
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebViewContentCache
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.orbisChatTextStyle
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import java.util.Locale
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun ChatMessage(
    node: MessageNode,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    lastMessage: Boolean = false,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onVoiceNotePlayed: ((OrbisVoiceNotePlayedEdit) -> Unit)? = null,
    onDeleteToolRecord: ((String) -> Unit)? = null,
    onRestoreToolRecord: ((String) -> Unit)? = null,
    contextPruningState: me.rerere.rikkahub.data.ai.contextpruning.ContextPruningState? = null,
    onRestoreContextPruning: ((String) -> Unit)? = null,
    onEventPresentation: suspend (me.rerere.rikkahub.data.model.OrbisEventPresentationEdit) -> Unit = {
        error("event_presentation_unavailable")
    },
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onQuote: ((MessageNode) -> Unit)? = null,
    onQuoteJump: ((me.rerere.ai.ui.OrbisMessageQuote) -> Unit)? = null,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, remember: Boolean) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
) {
    val originalMessage = node.messages[node.selectIndex]
    val privateOperation = originalMessage.hasPrivateRoomToolContent()
    // Render public prose, but keep mutations/branch selection attached to the ORIGINAL node.
    // Saving a presentation copy would erase the assistant's tool context.
    val message = originalMessage.privateRoomSafePresentation()
    if (privateOperation) {
        PrivateRoomPublicReply(message, node, modifier, loading, assistant, model, onShare, onUpdate,
            onToolApproval, onToolAnswer, onDeleteToolRecord, onRestoreToolRecord)
        return
    }
    val pruning = remember(message, contextPruningState) {
        if (privateOperation) null else contextPruningState?.let {
            me.rerere.rikkahub.data.ai.contextpruning.projectContextPruningForDisplay(message, it)
        }
    }
    val currentOnVoiceNotePlayed by rememberUpdatedState(onVoiceNotePlayed)
    if (message.orbisVoiceCallKind in setOf("begin", "archive", "summary", "ended_notice")) {
        me.rerere.rikkahub.ui.pages.orbis.OrbisVoiceCallMessageCard(message, modifier)
        return
    }
    val settings = LocalSettings.current.displaySetting
    val deepSeek = LocalOrbisDeepSeekStyle.current
    val segmentedReply = BuildConfig.ORBIS_ENABLED && message.role == MessageRole.ASSISTANT &&
        settings.appearanceForStyle(LocalOrbisDeepSeekStyle.current).chatFlow.enabled
    val hasSideAvatar = (!deepSeek || settings.deepSeekShowAvatars) && !message.parts.isEmptyUIMessage() && when (message.role) {
        MessageRole.USER -> settings.showUserAvatar
        MessageRole.ASSISTANT -> settings.showModelIcon && (model != null || assistant?.useAssistantAvatar == true)
        else -> false
    }
    val textStyle = rememberChatMessageTextStyle()
    val externalEvent = message.orbisEvent
    if (externalEvent != null) {
        val settingsStore = koinInject<SettingsStore>()
        ProvideTextStyle(textStyle) {
            OrbisEventMessageCard(
                event = externalEvent,
                originalText = message.toText(),
                modifier = modifier,
                appearance = settings.appearanceForStyle(LocalOrbisDeepSeekStyle.current),
                onOpacityChange = { settingsStore.updateOrbisEventOpacity(it, deepSeek) },
                onUpdate = { metadata ->
                    onEventPresentation(me.rerere.rikkahub.data.model.OrbisEventPresentationEdit(
                        nodeId = node.id,
                        messageId = message.id,
                        expected = externalEvent,
                        originalText = message.toText(),
                        read = metadata.read,
                        collapsed = metadata.collapsed,
                    ))
                },
            )
        }
        return
    }
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!BuildConfig.ORBIS_ENABLED && !message.parts.isEmptyUIMessage()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    model = model,
                    assistant = assistant,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        val renderParts: @Composable (List<UIMessagePart>) -> Unit = { displayedParts ->
            CompositionLocalProvider(LocalImportedHistory provides message.parts.isDeepSeekHistory()) {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(
                    assistant = assistant,
                    role = message.role,
                    parts = if (pruning == null) displayedParts else displayedParts.filterNot { part ->
                        message.parts.indexOf(part) in pruning.hiddenPartIndexes
                    },
                    annotations = message.annotations,
                    loading = loading,
                    model = model,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onUserMessageClick = if (!privateOperation && message.role == MessageRole.USER) onEdit else null,
                    segmentedReply = segmentedReply,
                    messageKey = message.id.toString(),
                    onDeleteToolRecord = if (BuildConfig.ORBIS_ENABLED) onDeleteToolRecord else null,
                    // Split display rows still resolve citations against the original turn.
                    deletedCitationTools = message.parts.filterIsInstance<UIMessagePart.Tool>() +
                        message.deletedToolRecords.map { it.tool },
                )
            }
            }
        }
        val messageExtras = @Composable {
            ProvideTextStyle(textStyle) {
                ContextPruningNotice(pruning, onRestoreContextPruning)
                if (BuildConfig.ORBIS_ENABLED && message.deletedToolRecords.isNotEmpty()) {
                    OrbisDeletedToolRecords(
                        records = message.deletedToolRecords.map { it.tool.toolCallId to it.tool.toolName },
                        onRestore = onRestoreToolRecord,
                    )
                }

                message.translation?.let { translation ->
                    CollapsibleTranslationText(
                        content = translation,
                        onClickCitation = {}
                    )
                }
            }
        }
        val voiceRows = remember(message.parts, message.role) {
            if (message.role == MessageRole.ASSISTANT) message.parts.orbisVoiceNoteDisplay() else null
        }
        if (voiceRows != null) {
            OrbisVoiceNoteRows(message, voiceRows, model, assistant, loading, segmentedReply,
                onPlayed = { row -> currentOnVoiceNotePlayed?.invoke(OrbisVoiceNotePlayedEdit(node.id, message.id,
                    row.partIndex, row.outputIndex, row.audio.voiceNotePlaybackKey(message.id.toString(), row.occurrenceKey))) },
                renderParts = renderParts)
            messageExtras()
        } else if (BuildConfig.ORBIS_ENABLED && !message.parts.isEmptyUIMessage()) {
            OrbisChatMessageLayout(message, model, assistant, loading, segmentedReply) {
                renderParts(message.parts)
                messageExtras()
            }
        } else {
            renderParts(message.parts)
            messageExtras()
        }

        // A quote belongs below its reply, outside the body bubble and above the actions.
        // Match the bubble's role alignment without occupying the side-avatar column.
        message.orbisQuote?.takeIf { it.isValid() }?.let { quote ->
            OrbisQuoteCard(
                quote = quote,
                modifier = if (BuildConfig.ORBIS_ENABLED && hasSideAvatar) Modifier.padding(
                    start = if (message.role == MessageRole.USER) 0.dp else 36.dp,
                    end = if (message.role == MessageRole.USER) 36.dp else 0.dp,
                ) else Modifier,
                onJump = onQuoteJump?.let { callback -> { callback(quote) } },
            )
        }

        val showActions = if (lastMessage) {
            !loading
        } else {
            message.parts.isEmptyUIMessage().not()
        }

        AnimatedVisibility(
            visible = showActions,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            Column(
                modifier = Modifier.animateContentSize().then(
                    if (BuildConfig.ORBIS_ENABLED && hasSideAvatar) Modifier.padding(
                        start = if (message.role == MessageRole.USER) 0.dp else 36.dp,
                        end = if (message.role == MessageRole.USER) 36.dp else 0.dp,
                    ) else Modifier
                )
            ) {
                ChatMessageActionButtons(
                    message = originalMessage,
                    onRegenerate = onRegenerate,
                    node = node,
                    onUpdate = onUpdate,
                    onOpenActionSheet = {
                        showActionsSheet = true
                    },
                    onTranslate = onTranslate.takeUnless { privateOperation },
                    onClearTranslation = onClearTranslation
                )
            }
        }

        val retainedPrunedFiles = remember(node, pruning) {
            me.rerere.rikkahub.ui.pages.chat.prunedContextFiles(node, pruning)
        }
        val retainedPrunedMedia = remember(node, pruning) {
            me.rerere.rikkahub.ui.pages.chat.prunedContextMedia(node, pruning)
        }
        if (retainedPrunedMedia.isNotEmpty()) renderParts(retainedPrunedMedia)
        me.rerere.rikkahub.ui.pages.orbis.OrbisCallFileAttachments(retainedPrunedFiles)
        EditedFilesList(
            parts = message.parts.filterIndexed { index, _ -> index !in (pruning?.hiddenPartIndexes ?: emptySet()) },
            assistant = assistant,
        )

        ProvideTextStyle(textStyle) {
            ChatMessageNerdLine(message = message)
        }

    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = originalMessage,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            isFavorite = isFavorite,
            onToggleFavorite = onToggleFavorite,
            onQuote = if (!loading && message.role in setOf(MessageRole.USER, MessageRole.ASSISTANT) &&
                message.parts.filterIsInstance<UIMessagePart.Text>().any { it.text.isNotBlank() })
                onQuote?.let { callback -> { callback(node.privateRoomSafePresentation()) } } else null,
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    val contentId = WebViewContentCache.store(context.cacheDir, htmlContent)
                    navController.navigate(Screen.WebView(contentId = contentId))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

/** Public reply and ordinary tool controls; mutations remain bound to original call IDs, not the projection. */
@Composable
private fun PrivateRoomPublicReply(
    message: UIMessage,
    originalNode: MessageNode,
    modifier: Modifier,
    loading: Boolean,
    assistant: Assistant?,
    model: Model?,
    onShare: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onToolApproval: ((String, Boolean, String, Boolean) -> Unit)?,
    onToolAnswer: ((String, String) -> Unit)?,
    onDeleteToolRecord: ((String) -> Unit)?,
    onRestoreToolRecord: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    val appearance = LocalSettings.current.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current)
    val segmented = BuildConfig.ORBIS_ENABLED && message.role == MessageRole.ASSISTANT && appearance.chatFlow.enabled
    val textStyle = rememberChatMessageTextStyle()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val body: @Composable () -> Unit = {
            ProvideTextStyle(textStyle) {
                MessagePartsBlock(assistant, message.role, model, message.parts, emptyList(), loading,
                    segmentedReply = segmented, messageKey = message.id.toString(),
                    onToolApproval = onToolApproval, onToolAnswer = onToolAnswer,
                    onDeleteToolRecord = onDeleteToolRecord,
                    deletedCitationTools = message.deletedToolRecords.map { it.tool })
                if (BuildConfig.ORBIS_ENABLED && message.deletedToolRecords.isNotEmpty()) {
                    OrbisDeletedToolRecords(
                        records = message.deletedToolRecords.map { it.tool.toolCallId to it.tool.toolName },
                        onRestore = onRestoreToolRecord,
                    )
                }
            }
        }
        if (BuildConfig.ORBIS_ENABLED) OrbisChatMessageLayout(message, model, assistant, loading, segmented, body)
        else body()
        if (!loading) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { context.copyMessageToClipboard(message) }) { Text(stringResource(R.string.copy)) }
            TextButton(onClick = onShare) { Text(stringResource(R.string.share)) }
            if (originalNode.messages.size > 1) {
                TextButton(enabled = originalNode.selectIndex > 0,
                    onClick = { onUpdate(originalNode.copy(selectIndex = originalNode.selectIndex - 1)) }) { Text("上一分支") }
                Text("${originalNode.selectIndex + 1}/${originalNode.messages.size}")
                TextButton(enabled = originalNode.selectIndex < originalNode.messages.lastIndex,
                    onClick = { onUpdate(originalNode.copy(selectIndex = originalNode.selectIndex + 1)) }) { Text("下一分支") }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
internal fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    loading: Boolean,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, remember: Boolean) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onUserMessageClick: (() -> Unit)? = null,
    segmentedReply: Boolean = false,
    messageKey: String = "",
    onDeleteToolRecord: ((String) -> Unit)? = null,
    deletedCitationTools: List<UIMessagePart.Tool> = emptyList(),
) {
    val privateOperation = parts.any { it.isPrivateRoomToolPart() } || deletedCitationTools.any { it.isPrivateRoomToolPart() }
    val parts = if (privateOperation) privateRoomPublicParts(parts) else parts
    val annotations = if (privateOperation) emptyList() else annotations
    val deletedCitationTools = if (privateOperation) emptyList() else deletedCitationTools
    val context = LocalContext.current
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)
    val deletedCitationToolsState by rememberUpdatedState(deletedCitationTools)

    val handleClickCitation: (String) -> Unit = remember {
        handler@{ citationId ->
            // Undo metadata is UI-only. Keep existing citation links usable after removing
            // a search call/result from provider-visible parts; never inject this into a request.
            (partsState + deletedCitationToolsState).forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    // Render parts in original order (group thinking/tool as chain-of-thought)
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    groupedParts.fastForEach { block ->
        when (block) {
            is MessagePartBlock.ThinkingBlock -> {
                if (block.steps.isNotEmpty()) {
                    val isReasoningOnlyBlock = block.steps.fastAll { it is ThinkingStep.ReasoningStep }
                    ChainOfThought(
                        modifier = Modifier.animateContentSize(),
                        steps = block.steps.withIndex().toList(),
                        // Paragraph bubbles change prose presentation, not chain expansion.
                        // Keep the same tail preview and reversible group toggle in both layouts.
                        collapsedVisibleCount = 2,
                        retainWhenCollapsed = { indexed ->
                            val tool = (indexed.value as? ThinkingStep.ToolStep)?.tool
                            tool != null && (tool.isPending || tool.hostToolFailure() != null)
                        },
                        collapsedAdaptiveWidth = segmentedReply || isReasoningOnlyBlock,
                        cardColors = CardDefaults.cardColors(
                            containerColor = if (BuildConfig.ORBIS_ENABLED) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                        ),
                    ) { indexedStep ->
                        val step = indexedStep.value
                        when (step) {
                            is ThinkingStep.ReasoningStep -> {
                                key(indexedStep.index, step.reasoning.createdAt) {
                                    ChatMessageReasoningStep(
                                        reasoning = step.reasoning,
                                        model = model,
                                        assistant = assistant,
                                        collapsedAdaptiveWidth = isReasoningOnlyBlock,
                                    )
                                }
                            }

                            is ThinkingStep.ToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageToolStep(
                                        tool = step.tool,
                                        loading = loading && !step.tool.isExecuted,
                                        onToolApproval = onToolApproval,
                                        onToolAnswer = onToolAnswer,
                                        initiallyExpanded = !segmentedReply,
                                        onDeleteRecord = onDeleteToolRecord?.let { action ->
                                            { action(step.tool.toolCallId) }
                                        },
                                    )
                                }
                            }

                            is ThinkingStep.ServerToolStep -> {
                                key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                                    ChatMessageServerToolStep(tool = step.tool)
                                }
                            }
                        }
                    }
                }
            }

            is MessagePartBlock.ContentBlock -> key(block.index) {
                when (val part = block.part) {
                    is UIMessagePart.Text -> {
                        val textContent = @Composable {
                            if (BuildConfig.ORBIS_ENABLED) {
                                OrbisStickerMessageText(
                                    content = part.text,
                                    allowReference = { reference ->
                                        reference.replaceRegexes(
                                            assistant = assistant,
                                            scope = if (role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ) == reference
                                    },
                                    modifier = Modifier.animateContentSize().then(
                                        if (role == MessageRole.USER && onUserMessageClick != null) {
                                            Modifier.clickable { onUserMessageClick() }
                                        } else Modifier
                                    ),
                                ) { plainText ->
                                    val visualText = plainText.replaceRegexes(
                                            assistant = assistant,
                                            scope = if (role == MessageRole.USER) AssistantAffectScope.USER else AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        )
                                    if (segmentedReply) OrbisReplyText(
                                        content = visualText,
                                        appearance = settings.displaySetting.appearanceForStyle(LocalOrbisDeepSeekStyle.current),
                                        contentKey = "$messageKey:${block.index}",
                                        onClickCitation = handleClickCitation,
                                    ) else MarkdownBlock(
                                        style = orbisChatTextStyle(),
                                        content = visualText,
                                        onClickCitation = handleClickCitation,
                                    )
                                }
                            } else if (role == MessageRole.USER) {
                                Surface(
                                    modifier = Modifier.animateContentSize(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    onClick = { onUserMessageClick?.invoke() },
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        MarkdownBlock(
                                            content = part.text.replaceRegexes(
                                                assistant = assistant,
                                                scope = AssistantAffectScope.USER,
                                                visual = true,
                                            ),
                                            onClickCitation = handleClickCitation
                                        )
                                    }
                                }
                            } else {
                                if (settings.displaySetting.showAssistantBubble) {
                                    Surface(
                                        modifier = Modifier.animateContentSize(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = settings.displaySetting.bubbleOpacity),
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            MarkdownBlock(
                                                content = part.text.replaceRegexes(
                                                    assistant = assistant,
                                                    scope = AssistantAffectScope.ASSISTANT,
                                                    visual = true,
                                                ),
                                                onClickCitation = handleClickCitation,
                                            )
                                        }
                                    }
                                } else {
                                    MarkdownBlock(
                                        content = part.text.replaceRegexes(
                                            assistant = assistant,
                                            scope = AssistantAffectScope.ASSISTANT,
                                            visual = true,
                                        ),
                                        onClickCitation = handleClickCitation,
                                        modifier = Modifier
                                            .animateContentSize()
                                    )
                                }
                            }
                        }

                        // 流式生成期间不启用 SelectionContainer：Markdown 在不断重渲染，
                        // 内部可选择的 Text 会频繁注册/注销，与 Compose 选择工具栏在绘制阶段
                        // 对 selectable 列表的排序产生并发修改，导致 ConcurrentModificationException。
                        // 生成结束后内容稳定，再启用文本选择。
                        if (loading) {
                            textContent()
                        } else {
                            SelectionContainer {
                                textContent()
                            }
                        }
                    }

                    is UIMessagePart.Video -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                Icon(HugeIcons.Video01, null)
                            }
                        }
                    }

                    is UIMessagePart.Audio -> {
                        if (part.isOrbisVoiceNote()) {
                            OrbisVoiceNoteBubble(part, messageKey)
                        } else {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = HugeIcons.MusicNote03,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                        }
                    is UIMessagePart.Image -> {
                        if (part.url.startsWith("orbis-video-frame://")) {
                            Text("视频临时画面 · 到期自动清理")
                        } else {
                        val isImageLoading =
                            part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
                        if (isImageLoading) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .shimmer(isLoading = true)
                            )
                        } else {
                            ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .height(72.dp)
                            )
                        }
                        }
                    }

                    is UIMessagePart.Document -> {
                        Surface(
                            tonalElevation = 2.dp,
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW)
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                intent.data = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    part.url.toUri().toFile()
                                )
                                val chooserIndent = Intent.createChooser(intent, null)
                                context.startActivity(chooserIndent)
                            },
                            modifier = Modifier,
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    when (part.mime) {
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.docx),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        "application/pdf" -> {
                                            Icon(
                                                painter = painterResource(R.drawable.pdf),
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        else -> {
                                            Icon(
                                                imageVector = HugeIcons.File02,
                                                contentDescription = null,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = part.fileName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 200.dp)
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        // Skip unknown part types (e.g., deprecated ToolCall, ToolResult, Search)
                    }
                }
            }
        }
    }

    // Annotations (always rendered at the end)
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }
}
