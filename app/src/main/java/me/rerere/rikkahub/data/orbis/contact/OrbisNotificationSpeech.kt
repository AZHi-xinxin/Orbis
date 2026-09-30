package me.rerere.rikkahub.data.orbis.contact

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lover.connect.AlarmRingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.service.OrbisIncomingCallRuntime
import me.rerere.rikkahub.service.OrbisVoiceCallRuntime
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.PlaybackStatus
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

/** Constructed by the host tool factory, never exposed as model-controlled tool parameters. */
data class OrbisNotificationSpeechScope(val assistantId: String, val conversationId: String)

@Serializable
data class OrbisNotificationSpeechReceipt(
    /** played = player completed; neither that nor posting a notification proves the human heard it. */
    val status: String,
    val reasonCode: String,
    val playbackStarted: Boolean = false,
    val userHeardConfirmed: Boolean = false,
)

internal interface OrbisNotificationSpeechLease {
    val playbackStarted: Boolean
    suspend fun play(text: String)
    suspend fun close()
}

internal interface OrbisNotificationSpeechPort {
    fun skipReason(scope: OrbisNotificationSpeechScope): String?
    suspend fun acquire(scope: OrbisNotificationSpeechScope): OrbisNotificationSpeechLease?
}

internal data class OrbisNotificationSpeechConditions(
    val enabled: Boolean = false,
    val assistantAvailable: Boolean = true,
    val providerAvailable: Boolean = true,
    val allowLocked: Boolean = false,
    val deviceLocked: Boolean = false,
    val callActive: Boolean = false,
    val alarmActive: Boolean = false,
    val silent: Boolean = false,
    val doNotDisturb: Boolean = false,
    val priorityAudioActive: Boolean = false,
    val otherAudioActive: Boolean = false,
)

internal fun notificationSpeechSkipReason(state: OrbisNotificationSpeechConditions): String? = when {
    !state.enabled -> "disabled"
    !state.assistantAvailable -> "assistant_unavailable"
    !state.providerAvailable -> "provider_unavailable"
    state.deviceLocked && !state.allowLocked -> "device_locked"
    state.callActive -> "call_active"
    state.alarmActive -> "alarm_active"
    state.silent -> "silent_mode"
    state.doNotDisturb -> "do_not_disturb"
    state.priorityAudioActive -> "priority_audio_active"
    state.otherAudioActive -> "other_audio_active"
    else -> null
}

/**
 * A one-shot, already-posted notification reader. No notification listener, history scan,
 * persistent queue, automatic replay, model request, or invented per-assistant voice setting.
 * The caller must have just successfully posted this same text under its existing authorization.
 */
