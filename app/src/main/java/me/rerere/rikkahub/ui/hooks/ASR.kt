package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRCorrectionResult
import me.rerere.asr.ASRCorrectionNotice
import me.rerere.asr.ASRTermCorrectionSettings
import me.rerere.asr.correctDeviceAsrTranscript
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

@Composable
fun rememberCustomAsrState(): CustomAsrState {
    val context = LocalContext.current
    val settingsStore = koinInject<SettingsStore>()
    val httpClient = koinInject<OkHttpClient>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    val asrState = remember {
        CustomAsrStateImpl(context.applicationContext, httpClient)
    }

    DisposableEffect(settings.selectedASRProviderId, settings.asrProviders) {
        asrState.updateProvider(settings.getSelectedASRProvider())
        onDispose { }
    }

    DisposableEffect(settings.asrCorrections) {
        asrState.corrections = settings.asrCorrections
        onDispose { }
    }

    DisposableEffect(asrState) {
        onDispose {
            asrState.cleanup()
        }
    }

    return asrState
}

interface CustomAsrState {
    val state: StateFlow<ASRState>
    val correctionReview: StateFlow<ASRCorrectionResult?>
    val correctionNotice: StateFlow<ASRCorrectionNotice>
    fun dismissCorrectionNotice(eventId: Long)
    fun start(onTranscriptChange: (String) -> Unit)
    /** Same ASR capture, optional local audio retention; PCM callback is on the IO thread. */
    fun startVoiceNote(onTranscriptChange: (String) -> Unit, onPcm: (ByteArray, Int) -> Unit): Boolean
    fun stop()
    fun cleanup()
}

private class CustomAsrStateImpl(
    private val context: Context,
    private val httpClient: OkHttpClient
) : CustomAsrState {
    private var controller: ASRController? = null
    private val idleState = MutableStateFlow(ASRState())
    var corrections = ASRTermCorrectionSettings()
    override val correctionReview = MutableStateFlow<ASRCorrectionResult?>(null)
    override val correctionNotice = MutableStateFlow(ASRCorrectionNotice())
    private var recognitionEpoch = 0L

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .build()

    override val state: StateFlow<ASRState>
        get() = controller?.state ?: idleState

    fun updateProvider(provider: ASRProviderSetting?) {
        recognitionEpoch++
        controller?.dispose()
        controller = provider?.let { createController(it) }
        if (controller == null) {
            idleState.value = ASRState()
        }
    }

    override fun start(onTranscriptChange: (String) -> Unit) {
        startCapture(onTranscriptChange, null)
    }

    override fun startVoiceNote(onTranscriptChange: (String) -> Unit, onPcm: (ByteArray, Int) -> Unit): Boolean =
        startCapture(onTranscriptChange, onPcm)

    private fun startCapture(onTranscriptChange: (String) -> Unit, onPcm: ((ByteArray, Int) -> Unit)?): Boolean {
        val activeController = controller ?: return false
        if (activeController.state.value.isRecording) return false
        val epoch = ++recognitionEpoch
        val currentCorrections = corrections
        correctionReview.value = null
        correctionNotice.update { it.begin(epoch) }
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            activeController.setPcmObserver(onPcm)
            try {
                activeController.start { original ->
                    if (epoch != recognitionEpoch || controller !== activeController) return@start
                    val corrected = correctDeviceAsrTranscript(original, currentCorrections)
                    correctionReview.value = corrected
                    correctionNotice.update { it.accept(epoch, corrected) }
                    onTranscriptChange(corrected.corrected)
                }
                if (!activeController.state.value.isRecording) {
                    activeController.setPcmObserver(null)
                    audioManager.abandonAudioFocusRequest(audioFocusRequest)
                    return false
                }
            } catch (error: Exception) {
                activeController.setPcmObserver(null)
                audioManager.abandonAudioFocusRequest(audioFocusRequest)
                throw error
            }
            return true
        } else {
            activeController.setPcmObserver(null)
            return false
        }
    }

    override fun stop() {
        controller?.setPcmObserver(null)
        controller?.stop()
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    override fun dismissCorrectionNotice(eventId: Long) {
        correctionNotice.update { it.dismiss(eventId) }
    }

    override fun cleanup() {
        recognitionEpoch++
        controller?.dispose()
        controller = null
        correctionReview.value = null
        correctionNotice.value = ASRCorrectionNotice(eventId = recognitionEpoch)
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun createController(provider: ASRProviderSetting): ASRController? {
        return when (provider) {
            is ASRProviderSetting.OpenAIRealtime -> {
                if (provider.apiKey.isBlank()) return null
                OpenAIRealtimeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.DashScope -> {
                if (provider.apiKey.isBlank()) return null
                DashScopeASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Volcengine -> {
                if (provider.apiKey.isBlank()) return null
                VolcengineASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.MiMo -> {
                if (provider.apiKey.isBlank()) return null
                MiMoASRController(context, httpClient, provider)
            }

            is ASRProviderSetting.Step -> {
                if (provider.apiKey.isBlank()) return null
                StepASRController(context, httpClient, provider)
            }
        }
    }
}
