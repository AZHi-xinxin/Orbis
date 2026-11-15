package me.rerere.search

import kotlinx.serialization.json.*
import me.rerere.common.deepseek.DeepSeekTools
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** A separate research request, never the companion's identity, memories or local function tools. */
internal object DeepSeekResearch {
    fun body(query: String): JsonObject {
        require(query.isNotBlank() && query.codePointCount(0, query.length) <= 4000) { "深度检索问题须为 1–4000 字符。" }
        return buildJsonObject {
            put("model", "deepseek-flash")
            put("stream", false)
            put("max_tokens", 8192)
            put("thinking", buildJsonObject { put("type", "enabled") })
            put("safety_check", buildJsonObject { put("level", "high") })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("type", "deepseek_web_search") })
                add(buildJsonObject { put("type", "deepseek_read_url") })
            })
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", query) }) })
        }
    }

    suspend fun search(query: String, key: String): SearchResult {
        val request = DeepSeekTools.request("/chat/completions", key)
            .post(body(query).toString().toRequestBody("application/json".toMediaType())).build()
        return parse(DeepSeekTools.bytes(DeepSeekTools.client(SearchService.httpClient, 240), request, 4L * 1024 * 1024).decodeToString())
    }

    fun parse(value: String): SearchResult {
        val root = Json.parseToJsonElement(value).jsonObject
        check(root["error"] == null) { "DeepSeek 深度检索未完成，未自动重试。" }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: error("DeepSeek 未返回检索结果。")
        val finish = choice["finish_reason"]?.jsonPrimitive?.content
        check(finish == "stop" || finish == "length") { "DeepSeek 深度检索被拒绝或未完整完成。" }
        val message = choice["message"]?.jsonObject ?: error("DeepSeek 未返回检索结果。")
        val answer = (message["content"] as? JsonPrimitive)?.content.orEmpty()
        check(answer.isNotBlank()) { "DeepSeek 未返回可读检索结果。" }
        val items = (message["annotations"] as? JsonArray).orEmpty().mapNotNull { annotation ->
            val cite = (annotation as? JsonObject)?.get("url_citation") as? JsonObject ?: return@mapNotNull null
            val url = cite["url"]?.jsonPrimitive?.content.orEmpty()
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
            SearchResult.SearchResultItem(cite["title"]?.jsonPrimitive?.content ?: url, url, "")
        }.distinctBy { it.url }
        return SearchResult(answer = (if (finish == "length") "[检索报告达到长度上限，以下为部分结果]\n" else "") + answer,
            items = items, retrievedAt = java.time.Instant.now().toString())
    }
}
