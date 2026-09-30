package me.rerere.rikkahub.ui.pages.orbis

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal interface GatewayRouteTransport {
    suspend fun capability(base: String): Boolean
    suspend fun directory(base: String, token: String): GatewayRouteDirectory
    suspend fun upsert(base: String, token: String, value: GatewayRouteWrite): GatewayRouteReceipt
    suspend fun receipt(base: String, token: String, value: GatewayRouteWrite): GatewayRouteReceipt?
}

/** Dedicated client: never inherits chat interceptors, proxies, retry, cookies or authenticators. */
internal class GatewayRouteClient : GatewayRouteTransport {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
        .cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun capability(base: String) = parseGatewayCapability(request(base, "capabilities", null))
    override suspend fun directory(base: String, token: String) = parseGatewayRoutes(request(base, "", token))
    override suspend fun upsert(base: String, token: String, value: GatewayRouteWrite): GatewayRouteReceipt {
        value.validate()
        return parseGatewayReceipt(request(base, "upsert", token, value), value.requestId, value.publicModel)
    }
    override suspend fun receipt(base: String, token: String, value: GatewayRouteWrite) =
        parseGatewayRequest(request(base, "requests/${value.requestId}", token), value.requestId, value.publicModel)

    private suspend fun request(base: String, endpoint: String, token: String?, write: GatewayRouteWrite? = null): String = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(gatewayManagementUrl(base, endpoint))
                .header("Accept", "application/json").header("Cache-Control", "no-store")
            token?.let { builder.header("Authorization", "Bearer ${gatewayAdminToken(it)}") }
            if (write == null) builder.get() else {
                val body = write.body()
                routeRequire(body.toByteArray().size <= 12_000)
                builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            }
            val call = client.newCall(builder.build())
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
            response.use {
                if (!it.isSuccessful) {
                    // A malformed/network/5xx response to POST remains uncertain. Do not echo bodies.
                    val known = when (it.code) {
                        401 -> "unauthorized"
                        403 -> "disabled"
                        404 -> if (endpoint == "capabilities") "not_gateway" else "connection"
                        400, 409, 429 -> runCatching {
                            val source = it.body.source()
                            if (source.request(4097)) null else gatewayObject(source.buffer.readUtf8())["error"]?.jsonObject
                                ?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { code -> code in setOf(
                                    "invalid_request", "unsafe_upstream", "revision_conflict", "request_conflict", "immutable_route",
                                    "route_limit_reached", "management_rate_limited", "journal_limit_reached") }
                        }.getOrNull()?.let { code -> when (code) { "invalid_request" -> "invalid"; "unsafe_upstream" -> "unsafe_address"; else -> code } }
                        else -> null
                    }
                    throw GatewayRouteException(known ?: if (write != null) "unknown" else "connection",
                        definiteRejection = write != null && known != null && it.code in setOf(400, 401, 403, 409, 429))
                }
                val body = it.body
                routeRequire(body.contentLength() <= GATEWAY_ROUTES_MAX_BYTES, "response")
                val source = body.source()
                routeRequire(!source.request(GATEWAY_ROUTES_MAX_BYTES.toLong() + 1), "response")
                val text = source.buffer.readUtf8()
                routeRequire(token.isNullOrEmpty() || !text.contains(token), "response")
                routeRequire(write?.apiKey.isNullOrEmpty() || !text.contains(write!!.apiKey!!), "response")
                text
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: GatewayRouteException) { throw error }
        catch (_: Exception) { throw GatewayRouteException(if (write == null) "connection" else "unknown") }
    }
}
