package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.parseErrorDetail
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/** A transport failure must never become callbackFlow.close(null), even for an empty proxy error. */
internal fun openAIStreamFailure(cause: Throwable?, response: Response?): Throwable {
    val status = response?.code
    val httpFailure = response?.isSuccessful == false
    val fallback = if (httpFailure || cause == null) HttpException(
        message = if (status != null) "连接返回 HTTP $status，本轮未完成。" else "响应连接失败，本轮未完成。",
        code = status?.toString() ?: "stream_transport_failure",
        errorType = "openai_transport_error",
        httpStatus = status?.takeIf { httpFailure },
    ) else cause
    val body = response?.body ?: return fallback
    // Proxy HTML is never echoed into errors/logs. Keep recognized structured provider codes,
    // but do not allocate an unbounded error body or let body-read/parsing failures hide HTTP status.
    val detail = runCatching {
        val source = body.source()
        if (source.request(65_537)) return@runCatching null
        val text = source.buffer.readUtf8()
        if (text.isBlank()) null else {
            val parsed = Json.parseToJsonElement(text)
            if (hasRecognizedProviderErrorText(parsed)) parsed.parseErrorDetail() else null
        }
    }.getOrNull() ?: return fallback
    return if (httpFailure) HttpException(
        message = "HTTP $status: ${detail.message.orEmpty()}",
        code = detail.code ?: status?.toString(),
        errorType = detail.errorType ?: "openai_transport_error",
        httpStatus = status,
        rejectedParameter = detail.rejectedParameter,
        gatewayBusyBeforeGeneration = detail.gatewayBusyBeforeGeneration,
    ) else detail
}

/** ErrorParser intentionally supports arbitrary JSON elsewhere. At this network boundary only
 * known message envelopes may reach it, including nested error/message chains; diagnostic-only
 * objects must never be stringified into user-visible errors or downstream exception logs.
 */
private fun hasRecognizedProviderErrorText(root: JsonElement): Boolean {
    var element = root
    var sawMessageField = false
    repeat(16) {
        element = when (val current = element) {
            is JsonObject -> {
                val key = listOf("error", "detail", "message", "description").firstOrNull { current[it] != null }
                    ?: return false
                sawMessageField = true
                current.getValue(key)
            }
            is JsonArray -> current.firstOrNull() ?: return false
            is JsonPrimitive -> return sawMessageField && current.isString
        }
    }
    return false
}

/** Common OpenAI SSE completion boundary, kept independent of Android and callbackFlow for tests. */
internal class OpenAIStreamListener(
    private val decoder: StreamChunkDecoder,
    private val sendChunks: (List<StreamChunk>) -> Unit,
    private val closeStream: (Throwable?) -> Unit,
) : EventSourceListener() {
    private var hasOutput = false
    private var closed = false

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        if (closed) return
        try {
            val result = decoder.accept(SseEvent(id = id, event = type, data = data))
            observe(result.chunks)
            if (result.completed) requireOutput()
            sendChunks(result.chunks)
            if (result.completed) finish(null)
        } catch (error: Throwable) { finish(error) }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        if (!closed) finish(openAIStreamFailure(t, response))
    }

    override fun onClosed(eventSource: EventSource) {
        if (closed) return
        try {
            val chunks = decoder.onClosed()
            observe(chunks)
            requireOutput()
            sendChunks(chunks)
            finish(null)
        } catch (error: Throwable) { finish(error) }
    }

    private fun observe(chunks: List<StreamChunk>) {
        if (chunks.any { chunk -> when (chunk) {
            is StreamChunk.TextDelta -> chunk.text.isNotEmpty()
            is StreamChunk.ReasoningDelta -> chunk.text.isNotEmpty() || chunk.metadata != null
            is StreamChunk.ReasoningStart -> chunk.metadata != null
            is StreamChunk.ToolCallStart, is StreamChunk.ToolCallDelta,
            is StreamChunk.ServerToolStart, is StreamChunk.ServerToolEnd -> true
            is StreamChunk.ImageDelta -> chunk.data.isNotEmpty()
            is StreamChunk.ImageSnapshot -> chunk.data.isNotEmpty()
            else -> false // Role-only, usage, empty starts, heartbeat and Finish are not output.
        } }) hasOutput = true
    }

    private fun requireOutput() {
        if (!hasOutput) throw HttpException(
            "响应流已结束，但没有返回正文、思考或工具内容；本轮未完成，未自动重发。",
            code = "upstream_empty_stream", errorType = "openai_stream_error",
        ) // Deliberately not IOException: an empty continuation must not replay prior tool work.
    }

    private fun finish(error: Throwable?) {
        if (closed) return
        closed = true
        closeStream(error)
    }
}
