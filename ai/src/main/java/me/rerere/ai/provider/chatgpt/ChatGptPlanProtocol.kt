package me.rerere.ai.provider.chatgpt

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Official OSS SIWC only. No Codex client impersonation or backend-api endpoint. */
object ChatGptPlanProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    const val PERMISSION = "chatgpt.tokens.use.direct"
    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorize(hostId: String, redirectUri: String, state: String, nonce: String, verifier: String,
        clientId: String? = null, email: String? = null, idToken: String? = null): String {
        val redirect = redirectUri.toHttpUrl()
        require(redirect.scheme == "http" && redirect.host == "127.0.0.1" && redirect.encodedPath == "/auth/callback" && redirect.query == null)
        return AUTHORIZE.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", clientId ?: "dynamic_agent_client")
            if (clientId == null) addQueryParameter("agent_name_hint", "Orbis")
            addQueryParameter("ext_agent_host_id", hostId)
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirectUri)
            addQueryParameter("scope", SCOPE)
            addQueryParameter("resource", RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge(verifier))
            if (clientId != null) {
                email?.takeIf(String::isNotBlank)?.let { addQueryParameter("login_hint", it) }
                idToken?.takeIf(String::isNotBlank)?.let { addQueryParameter("id_token_hint", it) }
            }
        }.build().toString()
    }

    fun callbackClient(expectedState: String, actualState: String, savedClient: String?, issuedClient: String?, error: String?): String {
        require(MessageDigest.isEqual(expectedState.toByteArray(), actualState.toByteArray())) { "siwc_state_invalid" }
        require(error == null) { "siwc_consent_declined" }
        if (savedClient != null) {
            require(issuedClient == null || issuedClient == savedClient) { "siwc_account_mismatch" }
            return savedClient
        }
        require(issuedClient != null && issuedClient.matches(Regex("oaiapp_[A-Za-z0-9_-]{1,180}"))) { "siwc_registration_incomplete" }
        return issuedClient
    }

    /** Metadata currently advertises RS256. Reject every other algorithm, including alg=none. */
    fun verifyIdToken(token: String, jwks: JsonObject, clientId: String, nonce: String?,
        now: Long = System.currentTimeMillis() / 1000): JsonObject {
        require(token.length <= 65_536) { "siwc_identity_invalid" }
        val parts = token.split('.')
        require(parts.size == 3) { "siwc_identity_invalid" }
        fun decode(part: String) = Base64.getUrlDecoder().decode(part)
        val header = Json.parseToJsonElement(decode(parts[0]).decodeToString()).jsonObject
        require(header["alg"]?.jsonPrimitive?.content == "RS256") { "siwc_identity_algorithm" }
        val kid = header["kid"]?.jsonPrimitive?.content ?: error("siwc_identity_key")
        val key = jwks["keys"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
            it["kid"]?.jsonPrimitive?.content == kid && it["kty"]?.jsonPrimitive?.content == "RSA" &&
                it["use"]?.jsonPrimitive?.content.let { use -> use == null || use == "sig" } &&
                it["alg"]?.jsonPrimitive?.content.let { alg -> alg == null || alg == "RS256" }
        } ?: error("siwc_identity_key")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decode(key.getValue("n").jsonPrimitive.content)),
            BigInteger(1, decode(key.getValue("e").jsonPrimitive.content))))
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decode(parts[2]))
        }) { "siwc_identity_signature" }
        val claims = Json.parseToJsonElement(decode(parts[1]).decodeToString()).jsonObject
        require(claims["iss"]?.jsonPrimitive?.content == ISSUER) { "siwc_identity_issuer" }
        val audiences = when (val aud = claims["aud"]) { is JsonArray -> aud.map { it.jsonPrimitive.content }; is JsonPrimitive -> listOf(aud.content); else -> emptyList() }
        require(clientId in audiences && (audiences.size <= 1 || claims["azp"]?.jsonPrimitive?.content == clientId)) { "siwc_identity_audience" }
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0) > now) { "siwc_identity_expired" }
        require((claims["nbf"]?.jsonPrimitive?.longOrNull ?: 0) <= now + 30) { "siwc_identity_not_yet_valid" }
        if (nonce != null) require(claims["nonce"]?.jsonPrimitive?.content == nonce) { "siwc_identity_nonce" }
        require(!claims["sub"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) { "siwc_identity_subject" }
        return claims
    }

    fun safeMessage(error: Throwable): String = when ((error as? me.rerere.ai.util.HttpException)?.code ?: error.message) {
        "siwc_consent_declined" -> "已取消授权，原有连接未改变。"
        "siwc_permission_missing" -> "已登录，但尚未获准使用 ChatGPT 套餐。请在 ChatGPT 设置中确认权限。"
        "siwc_reauth_required", "siwc_not_signed_in" -> "ChatGPT 授权已失效或尚未登录，请重新授权。"
        "siwc_stream_interrupted" -> "ChatGPT 响应中断，已保留收到的内容；没有自动重发或切换到付费 API。"
        "siwc_limit", "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 套餐用量已达限制。请到 ChatGPT 设置 → Usage 管理用量；未改用付费 API。"
        "siwc_capability", "subscription_sharing_unsupported_capability" -> "当前 ChatGPT 套餐接口不支持这项输入、工具或请求设置。"
        else -> ChatGptPlanFailure.from(error).message.orEmpty()
    }
}
