package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.chatgpt.ChatGptPlanAccounts
import me.rerere.ai.provider.chatgpt.ChatGptPlanCapabilities
import me.rerere.ai.provider.chatgpt.ChatGptPlanProtocol
import me.rerere.ai.provider.chatgpt.ChatGptPlanFailure
import me.rerere.ai.provider.stream.DecodeResult
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.StreamChunkDecoder
import me.rerere.ai.ui.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Account reference is resolved only at the transport boundary and never copied into Settings. */
internal class ChatGptPlanAPI(private val accounts: ChatGptPlanAccounts) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).readTimeout(0, TimeUnit.SECONDS)
        .addInterceptor { chain -> withChatGptEventStreamType(chain.proceed(chain.request())) }.build()
    private val encoder = ResponseAPI(client)

    suspend fun stream(setting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
        val account = setting.chatGptAccountId ?: error("siwc_not_signed_in")
        val token = accounts.accessToken(account)
        val body = buildChatGptPlanRequestBody(encoder, setting, messages, params)
        emitAll(callbackFlow {
            val source = AtomicReference<EventSource?>()
            val cancelled = AtomicBoolean(false)
            val tracking = accounts.track(account, token) {
                cancelled.set(true); source.get()?.cancel(); close(ChatGptPlanFailure.from(IllegalStateException("siwc_signed_out")))
            }
            val request = Request.Builder().url("${ChatGptPlanProtocol.RESOURCE}/responses")
                .header("Authorization", "Bearer $token").header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            try {
                var status: Int? = null
                var requestId: String? = null
                val delegate = OpenAIStreamListener(ChatGptPlanDecoder(params.tools.map { it.name }.toSet()), { chunks -> chunks.forEach { trySend(it) } }) { failure ->
                    close(failure?.let { ChatGptPlanFailure.from(it, status, requestId) })
                }
                val listener = object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        status = response.code
                        requestId = ChatGptPlanFailure.safeRequestId(response.header("x-request-id"))
                        delegate.onOpen(eventSource, response)
                    }
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) = delegate.onEvent(eventSource, id, type, data)
                    override fun onClosed(eventSource: EventSource) = delegate.onClosed(eventSource)
                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        close(ChatGptPlanFailure.http(t, response))
                    }
                }
                val eventSource = EventSources.createFactory(client).newEventSource(request, listener)
                source.set(eventSource)
                if (cancelled.get()) eventSource.cancel()
                awaitClose { eventSource.cancel() }
            } finally { accounts.untrack(tracking) }
        }.buffer(Channel.UNLIMITED))
    }.catch { error ->
        if (error is CancellationException) throw error
        throw ChatGptPlanFailure.from(error)
    }.flowOn(Dispatchers.IO)

    suspend fun generate(setting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        var finish: StreamChunk.Finish? = null
        stream(setting, messages, params).collect { chunk -> result = handler.handle(result, chunk); if (chunk is StreamChunk.Finish) finish = chunk }
        val terminal = finish ?: error("siwc_stream_interrupted")
        return TextGenerationResult(terminal.responseId.orEmpty(), terminal.model ?: params.model.modelId,
            result.last(), terminal.finishReason, result.last().usage)
    }
}

/** The same request path is used for streaming, buffered replies and connection tests. */
internal fun buildChatGptPlanRequestBody(
    encoder: ResponseAPI,
    setting: ProviderSetting.OpenAI,
    messages: List<UIMessage>,
    params: TextGenerationParams,
): JsonObject = buildChatGptPlanBody(encoder.buildRequestBody(
    setting.copy(baseUrl = ChatGptPlanProtocol.RESOURCE, responsesPath = "/responses", apiKey = ""),
    messages,
    params.copy(
        model = ChatGptPlanCapabilities.projectModelForRequest(params.model),
        reasoningLevel = ChatGptPlanCapabilities.reasoningLevelForRequest(params.model.modelId, params.reasoningLevel),
        customBody = emptyList(),
        customHeaders = emptyList(),
    ),
    true,
))

