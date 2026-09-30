package me.rerere.rikkahub.data.orbis.integration

import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.orbis.cloudtools.cloudReadBounded
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal const val ATLAS_BOOTSTRAP_SCOPE = "atlas.metadata.read"
internal fun atlasBootstrapDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Never a data class: no credential-bearing toString, logging, Settings or saved UI state. */
internal class AtlasBootstrapSource(val providerId: String, val root: String, val key: String,
    val fingerprint: String)

internal class AtlasBootstrapException(val code: String) : IOException(when (code) {
    "source_invalid" -> "请选择启用的单密钥 OpenAI 兼容连接；地址须为根地址或以 /v1 结尾。"
    "source_changed" -> "原连接已更改或停用，已停止登记；请撤销待确认授权或明确仅忘记本机记录。"
    "atlas_changed" -> "星图本机配置已改变，未覆盖；待确认授权仍保留。"
    "disabled" -> "此服务器尚未开启星图便捷授权，请使用高级手填或联系部署者。"
    "unauthorized" -> "服务器未接受授权；未删除本机待确认凭据。"
    "unsupported" -> "此地址未提供匹配的 ST 星图便捷接入协议。"
    "invalid_response" -> "服务器响应不符合星图协议，结果尚未确认。"
    "storage" -> "本机授权未能安全读取或保存；原文件保留，未自动重试。"
    "managed" -> "请先撤销当前授权，或明确仅忘记本机记录，再修改连接。"
    "revoked" -> "这次登记已经撤销，未启用星图；不会自动创建新授权。"
    else -> "连接结果尚未确认；凭据已保留，请手动重试确认，不会自动重新登记。"
})

internal fun atlasBootstrapSource(provider: ProviderSetting?): AtlasBootstrapSource {
    try {
        require(provider is ProviderSetting.OpenAI && provider.enabled)
        val keys = provider.apiKey.trim().split(Regex("[\\s,]+"))
        require(keys.size == 1)
        val key = validateOrbisIntegrationToken(keys.single())
        val raw = provider.baseUrl.trim()
        val uri = URI(raw)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        val path = uri.rawPath.orEmpty()
        require(path.none { it == '%' || it == '\\' } && !path.contains("//"))
        require(path.split('/').none { it == "." || it == ".." })
        val normalized = normalizeOrbisIntegrationUrl(raw)
        val stripped = path.trimEnd('/')
        require(stripped.isEmpty() || stripped.substringAfterLast('/') == "v1")
        val root = if (stripped.isEmpty()) normalized else normalized.removeSuffix("/v1")
        require(root.isNotEmpty() && !root.contains(key))
        val fingerprint = atlasBootstrapDigest(buildJsonArray {
            add(provider.id.toString()); add(root); add(key)
        }.toString())
        return AtlasBootstrapSource(provider.id.toString(), root, key, fingerprint)
    } catch (_: Exception) { throw AtlasBootstrapException("source_invalid") }
}

/** Flat, bounded protocol only; reject duplicate keys before kotlinx's last-value parser. */
internal fun atlasBootstrapObject(bytes: ByteArray): JsonObject {
    try {
        require(bytes.size <= 4096)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val keys = HashSet<String>()
        var i = 0
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { depth++; require(depth == 1) }
                '}' -> { depth--; require(depth == 0) }
                '[', ']' -> error("nested_protocol")
                '"' -> {
                    val start = i++
                    while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                    require(i < text.length && i - start < 512)
                    var next = i + 1
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val key = Json.parseToJsonElement(text.substring(start, i + 1)).jsonPrimitive.content
                        require(keys.add(key))
                    }
                }
            }
            i++
        }
        require(depth == 0)
        return Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) { throw AtlasBootstrapException("invalid_response") }
}

internal fun JsonObject.bootstrapString(key: String): String = getValue(key).jsonPrimitive.let {
    require(it.isString); it.content
}

