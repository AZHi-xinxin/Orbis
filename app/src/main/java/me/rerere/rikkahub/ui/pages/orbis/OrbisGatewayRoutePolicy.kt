package me.rerere.rikkahub.ui.pages.orbis

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val GATEWAY_ROUTES_MAX_BYTES = 65_536
internal class GatewayRouteException(val code: String, val definiteRejection: Boolean = false) : IllegalStateException(when (code) {
    "not_gateway" -> "这不是支持自助线路管理的 ST 网关；普通模型服务请放在下方「上游服务」中选择。"
    "disabled" -> "此 ST 尚未开启独立的人类线路管理授权，请先由服务器维护者启用一次。"
    "unauthorized" -> "人类管理授权未通过。这里需要专用管理凭据，不是聊天或 MCP 密钥。"
    "unsafe_address" -> "ST 管理连接仅支持 HTTPS 或 Tailscale 的 100.64–100.127 地址；上游必须为公开 HTTPS 地址。"
    "invalid" -> "线路信息不完整或格式不支持，请核对地址、精确型号和线路名。"
    "unsupported" -> "此连接有高级覆盖、非聊天接口或多个密钥，不能自动搬入；请使用单条兼容连接或在此页手动填写。"
    "revision_conflict" -> "服务器线路已被其他操作修改，请重新读取后核对；没有覆盖新配置。"
    "immutable_route" -> "服务器默认或环境配置线路不能在手机覆盖，请另起一个线路名。"
    "request_conflict" -> "同一提交编号已用于不同内容，已停止；请先核对原回执。"
    "route_limit_reached" -> "已达到服务器允许的线路数量，请先请维护者整理；没有替换旧线路。"
    "management_rate_limited" -> "操作过于频繁，请稍后重新读取和确认；没有自动重试。"
    "journal_limit_reached" -> "服务器提交回执记录已满，请维护者整理后再试；没有删除旧回执或替换线路。"
    "pending" -> "有一条提交尚未确认。请先核对上次提交，不会自动重发密钥或新建第二条。"
    "changed" -> "所选连接或型号已变化，请重新读取和确认；没有自动改绑。"
    "storage" -> "本机加密授权或提交记录未能安全读写，已停止；没有覆盖原文件。"
    "unknown" -> "提交结果尚未确认，已保留加密记录；请点「核对上次提交」，不要重复添加。"
    "response" -> "服务器响应不符合约定，已停止；没有采用不明配置。"
    else -> "这次连接没有完成，未自动重试；请核对 ST 状态后再操作。"
})
internal fun routeRequire(value: Boolean, code: String = "invalid") { if (!value) throw GatewayRouteException(code) }

/** Management secrets may use TLS or numeric Tailscale only, not ordinary cleartext LAN. */
internal fun gatewayManagementBase(value: String): HttpUrl {
    routeRequire(value.length <= 2048 && value.none { it.isISOControl() }, "unsafe_address")
    val url = value.trim().toHttpUrlOrNull() ?: throw GatewayRouteException("unsafe_address")
    val octets = url.host.split('.').map { it.toIntOrNull() }
    val tailnet = octets.size == 4 && octets.all { it != null && it in 0..255 } && octets[0] == 100 && octets[1]!! in 64..127
    routeRequire(url.isHttps || tailnet, "unsafe_address")
    routeRequire(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
        url.encodedPath.none { it == '%' || it == '\\' }, "unsafe_address")
    routeRequire(url.encodedPath.trimEnd('/').endsWith("/v1"), "unsafe_address")
    return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/')).build()
}
internal fun gatewayManagementUrl(base: String, endpoint: String): HttpUrl {
    routeRequire(endpoint in setOf("capabilities", "", "upsert") || endpoint.matches(Regex("requests/[0-9a-f]{32}")))
    val root = gatewayManagementBase(base)
    return root.newBuilder().encodedPath(root.encodedPath + "/st/routes" + if (endpoint.isEmpty()) "" else "/$endpoint").build()
}
internal fun gatewayUpstreamBase(value: String): String {
    val url = value.trim().toHttpUrlOrNull() ?: throw GatewayRouteException("unsafe_address")
    routeRequire(value.length <= 2048 && value.none { it.isISOControl() } && url.isHttps && url.username.isEmpty() &&
        url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath.none { it == '%' || it == '\\' }, "unsafe_address")
    val host = url.host.lowercase()
    val octets = host.split('.').map { it.toIntOrNull() }
    val numeric = octets.size == 4 && octets.all { it != null && it in 0..255 }
    val privateAddress = numeric && (octets[0] in setOf(0, 10, 127) || octets[0]!! >= 224 ||
        (octets[0] == 169 && octets[1] == 254) || (octets[0] == 172 && octets[1]!! in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) || (octets[0] == 100 && octets[1]!! in 64..127))
    routeRequire(!privateAddress && ':' !in host && host != "localhost" && !host.endsWith(".localhost") &&
        !host.endsWith(".local") && '.' in host, "unsafe_address")
    return url.toString().trimEnd('/') // Server also resolves and checks all addresses against its allowlist.
}
internal fun gatewayRouteName(value: String): String = value.trim().also {
    routeRequire(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) && !it.endsWith("--auxiliary-no-memory"))
}
internal fun gatewayUpstreamModel(value: String): String = value.trim().also {
    routeRequire(it.length in 1..128 && it.none { c -> c.isWhitespace() || c.isISOControl() })
}
internal fun gatewayAdminToken(value: String): String = value.trim().also {
    routeRequire(it.length in 32..4096 && it.all { c -> c.code in 33..126 }, "unauthorized")
}
internal fun gatewaySingleKey(value: String): String = value.trim().also {
    routeRequire(it.length in 1..4096 && it.none { c -> c.isWhitespace() || c.isISOControl() || c == ',' }, "unsupported")
}
internal fun gatewaySource(provider: ProviderSetting.OpenAI, model: Model): Pair<String, String> {
    routeRequire(provider.enabled && !provider.useResponseApi && provider.chatCompletionsPath == "/chat/completions" &&
        model.type == ModelType.CHAT && model.providerOverwrite == null && model.customBodies.isEmpty() &&
        model.customHeaders.isEmpty() && model.tools.isEmpty(), "unsupported")
    gatewaySingleKey(provider.apiKey)
    return gatewayUpstreamBase(provider.baseUrl) to gatewayUpstreamModel(model.modelId)
}

