package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Google `parameters` is an OpenAPI Schema subset, not arbitrary JSON Schema.
 * Keep RikkaHub's compatibility omissions and remove uniqueItems at every schema depth.
 * Orbis/MCP nullable type lists must become scalar type + nullable for this proto field.
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
    }.toMap()).withGoogleTypeUnion()
}

private fun JsonObject.withGoogleTypeUnion(): JsonObject {
    val array = this["type"] as? JsonArray ?: return this
    if (array.isEmpty() || array.any { it !is JsonPrimitive || !it.isString || it.content !in googleSchemaTypes }) return this
    val types = array.map { (it as JsonPrimitive).content }.distinct()
    val nonNull = types.filterNot { it == "null" }
    if (nonNull.isEmpty()) return JsonObject(this + ("type" to JsonPrimitive("null")))
    val schema = if (nonNull.size == 1) {
        JsonObject(this + ("type" to JsonPrimitive(nonNull.single())))
    } else {
        // Keep every type and its original constraints, including an existing anyOf.
        JsonObject(mapOf("anyOf" to JsonArray(nonNull.map { type ->
            JsonObject(this + ("type" to JsonPrimitive(type)))
        })))
    }
    return if ("null" in types) JsonObject(schema + ("nullable" to JsonPrimitive(true))) else schema
}

private val googleSchemaTypes = setOf("string", "number", "integer", "boolean", "array", "object", "null")

// RikkaHub GoogleProvider.kt @ a5db9c9b770f316c76136970aa01a9d790e10e51;
// retain Orbis's existing uniqueItems omission and schema-position-only traversal.
private val googleOmittedToolKeywords = setOf(
    "const", "exclusiveMaximum", "exclusiveMinimum", "format", "additionalProperties", "propertyNames", "enum", "uniqueItems",
)

/** On-demand structural checks on the exact Google adapter copy. No fields/values escape. */
fun googleToolSchemaIssueCodes(schema: JsonElement): Set<String> {
    val issues = linkedSetOf<String>()
    var nodes = 0
    fun visit(value: JsonElement, depth: Int) {
        if (++nodes > 4096 || depth > 32) { issues.add("schema_complexity_unchecked"); return }
        val obj = value as? JsonObject ?: return
        val type = obj["type"] as? JsonPrimitive
        if (type?.content?.lowercase() == "array" && obj["items"] !is JsonObject && obj["\$ref"] == null) issues.add("array_items_missing")
        if (obj["type"] is JsonArray) issues.add("type_array_unconverted")
        (obj["properties"] as? JsonObject)?.values?.forEach { visit(it, depth + 1) }
        (obj["\$defs"] as? JsonObject)?.values?.forEach { visit(it, depth + 1) }
        obj["items"]?.let { visit(it, depth + 1) }
        listOf("anyOf", "oneOf", "allOf").forEach { key -> (obj[key] as? JsonArray)?.forEach { visit(it, depth + 1) } }
    }
    // Bound the entire document before normalization, including less common schema keys.
    fun bounded(value: JsonElement, depth: Int): Boolean {
        if (++nodes > 4096 || depth > 32) return false
        return when (value) {
            is JsonObject -> value.values.all { bounded(it, depth + 1) }
            is JsonArray -> value.all { bounded(it, depth + 1) }
            else -> true
        }
    }
    if (!bounded(schema, 0)) return setOf("schema_complexity_unchecked")
    nodes = 0
    visit(schema.forGoogleToolParameters(), 0)
    return issues
}