internal data class AtlasBootstrapRegistration(val grantId: String, val active: Boolean)
internal interface AtlasBootstrapTransport {
    suspend fun capability(root: String)
    suspend fun register(source: AtlasBootstrapSource, requestId: String, deviceId: String,
        verifier: String): AtlasBootstrapRegistration
    suspend fun revoke(root: String, token: String, requestId: String)
}

internal class OrbisAtlasBootstrapClient : AtlasBootstrapTransport {
    internal val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).proxy(Proxy.NO_PROXY).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).cache(null)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun request(root: String, suffix: String, token: String? = null,
        body: JsonObject? = null): JsonObject = suspendCancellableCoroutine { continuation ->
        val url = normalizeOrbisIntegrationUrl(root) + suffix
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body == null) builder.get() else {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 2048)
            builder.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        val call = client.newCall(builder.build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(AtlasBootstrapException("unknown"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    if (it.code != 200) throw AtlasBootstrapException(when (it.code) {
                        401 -> "unauthorized"; 403 -> "disabled"; 404 -> "unsupported"; else -> "unknown"
                    })
                    require(it.request.url.toString() == url)
                    require(it.body.contentType()?.subtype == "json" && it.body.contentLength() <= 4096)
                    atlasBootstrapObject(it.body.byteStream().use { input -> input.cloudReadBounded(4096) })
                } }
                if (!continuation.isActive) return
                result.fold({ continuation.resume(it) }, {
                    continuation.resumeWithException(it as? AtlasBootstrapException ?: AtlasBootstrapException("invalid_response"))
                })
            }
        })
    }

    override suspend fun capability(root: String) {
        val value = request(root, "/v1/st/atlas/capabilities")
        validate {
            require(value.keys == setOf("schema", "service", "enabled", "scope", "atlasSchema"))
            require(value.bootstrapString("schema") == "orbis.st.atlas-bootstrap/1" &&
                value.bootstrapString("service") == "stillerbrain" &&
                value.bootstrapString("scope") == ATLAS_BOOTSTRAP_SCOPE &&
                value.bootstrapString("atlasSchema") == "orbis.st.atlas/1")
            val enabled = value.getValue("enabled").jsonPrimitive
            require(!enabled.isString)
            if (!enabled.boolean) throw AtlasBootstrapException("disabled")
        }
    }

    override suspend fun register(source: AtlasBootstrapSource, requestId: String, deviceId: String,
        verifier: String): AtlasBootstrapRegistration {
        val result = request(source.root, "/v1/st/atlas/grants", source.key, buildJsonObject {
            put("schema", "orbis.st.atlas-register/1"); put("requestId", requestId)
            put("deviceId", deviceId); put("verifier", verifier)
        })
        return validate {
            require(result.keys == setOf("schema", "requestId", "grantId", "status", "scope", "expiresAt"))
            require(result.bootstrapString("schema") == "orbis.st.atlas-register-result/1" &&
                result.bootstrapString("requestId") == requestId &&
                result.bootstrapString("scope") == ATLAS_BOOTSTRAP_SCOPE && result["expiresAt"] == JsonNull)
            val grant = result.bootstrapString("grantId")
            require(grant.matches(Regex("[0-9a-f]{32}")))
            val status = result.bootstrapString("status")
            require(status in setOf("active", "revoked"))
            AtlasBootstrapRegistration(grant, status == "active")
        }
    }

    override suspend fun revoke(root: String, token: String, requestId: String) {
        val result = request(root, "/v1/st/atlas/grants/revoke", token, buildJsonObject {
            put("schema", "orbis.st.atlas-revoke/1"); put("requestId", requestId)
        })
        validate {
            require(result.keys == setOf("schema", "status") &&
                result.bootstrapString("schema") == "orbis.st.atlas-revoke-result/1" &&
                result.bootstrapString("status") == "revoked")
        }
    }

    private inline fun <T> validate(block: () -> T): T = try { block() }
    catch (e: AtlasBootstrapException) { throw e }
    catch (_: Exception) { throw AtlasBootstrapException("invalid_response") }
}
