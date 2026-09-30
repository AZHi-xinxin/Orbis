package me.rerere.ai.util

import me.rerere.ai.provider.TextGenerationParams
import okhttp3.Headers

/** These are routing labels, not identity/authentication. No title, user text or device ID is sent. */
fun TextGenerationParams.orbisSourceHeaders(): Headers {
    val reserved = setOf("x-st-thread-id", "x-session-id", "x-st-client-id")
    val headers = customHeaders.filterNot { it.name.lowercase() in reserved }.toHeaders().newBuilder()
    val thread = orbisConversationId?.takeIf { it.matches(Regex("[A-Za-z0-9_.:/@-]{1,200}")) }
    if (thread != null && !model.modelId.endsWith("--auxiliary-no-memory")) {
        headers.set("X-ST-Thread-ID", "orbis:$thread")
        headers.set("X-ST-Client-ID", "orbis-dev")
    }
    return headers.build()
}
