package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.OrbisVoicePcmBuffer
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceNotes
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import org.koin.compose.koinInject
import java.util.concurrent.atomic.AtomicBoolean

/** Finishing creates a draft only. The ordinary chat Send action is still required. */
@Composable
internal fun OrbisVoiceNoteRecorder(
    asr: CustomAsrState,
    onDismiss: () -> Unit,
    onDraft: (UIMessagePart.Audio) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val files = koinInject<FilesManager>()
    val buffer = remember { OrbisVoicePcmBuffer() }
    val activeWindow = remember { AtomicBoolean(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val asrState by asr.state.collectAsState()
    var started by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var transcript by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun stop() { if (recording) { recording = false; asr.stop() } }
    LaunchedEffect(asrState.status) { if (asrState.status == ASRStatus.Error) stop() }
    DisposableEffect(asr) {
        activeWindow.set(true)
        onDispose { activeWindow.set(false); if (recording) asr.stop(); buffer.discard() }
    }
    DisposableEffect(lifecycleOwner, asr) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(1000); elapsed++
            if (elapsed >= 120 || buffer.limitReached) stop()
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) { stop(); onDismiss() } },
        title = { Text("录制语音条") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("使用当前语音识别服务同步转写，发送给 AI 的是转写文字。录音保存在本机；最多 2 分钟。录完先加入草稿，不会立即发送。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (recording) "正在录音 · ${elapsed}秒" else if (started) "录音已停止 · ${elapsed}秒" else "点开始后才开启麦克风")
                if (!started) Button(onClick = {
                    notice = null
                    try {
                        val accepted = asr.startVoiceNote(onTranscriptChange = { if (activeWindow.get()) transcript = it }, onPcm = buffer::append)
                        started = accepted; recording = accepted
                        if (!accepted) notice = "没有启动录音，请检查麦克风权限、识别服务和其他正在使用声音的应用。"
                    } catch (_: Exception) {
                        recording = false
                        notice = "录音没有启动，请检查语音识别设置后再试。"
                    }
                }, enabled = asrState.isAvailable && !asrState.isRecording) { Text("开始录音") }
                if (recording) Button(onClick = ::stop) { Text("停止录音") }
                if (asrState.status == ASRStatus.Stopping) Text("正在等待最后的识别结果…")
                if (asrState.status == ASRStatus.Error) Text(asrState.errorMessage ?: "识别失败，可重试录制", color = MaterialTheme.colorScheme.error)
                if (!recording && started) OutlinedTextField(value = transcript, onValueChange = { transcript = it },
                    label = { Text("转写文字（可核对修改）") }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = started && !recording && !saving && transcript.isNotBlank() &&
                asrState.status != ASRStatus.Stopping && asrState.status != ASRStatus.Connecting,
                onClick = {
                    saving = true
                    val original = asr.correctionReview.value?.original ?: asr.state.value.transcript
                    scope.launch {
                        try {
                            val (wav, duration) = buffer.finish()
                            onDraft(OrbisVoiceNotes(context, files).save(wav, "wav", transcript, original, duration))
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { notice = "未能保存录音，请取消后重新录制；原聊天草稿未改变。" }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "保存中…" else "加入草稿") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { stop(); onDismiss() }) { Text("取消") } })
}
