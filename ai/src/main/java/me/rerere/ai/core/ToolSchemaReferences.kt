package me.rerere.ai.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** No schema or argument values are included in the error shown to users. */
class InvalidToolSchemaException(val toolName: String, val reasonCode: String) : IllegalArgumentException(
    "工具「$toolName」的参数定义无效（$reasonCode）。请刷新此 MCP 的工具目录；若仍失败，请检查服务端定义。请求尚未发送给模型。"
)

/**
 * Check local references without expanding them. Expanding recursive schemas would loop, and
 * dropping a reference (or its siblings) would silently relax the server's constraints.
 * Every tool has its own root: identically named definitions in two servers never share a scope.
 * External/anchor/dynamic references need a resolver we do not have; fail before the HTTP request.
 */
fun JsonElement.validateToolSchemaReferences(toolName: String): JsonElement {
    if (this == JsonNull) return this
    val root = this as? JsonObject ?: throw InvalidToolSchemaException(toolName, "invalid_schema_root")
    var visited = 0
    val checkedReferences = mutableSetOf<String>()
    fun fail(code: String): Nothing = throw InvalidToolSchemaException(toolName, code)

    fun resolve(reference: String): JsonElement {
        if (reference == "#") return root
        if (!reference.startsWith("#/")) fail("unsupported_reference")
        val fragment = reference.substring(1)
        if (Regex("%(?![0-9a-fA-F]{2})").containsMatchIn(fragment)) fail("invalid_reference_pointer")
        val pointer = try {
            // URLDecoder treats '+' as a space, unlike URI fragment syntax.
            URLDecoder.decode(fragment.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (_: IllegalArgumentException) { fail("invalid_reference_pointer") }
        val parts = pointer.substring(1).split('/')
        if (parts.any { Regex("~(?![01])").containsMatchIn(it) }) fail("invalid_reference_pointer")
        var target: JsonElement = root
        for (part in parts) {
            val key = part.replace("~1", "/").replace("~0", "~")
            target = when (val current = target) {
                is JsonObject -> current[key] ?: fail("missing_local_reference")
                is JsonArray -> {
                    if (!Regex("0|[1-9][0-9]*").matches(key)) fail("invalid_reference_pointer")
                    current.getOrNull(key.toIntOrNull() ?: fail("invalid_reference_pointer"))
                        ?: fail("missing_local_reference")
                }
                else -> fail("missing_local_reference")
            }
        }
        if (target !is JsonObject && (target !is JsonPrimitive || target.isString || target.booleanOrNull == null)) {
            fail("reference_target_not_schema")
        }
        return target
    }

    fun walk(value: JsonElement, depth: Int) {
        if (++visited > 20_000 || depth > 128) fail("schema_complexity_limit")
        if (value is JsonPrimitive && !value.isString && value.booleanOrNull != null) return
        val node = value as? JsonObject ?: fail("invalid_schema_node")
        // A nested id changes the resolution base. Do not pretend it is the tool's root.
        if (depth > 0 && node.containsKey("\$id")) fail("unsupported_nested_schema_id")
        if (node.containsKey("\$dynamicRef") || node.containsKey("\$recursiveRef")) fail("unsupported_dynamic_reference")
        node["\$ref"]?.let { ref ->
            val text = (ref as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("invalid_reference")
            val target = resolve(text)
            if (checkedReferences.add(text)) walk(target, depth + 1)
        }
        // Visit schema positions only: examples/defaults/const may contain ordinary '$ref' data.
        for (key in listOf("\$defs", "definitions", "properties", "patternProperties", "dependentSchemas")) {
            node[key]?.let { children ->
                (children as? JsonObject ?: fail("invalid_schema_map")).values.forEach { walk(it, depth + 1) }
            }
        }
        for (key in listOf("additionalProperties", "additionalItems", "contains", "propertyNames", "not",
            "if", "then", "else", "unevaluatedProperties", "unevaluatedItems", "contentSchema")) {
            node[key]?.let { walk(it, depth + 1) }
        }
        for (key in listOf("allOf", "anyOf", "oneOf", "prefixItems")) {
            node[key]?.let { children ->
                (children as? JsonArray ?: fail("invalid_schema_array")).forEach { walk(it, depth + 1) }
            }
        }
        node["items"]?.let { items ->
            if (items is JsonArray) items.forEach { walk(it, depth + 1) } else walk(items, depth + 1)
        }
        node["dependencies"]?.let { children ->
            (children as? JsonObject ?: fail("invalid_schema_map")).values.forEach {
                if (it !is JsonArray) walk(it, depth + 1)
            }
        }
    }
    walk(root, 0)
    return this
}
