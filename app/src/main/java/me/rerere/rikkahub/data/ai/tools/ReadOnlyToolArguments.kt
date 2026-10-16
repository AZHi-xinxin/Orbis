package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*

/** Only explicit, known read-only action placeholders are omitted. Never use for writes,
 * meaningful IDs, unknown keys, or revisions used for pagination/concurrency control. */
internal fun omitReadPlaceholders(raw: JsonObject, actions: Set<String>, placeholders: Map<String, JsonElement>): JsonObject {
    if ((raw["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content !in actions) return raw
    return JsonObject(raw.filterNot { (key, value) -> placeholders[key] == value })
}
