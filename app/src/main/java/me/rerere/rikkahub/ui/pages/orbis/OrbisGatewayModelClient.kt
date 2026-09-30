package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal fun orbisGatewayModelsUrl(baseUrl: String): HttpUrl {
    val base = baseUrl.toHttpUrlOrNull() ?: throw OrbisGatewayModelException("connection")
    if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.query != null || base.fragment != null)
        throw OrbisGatewayModelException("connection")
    return base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/models").build()
}

/** One explicit same-origin directory GET. No chat request, redirects, retries or settings mutation. */
internal suspend fun readOrbisGatewayModels(client: OkHttpClient, provider: ProviderSetting.OpenAI): List<String> =
    withContext(Dispatchers.IO) {
        val readClient = client.newBuilder().apply {
            // No chat logging/interceptors may record credentials; direct same-origin read only.
            interceptors().clear(); networkInterceptors().clear()
        }.proxy(Proxy.NO_PROXY).cache(null).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        try {
            val key = KeyRoulette.default().next(provider.apiKey, provider.id.toString())
            val request = Request.Builder().url(orbisGatewayModelsUrl(provider.baseUrl))
                .header("Authorization", "Bearer $key").header("Accept", "application/json")
                .header("Cache-Control", "no-store").get().build()
            val call = readClient.newCall(request)
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use {
                if (!it.isSuccessful) throw OrbisGatewayModelException("http")
                val body = it.body
                if (body.contentLength() > GATEWAY_MODELS_MAX_BYTES) throw OrbisGatewayModelException("invalid_directory")
                val source = body.source()
                if (source.request(GATEWAY_MODELS_MAX_BYTES.toLong() + 1)) throw OrbisGatewayModelException("invalid_directory")
                val aliases = parseOrbisGatewayDirectory(source.buffer.readUtf8())
                if (key.isNotEmpty() && aliases.any { alias -> alias.contains(key) }) throw OrbisGatewayModelException("invalid_directory")
                aliases
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: OrbisGatewayModelException) { throw error }
        catch (_: Exception) { throw OrbisGatewayModelException("connection") }
    }
