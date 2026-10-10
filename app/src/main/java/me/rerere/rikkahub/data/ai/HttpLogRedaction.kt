package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val HTTP_LOG_BODY_LIMIT = 1_048_576L
internal const val HTTP_LOG_BODY_OMITTED = "[Request body omitted: only bounded JSON diagnostics are recorded.]"
internal const val HTTP_LOG_MEDIA_REDACTED = "[Media payload omitted from diagnostics.]"
internal const val HTTP_LOG_CREDENTIAL_REDACTED = "[Credential omitted]"

internal fun redactHttpLogHeaders(headers: Map<String, String>): Map<String, String> =
    headers.mapValues { (name, value) -> if (isHttpLogCredentialName(name)) HTTP_LOG_CREDENTIAL_REDACTED else value }

internal fun redactHttpLogUrl(value: String): String {
    val url = value.toHttpUrlOrNull() ?: return "[Unparseable URL omitted]"
    return url.newBuilder().apply {
        if (url.username.isNotEmpty()) username(HTTP_LOG_CREDENTIAL_REDACTED)
        if (url.password.isNotEmpty()) password(HTTP_LOG_CREDENTIAL_REDACTED)
        url.queryParameterNames.filter(::isHttpLogCredentialName).forEach { name ->
            setQueryParameter(name, HTTP_LOG_CREDENTIAL_REDACTED)
        }
        // Fragments are not sent over HTTP and have no diagnostic value here.
        fragment(null)
    }.build().toString()
}

private fun isHttpLogCredentialName(name: String): Boolean {
    val key = name.lowercase().filter(Char::isLetterOrDigit)
    return key in setOf("key", "auth", "cookie", "setcookie", "sig", "signature", "credential", "credentials", "password", "passwd") ||
        key.contains("authorization") || key.endsWith("apikey") || key.endsWith("token") ||
        key.endsWith("secret") || key.endsWith("accesskey") || key.endsWith("subscriptionkey") ||
        key.endsWith("signature") || key.endsWith("credential")
}

/** A diagnostic copy only. Never pass this projection back to a provider or request body. */
internal fun redactHttpLogBody(body: String): String {
    if (body.length > HTTP_LOG_BODY_LIMIT || !hasBoundedJsonNesting(body)) return HTTP_LOG_BODY_OMITTED
    return try {
        val parsed = Json.parseToJsonElement(body)
        if (parsed !is JsonObject && parsed !is JsonArray) HTTP_LOG_BODY_OMITTED
        else redactHttpLogElement(parsed, depth = 0).toString().takeIf { it.length <= HTTP_LOG_BODY_LIMIT }
            ?: HTTP_LOG_BODY_OMITTED
    } catch (_: Exception) {
        // Multipart, SSE, malformed/truncated JSON and unknown encodings may contain raw media.
        // They are not safe to publish as a plain string when structural redaction failed.
        HTTP_LOG_BODY_OMITTED
    }
}

/** Upstream/transport error prose can reflect an entire request, including unlabelled base64. */
internal fun redactHttpLogFailure(errorType: String): String =
    "HTTP request failed (${errorType.filter { it.isLetterOrDigit() || it == '_' }.take(80).ifBlank { "Exception" }}); raw error omitted from diagnostics."

private val mediaKeys = setOf(
    "image", "images", "imageurl", "imageurls", "imagedata", "inputimage", "outputimage",
    "audio", "audios", "audiourl", "audiodata", "inputaudio", "outputaudio",
    "video", "videos", "videourl", "videodata", "inputvideo",
    "inlinedata", "b64json", "base64", "bytes", "filedata", "data",
)
private val dataUri = Regex("data\\s*:\\s*(?:image|audio|video)/", RegexOption.IGNORE_CASE)
private val encodedDataUri = Regex("data%3a(?:image|audio|video)(?:/|%2f)", RegexOption.IGNORE_CASE)
private val encodedPayload = Regex("[A-Za-z0-9+/=_\\-\\r\\n]+")

/** Bound the parser itself, before its own recursive descent sees attacker-controlled nesting. */
private fun hasBoundedJsonNesting(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; if (depth > 64) return false }
            '}', ']' -> depth--
        }
    }
    return true
}

private fun redactHttpLogElement(value: JsonElement, depth: Int): JsonElement {
    // A deep or unusual shape must not make the logger recurse until the app crashes.
    if (depth > 48) return JsonPrimitive(HTTP_LOG_MEDIA_REDACTED)
    return when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            val normalized = key.lowercase().filter(Char::isLetterOrDigit)
            if (normalized in mediaKeys) JsonPrimitive(HTTP_LOG_MEDIA_REDACTED)
            else redactHttpLogElement(child, depth + 1)
        })
        is JsonArray -> JsonArray(value.map { redactHttpLogElement(it, depth + 1) })
        is JsonPrimitive -> {
            if (!value.isString) value else {
                val text = value.content
                when {
                    dataUri.containsMatchIn(text) || encodedDataUri.containsMatchIn(text) -> JsonPrimitive(HTTP_LOG_MEDIA_REDACTED)
                    // Some gateways return JSON inside a message string rather than an object.
                    text.trimStart().let { it.startsWith('{') || it.startsWith('[') } -> {
                        val nested = if (hasBoundedJsonNesting(text)) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
                        if (nested == null) JsonPrimitive(HTTP_LOG_MEDIA_REDACTED)
                        else JsonPrimitive(redactHttpLogElement(nested, depth + 1).toString())
                    }
                    // Unknown vendor fields can still carry bare base64. Prefer losing an opaque
                    // diagnostic identifier over retaining a screenshot after sharing has stopped.
                    text.length >= 64 && encodedPayload.matches(text) -> JsonPrimitive(HTTP_LOG_MEDIA_REDACTED)
                    else -> value
                }
            }
        }
    }
}
