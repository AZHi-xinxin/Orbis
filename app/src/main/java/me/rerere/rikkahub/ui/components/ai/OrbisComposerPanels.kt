package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.model.OrbisComposerAction
import me.rerere.rikkahub.data.model.orbisQuickEmotions
import me.rerere.rikkahub.ui.pages.orbis.OrbisTheme
import me.rerere.rikkahub.ui.pages.orbis.LocalOrbisDeepSeekStyle

@Composable
internal fun OrbisComposerTab(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = OrbisTheme.colors
    val deepSeek = LocalOrbisDeepSeekStyle.current
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(10.dp),
            color = if (selected) { if (deepSeek) colors.accent else colors.sand } else colors.raisedPanel,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
            Text(label, Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                fontSize = 11.sp, lineHeight = 14.sp,
                color = if (selected) { if (deepSeek) colors.onAccent else colors.onSand } else colors.mutedInk)
        }
    }
}

@Composable
internal fun OrbisCapabilityPanel(onAction: (OrbisComposerAction) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 224.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        // Context has one entry in the top-bar ring, rather than a second compression dialog.
        OrbisComposerAction.entries.filterNot { it == OrbisComposerAction.CONTEXT }.chunked(3).forEach { actions ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { action ->
                    Surface(onClick = { onAction(action) }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp), color = OrbisTheme.colors.raisedPanel,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                            Text(action.glyph, color = OrbisTheme.colors.onSand, fontSize = 15.sp, lineHeight = 18.sp)
                            Text(action.title, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                    }
                }
                repeat(3 - actions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("文件先加入草稿；工具配置可能对同一 AI 的其他窗口生效，以配置页为准。展开面板不执行工具。",
            fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
    }
}

@Composable
internal fun OrbisEmotionPanel(onInsert: (String) -> Unit) {
    var category by remember { mutableStateOf("全部") }
    Column(Modifier.fillMaxWidth().heightIn(max = 216.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        Text("快捷表情", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
        Text("点选只加入草稿，按发送后才发出；当前是文字表情，共享图片与原图授权尚未接通。",
            fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("全部", "抱抱", "搞怪", "干活").forEach { item ->
                OrbisComposerTab(item, category == item) { category = item }
            }
        }
        orbisQuickEmotions.filter { category == "全部" || it.category == category }.chunked(3).forEach { items ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { item ->
                    Surface(onClick = { onInsert(item.draftText) }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp), color = OrbisTheme.colors.raisedPanel,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(item.glyph, fontSize = 23.sp, lineHeight = 28.sp)
                            Text(item.label, fontSize = 10.sp, lineHeight = 14.sp, color = OrbisTheme.colors.mutedInk)
                        }
                    }
                }
                repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun OrbisVoicePanel(
    canRecognize: Boolean,
    recording: Boolean,
    busy: Boolean,
    canStartVoice: Boolean,
    voiceUnavailableReason: String? = null,
    batchVoiceMode: Boolean = false,
    canSpeak: Boolean,
    speaking: Boolean,
    autoRead: Boolean,
    onRecognize: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    voiceActive: Boolean,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    onConfigure: () -> Unit,
    onVoiceNote: (() -> Unit)? = null,
    canRecordNote: Boolean = true,
    canStartVideo: Boolean = false,
    videoUnavailableReason: String? = null,
    onStartVideo: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 224.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        Text("语音与视频", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = if (voiceActive) onStopVoice else onStartVoice,
                enabled = voiceActive || canStartVoice, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                Text(if (voiceActive) "挂断通话" else "语音通话", fontSize = 11.sp, lineHeight = 16.sp)
            }
            OutlinedButton(onClick = onStartVideo, enabled = !voiceActive && canStartVideo,
                modifier = Modifier.weight(1f).testTag("orbis-start-video-call"), contentPadding = PaddingValues(6.dp)) {
                Text("视频通话", fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        if (!voiceActive && !canStartVoice && voiceUnavailableReason != null) {
            Text("暂不能通话：$voiceUnavailableReason", fontSize = 11.sp, lineHeight = 16.sp,
                color = OrbisTheme.colors.mutedInk)
        } else if (!voiceActive && !canStartVideo && videoUnavailableReason != null) {
            Text("暂不能视频：$videoUnavailableReason", fontSize = 11.sp, lineHeight = 16.sp,
                color = OrbisTheme.colors.mutedInk)
        }
        Text("视频会把相机画面定期发给当前模型；需要相机权限，可能产生图片用量。", fontSize = 10.sp,
            lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
        if (onVoiceNote != null) OutlinedButton(onClick = onVoiceNote,
            enabled = canRecognize && canRecordNote && !busy && !recording && !voiceActive,
            modifier = Modifier.fillMaxWidth()) { Text("发送语音条 · 录音后加入草稿") }
        Text("语音输入先转成草稿。语音通话会自动发送并朗读回复，需主动开启；收起通话界面不会挂断。全局自动朗读：${if (autoRead) "开" else "关"}。",
            fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
        if (batchVoiceMode) Text("MiMo / Step 通话：说完后本地等待约 3 秒停顿，再完成识别，回复会多等一会儿。请轮流说话，暂不支持在朗读中抢话；本地未检测到说话时不上传。",
            fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onRecognize, enabled = canRecognize && !busy && !voiceActive,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                Text(if (recording) "停止录音" else "语音输入", fontSize = 11.sp, lineHeight = 16.sp)
            }
            OutlinedButton(onClick = if (speaking) onStopSpeaking else onSpeak,
                enabled = speaking || canSpeak, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                Text(if (speaking) "停止朗读" else "试听当前音色", fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
        TextButton(onClick = onConfigure, modifier = Modifier.fillMaxWidth()) { Text("音色与语音设置", fontSize = 11.sp, lineHeight = 16.sp) }
        Text("未配置的能力不会启动。试听、识别、语音通话及挂断后的 AI 归档可能使用已配置的收费服务；打开面板不录音、不请求服务。",
            fontSize = 10.sp, lineHeight = 15.sp, color = OrbisTheme.colors.mutedInk)
    }
}