/** Apply after all generic composition, so settings/custom bodies cannot re-enable paid/unsupported behavior. */
internal fun buildChatGptPlanBody(base: JsonObject): JsonObject = buildJsonObject {
    val unsupported = setOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")
    base.filterKeys { it !in unsupported && it !in setOf("tools", "input", "store", "stream") }.forEach { (key, value) -> put(key, value) }
    put("store", false); put("stream", true)
    put("input", buildJsonArray {
        (base["input"] as? JsonArray).orEmpty().forEach { raw ->
            val item = raw.jsonObject
            add(buildJsonObject {
                item.forEach { (key, value) -> put(key, value) }
                if (item["role"]?.jsonPrimitive?.contentOrNull == "system") put("role", "developer")
                if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") put("namespace", "orbis")
            })
        }
    })
    val tools = (base["tools"] as? JsonArray).orEmpty()
    require(tools.all { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull in setOf("function", "web_search") }) { "siwc_capability" }
    val functions = tools.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function" }
    if (tools.isNotEmpty()) put("tools", buildJsonArray {
        if (functions.isNotEmpty()) add(buildJsonObject {
            put("type", "namespace"); put("name", "orbis"); put("description", "Tools executed locally by Orbis under the user's permissions.")
            put("tools", JsonArray(functions.map { function -> buildJsonObject {
                function.jsonObject.forEach { (key, value) -> put(key, value) }
                // Host tools use omission for optional/action-specific arguments. Responses may
                // otherwise normalize these schemas to strict mode and invent values for them.
                // Keep the original schema and arguments; local validation/approval still applies.
                put("strict", false)
            } }))
        })
        tools.filterNot { it in functions }.forEach(::add)
    })
}

/** Unlike generic compatible gateways, SIWC explicitly requires response.completed (not EOF/[DONE]). */
internal class ChatGptPlanDecoder(private val allowedFunctions: Set<String>? = null) : StreamChunkDecoder {
    private val delegate = ResponseApiStreamDecoder()
    private var completed = false
    override fun accept(event: SseEvent): DecodeResult {
        if (event.data == "[DONE]") { check(completed) { "siwc_stream_interrupted" }; return DecodeResult(completed = true) }
        val body = Json.parseToJsonElement(event.data).jsonObject
        val eventType = body["type"]?.jsonPrimitive?.contentOrNull ?: event.event
        fun validateItem(item: JsonObject) {
            when (val type = item["type"]?.jsonPrimitive?.contentOrNull) {
                "function_call" -> {
                    require(item["namespace"]?.jsonPrimitive?.contentOrNull == "orbis") { "siwc_capability" }
                    val name = item["name"]?.jsonPrimitive?.contentOrNull
                    require(!name.isNullOrBlank() && (allowedFunctions == null || name in allowedFunctions)) { "siwc_capability" }
                }
                else -> require(type?.endsWith("_call") != true || type == "web_search_call") { "siwc_capability" }
            }
        }
        // Terminal snapshots can carry tool calls even without an output_item.added event.
        // Check both representations before emitting Finish (which admits local tool execution).
        (body["item"] as? JsonObject)?.let(::validateItem)
        ((body["response"] as? JsonObject)?.get("output") as? JsonArray)?.forEach { validateItem(it.jsonObject) }
        require(eventType?.startsWith("response.image_generation_call") != true) { "siwc_capability" }
        when (eventType) {
            "response.failed", "error" -> {
                throw ChatGptPlanFailure.stream(body)
            }
            "response.incomplete" -> error("siwc_stream_interrupted")
            "response.completed" -> {
                val response = body["response"] as? JsonObject ?: error("siwc_stream_interrupted")
                check(response["status"]?.jsonPrimitive?.contentOrNull == "completed" &&
                    (response["error"] == null || response["error"] is JsonNull)) { "siwc_stream_interrupted" }
                completed = true
            }
        }
        val decoded = delegate.accept(event)
        // Generic compatible servers may use the SSE event header as a terminal marker.
        // Plan usage still requires the validated completed JSON response above.
        check(!decoded.completed || completed) { "siwc_stream_interrupted" }
        return decoded
    }
    override fun onClosed(): List<StreamChunk> { check(completed) { "siwc_stream_interrupted" }; return emptyList() }
}
