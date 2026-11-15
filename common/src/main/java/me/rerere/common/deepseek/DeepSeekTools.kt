package me.rerere.common.deepseek

import kotlinx.coroutines.CancellationException
import me.rerere.common.http.awaitAndUse
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Independent official API transport: never inherits model/ST credentials or replays a paid call. */
object DeepSeekTools {
    const val ORIGIN = "https://api.deepseek.com"
    fun request(path: String, apiKey: String): Request.Builder {
        require(path in setOf("/v1/tools/asr", "/v1/tools/tts", "/v1/tools/ocr", "/files", "/chat/completions"))
        val key = apiKey.trim()
        require(key.isNotEmpty() && key.all { it.code in 33..126 }) { "请填写有效的 DeepSeek 官方 API Key。" }
        return Request.Builder().url(ORIGIN + path).header("Authorization", "Bearer $key")
    }

    fun client(base: OkHttpClient, seconds: Long = 200): OkHttpClient = base.newBuilder()
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .authenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(seconds, TimeUnit.SECONDS).callTimeout(seconds, TimeUnit.SECONDS).build()

    suspend fun bytes(client: OkHttpClient, request: Request, limit: Long): ByteArray = try {
        client.newCall(request).awaitAndUse { response ->
            check(response.isSuccessful) { "DeepSeek 请求失败（HTTP ${response.code}），未自动重试。请检查官方 Key、余额和服务状态。" }
            val body = response.body
            check(body.contentLength() <= limit) { "DeepSeek 返回内容过大，请缩小文件或问题。" }
            val source = body.source()
            check(!source.request(limit + 1)) { "DeepSeek 返回内容过大，请缩小文件或问题。" }
            source.readByteArray()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IOException) { throw IOException("DeepSeek 连接中断或超时，未自动重试；服务端已接收的任务可能仍会完成并计费。") }
}
