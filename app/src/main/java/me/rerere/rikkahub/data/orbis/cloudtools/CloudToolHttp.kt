package me.rerere.rikkahub.data.orbis.cloudtools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal data class CloudHttpResponse(val status: Int, val body: ByteArray)
internal interface CloudToolHttp {
    suspend fun request(credential: CloudGatewayCredential, family: CloudToolFamily, body: String? = null): CloudHttpResponse
}

/** Fresh client: no shared proxy/auth/logger, redirects, cookies, cache or automatic retry. */
internal class OkHttpCloudToolHttp : CloudToolHttp {
    internal val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(90, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(85, TimeUnit.SECONDS).build()
    private val catalogClient = client.newBuilder().callTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).build()

    override suspend fun request(credential: CloudGatewayCredential, family: CloudToolFamily, body: String?): CloudHttpResponse {
        val base = normalizeCloudGatewayUrl(credential.baseUrl)
        val url = if (body == null) "$base/v1/catalog?family=${family.wireName}" else "$base/v1/call"
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${credential.token}")
            .header("Accept", "application/json")
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 256 * 1024) { "arguments_too_large" }
            builder.post(object : RequestBody() {
                override fun contentType() = "application/json; charset=utf-8".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun isOneShot() = true // Includes HTTP 408/503 follow-up protection.
                override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            })
        }
        return suspendCancellableCoroutine { continuation ->
            val call = (if (body == null) catalogClient else client).newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(CloudToolsException("transport_unknown"))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            val limit = if (body == null) 512 * 1024 else 4 * 1024 * 1024
                            val payload = it.body.byteStream().use { input -> input.cloudReadBounded(limit) }
                            CloudHttpResponse(it.code, payload)
                        }
                    }
                    if (!continuation.isActive) return
                    result.fold(
                        onSuccess = { continuation.resume(it) },
                        onFailure = { continuation.resumeWithException(CloudToolsException("response_unavailable")) },
                    )
                }
            })
        }
    }
}

internal fun InputStream.cloudReadBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count < 0) break
        total += count
        require(total <= limit) { "response_too_large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
