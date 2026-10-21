package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.data.orbis.voice.VoiceMixerPreview
import me.rerere.rikkahub.data.orbis.voice.VoicePreviewStage
import me.rerere.rikkahub.data.orbis.voice.collectOrbisVoiceAudio
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.tts.controller.*
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.compose.koinInject
import kotlin.math.sin

/** A full destination matching the design sheet. Closing never ends a call. */
@Composable
internal fun OrbisVoiceMixer(
    onClose: () -> Unit,
    title: String = "当前音色",
    store: SettingsStore = koinInject(),
    previewSynthesize: (suspend (TTSProviderSetting, String) -> TTSResponse)? = null,
) {
    val context = LocalContext.current
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val runtime = remember(context) { OrbisVoiceCallRuntime.get(context) }
    val call by runtime.callState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(store.settingsFlow.value.voiceMixer) }
    val gate = remember { Mutex() }
    var notice by remember { mutableStateOf<String?>(null) }
    var presetName by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf(false) }
    var phrase by rememberSaveable { mutableStateOf("早上好，我在这里陪着你。慢慢调到你喜欢的声音吧。") }
    val provider = settings.getSelectedTTSProvider()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val factory = rememberUpdatedState(previewSynthesize)
    val baseSpeed = rememberUpdatedState(settings.defaultTTSPlaybackSpeed)
    val previewConfig = rememberUpdatedState(draft)
    val previewProvider = rememberUpdatedState(provider)
    // This player is owned by this page only. It cannot stop or replace the call player.
    val player = remember(context) { lazy { AudioPlayer(context) } }
    val preview = remember(context, lifecycle) {
        var playback = VoicePlaybackSnapshot.capture()
        VoiceMixerPreview(CoroutineScope(scope.coroutineContext + Dispatchers.Main.immediate), synthesize = { text ->
            val selected = requireNotNull(previewProvider.value).copyProvider()
            playback = VoicePlaybackSnapshot.capture(baseSpeed.value, previewConfig.value)
            factory.value?.invoke(selected, text) ?: withContext(Dispatchers.IO) {
                val audio = collectOrbisVoiceAudio(TTSManager(context).generateSpeech(selected, TTSRequest(text)))
                TTSResponse(audio.bytes, AudioFormat.valueOf(audio.extension.uppercase(java.util.Locale.ROOT)))
            }
        }, play = { audio ->
            // The constructor also touches Media3, not just play(). Synthesis can
            // resume on a worker in an undispatched/test context; never initialize
            // the lazy Android player there.
            withContext(Dispatchers.Main.immediate) {
                player.value.play(audio, playback)
            }
        },
            stopPlayer = { if (player.isInitialized()) player.value.stop() },
            canPreview = { !runtime.callState.value.isActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
    }
    val previewState by preview.state.collectAsStateWithLifecycle()
    DisposableEffect(preview, lifecycle) {
        // Stop synchronously on pause: a lifecycle-gated flow can stop collecting
        // before a rapid background transition's last state reaches the UI.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) preview.stop()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); preview.close() }
    }
    DisposableEffect(player) { onDispose {
        if (player.isInitialized()) player.value.release()
    } }
    LaunchedEffect(call.isActive, preview) {
        if (call.isActive) preview.stop()
    }
    fun change(value: VoiceMixerConfig) { draft = value }
    fun save(value: VoiceMixerConfig = draft) {
        scope.launch {
            gate.withLock {
                try {
                    val ok = withContext(NonCancellable + Dispatchers.IO) { store.update { it.copy(voiceMixer = value) } }
                    notice = if (ok) null else "未能保存，原设置仍在；本轮试听不影响聊天。"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { notice = "未能保存，原设置仍在。" }
            }
        }
    }
    CompanionWindow(onClose) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("返回") }
            Column(Modifier.weight(1f)) {
                Text("ORBIS · 声音", style = MaterialTheme.typography.labelMedium)
                Text("语音台", style = MaterialTheme.typography.headlineSmall)
            }
            Text("原声", style = MaterialTheme.typography.labelLarge)
            Switch(!draft.enabled, { change(draft.copy(enabled = !it)); save() }, enabled = !settings.init)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .testTag("voice-mixer-fullscreen"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("TTS 生成声音 → 语音台调音 → 播放", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("全局音色 · 通话、语音条、朗读自动沿用", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            val ink = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(72.dp)) {
                // Decorative, not a fake measurement or running analyzer.
                for (i in 0..40) {
                    val x = size.width * (i + 1) / 42
                    val height = (0.1f + kotlin.math.abs(sin(i * 1.7f)) * .65f) * size.height / 2
                    drawLine(ink.copy(alpha = .55f), Offset(x, size.height/2-height), Offset(x, size.height/2+height), 3.dp.toPx(), StrokeCap.Round)
                }
            }
            Text(provider?.let { "当前 TTS：${it.name}" } ?: "尚未配置 TTS，请先在语音设置中选择语音服务。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = phrase, onValueChange = { phrase = it },
                label = { Text("试听文字") }, minLines = 2, maxLines = 4, enabled = !previewState.busy,
                modifier = Modifier.fillMaxWidth().testTag("voice-preview-text"))
            FilledTonalButton(modifier = Modifier.fillMaxWidth().testTag("voice-preview-start"),
                enabled = previewState.busy || (!settings.init && provider != null && !call.isActive && phrase.isNotBlank()),
                onClick = { if (previewState.busy) preview.stop() else { notice = null; preview.start(phrase) } }) {
                Text(when (previewState.stage) {
                    VoicePreviewStage.REQUESTING -> "正在请求 TTS · 取消"
                    VoicePreviewStage.PLAYING -> "停止试听"
                    else -> "▷ 请求 TTS 试听"
                })
            }
            Text("每次试听直接请求当前 TTS，按服务商规则计费。本轮声音使用开始生成时的设置；修改从下次生成或播放生效。",
                style = MaterialTheme.typography.bodySmall)
            previewState.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (call.isActive) Text("设置会用于下一轮回复；通话期间不另开试听。", style = MaterialTheme.typography.bodySmall)
            MixerSlider("语速", "%.2f×".format(draft.mix.speed), "说话快慢，从下一轮语音生效", draft.mix.speed, .5f..2f,
                { change(draft.copy(enabled = true, mix = draft.mix.copy(speed = it))) }, { save() })
            MixerSlider("音高", "%.1f 半音".format(draft.mix.semitones), "声音高低，从下一轮语音生效", draft.mix.semitones, -12f..12f,
                { change(draft.copy(enabled = true, mix = draft.mix.copy(semitones = it))) }, { save() })
            MixerSlider("音量", "${(draft.mix.volume * 100).toInt()}%", "只调整播放音量，不改变手机系统音量", draft.mix.volume, 0f..1f,
                { change(draft.copy(enabled = true, mix = draft.mix.copy(volume = it))) }, { save() })
            HorizontalDivider()
            Text("声音在什么地方", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceSpace.entries.forEach { space ->
                    FilterChip(selected = draft.mix.space == space,
                        onClick = { change(draft.copy(enabled = true, mix = draft.mix.copy(space = space))); save() },
                        label = { Text(space.label()) }, modifier = Modifier.testTag("mixer-space-${space.name}"))
                }
            }
            MixerSlider("距离", when {
                draft.mix.distance < .15f -> "贴近"
                draft.mix.distance < .4f -> "很近"
                draft.mix.distance < .65f -> "中等"
                draft.mix.distance < .85f -> "有点远"
                else -> "远处"
            }, "越远声音越轻，高频越柔和", draft.mix.distance, 0f..1f,
                { change(draft.copy(enabled = true, mix = draft.mix.copy(distance = it))) }, { save() })
            MixerSlider("空间回响", "${(draft.mix.reverb * 100).toInt()}%", "选择环境后生效；左右反射不同，戴耳机更明显",
                draft.mix.reverb, 0f..1f,
                { change(draft.copy(enabled = true, mix = draft.mix.copy(reverb = it))) }, { save() })
            Text("声音滤镜", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceFilter.entries.forEach { filter ->
                    FilterChip(selected = draft.mix.filter == filter,
                        onClick = { change(draft.copy(enabled = true, mix = draft.mix.copy(filter = filter))); save() },
                        label = { Text(filter.label()) }, modifier = Modifier.testTag("mixer-filter-${filter.name}"))
                }
            }
            HorizontalDivider()
            Text("本机预设", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(VoicePreset("原声", VoiceMix()), VoicePreset("低柔", VoiceMix(.95f, -2f)), VoicePreset("轻亮", VoiceMix(1.05f, 2f)),
                    VoicePreset("枕边小屋", VoiceMix(.95f, -1f, space = VoiceSpace.SMALL_ROOM, reverb = .18f, filter = VoiceFilter.SOFT)),
                    VoicePreset("旧时电台", VoiceMix(space = VoiceSpace.SMALL_ROOM, reverb = .12f, filter = VoiceFilter.RADIO))) + draft.presets).forEach { preset ->
                    FilterChip(selected = if (preset.name == "原声") !draft.enabled else draft.enabled && draft.mix == preset.mix,
                        onClick = { change(draft.copy(enabled = preset.name != "原声", mix = preset.mix)); save() }, label = { Text(preset.name) })
                }
            }
            if (draft.presets.isNotEmpty()) {
                Text("整理我的预设", style = MaterialTheme.typography.labelLarge)
                draft.presets.forEach { preset ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.name, Modifier.weight(1f))
                        TextButton(onClick = { change(draft.copy(presets = draft.presets.filterNot { it.name == preset.name })); save() }) { Text("移除") }
                    }
                }
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("用于朗读、语音气泡与通话。原声开关不会更换模型、音色或服务。", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            CallAcousticControls(store)
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { naming = true }, modifier = Modifier.weight(1f), enabled = !settings.init) { Text("保存预设") }
            OutlinedButton(onClick = { change(draft.copy(enabled = false, mix = VoiceMix())); save() }, modifier = Modifier.weight(1f)) { Text("恢复原声") }
        }
        if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text("预设名称") }, text = {
            OutlinedTextField(presetName, { presetName = it.filterNot(Char::isISOControl).take(40) }, singleLine = true)
        }, confirmButton = { TextButton(enabled = presetName.trim().isNotEmpty() && presetName.trim() !in setOf("原声", "低柔", "轻亮", "枕边小屋", "旧时电台"), onClick = {
            val name = presetName.trim()
            val next = draft.copy(presets = draft.presets.filterNot { it.name == name } + VoicePreset(name, draft.mix))
            if (next.presets.size <= 100) { change(next); save(); naming = false; presetName = "" }
            else notice = "预设较多，请先整理已有预设。"
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } })
    }
}

