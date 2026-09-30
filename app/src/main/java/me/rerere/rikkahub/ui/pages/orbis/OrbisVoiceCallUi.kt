package me.rerere.rikkahub.ui.pages.orbis

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.voice.*
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.OrbisHeartbeatLoading
import kotlin.uuid.Uuid

/** A phone surface; closing/minimizing it is deliberately not the hang-up action. */
@Composable
fun OrbisVoiceCallOverlay(runtime: OrbisVoiceCallRuntime, assistant: Assistant?,
    repository: OrbisVoiceCallRepository, currentConversationId: Uuid? = null,
    summaryCallIds: Set<String> = emptySet()) {
    val call by runtime.callState.collectAsStateWithLifecycle()
    val voice by runtime.voiceSession.state.collectAsStateWithLifecycle()
    var minimized by rememberSaveable(call.callId) { mutableStateOf(false) }
    var microphoneError by remember(call.callId) { mutableStateOf<String?>(null) }
    val callId = call.callId ?: return
    if (!call.isActive) {
        if (!call.returnScreenDismissed) OrbisVoiceCallReturnOverlay(
            repository = repository, callId = callId, runtimeEnding = call.ending,
            runtimeError = call.error,
            summaryVisible = call.conversationId != currentConversationId || callId in summaryCallIds,
            onDismiss = { runtime.dismissReturnScreen(callId) },
        )
        return
    }
    val microphonePermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(microphonePermission)
    if (minimized) {
        // Returning to the chat can always reopen the call; the notification also returns here.
        OrbisVoiceCallReopenButton(onReopen = { minimized = false })
        return
    }
    val callAssistant = assistant.takeIf { call.conversationId == currentConversationId }
    var now by remember(call.callId) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(call.callId) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    Dialog(onDismissRequest = { minimized = true }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false,
    )) {
        OrbisVisualTheme {
            val colors = OrbisTheme.colors
            val cream = colors.onDock
            Surface(Modifier.fillMaxSize().testTag("orbis-voice-call"), color = colors.dock) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("ORBIS · 语音通话", color = cream.copy(alpha = .65f), fontSize = 12.sp)
                        TextButton(onClick = { minimized = true }) { Text("收起", color = cream) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (callAssistant != null) UIAvatar(name = call.title, value = callAssistant.avatar,
                            modifier = Modifier.size(112.dp))
                        else Surface(Modifier.size(112.dp), shape = CircleShape, color = colors.indigo) {
                            Box(contentAlignment = Alignment.Center) { Text("✦", fontSize = 44.sp, color = cream) }
                        }
                        Text(call.title, color = cream, fontSize = 27.sp)
                        Text(call.connectedElapsedMillis?.let {
                            val seconds = ((now - it).coerceAtLeast(0) / 1000)
                            "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                        } ?: "正在接通…", color = cream.copy(alpha = .7f), fontSize = 16.sp)
                        Text(when (voice.phase) {
                            VoicePhase.Connecting -> "正在连接"
                            VoicePhase.Listening -> if (!voice.microphoneEnabled) "麦克风已关闭"
                                else if (voice.pendingReplies > 0) "正在回应，也在等你说完" else "正在聆听"
                            VoicePhase.Transcribing -> "正在听清你的话"
                            VoicePhase.Speaking -> "正在说话"
                            VoicePhase.Error -> voice.error ?: "通话暂时中断"
                            VoicePhase.Off -> "正在准备"
                        }, color = cream, fontSize = 15.sp)
                        if (voice.transcript.isNotBlank()) Text(voice.transcript,
                            modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
                            color = cream.copy(alpha = .6f), fontSize = 13.sp)
                        if (voice.lastReplyText.isNotBlank()) Surface(
                            shape = RoundedCornerShape(16.dp), color = cream.copy(alpha = .06f),
                        ) {
                            // This is reply text, not an assertion that all of it was played/heard.
                            Text(voice.lastReplyText, Modifier.padding(14.dp).heightIn(max = 130.dp)
                                .verticalScroll(rememberScrollState()).testTag("orbis-call-reply-text"),
                                color = cream.copy(alpha = .85f), fontSize = 14.sp)
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(when {
                            !voice.microphoneEnabled -> "静音接听中；不会收取你的声音"
                            voice.canInterruptPlayback -> "可直接插话；停顿时会等你说完"
                            else -> "当前轮流说话；耳机或可用回声处理支持播放时插话"
                        }, color = cream.copy(alpha = .65f), fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Top) {
                            OrbisCallRoundControl(label = if (voice.microphoneEnabled) "关闭麦克风" else "开启麦克风",
                                tag = "orbis-call-microphone", state = if (voice.microphoneEnabled) "麦克风已开启" else "麦克风已关闭",
                                color = cream.copy(alpha = .1f), foreground = cream,
                                icon = 0, crossedOut = !voice.microphoneEnabled, onClick = {
                                val enabled = !voice.microphoneEnabled
                                microphonePermission.updatePermissionStates()
                                if (enabled && !microphonePermission.allRequiredPermissionsGranted) {
                                    microphoneError = "请允许录音权限；授权后再次点击「开启麦克风」，才会开始收音。"
                                    microphonePermission.requestPermissions()
                                } else microphoneError = if (runtime.setMicrophoneEnabled(enabled)) null
                                    else "麦克风状态未能切换，请回到通话重试。"
                            })
                            OrbisCallRoundControl(label = "挂断", tag = "orbis-call-hangup", state = "结束语音通话",
                                color = Color(0xFFBA4D59), foreground = Color.White,
                                labelColor = cream, icon = 1, onClick = runtime::hangUp)
                            OrbisCallRoundControl(label = if (voice.speakerEnabled) "关闭声音" else "开启声音",
                                tag = "orbis-call-speaker", state = if (voice.speakerEnabled) "语音播放已开启" else "语音播放已关闭",
                                color = cream.copy(alpha = .1f), foreground = cream,
                                icon = 2, crossedOut = !voice.speakerEnabled,
                                onClick = { runtime.setSpeakerEnabled(!voice.speakerEnabled) })
                        }
                        microphoneError?.let { Text(it, color = cream, fontSize = 12.sp) }
                        Text("可切到后台或锁屏；挂断后停止收音", color = cream.copy(alpha = .55f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Compact phone controls: microphone / hang up / playback, with explicit off glyphs and labels. */
@Composable
private fun OrbisCallRoundControl(label: String, tag: String, state: String,
    color: Color, foreground: Color, icon: Int, labelColor: Color = foreground,
    crossedOut: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onClick, modifier = Modifier.size(48.dp).testTag(tag).semantics {
            contentDescription = label
            stateDescription = state
        }, shape = CircleShape, contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = foreground)) {
            if (icon == 1) Canvas(Modifier.size(25.dp)) {
                // Code-native handset glyph; no asset/network dependency.
                val handset = Path().apply {
                    moveTo(3f, 15f); cubicTo(7f, 8f, 17f, 8f, 21f, 15f)
                    lineTo(19f, 17f); lineTo(16f, 15f); lineTo(16f, 13f)
                    cubicTo(13.5f, 12f, 10.5f, 12f, 8f, 13f)
                    lineTo(8f, 15f); lineTo(5f, 17f); close()
                }
                scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                    drawPath(handset, foreground, style = Stroke(width = 1.8f,
                        cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            } else Icon(if (icon == 0) HugeIcons.Mic01 else HugeIcons.VolumeHigh,
                contentDescription = null, modifier = Modifier.size(24.dp).drawWithContent {
                    drawContent()
                    if (crossedOut) drawLine(foreground, Offset(size.width * .08f, size.height * .08f),
                        Offset(size.width * .92f, size.height * .92f), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                })
        }
        Text(label, color = labelColor, fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Keep the chat covered until both durable archive and folded chat state have arrived. */
@Composable
internal fun OrbisVoiceCallReturnOverlay(repository: OrbisVoiceCallRepository, callId: String,
    runtimeEnding: Boolean, runtimeError: String?, summaryVisible: Boolean, onDismiss: () -> Unit) {
    val revision by repository.revision.collectAsStateWithLifecycle()
    var record by remember(callId) { mutableStateOf<OrbisVoiceCallRecord?>(null) }
    var loadFailed by remember(callId) { mutableStateOf(false) }
    LaunchedEffect(callId, revision) {
        try {
            record = repository.get(callId)
            loadFailed = record == null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loadFailed = true
        }
    }
    val phase = orbisVoiceReturnPhase(record, runtimeEnding, runtimeError, loadFailed, summaryVisible)
    val currentDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(phase) {
        if (phase == OrbisVoiceReturnPhase.COMPLETE) currentDismiss()
    }
    if (phase != OrbisVoiceReturnPhase.COMPLETE) OrbisVoiceCallReturnDialog(phase, onDismiss)
}

/** No raw transcript, model JSON, retry request or microphone action belongs on this surface. */
@Composable
internal fun OrbisVoiceCallReturnDialog(phase: OrbisVoiceReturnPhase, onDismiss: () -> Unit) {
    val failed = phase == OrbisVoiceReturnPhase.FAILED
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false,
    )) {
        OrbisVisualTheme {
            val colors = OrbisTheme.colors
            Surface(Modifier.fillMaxSize().testTag("orbis-call-return"), color = colors.dock) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween) {
                    Text("ORBIS · 语音通话", color = colors.onDock.copy(alpha = .55f), fontSize = 12.sp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (!failed) OrbisHeartbeatLoading(visible = true, reduceMotion = false,
                            modifier = Modifier.width(180.dp), textColor = colors.onDock)
                        Text(if (failed) "通话已结束" else "正在回到聊天", color = colors.onDock, fontSize = 25.sp)
                        Text(if (failed) "记录尚未整理完成，可到「通话记录」查看详情和已保存的内容。"
                            else "本次通话记录整理完成后，将自动返回。", color = colors.onDock.copy(alpha = .75f),
                            fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("麦克风已关闭", color = colors.onDock.copy(alpha = .5f), fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = onDismiss, modifier = Modifier.testTag("orbis-call-return-dismiss")) {
                            Text(if (failed) "返回聊天" else "先返回聊天", color = colors.onDock)
                        }
                        Text("北斗导航 → 工具娱乐 → 通话记录", color = colors.onDock.copy(alpha = .45f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Its own compact, non-modal window stays above later Scaffolds without taking their touches. */
@Composable
internal fun OrbisVoiceCallReopenButton(onReopen: () -> Unit) {
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density)
    val margin = with(density) { 6.dp.roundToPx() }
    val position = remember(topInset, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
                ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
                (topInset + margin).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
            )
        }
    }
    Popup(popupPositionProvider = position, properties = PopupProperties(
        focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false,
    )) {
        FilledTonalButton(onClick = onReopen, modifier = Modifier.testTag("orbis-call-reopen")) {
            Text("语音通话中 · 返回通话")
        }
    }
}

/** UI-only fold. The underlying assistant message (including its summary) is never rewritten. */
@Composable
fun OrbisVoiceCallMessageCard(message: UIMessage, modifier: Modifier = Modifier) {
    // The user's end request owns this one control row; the assistant's raw archive JSON is data.
    if (message.orbisVoiceCallKind == "archive" && message.role == MessageRole.ASSISTANT) return
    val marker = remember(message.parts) { OrbisVoiceCallProtocol.parse(message.toText()) }
    var expanded by rememberSaveable(message.id.toString()) { mutableStateOf(false) }
    val summary = marker?.summary
    val title = when (message.orbisVoiceCallKind) {
        "begin" -> "已进入语音通话"
        "archive" -> "通话结束 · 记录归档"
        "ended_notice" -> "通话结束 · 记录待整理"
        else -> OrbisVoiceCallProtocol.title(marker?.durationMs).removeSurrounding("【", "】")
    }
    Column(modifier.fillMaxWidth().testTag("orbis-call-record"), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .42f),
            shape = RoundedCornerShape(14.dp), border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = .22f))) {
            Row(Modifier.heightIn(min = 44.dp).semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .clickable(enabled = summary != null, role = Role.Button,
                    onClickLabel = if (expanded) "收起通话摘要" else "展开通话摘要") { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("◌  $title", style = MaterialTheme.typography.bodySmall)
                if (summary != null) Text(if (expanded) "  ▴" else "  ▾", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded && summary != null) Surface(Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .42f)) {
            Text(summary, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
