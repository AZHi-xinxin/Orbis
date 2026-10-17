package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Official Search/Fetch tools, independent of the model provider or ST gateway credentials. */
object DeepSeekSearchService : SearchService<SearchServiceOptions.DeepSeekOptions> {
    override val name = "DeepSeek"
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024L

    @Composable
    override fun Description() {
        val uri = LocalUriHandler.current
        Text("直接使用 DeepSeek 官方搜索与网页读取，不更换聊天模型或 ST 网关。需要官方 API Key，不能填写 ST 网关密钥。")
        Text("搜索和网页读取分别按成功调用计费，当前各为 5 元 / 千次；测试搜索也会计费。最新价格以官方文档为准。")
        TextButton(onClick = { uri.openUri("https://platform.deepseek.com/api_keys") }) {
            Text("获取 DeepSeek 官方 API Key")
        }
        TextButton(onClick = { uri.openUri("https://api-docs.deepseek.com/zh-cn/guides/search/") }) {
            Text("搜索用法与计费说明")
        }
    }

    override fun parameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 128)
                put("description", "Focused search query (1–128 Unicode characters). Supports site:, -site:, lang:, after:, before: and quoted phrases; include these only when relevant.")
            })
        }, required = listOf("query"))

    override fun scrapingParameters(options: SearchServiceOptions.DeepSeekOptions): InputSchema = InputSchema.Obj(
        properties = buildJsonObject {
            put("url", buildJsonObject {
                put("type", "string")
                put("maxLength", 8192)
                put("description", "One HTTP or HTTPS page URL to read. Must not contain a username or password.")
            })
        }, required = listOf("url"))

    override suspend fun search(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions): Result<SearchResult> = safely {
        val body = searchBody(params, commonOptions.resultSize)
        parseSearch(post("search", body, serviceOptions))
    }

    override suspend fun scrape(params: JsonObject, commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DeepSeekOptions): Result<ScrapedResult> = safely {
        val body = fetchBody(params)
        parseFetch(post("fetch", body, serviceOptions), (body.getValue("url") as JsonPrimitive).content)
    }

    internal fun searchBody(params: JsonObject, count: Int): JsonObject {
        val query = string(params, "query")?.trim().orEmpty()
        require(query.isNotBlank() && query.codePointCount(0, query.length) <= 128) {
            "DeepSeek 搜索词须为 1–128 个字符，请缩短搜索词后再试。"
        }
        return buildJsonObject {
            put("query", query)
            put("count", count.coerceIn(1, 50))
            // Summaries are for model reading; full pages are explicitly fetched only when needed.
            put("need_content", false)
        }
    }

    internal fun fetchBody(params: JsonObject): JsonObject {
        val url = string(params, "url")?.trim().orEmpty()
        val parsed = url.toHttpUrlOrNull()
        require(url.codePointCount(0, url.length) in 1..8192 && parsed != null &&
            parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "DeepSeek 网页读取需要有效的 HTTP/HTTPS 地址，不能包含用户名或密码。"
        }
        return buildJsonObject { put("url", url) }
    }

    internal fun request(operation: String, body: JsonObject, key: String): Request {
        require(operation == "search" || operation == "fetch")
        val trimmedKey = key.trim()
        require(trimmedKey.isNotEmpty() && trimmedKey.all { it.code in 33..126 }) {
            "请在联网搜索设置中填写有效的 DeepSeek 官方 API Key（不是 ST 网关密钥）。"
        }
        return Request.Builder().url("https://api.deepseek.com/v1/tools/$operation")
            .header("Authorization", "Bearer $trimmedKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    internal fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .apply { interceptors().clear(); networkInterceptors().clear() }
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        // Retain the user's configured proxy authentication; it is separate from API credentials.
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .callTimeout(40, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).build()

    private suspend fun post(operation: String, body: JsonObject,
        options: SearchServiceOptions.DeepSeekOptions): String {
        require(options.apiKey.isNotBlank()) { "请先在联网搜索设置中填写 DeepSeek 官方 API Key。" }
        val key = keyRoulette.next(options.apiKey, options.id.toString())
        return execute(request(operation, body, key), client(httpClient))
    }

    /** Cancellation covers both headers and body. No retry, redirect, raw-body logging or key-bearing errors. */
    internal suspend fun execute(request: Request, client: OkHttpClient): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(IOException("DeepSeek 搜索连接失败或超时，未自动重试。请检查网络后再试。")))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        check(it.isSuccessful) { httpFailure(it.code) }
                        val body = it.body
                        check(body.contentLength() <= MAX_RESPONSE_BYTES) { "DeepSeek 返回内容过大，请缩小查询范围。" }
                        val source = body.source()
                        check(!source.request(MAX_RESPONSE_BYTES + 1)) { "DeepSeek 返回内容过大，请缩小查询范围。" }
                        source.buffer.readUtf8()
                    }
                }.recoverCatching { failure ->
                    if (failure is IOException) throw IOException("DeepSeek 搜索读取失败或超时，未自动重试。")
                    throw failure
                }
                cont.resumeWith(result)
            }
        })
    }

    internal fun httpFailure(status: Int): String = "DeepSeek 搜索 HTTP $status：" + when (status) {
        400 -> "请求参数不符合要求。"
        401 -> "官方 API Key 无效或已失效；请勿使用 ST 网关密钥。"
        402 -> "DeepSeek 账户余额不足；未自动重试。"
        403 -> "该请求被服务端权限或安全策略拒绝。"
        404 -> "网页不可用或无法提取正文。"
        408, 504 -> "请求超时，请稍后再试。"
        413 -> "请求内容过大。"
        429 -> "调用过于频繁，请稍后再试。"
        in 300..399 -> "接口返回跳转，已停止以保护密钥。"
        else -> "服务暂不可用，未自动重试。"
    }

    internal fun parseSearch(raw: String): SearchResult {
        val response = decode<SearchResponse>(raw)
        return SearchResult(items = response.results.map {
            SearchResult.SearchResultItem(title = it.title, url = it.url,
                text = it.summary?.takeIf(String::isNotBlank) ?: it.snippet.orEmpty(),
                publishedDate = it.date?.takeIf(String::isNotBlank))
        })
    }

    internal fun parseFetch(raw: String, url: String): ScrapedResult {
        val response = decode<FetchResponse>(raw)
        check(response.content.isNotBlank()) { "DeepSeek 未返回可读网页正文。" }
        return ScrapedResult(listOf(ScrapedResultUrl(url, response.content,
            ScrapedResultMetadata(title = response.title?.takeIf(String::isNotBlank)))))
    }

    private inline fun <reified T> decode(raw: String): T = try {
        json.decodeFromString<T>(raw)
    } catch (_: Exception) {
        // Serialization exceptions may contain raw source text; do not expose them to chat/diagnostics.
        throw IllegalStateException("DeepSeek 返回格式无法识别，未将错误内容作为搜索结果。")
    }

    private fun string(params: JsonObject, name: String): String? =
        (params[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun <T> safely(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
    }

    @Serializable private data class SearchResponse(val results: List<SearchItem>)
    @Serializable private data class SearchItem(val title: String, val url: String,
        val summary: String? = null, val snippet: String? = null, val date: String? = null)
    @Serializable private data class FetchResponse(val title: String? = null, val content: String)
}
