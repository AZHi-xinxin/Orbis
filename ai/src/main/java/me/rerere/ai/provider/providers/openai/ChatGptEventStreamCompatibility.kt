package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.chatgpt.ChatGptPlanProtocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.TimeUnit

/** Some direct-route responses omit Content-Type despite carrying genuine Responses SSE.
 * Repair only a missing type, on the fixed official inference route, after a bounded,
 * non-consuming peek at a typed Responses event. No retry, body rewrite or synthetic Finish.
 */
internal fun withChatGptEventStreamType(response: Response): Response {
    val body = response.body
    if (!response.isSuccessful || response.request.method != "POST" ||
        response.request.url.toString() != "${ChatGptPlanProtocol.RESOURCE}/responses" ||
        response.header("Content-Type") != null || body.contentType() != null) return response

    val source = body.source()
    val timeout = source.timeout()
    val previousDeadline = if (timeout.hasDeadline()) timeout.deadlineNanoTime() else null
    val probeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    try {
        timeout.deadlineNanoTime(previousDeadline?.coerceAtMost(probeDeadline) ?: probeDeadline)
        if (!source.peek().use(::hasTypedResponsesEvent)) return response
    } catch (failure: Throwable) {
        response.close()
        throw failure
    } finally {
        if (previousDeadline == null) timeout.clearDeadline() else timeout.deadlineNanoTime(previousDeadline)
    }
    return response.newBuilder().header("Content-Type", "text/event-stream")
        .body(object : ResponseBody() {
            override fun contentType() = "text/event-stream".toMediaType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }).build()
}

private fun hasTypedResponsesEvent(peek: BufferedSource): Boolean {
    // response.created contains the response's echoed tools/instructions. A normal chat
    // can therefore have a much larger first event than the tiny connection-test prompt.
    // Keep an independent small preamble budget, but validate one complete event up to
    // 1 MiB rather than imposing an 8 KiB limit on a legitimate JSON data line.
    var eventBytes = 0L
    var preambleBytes = 0L
    var preambleLines = 0
    var data: StringBuilder? = null
    repeat(4096) {
        if (!peek.request(1L)) return false
        val dataLine = peek.buffer[0L] == 'd'.code.toByte() &&
            peek.rangeEquals(0L, DATA_FIELD_PREFIX)
        val beforeData = data == null && !dataLine
        val budget = if (beforeData) {
            minOf(MAX_FIRST_EVENT_BYTES - eventBytes, 16_384L - preambleBytes, 8192L)
        } else {
            MAX_FIRST_EVENT_BYTES - eventBytes
        }
        if (budget <= 0) return false
        // Count actual wire bytes, including CRLF, rather than UTF-16 string length.
        val newline = peek.indexOf('\n'.code.toByte(), 0L, budget)
        if (newline < 0) return false
        val rawLine = peek.readUtf8(newline)
        peek.skip(1L)
        val line = rawLine.removeSuffix("\r")
        val wireBytes = newline + 1L
        eventBytes += wireBytes
        if (beforeData) {
            preambleBytes += wireBytes
            if (++preambleLines > 32) return false
        }
        when {
            line.isEmpty() -> {
                // Do not accept an unterminated/truncated first event. OkHttp receives
                // these same bytes afterwards and remains responsible for SSE decoding.
                if (data != null) return isTypedResponsesEvent(data.toString())
            }
            dataLine -> {
                val value = line.removePrefix("data:").removePrefix(" ")
                if (data == null) data = StringBuilder() else data!!.append('\n')
                data!!.append(value)
            }
            line.startsWith(':') || line.startsWith("event:") ||
                line.startsWith("id:") || line.startsWith("retry:") -> Unit
            else -> return false
        }
    }
    return false
}

private val DATA_FIELD_PREFIX = "data:".encodeUtf8()
private const val MAX_FIRST_EVENT_BYTES = 1024L * 1024L

private fun isTypedResponsesEvent(data: String): Boolean {
    val json = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return false
    val type = (json["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return type in setOf("response.created", "response.in_progress", "response.queued",
        "response.completed", "response.failed", "response.incomplete", "error")
}
