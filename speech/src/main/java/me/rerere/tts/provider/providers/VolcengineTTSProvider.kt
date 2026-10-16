package me.rerere.tts.provider.providers

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.*
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/** V3 HTTP SSE. Transport streams; the existing player still buffers each text segment. */
internal fun volcengineTtsRequest(setting: TTSProviderSetting.Volcengine, text: String): Request {
    require(text.isNotBlank()) { "朗读文字不能为空" }
    require(setting.resourceId in setOf("seed-tts-2.0", "seed-icl-2.0")) { "请选择豆包 2.0 资源" }
    require(setting.speaker.isNotBlank()) { "请填写已开通的音色 ID" }
    require(setting.format in setOf("pcm", "mp3") && setting.sampleRate in setOf(8000,16000,22050,24000,32000,44100,48000))
    val body = buildJsonObject {
        put("user", buildJsonObject { put("uid", "orbis") })
        put("req_params", buildJsonObject {
            put("text", text)
            put("speaker", setting.speaker.trim())
            put("audio_params", buildJsonObject {
                put("format", setting.format); put("sample_rate", setting.sampleRate)
            })
        })
    }
    val request = Request.Builder()
        .url(setting.baseUrl.trim().trimEnd('/') + "/api/v3/tts/unidirectional/sse")
        .header("X-Api-Resource-Id", setting.resourceId)
        .header("X-Api-Request-Id", UUID.randomUUID().toString())
        .header("Accept", "text/event-stream")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
    require(request.build().url.isHttps) { "语音服务必须使用 HTTPS，避免明文发送密钥" }
    if (setting.legacyAuth) {
        require(setting.appId.isNotBlank() && setting.accessToken.isNotBlank()) { "请填写 App ID 和 Access Token" }
        request.header("X-Api-App-Id", setting.appId.trim()).header("X-Api-Access-Key", setting.accessToken.trim())
    } else {
        require(setting.apiKey.isNotBlank()) { "请填写语音控制台 API Key" }
        request.header("X-Api-Key", setting.apiKey.trim())
    }
    return request.build()
}

internal class VolcengineTtsDecoder(private val setting: TTSProviderSetting.Volcengine) {
    var finished = false
        private set
    private var audioBytes = 0L
    fun decode(data: String): AudioChunk? {
        check(!finished) { "语音流已结束" }
        require(data.length <= 4 * 1024 * 1024) { "语音分片过大" }
        val obj = try { Json.parseToJsonElement(data).jsonObject } catch (_: Exception) {
            throw IllegalStateException("豆包语音返回格式无效")
        }
        val code = (obj["code"] as? JsonPrimitive)?.intOrNull
        check(code == 0 || code == 20000000) { "豆包语音未完成（错误码 ${code ?: "未知"}），请检查资源、音色权限和额度" }
        val encoded = (obj["data"] as? JsonPrimitive)?.contentOrNull
        val bytes = try { if (encoded.isNullOrEmpty()) byteArrayOf() else Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw IllegalStateException("豆包语音分片编码无效") }
        audioBytes += bytes.size
        check(audioBytes <= 32 * 1024 * 1024) { "单段语音过大，请缩短文字" }
        if (code == 20000000) {
            check(audioBytes > 0) { "豆包语音没有返回音频" }
            if (setting.format == "pcm") check(audioBytes % 2 == 0L) { "豆包语音音频未完整结束" }
            finished = true
        }
        if (bytes.isEmpty() && !finished) return null
        return AudioChunk(bytes, if (setting.format == "pcm") AudioFormat.PCM else AudioFormat.MP3,
            sampleRate = setting.sampleRate, isLast = finished, metadata = mapOf("provider" to "volcengine"))
    }
}

class VolcengineTTSProvider : TTSProvider<TTSProviderSetting.Volcengine> {
    private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    override fun generateSpeech(context: Context, providerSetting: TTSProviderSetting.Volcengine,
        request: TTSRequest): Flow<AudioChunk> = callbackFlow {
        val decoder = VolcengineTtsDecoder(providerSetting)
        val source = EventSources.createFactory(client).newEventSource(volcengineTtsRequest(providerSetting, request.text),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    try {
                        // Backpressure, never silently drop audio when the consumer is slower.
                        decoder.decode(data)?.let { if (trySendBlocking(it).isFailure) eventSource.cancel() }
                        if (decoder.finished) { close(); eventSource.cancel() }
                    } catch (error: Exception) { close(error); eventSource.cancel() }
                }
                override fun onClosed(eventSource: EventSource) {
                    if (!decoder.finished) close(IllegalStateException("豆包语音提前断开，未收到完成标记"))
                }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (decoder.finished) return
                    // Never surface server bodies or request headers containing private material.
                    val code = response?.code
                    close(if (code != null && code !in 200..299) TTSProviderException("豆包语音请求失败：HTTP $code", code)
                        else IllegalStateException("豆包语音连接中断，请检查网络后重试"))
                }
            })
        awaitClose { source.cancel() }
    }
}
