package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)
    // Ordinary API connections do not need the separate account store (or its disk setup).
    private val planAccounts by lazy { context?.let { me.rerere.ai.provider.chatgpt.ChatGptPlanAccounts(it) } }
    private val planAPI by lazy { planAccounts?.let(::ChatGptPlanAPI) }


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            providerSetting.chatGptAccountId?.let { return@withContext (planAccounts ?: error("siwc_not_signed_in")).models(it) }
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                SeedreamImageApi.modelDefaults(Model(
                    modelId = id,
                    displayName = id,
                ))
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        require(providerSetting.chatGptAccountId == null) { "ChatGPT 套餐用量请到 ChatGPT 设置 → Usage 查看。" }
        providerSetting.retiredSiliconFlowBalanceNotice()?.let { error(it) }
        require(providerSetting.apiKey.isNotBlank()) { "未配置此连接的密钥" }
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val balanceClient = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        var url = balanceUrl(providerSetting.baseUrl, providerSetting.balanceOption.apiPath)
        var fallback = false
        while (true) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
            balanceClient.newCall(request).await().use { response ->
                val next = if (!fallback) legacyBalanceFallback(providerSetting, response.code) else null
                if (next != null) {
                    // Only the historical default /credits 404 has this same-origin compatibility path.
                    url = next
                    fallback = true
                } else {
                    if (!response.isSuccessful) balanceHttpFailure(response.code)
                    require(response.body.contentLength() <= 65_536) { "余额接口返回异常" }
                    val source = response.body.source()
                    require(!source.request(65_537)) { "余额接口返回异常" }
                    val bytes = source.buffer.readByteArray()
                    val bodyJson = try { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
                        catch (_: Exception) { error("余额接口返回格式不正确") }
                    return@withContext try {
                        formatProviderBalance(bodyJson, providerSetting.balanceOption.resultPath,
                            fallback || providerSetting.balanceOption.resultPath == "balance_infos[0].total_balance")
                    } catch (_: Exception) { error("余额接口未返回可用金额") }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("余额暂不可用")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.chatGptAccountId != null) {
        (planAPI ?: error("siwc_not_signed_in")).stream(providerSetting, messages, params)
    } else if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.chatGptAccountId != null) {
        (planAPI ?: error("siwc_not_signed_in")).generate(providerSetting, messages, params)
    } else if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting.chatGptAccountId == null) { "ChatGPT 套餐接入不提供嵌入接口，请配置独立 API。" }
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(providerSetting.chatGptAccountId == null) { "ChatGPT 套餐接入不提供生图接口，请配置独立生图 AI。" }
        require(!SeedreamImageApi.isVideoModel(params.model.modelId)) { "workshop_video_model" }
        if (SeedreamImageApi.usesArk(providerSetting, params.model)) {
            seedreamImages(providerSetting, params.model, params.prompt, params.size, params.numOfImages,
                params.customHeaders, params.customBody, emptyList()).forEach { emit(it) }
            return@flow
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                
                val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) || 
                    params.model.modelId.contains("grok", ignoreCase = true)
                
                if (params.size.isNotBlank() && !isGrok) {
                    put("size", params.size)
                }
            }
                .mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage: request prepared")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(providerSetting.chatGptAccountId == null) { "ChatGPT 套餐接入不提供改图接口，请配置独立生图 AI。" }
        require(!SeedreamImageApi.isVideoModel(params.model.modelId)) { "workshop_video_model" }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }
        if (SeedreamImageApi.usesArk(providerSetting, params.model)) {
            seedreamImages(providerSetting, params.model, params.prompt, params.size, params.numOfImages,
                params.customHeaders, params.customBody, params.images).forEach { emit(it) }
            return@flow
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun seedreamImages(setting: ProviderSetting.OpenAI, model: Model, prompt: String,
        size: String, count: Int, headers: List<me.rerere.ai.provider.CustomHeader>,
        custom: List<me.rerere.ai.provider.CustomBody>, images: List<String>): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        require(images.size <= 4) { "workshop_reference" }
        val references = images.map { path ->
            val file = File(path)
            require(file.isFile && file.length() in 1..(24L * 1024 * 1024) && file.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "workshop_image_format"
            }
            "data:${file.imageMediaType()};base64,${Base64.encode(file.readBytes())}"
        }
        val body = SeedreamImageApi.requestBody(model, prompt, size, count, custom, references)
        val encodedBody = body.toString().toRequestBody("application/json".toMediaType())
        val oneShotBody = object : okhttp3.RequestBody() {
            override fun contentType() = encodedBody.contentType()
            override fun contentLength() = encodedBody.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = encodedBody.writeTo(sink)
        }
        val request = Request.Builder().url("${setting.baseUrl.trimEnd('/')}/images/generations")
            .headers(headers.toHeaders())
            .header("Authorization", "Bearer ${keyRoulette.next(setting.apiKey, setting.id.toString())}")
            .header("Content-Type", "application/json")
            .post(oneShotBody).build()
        // Do not retry a potentially billed image request or follow credential-bearing redirects.
        val imageClient = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        imageClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) error(when (response.code) {
                401 -> "workshop_image_auth"
                403 -> "workshop_image_access"
                404 -> "workshop_image_model"
                429 -> "workshop_image_limit"
                else -> "workshop_provider_failed"
            })
            parseImageResponse(response.body.string())
        }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
