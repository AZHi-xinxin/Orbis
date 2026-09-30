package me.rerere.ai.provider.providers.openai

import java.math.RoundingMode
import java.math.BigDecimal
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.getByKey
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

const val SILICON_FLOW_BALANCE_WEBSITE = "https://cloud.siliconflow.cn/"
const val SILICON_FLOW_RETIRED_BALANCE_NOTICE = "余额查询接口已由硅基流动停用，请到官方控制台查看"

/** Official CN account API was retired on 2026-08-14. Match the exact HTTPS origin and only
 * the retired endpoint / historical defaults (including a copied DeepSeek preset). Never infer from display name, redirect a key,
 * override a proxy's contract, or disable an explicitly configured custom balance endpoint.
 * https://docs.siliconflow.cn/docs/release-notes/overview
 */
fun ProviderSetting.OpenAI.retiredSiliconFlowBalanceNotice(): String? {
    val legacy = balanceOption.apiPath == "/user/info" ||
        (balanceOption.apiPath == "/credits" && balanceOption.resultPath == "data.total_usage") ||
        (balanceOption.apiPath == "/user/balance" && balanceOption.resultPath == "balance_infos[0].total_balance")
    if (!legacy) return null
    val address = runCatching {
        val uri = URI(baseUrl)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.orEmpty().trimEnd('/') in setOf("", "/v1"))
        require(baseUrl.none { it <= ' ' || it == '\\' || it == '\u007f' })
        baseUrl.toHttpUrl()
    }.getOrNull() ?: return null
    return SILICON_FLOW_RETIRED_BALANCE_NOTICE.takeIf {
        address.scheme == "https" && address.host == "api.siliconflow.cn" && address.port == 443 &&
            address.encodedPath.trimEnd('/') in setOf("", "/v1")
    }
}

/** No provider-name inference and no credential substitution between providers. */
internal fun balanceUrl(baseUrl: String, apiPath: String): HttpUrl {
    require(apiPath.startsWith('/') && !apiPath.startsWith("//") &&
        apiPath.none { it <= ' ' || it == '\\' || it == '\u007f' }) { "余额接口地址无效" }
    val base = try {
        require(baseUrl.none { it <= ' ' || it == '\\' || it == '\u007f' })
        val uri = URI(baseUrl)
        require(uri.scheme in setOf("http", "https") && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && !uri.host.isNullOrEmpty())
        baseUrl.trimEnd('/').toHttpUrl()
    } catch (_: Exception) { error("余额接口地址无效") }
    // DeepSeek documents this account endpoint at the origin, not under a model API version.
    if (base.host == "api.deepseek.com" && base.scheme == "https" &&
        base.encodedPath.trimEnd('/') in listOf("", "/v1", "/beta") && apiPath == "/user/balance") {
        return base.newBuilder().encodedPath(apiPath).query(null).fragment(null).build()
    }
    return try {
        (baseUrl.trimEnd('/') + apiPath).toHttpUrl().also {
            require(it.scheme == base.scheme && it.host == base.host && it.port == base.port &&
                it.username.isEmpty() && it.password.isEmpty())
        }
    } catch (_: Exception) { error("余额接口地址无效") }
}

internal fun legacyBalanceFallback(setting: ProviderSetting.OpenAI, code: Int): HttpUrl? =
    if (code == 404 && setting.balanceOption.apiPath == "/credits" &&
        setting.balanceOption.resultPath == "data.total_usage") balanceUrl(setting.baseUrl, "/user/balance") else null

internal fun formatProviderBalance(body: JsonObject, resultPath: String, officialShape: Boolean): String {
    if (officialShape) {
        val balances = body["balance_infos"]?.jsonArray ?: error("余额接口未返回可用金额")
        require(balances.isNotEmpty()) { "余额接口未返回可用金额" }
        // Each currency stays separate; never add CNY and USD or mistake usage for balance.
        return balances.joinToString(" · ") {
            val entry = it.jsonObject
            val currency = entry["currency"]?.jsonPrimitive?.contentOrNull
            require(currency in setOf("CNY", "USD")) { "余额接口返回了未知币种" }
            val amount = entry["total_balance"]?.jsonPrimitive?.contentOrNull?.boundedBalanceDecimal()
                ?: error("余额接口未返回可用金额")
            "${amount.setScale(2, RoundingMode.HALF_UP).toPlainString()} $currency"
        }
    }
    val value = body.getByKey(resultPath)
    require(value.isNotBlank() && value != "null") { "余额接口未返回可用金额" }
    return value.boundedBalanceDecimal()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: value
}

private fun String.boundedBalanceDecimal(): BigDecimal? {
    require(length <= 96) { "余额接口返回异常" }
    val value = toBigDecimalOrNull() ?: return null
    require(value.precision() <= 48 && value.scale() in -12..24) { "余额接口返回异常" }
    return value
}

/** In-memory cache identity contains no raw credential and changes when any routing input changes. */
fun ProviderSetting.OpenAI.balanceCacheIdentity(): String {
    val data = listOf(id.toString(), baseUrl, apiKey, balanceOption.enabled.toString(),
        balanceOption.apiPath, balanceOption.resultPath).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

internal fun balanceHttpFailure(code: Int): Nothing = error(when (code) {
    401, 403 -> "余额查询未获授权，请检查此连接的密钥"
    404 -> "此连接未提供余额接口"
    429 -> "余额查询过于频繁，请稍后刷新"
    else -> "余额暂不可用（HTTP $code）"
})
