package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Google `parameters` is an OpenAPI Schema subset, not arbitrary JSON Schema.
 * Keep the existing compatibility omissions and remove uniqueItems at every schema depth.
 * This is only a provider-facing copy: tools still validate their original arguments locally.
 * Never treat a parameter name or an example/default object as a schema keyword.
 */
internal fun JsonElement.forGoogleToolParameters(): JsonElement {
    if (this !is JsonObject) return this
    return JsonObject(entries.mapNotNull { (key, value) ->
        if (key in googleOmittedToolKeywords) return@mapNotNull null
        key to when (key) {
            "properties", "\$defs", "definitions", "patternProperties", "dependentSchemas" ->
                if (value is JsonObject) JsonObject(value.mapValues { (_, schema) -> schema.forGoogleToolParameters() })
                else value
            "items", "additionalItems", "contains", "not", "if", "then", "else", "propertyNames" ->
                if (value is JsonArray) JsonArray(value.map { it.forGoogleToolParameters() })
                else value.forGoogleToolParameters()
            "anyOf", "allOf", "oneOf", "prefixItems" ->
                if (value is JsonArray) JsonArray(value.map { it.forGoogleToolParameters() }) else value
            else -> value
        }
    }.toMap())
}

private val googleOmittedToolKeywords = setOf(
    "const", "exclusiveMaximum", "exclusiveMinimum", "format", "additionalProperties", "enum", "uniqueItems",
)
