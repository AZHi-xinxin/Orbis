package me.rerere.rikkahub.data.orbis.cloudtools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
enum class CloudToolFamily(val wireName: String) {
    ORBIS("orbis"), READING("reading"), TURTLESOUP("turtlesoup"),
}

/** Only opt-in names belong in ordinary assistant backups; never addresses or credentials. */
@Serializable
data class CloudToolSelection(
    val orbis: Set<String> = emptySet(),
    val reading: Set<String> = emptySet(),
    val turtlesoup: Set<String> = emptySet(),
) {
    fun enabled(family: CloudToolFamily): Set<String> = when (family) {
        CloudToolFamily.ORBIS -> orbis
        CloudToolFamily.READING -> reading
        CloudToolFamily.TURTLESOUP -> turtlesoup
    }

    fun withEnabled(family: CloudToolFamily, name: String, enabled: Boolean): CloudToolSelection {
        require(CLOUD_TOOL_NAME.matches(name)) { "invalid_tool_name" }
        val names = if (enabled) this.enabled(family) + name else this.enabled(family) - name
        require(names.size <= 128) { "too_many_selected_tools" }
        return when (family) {
            CloudToolFamily.ORBIS -> copy(orbis = names)
            CloudToolFamily.READING -> copy(reading = names)
            CloudToolFamily.TURTLESOUP -> copy(turtlesoup = names)
        }
    }
}

@Serializable
data class CloudToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val effect: String,
    val requiresApproval: Boolean,
) {
    val needsApproval: Boolean get() = effect != "read" || requiresApproval
}

internal val CLOUD_TOOL_NAME = Regex("[A-Za-z][A-Za-z0-9_]{0,44}")
internal fun cloudNativeToolName(family: CloudToolFamily, name: String) = "cloud_${family.wireName}_$name"

/** The independent gateway is HTTPS-only on a Tailnet certificate hostname and fixed port. */
internal fun normalizeCloudGatewayUrl(value: String): String {
    require(value == value.trim() && value.length <= 512 && '\\' !in value && '%' !in value) { "invalid_gateway_url" }
    require(value.matches(Regex("https://[A-Za-z0-9.-]+:18910/?"))) { "invalid_gateway_url" }
    val url = value.toHttpUrlOrNull() ?: error("invalid_gateway_url")
    require(url.isHttps && url.port == 18910 && url.host.endsWith(".ts.net") &&
        url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" &&
        url.query == null && url.fragment == null) { "invalid_gateway_url" }
    return url.toString().removeSuffix("/")
}

/** Deliberately not a data class: toString must never disclose credentials. */
internal class CloudGatewayCredential(val baseUrl: String, val token: String) {
    override fun toString() = "CloudGatewayCredential(redacted)"
    fun sameAs(other: CloudGatewayCredential?) = other != null && baseUrl == other.baseUrl && token == other.token
}

internal fun validateCloudGatewayCredential(baseUrl: String, token: String): CloudGatewayCredential {
    val normalized = normalizeCloudGatewayUrl(baseUrl)
    require(token.matches(Regex("[A-Za-z0-9_-]{43,128}"))) {
        "invalid_device_credential"
    }
    return CloudGatewayCredential(normalized, token)
}

internal fun interface CloudCredentialSource {
    suspend fun readCredential(): CloudGatewayCredential?
}

class CloudToolsException(val code: String) : IllegalStateException("云端原生工具不可用：$code")
