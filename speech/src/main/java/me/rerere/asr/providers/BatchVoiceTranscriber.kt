package me.rerere.asr.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.BatchVoiceRecognitionException
import me.rerere.common.http.awaitAndUse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Dedicated call transport. A failed/cancelled segment is never automatically replayed. */
internal class BatchVoiceTranscriber(client: OkHttpClient, private val provider: ASRProviderSetting) {
    private val http = client.newBuilder()
        .callTimeout(18, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    suspend fun transcribe(pcm: ByteArray): String {
        val request = batchVoiceRequest(provider, pcm)
        return http.newCall(request).awaitAndUse { response ->
            if (!response.isSuccessful) throw BatchVoiceRecognitionException(
                "语音识别服务返回 HTTP ${response.code}；本句未提交，请检查配置或稍后重试。[ASR_BATCH_HTTP]")
            when (provider) {
                is ASRProviderSetting.MiMo -> {
                    val source = response.body.source()
                    if (source.request(MAX_RESPONSE_BYTES + 1)) invalidResponse()
                    parseMiMoVoiceResponse(source.readUtf8())
                }
                is ASRProviderSetting.Step -> parseStepVoiceResponse(response.body.source())
                else -> error("Not a batch voice provider")
            }
        }
    }
}

internal fun batchVoiceRequest(provider: ASRProviderSetting, pcm: ByteArray): Request {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0 && pcm.size <= 1_440_000)
    val builder = Request.Builder()
    val body = when (provider) {
        is ASRProviderSetting.MiMo -> {
            builder.url("${provider.baseUrl.trim().trimEnd('/')}/chat/completions")
            if (provider.apiKey.isNotBlank()) builder.header("api-key", provider.apiKey)
            buildJsonObject {
                put("model", provider.model)
                put("stream", false)
                put("messages", buildJsonArray { add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray { add(buildJsonObject {
                        put("type", "input_audio")
                        put("input_audio", buildJsonObject {
                            put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(pcmVoiceWav(pcm, provider.sampleRate)))
                        })
                    }) })
                }) })
                if (provider.language.isNotBlank()) put("asr_options", buildJsonObject { put("language", provider.language) })
            }
        }
        is ASRProviderSetting.Step -> {
            builder.url("${provider.baseUrl.trim().trimEnd('/')}/v1/audio/asr/sse")
            if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${provider.apiKey}")
            builder.header("Accept", "text/event-stream")
            buildJsonObject { put("audio", buildJsonObject {
                put("data", Base64.getEncoder().encodeToString(pcm))
                put("input", buildJsonObject {
                    put("transcription", buildJsonObject {
                        put("model", provider.model)
                        put("enable_itn", provider.enableItn)
                        put("enable_timestamp", provider.enableTimestamp)
                        if (provider.language.isNotBlank()) put("language", provider.language)
                        if (provider.hotwords.isNotEmpty()) put("hotwords", JsonArray(provider.hotwords.map(::JsonPrimitive)))
                    })
                    put("format", buildJsonObject {
                        put("type", "pcm"); put("codec", "pcm_s16le")
                        put("rate", provider.sampleRate); put("bits", 16); put("channel", 1)
                    })
                })
            }) }
        }
        else -> error("Not a batch voice provider")
    }
    return builder.post(body.toString().toRequestBody("application/json".toMediaType())).build()
}

private fun pcmVoiceWav(pcm: ByteArray, rate: Int): ByteArray = ByteBuffer.allocate(44 + pcm.size)
    .order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
        putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(pcm.size); put(pcm)
    }.array()

internal fun parseMiMoVoiceResponse(value: String): String {
    val root = parseObject(value)
    val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: invalidResponse()
    val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
    if (reason != null && reason != "stop") invalidResponse()
    val content = (choice["message"] as? JsonObject)?.get("content") as? JsonPrimitive ?: invalidResponse()
    if (!content.isString) invalidResponse()
    return content.content.trim()
}

internal fun parseStepVoiceResponse(source: BufferedSource): String {
    val transcript = StringBuilder()
    var eventType = ""
    val data = StringBuilder()
    var total = 0L
    fun dispatch(): Boolean {
        if (data.isEmpty()) { eventType = ""; return false }
        if (data.toString() == "[DONE]") invalidResponse() // Delta-only/truncated output is not a final.
        val event = parseObject(data.toString())
        val type = eventType.ifBlank { event.string("type").orEmpty() }
        when (type) {
            "transcript.text.delta" -> transcript.append(event.string("delta") ?: invalidResponse())
            "transcript.text.done" -> {
                val final = event.string("text") ?: invalidResponse()
                transcript.clear(); transcript.append(final)
                return true
            }
            "error" -> throw BatchVoiceRecognitionException(
                "语音识别服务未能完成本句；请检查模型设置后重试。[ASR_BATCH_PROVIDER]")
        }
        if (transcript.length > 64_000) invalidResponse()
        data.clear(); eventType = ""
        return false
    }
    while (!source.exhausted()) {
        val line = source.readUtf8LineStrict(65_536)
        total += line.length + 1
        if (total > MAX_RESPONSE_BYTES) invalidResponse()
        when {
            line.isEmpty() -> if (dispatch()) return transcript.toString().trim()
            line.startsWith("event:") -> eventType = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
            }
        }
    }
    if (dispatch()) return transcript.toString().trim()
    invalidResponse()
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
private fun parseObject(value: String): JsonObject =
    runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull() ?: invalidResponse()
private fun invalidResponse(): Nothing = throw BatchVoiceRecognitionException(
    "识别服务没有返回完整有效结果，本句未提交；请稍后重试。[ASR_BATCH_RESPONSE]")
private const val MAX_RESPONSE_BYTES = 1_048_576L