class OrbisNotificationSpeech internal constructor(
    private val port: OrbisNotificationSpeechPort,
    private val speaking: Mutex = activeSpeech,
    private val timeoutMillis: Long = 45_000,
) {
    suspend fun readNotification(text: String, assistantId: String, conversationId: String): OrbisNotificationSpeechReceipt {
        val scope = OrbisNotificationSpeechScope(assistantId, conversationId)
        if (runCatching { Uuid.parse(assistantId); Uuid.parse(conversationId) }.isFailure) return skipped("invalid_host_scope")
        if (text.isBlank()) return skipped("empty_text")
        // Do not truncate a long message and then claim all of it was spoken.
        if (text.length > MAX_TEXT_CHARS) return skipped("text_too_long")
        if (!speaking.tryLock()) return skipped("speech_busy")
        var lease: OrbisNotificationSpeechLease? = null
        return try {
            port.skipReason(scope)?.let { return skipped(it) }
            withTimeoutOrNull(timeoutMillis) {
                // A fast acquire captures settings and creates a lazy lease, without requesting
                // focus or synthesizing. Finally must see it even if the dispatcher hop is cancelled.
                lease = withContext(NonCancellable) { port.acquire(scope) }
                    ?: return@withTimeoutOrNull skipped("audio_focus_denied")
                currentCoroutineContext().ensureActive()
                // Permission, setting and device state can change while creating the lease.
                port.skipReason(scope)?.let { return@withTimeoutOrNull skipped(it) }
                lease!!.play(text)
                OrbisNotificationSpeechReceipt("played", "playback_completed", playbackStarted = lease!!.playbackStarted)
            } ?: OrbisNotificationSpeechReceipt("failed", "speech_timeout", lease?.playbackStarted == true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: NotificationSpeechInterrupted) {
            OrbisNotificationSpeechReceipt("skipped", interrupted.code, lease?.playbackStarted == true)
        } catch (_: Exception) {
            // Provider errors may contain request text, credentials, or URLs; none leave this boundary.
            OrbisNotificationSpeechReceipt("failed", "speech_failed", lease?.playbackStarted == true)
        } finally {
            try { withContext(NonCancellable) { try { lease?.close() } catch (_: Exception) { /* No private platform error escapes. */ } } }
            finally { speaking.unlock() }
        }
    }

    companion object {
        const val MAX_TEXT_CHARS = 1_000
        private val activeSpeech = Mutex()
        @Volatile private var instance: OrbisNotificationSpeech? = null
        fun get(context: Context): OrbisNotificationSpeech = instance ?: synchronized(this) {
            instance ?: OrbisNotificationSpeech(AndroidNotificationSpeechPort(
                context.applicationContext, getKoin().get(), getKoin().get(),
            )).also { instance = it }
        }
        private fun skipped(reason: String) = OrbisNotificationSpeechReceipt("skipped", reason)
    }
}

/** Explicit mapping also tested on Android without requesting focus or playing any sound. */
internal fun notificationSpeechFocusChange(change: Int): NotificationSpeechFocusChange? = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> NotificationSpeechFocusChange.GAIN
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> NotificationSpeechFocusChange.TRANSIENT_LOSS
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> NotificationSpeechFocusChange.DUCK
    AudioManager.AUDIOFOCUS_LOSS -> NotificationSpeechFocusChange.LOSS
    else -> null
}

internal fun notificationSpeechFocusRequest(onChange: (NotificationSpeechFocusChange) -> Unit): AudioFocusRequest =
    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(true)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> notificationSpeechFocusChange(change)?.let(onChange) },
            Handler(Looper.getMainLooper()))
        .build()