private fun VoiceSpace.label(): String = when (this) {
    VoiceSpace.NONE -> "不加环境"
    VoiceSpace.SMALL_ROOM -> "小房间"
    VoiceSpace.LARGE_ROOM -> "大房间"
    VoiceSpace.HALL -> "大厅"
    VoiceSpace.TUNNEL -> "隧道"
    VoiceSpace.OPEN_AIR -> "空旷户外"
    VoiceSpace.FOREST -> "树林"
}

private fun VoiceFilter.label(): String = when (this) {
    VoiceFilter.NONE -> "不加滤镜"
    VoiceFilter.TELEPHONE -> "电话"
    VoiceFilter.RADIO -> "老电台"
    VoiceFilter.ROBOT -> "机器人"
    VoiceFilter.SOFT -> "柔和"
}

@Composable
private fun CallAcousticControls(store: SettingsStore) {
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    fun change(transform: (me.rerere.rikkahub.data.orbis.voice.CallAcousticConfig) -> me.rerere.rikkahub.data.orbis.voice.CallAcousticConfig) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { store.update { it.copy(callAcoustics = transform(it.callAcoustics)) } }.getOrDefault(false) }
            if (ok) OrbisVoiceCallRuntime.get(context).resetAcousticWindow()
            note = if (ok) null else "未保存，保留原来的通话设置。"
        }
    }
    val cfg = settings.callAcoustics
    Text("通话声学提示", style = MaterialTheme.typography.titleMedium)
    Text("可选功能：仅在本机测量有效收音，不保存录音。提示随你下一条真实发言交给本次通话的 AI，不另外唤醒或打断通话；不是情绪判断。", style = MaterialTheme.typography.bodySmall)
    @Composable fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f)); Switch(value, set, enabled = enabled && !settings.init)
        }
    }
    toggle("启用声学提示", cfg.enabled) { change { old -> old.copy(enabled = it) } }
    if (cfg.enabled) {
        toggle("持续未发声", cfg.silence) { change { old -> old.copy(silence = it) } }
        toggle("相对音量变化", cfg.volume) { change { old -> old.copy(volume = it) } }
        toggle("粗估音高变化 · 实验性", cfg.pitch) { change { old -> old.copy(pitch = it) } }
        Text("静音、AI 播放、识别重连时暂停检测；音量和音高需先积累本次通话基线，不能测量时不猜。每次通话最多附带 5 次提示。", style = MaterialTheme.typography.bodySmall)
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun MixerSlider(name: String, valueText: String, help: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit, finish: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(name, style = MaterialTheme.typography.titleMedium); Text(valueText, style = MaterialTheme.typography.titleMedium)
        }
        Slider(value = value, onValueChange = change, onValueChangeFinished = finish, valueRange = range, modifier = Modifier.testTag("mixer-$name"))
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
