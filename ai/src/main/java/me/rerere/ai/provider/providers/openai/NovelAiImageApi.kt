package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** Native NovelAI image protocol. The existing connection stores the token, never a tool argument. */
object NovelAiImageApi {
    const val BASE_URL = "https://image.novelai.net"
    const val MODEL = "nai-diffusion-4-5-full"
    const val V5_FULL = "nai-diffusion-5-full"
    const val V5_CURATED = "nai-diffusion-5-curated"
    val supportedModels = linkedMapOf(MODEL to "NovelAI Diffusion 4.5 Full",
        V5_FULL to "NovelAI Diffusion V5 Full", V5_CURATED to "NovelAI Diffusion V5 Curated")
    fun supportsModel(modelId: String) = modelId in supportedModels
    private const val MAX_IMAGE = 24 * 1024 * 1024
    private const val MAX_RESPONSE = 34 * 1024 * 1024
    private val random = SecureRandom()

    fun isConnection(provider: ProviderSetting): Boolean {
        if (provider !is ProviderSetting.OpenAI || provider.chatGptAccountId != null) return false
        val url = provider.baseUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "image.novelai.net" && url.port == 443 &&
            url.encodedPath.trimEnd('/').isEmpty() && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null
    }

    fun model(modelId: String = MODEL) = Model(modelId = modelId, displayName = supportedModels.getValue(modelId),
        type = ModelType.IMAGE, inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.IMAGE), abilities = emptyList())

    /** Fetch-models is also a connection check in settings; never succeed using a local list alone. */
    suspend fun listModels(client: OkHttpClient, provider: ProviderSetting.OpenAI): List<Model> {
        require(isConnection(provider)) { "workshop_provider_unsupported" }
        require(provider.apiKey.trim().startsWith("pst-")) { "workshop_image_auth" }
        val transport = client.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url("$BASE_URL/user/subscription")
            .header("Authorization", "Bearer ${provider.apiKey.trim()}").header("Accept", "application/json").get().build()
        transport.newCall(request).await().use { response ->
            require(response.isSuccessful) { errorCode(response.code) }
            val bytes = response.body.byteStream().use { readBounded(it, 64 * 1024) }
            val account = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            require((account?.get("tier") as? JsonPrimitive)?.intOrNull != null) { "workshop_image_format" }
        }
        return supportedModels.keys.map(::model)
    }

    fun requestBody(params: ImageGenerationParams): JsonObject {
        require(supportsModel(params.model.modelId)) { "workshop_image_model" }
        require(params.numOfImages == 1) { "workshop_single_image_only" }
        require(params.prompt.isNotBlank()) { "workshop_prompt" }
        val allowed = setOf("width", "height", "steps", "scale", "seed", "sampler", "negative_prompt")
        require(params.customBody.map { it.key }.distinct().size == params.customBody.size &&
            params.customBody.all { it.key in allowed }) { "workshop_novelai_parameters" }
        val custom = params.customBody.associate { it.key to it.value }
        fun int(key: String, fallback: Int): Int = custom[key]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
                ?: error("workshop_novelai_parameters")
        } ?: fallback
        fun text(key: String, fallback: String): String = custom[key]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
                ?: error("workshop_novelai_parameters")
        } ?: fallback
        val size = if (params.size.isBlank() || params.size == "auto") "1024x1024" else params.size
        val dimensions = Regex("^(\\d{2,4})x(\\d{2,4})$").matchEntire(size)
            ?: error("workshop_novelai_parameters")
        val width = int("width", dimensions.groupValues[1].toInt())
        val height = int("height", dimensions.groupValues[2].toInt())
        val steps = int("steps", 23)
        val scale = custom["scale"]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.doubleOrNull
                ?: error("workshop_novelai_parameters")
        } ?: if (params.model.modelId == MODEL) 5.0 else 7.0
        val seed = custom["seed"]?.let {
            (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull
                ?: error("workshop_novelai_parameters")
        } ?: (random.nextInt().toLong() and 0xffffffffL)
        val sampler = text("sampler", "k_euler_ancestral")
        val negative = text("negative_prompt", "blurry, low quality, watermark")
        require(width in 64..2048 && height in 64..2048 && width % 64 == 0 && height % 64 == 0 &&
            width.toLong() * height <= 3_145_728 && steps in 1..50 && scale.isFinite() && scale in 0.0..10.0 &&
            seed in 0..0xffffffffL && sampler in setOf("k_euler_ancestral", "k_euler", "k_dpmpp_2m", "k_dpmpp_sde") &&
            negative.length <= 12000) { "workshop_novelai_parameters" }
        fun caption(value: String) = buildJsonObject {
            put("caption", buildJsonObject { put("base_caption", value); put("char_captions", JsonArray(emptyList())) })
            put("use_coords", false); put("use_order", true)
        }
        return buildJsonObject {
            put("input", params.prompt); put("model", params.model.modelId); put("action", "generate")
            put("parameters", buildJsonObject {
                put("params_version", if (params.model.modelId == MODEL) 3 else 4); put("width", width); put("height", height)
                put("steps", steps); put("scale", scale); put("seed", seed); put("sampler", sampler)
                put("n_samples", 1); put("noise_schedule", "karras")
                put("negative_prompt", negative); put("qualityToggle", false); put("ucPreset", 0)
                put("v4_prompt", caption(params.prompt))
                put("v4_negative_prompt", buildJsonObject {
                    put("caption", caption(negative).getValue("caption")); put("legacy_uc", false)
                })
            })
        }
    }

    suspend fun generate(client: OkHttpClient, provider: ProviderSetting.OpenAI,
        params: ImageGenerationParams): List<ImageGenerationItem> {
        require(isConnection(provider)) { "workshop_provider_unsupported" }
        require(provider.apiKey.trim().startsWith("pst-")) { "workshop_image_auth" }
        return request(client, provider, requestBody(params))
    }

    fun editRequestBody(params: ImageEditParams, encodedImage: String, size: String = params.size): JsonObject {
        require(params.images.size == 1 && params.numOfImages == 1) { "workshop_single_reference_only" }
        val controls = params.customBody.filter { it.key in setOf("reference_mode", "strength") }
        require(controls.map { it.key }.distinct().size == controls.size) { "workshop_novelai_parameters" }
        val values = controls.associate { it.key to it.value }
        val mode = values["reference_mode"]?.let { (it as? JsonPrimitive)?.takeIf { v -> v.isString }?.content
            ?: error("workshop_novelai_parameters") } ?: "img2img"
        val strength = values["strength"]?.let { (it as? JsonPrimitive)?.takeUnless { v -> v.isString }?.doubleOrNull
            ?: error("workshop_novelai_parameters") } ?: 0.6
        require(mode in setOf("img2img", "character&style") && strength.isFinite() && strength in 0.0..1.0) { "workshop_novelai_parameters" }
        require(mode == "img2img" || params.model.modelId == MODEL) { "workshop_reference_mode" }
        require(encodedImage.isNotBlank() && encodedImage.length <= MAX_IMAGE * 4 / 3 + 4) { "workshop_image_large" }
        val base = requestBody(ImageGenerationParams(params.model, params.prompt, params.numOfImages, size,
            customBody = params.customBody.filterNot { it.key in setOf("reference_mode", "strength") }))
        val parameters = base.getValue("parameters").jsonObject
        val extra = buildJsonObject {
            if (mode == "img2img") {
                put("image", encodedImage); put("strength", strength); put("noise", 0)
                put("extra_noise_seed", parameters.getValue("seed"))
            } else {
                put("director_reference_images", JsonArray(listOf(JsonPrimitive(encodedImage))))
                put("director_reference_descriptions", buildJsonArray { add(buildJsonObject {
                    put("caption", buildJsonObject { put("base_caption", "character&style"); put("char_captions", JsonArray(emptyList())) })
                    put("use_coords", false); put("use_order", true)
                }) })
                put("director_reference_information_extracted", JsonArray(listOf(JsonPrimitive(1.0))))
                put("director_reference_strength_values", JsonArray(listOf(JsonPrimitive(strength))))
                put("director_reference_secondary_strength_values", JsonArray(listOf(JsonPrimitive(0.5))))
            }
        }
        return JsonObject(base + ("action" to JsonPrimitive(if (mode == "img2img") "img2img" else "generate")) +
            ("parameters" to JsonObject(parameters + extra)))
    }

    suspend fun edit(client: OkHttpClient, provider: ProviderSetting.OpenAI, params: ImageEditParams): List<ImageGenerationItem> {
        require(isConnection(provider)) { "workshop_provider_unsupported" }
        require(provider.apiKey.trim().startsWith("pst-")) { "workshop_image_auth" }
        // Validate model/control fields before touching the selected local file or making a paid call.
        editRequestBody(params, "validation-only")
        val reference = prepareNovelAiReference(params)
        return request(client, provider, editRequestBody(params, reference.base64, reference.size))
    }

    private suspend fun request(client: OkHttpClient, provider: ProviderSetting.OpenAI, body: JsonObject): List<ImageGenerationItem> {
        // This call can consume Anlas. Never replay or follow a redirect with the token.
        val transport = client.newBuilder().retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).callTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES).build()
        val encoded = body.toString().toRequestBody("application/json".toMediaType())
        val oneShot = object : okhttp3.RequestBody() {
            override fun contentType() = encoded.contentType()
            override fun contentLength() = encoded.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) = encoded.writeTo(sink)
        }
        val request = Request.Builder().url("$BASE_URL/ai/generate-image")
            .header("Authorization", "Bearer ${provider.apiKey.trim()}")
            .header("Accept", "application/json")
            .post(oneShot).build()
        return transport.newCall(request).await().use { response ->
            require(response.isSuccessful) { errorCode(response.code) }
            val bytes = response.body.byteStream().use { readBounded(it, MAX_RESPONSE) }
            decode(bytes, response.header("Content-Type").orEmpty())
        }
    }

    fun errorCode(status: Int) = when (status) {
        401 -> "workshop_image_auth"
        402 -> "workshop_image_credit"
        403 -> "workshop_image_access"
        400, 422 -> "workshop_novelai_parameters"
        404 -> "workshop_image_model"
        429 -> "workshop_image_limit"
        else -> "workshop_provider_failed"
    }

    fun decode(bytes: ByteArray, contentType: String): List<ImageGenerationItem> {
        require(bytes.size <= MAX_RESPONSE) { "workshop_image_large" }
        val images = if (contentType.substringBefore(';').trim() == "application/json") {
            val root = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
                ?: error("workshop_image_format")
            val list = root["images"] as? JsonArray ?: error("workshop_image_format")
            require(list.size == 1) { "workshop_result_limit" }
            list.map { item ->
                val encoded = (item as? JsonObject)?.get("image") as? JsonPrimitive
                    ?: error("workshop_image_format")
                require(encoded.isString && encoded.content.length <= MAX_IMAGE * 4 / 3 + 4) { "workshop_image_large" }
                try { Base64.getDecoder().decode(encoded.content) }
                catch (_: IllegalArgumentException) { error("workshop_image_format") }
            }
        } else {
            require(contentType.substringBefore(';').trim() in setOf("application/zip", "application/x-zip-compressed")) {
                "workshop_image_format"
            }
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                val entry = zip.nextEntry ?: error("workshop_empty_result")
                require(!entry.isDirectory && entry.name.matches(Regex("[A-Za-z0-9_-]+\\.png"))) { "workshop_image_format" }
                val image = readBounded(zip, MAX_IMAGE)
                require(zip.nextEntry == null) { "workshop_result_limit" }
                listOf(image)
            }
        }
        return images.map { image ->
            require(image.size in 8..MAX_IMAGE && image.take(8) ==
                listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() }) { "workshop_image_format" }
            ImageGenerationItem(Base64.getEncoder().encodeToString(image), "image/png")
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { "workshop_image_large" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
