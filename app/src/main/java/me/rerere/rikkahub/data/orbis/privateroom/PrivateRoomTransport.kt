package me.rerere.rikkahub.data.orbis.privateroom

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.ui.UIMessage
import okhttp3.CookieJar
import okhttp3.Authenticator
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reuse only user's transport/proxy/TLS configuration; never inherit app interceptors or logs. */
internal fun privateRoomHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder().apply {
    interceptors().clear()
    networkInterceptors().clear()
    eventListener(EventListener.NONE)
    cookieJar(CookieJar.NO_COOKIES)
    authenticator(Authenticator.NONE)
    proxyAuthenticator(Authenticator.NONE)
    cache(null)
    retryOnConnectionFailure(false)
    followRedirects(false)
    followSslRedirects(false)
    callTimeout(40, TimeUnit.SECONDS)
    readTimeout(40, TimeUnit.SECONDS)
    addInterceptor { chain ->
        val original = chain.request()
        val request = if (original.url.encodedPath == "/v1/private-room/chat/completions")
            original.newBuilder().header("X-Orbis-Private-Room", "orbis-private-room/1").build() else original
        val response = chain.proceed(request)
        val body = response.body
        if (body.contentLength() > 1_048_576) { response.close(); throw IOException("private_response_limit") }
        val source = object : ForwardingSource(body.source()) {
            var remaining = 1_048_576L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, minOf(byteCount, remaining + 1))
                if (count > remaining) throw IOException("private_response_limit")
                if (count > 0) remaining -= count
                return count
            }
        }.buffer()
        val bounded = object : ResponseBody() {
            override fun contentType() = body.contentType()
            override fun contentLength() = body.contentLength()
            override fun source() = source
        }
        val content = bounded.use { it.string() }
        if (!privateJsonNestingAllowed(content, maximum = 48)) {
            response.close(); throw IOException("private_response_shape")
        }
        response.newBuilder().body(content.toResponseBody(body.contentType())).build()
    }
}.build()

/** Provider objects have no Android Context: no per-request cache/preferences or application logs. */
internal class PrivateRoomProviderModel(
    settings: ProviderSetting,
    private val params: TextGenerationParams,
    client: OkHttpClient,
) : PrivateRoomModel {
    private val settings = if (settings is ProviderSetting.Claude) settings.copy(promptCaching = false) else settings
    @Suppress("UNCHECKED_CAST")
    private val provider: Provider<ProviderSetting> = when (settings) {
        is ProviderSetting.OpenAI -> OpenAIProvider(client)
        is ProviderSetting.Google -> GoogleProvider(client)
        is ProviderSetting.Claude -> ClaudeProvider(client)
    } as Provider<ProviderSetting>

    override suspend fun respond(history: List<UIMessage>, tools: List<Tool>): UIMessage = try {
        val result = provider.generateText(settings, history, params.copy(tools = tools,
            model = params.model.copy(tools = emptySet(), customBodies = emptyList(), customHeaders = emptyList(), providerOverwrite = null),
            sessionId = null, orbisConversationId = null, maxAutomaticContinuations = 0,
            customHeaders = emptyList(), customBody = emptyList(), onGatewayRequest = null))
        if (result.finishReason?.lowercase() !in setOf("stop", "end_turn", "tool_calls", "tool_use", "function_call", "stop_sequence"))
            throw IOException("private_request_incomplete")
        result.message
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { throw IOException("private_request_failed") } // Never attach provider body/cause.
}