private class AndroidNotificationSpeechPort(
    private val context: Context,
    private val settings: SettingsStore,
    private val tts: TTSManager,
) : OrbisNotificationSpeechPort {
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    override fun skipReason(scope: OrbisNotificationSpeechScope): String? = blocked(scope, ownPlaying = false)

    private fun blocked(scope: OrbisNotificationSpeechScope, ownPlaying: Boolean): String? {
        val live = settings.settingsFlow.value
        if (live.init || !live.orbisContact.notificationAutoRead) return "disabled"
        val notification = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Public configurations report active players. Notification pings are not competing speech.
        val playback = audio.activePlaybackConfigurations
        val media = playback.count { it.audioAttributes.usage in setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANT, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioAttributes.USAGE_UNKNOWN) }
        return notificationSpeechSkipReason(OrbisNotificationSpeechConditions(
            enabled = true,
            assistantAvailable = live.getAssistantById(Uuid.parse(scope.assistantId)) != null,
            providerAvailable = live.getSelectedTTSProvider() != null,
            allowLocked = live.orbisContact.notificationReadWhenLocked,
            deviceLocked = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked,
            callActive = audio.mode != AudioManager.MODE_NORMAL || OrbisVoiceCallRuntime.get(context).callState.value.isActive ||
                OrbisIncomingCallRuntime.get(context).state.value?.attempt?.outcome in setOf(IncomingCallOutcome.RINGING, IncomingCallOutcome.CONNECTING),
            alarmActive = AlarmRingService.activeAlarmId != null,
            silent = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0,
            doNotDisturb = notification.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
            priorityAudioActive = playback.any { it.audioAttributes.usage in setOf(AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE, AudioAttributes.USAGE_VOICE_COMMUNICATION) },
            otherAudioActive = media > (if (ownPlaying) 1 else 0) || (!ownPlaying && audio.isMusicActive),
        ))
    }

    override suspend fun acquire(scope: OrbisNotificationSpeechScope): OrbisNotificationSpeechLease? = withContext(Dispatchers.Main.immediate) {
        val snapshot = settings.settingsFlow.value
        val provider = snapshot.getSelectedTTSProvider() ?: return@withContext null
        val speed = snapshot.defaultTTSPlaybackSpeed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2.0f) ?: 1.0f
        val session = NotificationSpeechPlaybackSession(object : NotificationSpeechPlaybackPort<TTSResponse> {
            private var player: AudioPlayer? = null
            private var focus: AudioFocusRequest? = null

            override fun blockedReason(): String? {
                val live = settings.settingsFlow.value
                if (live.getSelectedTTSProvider() != provider || live.defaultTTSPlaybackSpeed != snapshot.defaultTTSPlaybackSpeed)
                    return "voice_setting_changed"
                // Keep the existing owned-player allowance: Android's public playback snapshot
                // can lag a pause/end callback. Focus must independently permit every resume.
                return blocked(scope, ownPlaying = player != null)
            }

            override suspend fun synthesize(text: String): TTSResponse = synthesizeBounded(provider, text)

            override fun requestFocus(onChange: (NotificationSpeechFocusChange) -> Unit): NotificationSpeechFocusResult {
                val request = notificationSpeechFocusRequest(onChange)
                focus = request
                return when (audio.requestAudioFocus(request)) {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> NotificationSpeechFocusResult.GRANTED
                    AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> NotificationSpeechFocusResult.DELAYED
                    else -> NotificationSpeechFocusResult.DENIED
                }
            }

            override fun abandonFocus() {
                val request = focus ?: return
                focus = null
                audio.abandonAudioFocusRequest(request)
            }

            override suspend fun play(audio: TTSResponse, onStarted: () -> Unit) = coroutineScope {
                val audioPlayer = AudioPlayer(context, audioUsage = AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .also { player = it; it.setSpeed(speed) }
                val observe = launch {
                    audioPlayer.playbackState.collect { if (it.status == PlaybackStatus.Playing) onStarted() }
                }
                try { audioPlayer.play(audio) } finally { observe.cancelAndJoin() }
            }

            override fun pause() { player?.pause() }
            override fun resume() { player?.resume() }

            override fun disposePlayer() {
                player?.let {
                    runCatching { it.stop() }; runCatching { it.clear() }; runCatching { it.release() }
                }
                player = null
            }
        }, nowMillis = SystemClock::elapsedRealtime)
        object : OrbisNotificationSpeechLease {
            override val playbackStarted: Boolean get() = session.playbackStarted
            override suspend fun play(text: String) = withContext(Dispatchers.Main.immediate) { session.play(text) }
            override suspend fun close() = withContext(Dispatchers.Main.immediate) {
                session.close()
                Unit
            }
        }
    }

    private suspend fun synthesizeBounded(provider: TTSProviderSetting, text: String): TTSResponse = withContext(Dispatchers.IO) {
        var format: AudioFormat? = null
        var sampleRate: Int? = null
        val output = ByteArrayOutputStream()
        tts.generateSpeech(provider, TTSRequest(text)).collect { chunk ->
            check(output.size().toLong() + chunk.data.size <= 8L * 1024 * 1024) { "speech_audio_limit" }
            check(format == null || format == chunk.format) { "speech_format_changed" }
            check(sampleRate == null || chunk.sampleRate == null || sampleRate == chunk.sampleRate) { "speech_rate_changed" }
            format = chunk.format
            if (sampleRate == null) sampleRate = chunk.sampleRate
            output.write(chunk.data)
        }
        check(output.size() > 0 && format != null) { "speech_audio_empty" }
        TTSResponse(output.toByteArray(), checkNotNull(format), sampleRate)
    }
}
