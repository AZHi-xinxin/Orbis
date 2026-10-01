package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.voiceNoteDurationMs
import me.rerere.rikkahub.data.model.voiceNoteTranscript
import me.rerere.rikkahub.data.model.voiceNotePlaybackKey
import me.rerere.rikkahub.data.model.voiceNotePlayed
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File
import java.security.MessageDigest

/** One playback at a time, no auto-play, and no remote URL fetching during rendering. */
private object VoiceNotePlayback {
    val active = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    fun stop(key: String? = null) {
        if (key != null && active.value != key) return
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        active.value = null
        val manager = audioManager
        val focus = focusRequest
        audioManager = null
        focusRequest = null
        if (manager != null && focus != null) runCatching { manager.abandonAudioFocusRequest(focus) }
    }
    fun play(context: Context, key: String, url: String, started: () -> Unit, failed: () -> Unit) {
        stop()
        try {
            val uri = url.toUri()
            if (uri.scheme != "file" || !uri.authority.isNullOrEmpty()) { failed(); return }
            val file = File(uri.path ?: "").canonicalFile
            val root = File(context.filesDir, "upload").canonicalFile
            if (!file.toPath().startsWith(root.toPath()) || !file.isFile) { failed(); return }
            val next = MediaPlayer()
            player = next
            active.value = key
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val manager = context.getSystemService(AudioManager::class.java)
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
                    if (change <= AudioManager.AUDIOFOCUS_LOSS && player === next) stop(key)
                }, Handler(Looper.getMainLooper())).build()
            audioManager = manager
            focusRequest = focus
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            next.setAudioAttributes(attributes)
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (player === it) {
                    runCatching { it.start(); started() }.onFailure { stop(key); failed() }
                }
            }
            next.setOnCompletionListener { if (player === it) stop(key) }
            next.setOnErrorListener { _, _, _ -> if (player === next) { stop(key); failed() }; true }
            next.prepareAsync()
        } catch (_: Exception) { stop(key); failed() }
    }
}

/** Shared main/drawer display path; rows are not database messages or provider requests. */
@Composable
internal fun OrbisVoiceNoteRows(
    message: UIMessage,
    rows: List<OrbisVoiceNoteDisplayItem>,
    model: Model?,
    assistant: Assistant?,
    loading: Boolean,
    segmentedReply: Boolean,
    onPlayed: ((OrbisVoiceNoteDisplayItem.Voice) -> Unit)? = null,
    renderParts: @Composable (List<UIMessagePart>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            when (row) {
                is OrbisVoiceNoteDisplayItem.Content -> key("voice-prose", row.startIndex) {
                    Column(Modifier.testTag("orbis-voice-prose-row")) {
                        if (BuildConfig.ORBIS_ENABLED) {
                            OrbisChatMessageLayout(message.copy(parts = row.parts), model, assistant, loading, segmentedReply) {
                                renderParts(row.parts)
                            }
                        } else renderParts(row.parts)
                    }
                }
                is OrbisVoiceNoteDisplayItem.Voice -> key("voice-note", row.occurrenceKey) {
                    val voiceContent = @Composable {
                        OrbisVoiceNoteBubble(row.audio, message.id.toString(), row.occurrenceKey, row.details,
                            onPlayed = onPlayed?.let { callback -> { callback(row) } })
                    }
                    Column(Modifier.testTag("orbis-voice-independent-row")) {
                        if (BuildConfig.ORBIS_ENABLED) {
                            // The native voice Surface is already a bubble. Never wrap it in a prose bubble.
                            OrbisChatMessageLayout(message.copy(parts = listOf(row.audio)), model, assistant, loading,
                                segmentedReply = true, content = voiceContent)
                        } else voiceContent()
                    }
                }
            }
        }
    }
}

@Composable
internal fun OrbisVoiceNoteBubble(
    audio: UIMessagePart.Audio,
    messageKey: String,
    occurrenceKey: String = "",
    details: List<UIMessagePart> = emptyList(),
    onPlayed: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val key = remember(audio.metadata, messageKey, occurrenceKey) { audio.voiceNotePlaybackKey(messageKey, occurrenceKey) }
    // Read the previous URL-based key for an existing installation. New state uses persistent identity.
    val legacyKey = remember(audio.url, messageKey) {
        MessageDigest.getInstance("SHA-256").digest("$messageKey|${audio.url}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val preferences = remember(context) { context.getSharedPreferences("orbis_voice_note_played", Context.MODE_PRIVATE) }
    var heard by remember(key) { mutableStateOf(audio.voiceNotePlayed() ||
        preferences.getBoolean(key, false) || preferences.getBoolean(legacyKey, false)) }
    LaunchedEffect(key, audio.voiceNotePlayed()) { if (audio.voiceNotePlayed()) heard = true }
    val currentOnPlayed by rememberUpdatedState(onPlayed)
    var menu by remember(key) { mutableStateOf(false) }
    var detailsVisible by remember(key) { mutableStateOf(false) }
    var transcriptVisible by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    val active by VoiceNotePlayback.active.collectAsState()
    val duration = audio.voiceNoteDurationMs()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(key) { onDispose { VoiceNotePlayback.stop(key) } }
    DisposableEffect(key, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) VoiceNotePlayback.stop(key)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.testTag("orbis-voice-note"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.widthIn(min = 128.dp, max = 280.dp).combinedClickable(
                    onClick = {
                        if (active == key) VoiceNotePlayback.stop(key) else {
                            error = null
                            VoiceNotePlayback.play(context, key, audio.url,
                                started = {
                                    heard = true
                                    preferences.edit().putBoolean(key, true).apply()
                                    currentOnPlayed?.invoke()
                                },
                                failed = { error = "暂时无法播放，请检查本机语音文件是否仍在。" })
                        }
                    }, onLongClick = { menu = true },
                    onClickLabel = if (active == key) "停止语音条" else "播放语音条",
                    onLongClickLabel = "语音条菜单",
                ).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (active == key) "Ⅱ" else "▷", style = MaterialTheme.typography.titleLarge)
                    Text(if (active == key) "播放中" else "▂ ▄ ▆ ▃ ▅", style = MaterialTheme.typography.bodyMedium)
                    Text(if (duration > 0) "${(duration + 999) / 1000}″" else "语音")
                    if (!heard) Text("●", Modifier.testTag("orbis-voice-note-unheard"),
                        color = Color(0xffd84c55), style = MaterialTheme.typography.labelSmall)
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (transcriptVisible) "收起文字" else "转文字") },
                    onClick = { menu = false; transcriptVisible = !transcriptVisible })
                if (details.isNotEmpty()) DropdownMenuItem(text = { Text("生成详情") },
                    onClick = { menu = false; detailsVisible = true })
            }
        }
        if (transcriptVisible) Text(audio.voiceNoteTranscript().ifBlank { "这条语音没有保存转写文字。" },
            style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
    if (detailsVisible) {
        AlertDialog(
            onDismissRequest = { detailsVisible = false },
            title = { Text("语音条生成详情") },
            text = {
                // An explicit, read-only view of the original records; never a second tool execution.
                SelectionContainer {
                    Text(remember(details) { JsonInstantPretty.encodeToString(ListSerializer(UIMessagePart.serializer()), details) },
                        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            .testTag("orbis-voice-note-details"), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { detailsVisible = false }) { Text("关闭") } },
        )
    }
}
