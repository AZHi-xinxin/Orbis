package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.util.mergeCustomBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Ark's image editing is JSON on /images/generations, not OpenAI multipart /images/edits. */
object SeedreamImageApi {
    fun isImageModel(id: String) = Regex("^doubao-seedream-[45]-", RegexOption.IGNORE_CASE).containsMatchIn(id)
    fun isVideoModel(id: String) = id.startsWith("doubao-seedance-", ignoreCase = true)
    fun usesArk(provider: ProviderSetting, model: Model): Boolean {
        if (provider !is ProviderSetting.OpenAI || provider.chatGptAccountId != null || !isImageModel(model.modelId)) return false
        val url = provider.baseUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "ark.cn-beijing.volces.com" && url.port == 443 &&
            url.encodedPath.trimEnd('/') == "/api/v3" && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null
    }

    fun modelDefaults(model: Model): Model = if (isImageModel(model.modelId)) model.copy(
        type = ModelType.IMAGE, inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        outputModalities = listOf(Modality.IMAGE), abilities = emptyList()) else model

    fun requestBody(model: Model, prompt: String, size: String, count: Int,
        custom: List<CustomBody>, references: List<String> = emptyList()): JsonObject {
        require(isImageModel(model.modelId)) { "workshop_provider_unsupported" }
        require(count == 1) { "workshop_single_image_only" }
        require(references.size <= 4) { "workshop_reference" }
        require(custom.none { it.key in setOf("model", "prompt", "image", "n", "layer_decomposition", "sequential_image_generation_options") }) {
            "workshop_seedream_parameters"
        }
        val supportsSequential = !model.modelId.contains("-pro-", true) && !model.modelId.contains("-flash-", true)
        require(supportsSequential || custom.none { it.key in setOf("sequential_image_generation", "stream") }) {
            "workshop_seedream_parameters"
        }
        val merged = buildJsonObject {
            put("model", model.modelId); put("prompt", prompt)
            // OpenAI's default "auto" is not accepted by Seedream 4.0/4.5.
            put("size", if (size.isBlank() || size == "auto") "2K" else size)
            put("response_format", "b64_json")
            if (supportsSequential) {
                put("stream", false)
                put("sequential_image_generation", "disabled")
            }
            if (references.isNotEmpty()) put("image", JsonArray(references.map(::JsonPrimitive)))
        }.mergeCustomBody(custom)
        require(merged["stream"]?.let { it == JsonPrimitive(false) } != false &&
            merged["sequential_image_generation"]?.let { it == JsonPrimitive("disabled") } != false) { "workshop_seedream_parameters" }
        require(merged["response_format"] in listOf(JsonPrimitive("b64_json"), JsonPrimitive("url"))) { "workshop_seedream_parameters" }
        return merged
    }
}
