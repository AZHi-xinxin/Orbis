package me.rerere.rikkahub.data.orbis.plugins

import kotlinx.serialization.json.*

/** Small, explicit JSON Schema subset: reject unsupported constraints instead of pretending to enforce them. */
internal object PluginSchema {
    private val keys = setOf("type", "description", "properties", "required", "items", "enum", "additionalProperties",
        "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "title", "default")
    fun validateSchema(schema: JsonObject) {
        require(schema.toString().toByteArray().size <= 16 * 1024) { "plugin_schema_size" }
        require(schema["type"]?.jsonPrimitive?.contentOrNull == "object") { "plugin_schema_object_required" }
        schema(schema, 0)
    }
    private fun schema(s: JsonObject, depth: Int) {
        require(depth <= 8 && s.keys.all { it in keys }) { "plugin_schema_unsupported" }
        val type = (s["type"] as? JsonPrimitive)?.contentOrNull
        require(type in setOf("object", "array", "string", "number", "integer", "boolean", "null")) { "plugin_schema_unsupported" }
        s["additionalProperties"]?.let { require(it == JsonPrimitive(false) || it == JsonPrimitive(true)) { "plugin_schema_unsupported" } }
        s["enum"]?.let { require(it is JsonArray && it.size in 1..100 && it.toString().length <= 4000) { "plugin_schema_unsupported" } }
        listOf("minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems").forEach { key ->
            s[key]?.let { require((it as? JsonPrimitive)?.doubleOrNull?.isFinite() == true) { "plugin_schema_unsupported" } }
        }
        s["properties"]?.let { props ->
            require(type == "object" && props is JsonObject && props.size <= 50) { "plugin_schema_unsupported" }
            props.forEach { (k,v) -> require(k.length in 1..80 && v is JsonObject) { "plugin_schema_unsupported" }; schema(v, depth + 1) }
        }
        s["required"]?.let { required ->
            require(type == "object" && required is JsonArray && required.size <= 50) { "plugin_schema_unsupported" }
            val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
            require(required.all { it is JsonPrimitive && it.isString && it.content in props }) { "plugin_schema_unsupported" }
        }
        s["items"]?.let { require(type == "array" && it is JsonObject) { "plugin_schema_unsupported" }; schema(it, depth + 1) }
    }
    fun validateArguments(schema: JsonObject, value: JsonElement) {
        require(value.toString().toByteArray().size <= 64 * 1024) { "plugin_arguments_invalid" }
        require(matches(schema, value, 0)) { "plugin_arguments_invalid" }
    }
    private fun matches(s: JsonObject, value: JsonElement, depth: Int): Boolean {
        if (depth > 16) return false
        if (s["enum"] is JsonArray && value !in s["enum"]!!.jsonArray) return false
        fun range(n: Double, low: String, high: String) = n.isFinite() &&
            (s[low]?.jsonPrimitive?.doubleOrNull?.let { n >= it } ?: true) &&
            (s[high]?.jsonPrimitive?.doubleOrNull?.let { n <= it } ?: true)
        return when (s["type"]?.jsonPrimitive?.content) {
            "object" -> value is JsonObject && value.size <= 100 && run {
                val props = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
                (s["required"] as? JsonArray).orEmpty().all { it.jsonPrimitive.content in value } &&
                    (s["additionalProperties"] != JsonPrimitive(false) || value.keys.all { it in props }) &&
                    props.all { (k,v) -> k !in value || matches(v.jsonObject, value.getValue(k), depth + 1) }
            }
            "array" -> value is JsonArray && value.size <= 16_384 && range(value.size.toDouble(), "minItems", "maxItems") &&
                ((s["items"] as? JsonObject)?.let { item -> value.all { matches(item,it,depth+1) } } ?: true)
            "string" -> value is JsonPrimitive && value.isString && range(value.content.length.toDouble(), "minLength", "maxLength")
            "number", "integer" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let {
                range(it,"minimum","maximum") && (s["type"]!!.jsonPrimitive.content != "integer" || it % 1.0 == 0.0)
            } == true
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "null" -> value == JsonNull
            else -> false
        }
    }
}
