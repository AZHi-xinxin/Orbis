package me.rerere.tts.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import me.rerere.tts.provider.TTSProviderException
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** IDs verified against the official model guide; persisted user selection is never rewritten. */
val ELEVENLABS_TTS_MODELS = listOf(
    "eleven_v4" to "Eleven v4",
    "eleven_multilingual_v2" to "Eleven Multilingual v2",
    "eleven_v3" to "Eleven v3",
    "eleven_flash_v2_5" to "Eleven Flash v2.5",
)

internal fun elevenLabsApiUrl(baseUrl: String, vararg path: String): HttpUrl {
    val base = baseUrl.trim().trimEnd('/').toHttpUrl()
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
        "ElevenLabs 地址不能含用户名、密码、查询参数或片段。"
    }
    val builder = base.newBuilder()
    if (base.pathSegments.lastOrNull()?.isNotEmpty() != true) builder.removePathSegment(base.pathSegments.lastIndex)
    if (builder.build().pathSegments.lastOrNull() != "v1") builder.addPathSegment("v1")
    path.forEach { builder.addPathSegment(it) }
    return builder.build()
}

internal fun elevenLabsSpeechRequest(setting: TTSProviderSetting.ElevenLabs, text: String): Request {
    val model = setting.model.trim()
    require(model.isNotEmpty() && setting.voiceId.isNotBlank()) { "请填写模型 ID 和 Voice ID。" }
    require(model !in setOf("eleven_v4_turbo", "eleven_v3_conversational")) {
        "这个模型需要 Text-to-Dialogue WebSocket；当前朗读连接请选 Eleven v4 或其他 HTTP TTS 模型。"
    }
    val dialogue = model == "eleven_v4"
    // Official dialogue API recommends <= 2,000 characters. Normal playback is already
    // sentence-chunked; direct callers get an explicit error, never silently truncated speech.
    require(!dialogue || text.length <= 2_000) { "Eleven v4 单次朗读请分段到 2,000 字符以内。" }
    val body = buildJsonObject {
        put("model_id", model)
        if (dialogue) {
            putJsonArray("inputs") {
                addJsonObject {
                    put("text", text)
                    put("voice_id", setting.voiceId.trim())
                }
            }
            putJsonObject("settings") {
                put("stability", setting.stability.coerceIn(0f, 1f))
                put("similarity", setting.similarityBoost.coerceIn(0f, 1f))
            }
        } else {
            put("text", text)
            putJsonObject("voice_settings") {
                put("stability", setting.stability.coerceIn(0f, 1f))
                put("similarity_boost", setting.similarityBoost.coerceIn(0f, 1f))
            }
        }
    }
    val url = if (dialogue) elevenLabsApiUrl(setting.baseUrl, "text-to-dialogue")
        else elevenLabsApiUrl(setting.baseUrl, "text-to-speech", setting.voiceId.trim())
    return Request.Builder()
        .url(url.newBuilder()
            .addQueryParameter("output_format", "mp3_44100_128").build())
        .header("xi-api-key", setting.apiKey)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
}

internal fun parseElevenLabsTtsModels(payload: String): List<Pair<String, String>> {
    val root = Json.parseToJsonElement(payload) as? JsonArray ?: error("模型目录格式不正确。")
    require(root.size <= 500) { "模型目录过大。" }
    return root.mapNotNull { entry ->
        val model = entry as? JsonObject ?: return@mapNotNull null
        val id = model["model_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (model["can_do_text_to_speech"]?.jsonPrimitive?.booleanOrNull != true ||
            id.isBlank() || id.length > 200 || id in setOf("eleven_v4_turbo", "eleven_v3_conversational")) return@mapNotNull null
        id to (model["name"]?.jsonPrimitive?.contentOrNull?.take(200)?.ifBlank { id } ?: id)
    }.distinctBy { it.first }
}

/** Explicit user refresh only: no synthesis, no transcript, no automatic request on opening settings. */
suspend fun fetchElevenLabsTtsModels(setting: TTSProviderSetting.ElevenLabs): List<Pair<String, String>> =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(elevenLabsApiUrl(setting.baseUrl, "models"))
            .apply { if (setting.apiKey.isNotBlank()) header("xi-api-key", setting.apiKey) }.get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw elevenLabsHttpError(response, catalogue = true)
            val source = response.body.source()
            val maximumBytes = 512L * 1024L
            if (source.request(maximumBytes + 1)) error("模型目录超过大小限制。")
            parseElevenLabsTtsModels(source.readUtf8())
        }
    }

/** Classify the HTTP failure without exposing response text, keys, URLs or user speech.
 * Gate business errors to 4xx: a gateway's 502 must not become a claim of insufficient credits. */
internal fun elevenLabsHttpError(response: Response, catalogue: Boolean = false): TTSProviderException {
    val code = response.code
    val detailStatus = if (code in 400..499) runCatching {
        val body = Json.parseToJsonElement(response.peekBody(8_192).string()) as? JsonObject
        val detail = body?.get("detail") as? JsonObject
        (detail?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    }.getOrNull() else null
    val hint = when {
        code in setOf(502, 503, 504) -> "服务端或中间网关暂时异常。请稍后重试，或对比切换网络后的结果；此状态码不代表账户额度不足。"
        code in 500..599 -> "服务端处理异常。请稍后重试；此状态码不代表账户额度不足。"
        detailStatus == "quota_exceeded" -> "服务返回额度不足（quota_exceeded）。请核对当前 API Key 所属工作区及其额度限制。"
        detailStatus == "invalid_api_key" || code == 401 -> "身份验证失败。请检查 API Key 是否有效及所属工作区。"
        code == 429 -> "请求过于频繁或服务繁忙。请稍后重试。"
        code == 408 -> "请求超时。请检查网络连接并稍后重试。"
        code == 403 -> if (catalogue) "访问被拒绝。请检查 API Key 的 Models 读取权限及服务访问限制，也可手填模型 ID。"
            else "访问被拒绝。请检查 API Key 权限及服务访问限制。"
        code == 404 || detailStatus == "voice_not_found" -> if (catalogue) "模型目录接口不存在。请检查接口地址，也可手填模型 ID。"
            else "接口或音色未找到。请检查接口地址和 Voice ID。"
        code == 400 || code == 422 -> if (catalogue) "模型目录请求不被接受。请检查接口地址及读取权限，也可手填模型 ID。"
            else "朗读参数不被接受。请检查模型、Voice ID、文本长度及声音设置。"
        code in 300..399 -> "接口要求跳转到其他地址，本次未继续请求。请核对接口地址及当前网络的跳转结果；此状态码不代表账户额度不足。"
        else -> "服务返回异常。请核对接口地址，或稍后重试。"
    }
    val action = if (catalogue) "模型目录读取失败" else "朗读失败"
    return TTSProviderException("ElevenLabs $action：HTTP $code。$hint", code)
}
