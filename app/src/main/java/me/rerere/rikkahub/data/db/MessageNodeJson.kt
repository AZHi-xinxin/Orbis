package me.rerere.rikkahub.data.db

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.JsonInstant
import java.io.ByteArrayOutputStream

/**
 * Use the same UTF-8 encoding boundary as the generation journal before SQLite binds UTF-16.
 * A truncated/incoming string may contain a lone surrogate: handing it directly to a native
 * SQLite binder can consume the following JSON delimiter. The streaming encoder replaces only
 * invalid Unicode units, as it already does in checkpoints; valid text and JSON remain identical.
 * This applies to new writes only and never repairs, truncates, or rewrites stored history.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun encodeMessageNodeMessages(messages: List<UIMessage>): String {
    val output = ByteArrayOutputStream()
    JsonInstant.encodeToStream(messages, output)
    return output.toByteArray().toString(Charsets.UTF_8)
}
