package me.rerere.rikkahub.data.orbis.integration

import kotlinx.serialization.json.*

/** Only the human's native guide consumes this value. Never serialize it into tool output. */
internal class DeploymentCredentials(val gateway: String, val mcp: String) {
    override fun toString() = "DeploymentCredentials([redacted])"
    fun humanExport() = "ST 网关密钥\n$gateway\n\nST MCP 密钥\n$mcp\n"
}

internal fun parseDeploymentCredentials(text: String): DeploymentCredentials {
    require(text.toByteArray(Charsets.UTF_8).size <= 16384) { "credential_file_too_large" }
    val keys = setOf("STBRAIN_GATEWAY_TOKEN", "STBRAIN_MCP_TOKEN")
    val values = if (text.trimStart().startsWith("{")) {
        val obj = Json.parseToJsonElement(text).jsonObject
        keys.associateWith { key -> (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("credential_missing") }
    } else {
        val result = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val source = line.trim().removePrefix("export ")
            val key = source.substringBefore('=', "").trim()
            if (key in keys) {
                require(key !in result) { "duplicate_credential" }
                var value = source.substringAfter('=').trim()
                if (value.startsWith('"') || value.startsWith('\'')) {
                    require(value.length >= 2 && value.last() == value.first()) { "invalid_credential" }
                    value = value.substring(1, value.lastIndex)
                }
                result[key] = value
            }
        }
        result
    }
    fun token(key: String): String = values[key]?.also { value ->
        require(value.length in 16..1024 && value.all { it.code in 33..126 && it !in "\\\"'`$" }) { "invalid_credential" }
    } ?: error("credential_missing")
    val gateway = token("STBRAIN_GATEWAY_TOKEN"); val mcp = token("STBRAIN_MCP_TOKEN")
    require(gateway != mcp) { "credentials_must_differ" }
    return DeploymentCredentials(gateway, mcp)
}
