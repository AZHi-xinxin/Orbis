package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ElevenLabsTTSProvider : TTSProvider<TTSProviderSetting.ElevenLabs> {
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.ElevenLabs,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val httpRequest = elevenLabsSpeechRequest(providerSetting, request.text)
        val audioData = httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw TTSProviderException(
                message = "ElevenLabs 朗读失败：HTTP ${response.code}。请检查模型、Voice ID、API Key 权限和账户额度。",
                statusCode = response.code,
            )
            response.body.bytes()
        }

        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                isLast = true,
                metadata = mapOf(
                    "provider" to "elevenlabs",
                    "model" to providerSetting.model,
                    "voiceId" to providerSetting.voiceId
                )
            )
        )
    }
}