internal data class GatewayRouteRow(val publicModel: String, val upstreamBaseUrl: String, val upstreamModel: String,
    val managed: Boolean, val keyConfigured: Boolean)
internal data class GatewayRouteDirectory(val revision: Long, val routes: List<GatewayRouteRow>)
internal data class GatewayRouteReceipt(val requestId: String, val revision: Long, val publicModel: String)
internal fun gatewayObject(body: String): JsonObject = try {
    routeRequire(body.toByteArray().size <= GATEWAY_ROUTES_MAX_BYTES, "response")
    Json.parseToJsonElement(body).jsonObject
} catch (error: GatewayRouteException) { throw error } catch (_: Exception) { throw GatewayRouteException("response") }
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.let { routeRequire(it.isString, "response"); it.content }
private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.let { routeRequire(!it.isString, "response"); it.boolean }
internal fun parseGatewayCapability(body: String): Boolean = guardedRouteParse {
    val root = gatewayObject(body)
    routeRequire(root.text("schema") == "orbis.st.routes-capabilities/1" && root.text("authorization") == "dedicated-human-bearer", "not_gateway")
    routeRequire(root.getValue("maxRoutes").jsonPrimitive.int in 1..64, "response")
    root.bool("enabled")
}
internal fun parseGatewayRoutes(body: String): GatewayRouteDirectory = guardedRouteParse {
    val root = gatewayObject(body)
    routeRequire(root.text("schema") == "orbis.st.routes/1", "response")
    val revision = root.getValue("revision").jsonPrimitive.long.also { routeRequire(it >= 0, "response") }
    val data = root.getValue("routes").jsonArray.also { routeRequire(it.size <= 64, "response") }
    val rows = data.map { element -> val row = element.jsonObject
        GatewayRouteRow(gatewayRouteName(row.text("publicModel")), row.text("upstreamBaseUrl").also {
            routeRequire(it.length <= 2048 && it.none(Char::isISOControl), "response")
        }, gatewayUpstreamModel(row.text("upstreamModel")), row.bool("managed"), row.bool("keyConfigured"))
    }
    routeRequire(rows.map { it.publicModel }.distinct().size == rows.size, "response")
    GatewayRouteDirectory(revision, rows)
}
internal fun parseGatewayReceipt(body: String, requestId: String, alias: String): GatewayRouteReceipt = guardedRouteParse {
    val root = gatewayObject(body)
    routeRequire(root.text("schema") == "orbis.st.routes-upsert-result/1" && root.text("requestId") == requestId &&
        root.text("publicModel") == alias && root.text("status") == "saved", "response")
    GatewayRouteReceipt(requestId, root.getValue("revision").jsonPrimitive.long.also { routeRequire(it >= 0, "response") }, alias)
}
internal fun parseGatewayRequest(body: String, requestId: String, alias: String): GatewayRouteReceipt? = guardedRouteParse {
    val root = gatewayObject(body)
    routeRequire(root.text("schema") == "orbis.st.routes-request/1" && root.text("requestId") == requestId, "response")
    if (root.bool("found")) parseGatewayReceipt(root.getValue("result").toString(), requestId, alias)
    else { routeRequire(root["result"] == JsonNull, "response"); null }
}
private inline fun <T> guardedRouteParse(action: () -> T): T = try { action() }
    catch (error: GatewayRouteException) { throw error } catch (_: Exception) { throw GatewayRouteException("response") }

@Serializable
internal class GatewayRouteWrite(val requestId: String, val expectedRevision: Long, val publicModel: String,
    val upstreamBaseUrl: String, val upstreamModel: String, val apiKey: String? = null) {
    fun body(): String = buildJsonObject {
        put("schema", "orbis.st.routes-upsert/1"); put("requestId", requestId); put("expectedRevision", expectedRevision)
        put("publicModel", publicModel); put("upstreamBaseUrl", upstreamBaseUrl); put("upstreamModel", upstreamModel)
        apiKey?.let { put("apiKey", it) }
    }.toString()
    fun validate() {
        routeRequire(requestId.matches(Regex("[0-9a-f]{32}")) && expectedRevision >= 0)
        gatewayRouteName(publicModel); gatewayUpstreamBase(upstreamBaseUrl); gatewayUpstreamModel(upstreamModel)
        apiKey?.let(::gatewaySingleKey)
    }
    override fun toString() = "GatewayRouteWrite(private)"
}
