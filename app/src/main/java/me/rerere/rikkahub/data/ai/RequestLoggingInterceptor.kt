package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = redactHttpLogHeaders(request.headers.toMap())
        val requestUrl = redactHttpLogUrl(request.url.toString())
        val requestBody = request.body?.let { body ->
            // Do not consume streaming/one-shot bodies for diagnostics or retain raw media in
            // the 100-entry log ring after the screen-sharing buffer has been cleared.
            val length = runCatching { body.contentLength() }.getOrDefault(-1L)
            val subtype = body.contentType()?.subtype.orEmpty()
            if (body.isDuplex() || body.isOneShot() || length !in 0..HTTP_LOG_BODY_LIMIT ||
                !(subtype.equals("json", ignoreCase = true) || subtype.endsWith("+json", ignoreCase = true))) {
                HTTP_LOG_BODY_OMITTED
            } else runCatching {
                Buffer().use { buffer ->
                    body.writeTo(buffer)
                    if (buffer.size > HTTP_LOG_BODY_LIMIT) HTTP_LOG_BODY_OMITTED
                    else redactHttpLogBody(buffer.readUtf8())
                }
            }.getOrDefault(HTTP_LOG_BODY_OMITTED)
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = redactHttpLogFailure(e.javaClass.simpleName)
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = requestUrl,
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = redactHttpLogHeaders(response.headers.toMap())

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = requestUrl,
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }
}
