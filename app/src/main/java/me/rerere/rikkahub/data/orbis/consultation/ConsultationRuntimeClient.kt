package me.rerere.rikkahub.data.orbis.consultation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.integration.consultationBase
import me.rerere.rikkahub.data.orbis.integration.consultationToken
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import java.net.Proxy

internal class ConsultationRuntimeFailure(val code: String) : IllegalStateException("consultation_runtime_$code")
internal class ConsultationRuntimeClient(client: OkHttpClient = OkHttpClient(),
    private val feature: ConsultationFeaturePolicy = consultationFeature) {
    private val http = client.newBuilder().cache(null).cookieJar(CookieJar.NO_COOKIES)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(8, TimeUnit.SECONDS).build()
    suspend fun call(config: ConsultationRuntimeConfig, endpoint: String, data: JsonObject? = null,
        token: String? = config.aiToken): JsonObject = withContext(Dispatchers.IO) {
        if (!feature.enabled) throw ConsultationRuntimeFailure("not_open")
        require(endpoint.matches(Regex("[a-z_]+|sessions/[a-f0-9]{32}/[a-z_]+")))
        val base = consultationBase(config.baseUrl).toHttpUrl()
        require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1")) { "public_https_required" }
        val builder = Request.Builder().url(base.newBuilder().addPathSegments("v1/consultation/$endpoint").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store")
        if (token != null) builder.header("Authorization", "Bearer ${consultationToken(token)}")
        if (data != null) {
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 98304)
            builder.post(object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            })
        }
        http.newCall(builder.build()).execute().use { response ->
            if (response.code in 300..399) throw ConsultationRuntimeFailure("redirect_refused")
            if (response.body.contentLength() > 1048576 || response.body.source().request(1048577))
                throw ConsultationRuntimeFailure("response_too_large")
            val body = Json.parseToJsonElement(response.body.string()) as? JsonObject ?: throw ConsultationRuntimeFailure("invalid_response")
            if (!response.isSuccessful) {
                val safe = (body["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull
                throw ConsultationRuntimeFailure(safe?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "request_failed")
            }
            body
        }
    }
    suspend fun verify(config: ConsultationRuntimeConfig): JsonObject = call(config, "capabilities").also {
        require(it["protocol"]?.jsonPrimitive?.content == "orbis.consultation/1")
        require(it["runtime_protocol"]?.jsonPrimitive?.content == "orbis.consultation-runtime/1")
        require(it["role"]?.jsonPrimitive?.content == "ai")
        require(it["subject"]?.jsonPrimitive?.content == config.subject)
        require(it["enabled"]?.jsonPrimitive?.boolean == true)
    }
}
