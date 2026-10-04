package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Unknown proxies are NOT presumed stateless. No private body is sent by this capability probe. */
internal suspend fun privateRoomRoute(provider: ProviderSetting, modelId: String, client: OkHttpClient,
    confirmedDirectApi: Boolean = false): ProviderSetting {
    val base = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
    }.toHttpUrlOrNull() ?: error("private_route_unavailable")
    if (base.username.isNotEmpty() || base.password.isNotEmpty() || !privateRoomTransportAllowed(provider)) error("private_route_unavailable")
    if (base.query != null || base.fragment != null ||
        provider is ProviderSetting.Google && (provider.vertexAI || provider.useServiceAccount)) error("private_route_unavailable")
    // Explicit human-bound choice allows ordinary stateless API providers without an Orbis/ST gateway.
    // This is informed routing consent, NOT a claim that a third-party provider has zero retention.
    if (confirmedDirectApi) return privateRoomDirectProvider(provider)
    val directHosts = when (provider) {
        is ProviderSetting.OpenAI -> setOf("api.openai.com", "api.deepseek.com")
        is ProviderSetting.Claude -> setOf("api.anthropic.com")
        is ProviderSetting.Google -> setOf("generativelanguage.googleapis.com")
    }
    if (base.isHttps && base.port == 443 && base.host in directHosts) return privateRoomDirectProvider(provider)
    if (provider !is ProviderSetting.OpenAI || base.encodedPath.trimEnd('/') != "/v1" ||
        base.query != null || base.fragment != null) error("private_route_unavailable")
    val key = KeyRoulette.default().next(provider.apiKey, provider.id.toString())
    val capabilities = base.newBuilder().encodedPath("/v1/private-room/capabilities").build()
    val request = Request.Builder().url(capabilities).header("Authorization", "Bearer $key").get().build()
    val contract = client.newCall(request).await().use { response ->
        if (response.code != 200) error("private_route_unavailable")
        Json.parseToJsonElement(response.body.string()) as? JsonObject ?: error("private_route_unavailable")
    }
    requirePrivateRoomContract(contract, modelId)
    return provider.copy(apiKey = key, baseUrl = base.newBuilder().encodedPath("/v1").build().toString().trimEnd('/'),
        useResponseApi = false, chatCompletionsPath = "/private-room/chat/completions")
}

private fun privateRoomDirectProvider(provider: ProviderSetting): ProviderSetting = when (provider) {
    is ProviderSetting.OpenAI -> {
        check(provider.chatCompletionsPath == "/chat/completions") { "private_route_unavailable" }
        provider.copy(baseUrl = provider.baseUrl.trimEnd('/'), useResponseApi = false)
    }
    is ProviderSetting.Claude -> provider.copy(promptCaching = false)
    is ProviderSetting.Google -> provider
}

internal fun requirePrivateRoomContract(contract: JsonObject, modelId: String) {
    fun flag(key: String) = contract[key]?.jsonPrimitive?.takeIf { !it.isString }?.booleanOrNull
    check(contract["contract"]?.jsonPrimitive?.contentOrNull == "orbis-private-room/1" &&
        flag("stateless") == true && flag("client_tools_only") == true && flag("server_memory") == false &&
        contract["chat_completions_path"]?.jsonPrimitive?.contentOrNull == "/v1/private-room/chat/completions" &&
        contract["models"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == modelId } == true) { "private_route_unavailable" }
}
