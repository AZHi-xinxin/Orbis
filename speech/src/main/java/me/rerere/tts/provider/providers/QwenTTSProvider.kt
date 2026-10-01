package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class QwenTTSProvider : TTSProvider<TTSProviderSetting.Qwen> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Qwen,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        require(!providerSetting.model.startsWith("qwen3-tts")) {
            "旧版 Qwen3 TTS 模型已不再支持，请在 TTS 设置中改用 qwen-audio-3.0-tts-plus 或 qwen-audio-3.0-tts-flash"
        }
        require(!providerSetting.baseUrl.contains("{WorkspaceId}")) {
            "请在 Base URL 中将 {WorkspaceId} 替换为阿里云百炼业务空间 ID"
        }

        val requestBody = JSONObject().apply {
            put("model", providerSetting.model)
            put("input", JSONObject().apply {
                put("text", request.text)
                put("voice", providerSetting.voice)
                put("format", providerSetting.format)
                put("sample_rate", providerSetting.sampleRate)
            })
        }

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/services/audio/tts/SpeechSynthesizer")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "enable")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw TTSProviderException(
                    message = "Qwen TTS request failed: HTTP ${response.code}",
                    statusCode = response.code
                )
            }

            response.body.byteStream().bufferedReader().use { reader ->
                var currentData = StringBuilder()
                var complete = false
                suspend fun publish(payload: String) {
                    parseQwenAudioFrame(payload, providerSetting.format, providerSetting.sampleRate,
                        providerSetting.model, providerSetting.voice)?.let { chunk ->
                        check(!complete) { "Qwen 在完成标记后返回了额外音频。" }
                        if (chunk.isLast) complete = true
                        emit(chunk)
                    }
                }

                reader.lineSequence().forEach { line ->
                    when {
                        line.startsWith("data:") -> {
                            currentData.append(line.removePrefix("data:").trimStart())
                        }

                        line.isEmpty() && currentData.isNotEmpty() -> {
                            publish(currentData.toString())
                            currentData = StringBuilder()
                        }
                    }
                }

                // 兼容最后一个 SSE event 后没有空行、直接 EOF 的响应。
                if (currentData.isNotEmpty()) {
                    publish(currentData.toString())
                }
                check(complete) { "Qwen 未返回合成完成标记，本轮未完成。" }
            }
        }
    }

}
