package me.rerere.ai.provider.providers.claude

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.PrefixObservation
import me.rerere.ai.provider.providers.openai.prefixUsageSnapshot
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.StreamChunk
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ClaudePrefixDiagnosticsTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun request(key: String = "private-secret", url: String = "https://private.invalid/v1/messages") =
        Request.Builder().url(url).header("x-api-key", key).build()
    private fun body(time: String = "fixed private time", mark: Int = 0, system: String = "private system",
        ttl: String = "5m", extra: Boolean = false) = buildJsonObject {
        put("model", "claude-private-model")
        put("system", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", system) }) })
        put("tools", obj("""{"placeholder":true}""").let { buildJsonArray { add(it) } })
        putJsonArray("messages") {
            for (i in 0 until if (extra) 3 else 2) add(buildJsonObject {
                put("role", "user")
                putJsonArray("content") { add(buildJsonObject {
                    put("type", "text"); put("text", if (i == 0) time else "private human $i")
                    if (i == mark) put("cache_control", buildJsonObject { put("type", "ephemeral"); put("ttl", ttl) })
                }) }
            })
        }
    }
    private fun api(wires: MutableList<String>, payload: String, stream: Boolean = false) = ClaudeProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer); wires += buffer.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build())
    private val response = """{"id":"private-response","model":"claude-test","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","usage":{"input_tokens":19,"output_tokens":44,"cache_read_input_tokens":88,"cache_creation_input_tokens":59619,"cache_creation":{"ephemeral_5m_input_tokens":59619},"private_secret":123}}"""

    @Test fun `moving breakpoints is distinguishable from content prefix changes`() {
        val probe = ClaudePrefixProbe()
        val first = probe.observe("private-session", body(), request(), emptyList())
        assertFalse(first.compared); assertNull(first.nativeCache!!.systemSame)
        val next = probe.observe("private-session", body(mark = 1, extra = true), request(), emptyList())
        assertEquals("anthropic_messages", next.protocol)
        assertEquals(0, next.commonMessages)
        assertEquals(2, next.nativeCache!!.contentCommonMessages)
        assertNull(next.nativeCache.contentFirstChangedMessage)
        assertEquals(true, next.nativeCache.systemContentSame)
        assertEquals(false, next.nativeCache.cacheControlSame)
        assertEquals(1, next.nativeCache.cachePoints.single().item)
        assertEquals(body(mark = 1, extra = true)["messages"]!!.jsonArray.sumOf { it.toString().toByteArray().size }, next.messageBytes)
        assertFalse(Json.encodeToString(next).contains("private"))
    }

    @Test fun `system tools options routing headers and TTL are compared independently without exporting secrets`() {
        val probe = ClaudePrefixProbe(); val baseline = body()
        fun compare(changed: JsonObject, req: Request = request()): PrefixObservation {
            probe.observe("one", baseline, request(), emptyList())
            return probe.observe("one", changed, req, emptyList())
        }
        val system = compare(body(system = "a new private system"))
        assertFalse(system.nativeCache!!.systemContentSame!!)
        assertEquals(0, system.nativeCache.firstChangedSystemBlock)
        assertTrue(system.optionsSame); assertEquals(2, system.commonMessages)
        val tool = compare(JsonObject(baseline + ("tools" to JsonArray(listOf(obj("""{"name":"private tool"}"""))))))
        assertFalse(tool.toolsSame); assertFalse(tool.nativeCache!!.toolsContentSame!!)
        val option = compare(JsonObject(baseline + ("temperature" to JsonPrimitive(0.4))))
        assertFalse(option.optionsSame); assertTrue(option.nativeCache!!.systemContentSame!!)
        val route = compare(baseline, request("new-private-key", "https://other-private.invalid/messages"))
        assertFalse(route.nativeCache!!.headersSame!!); assertFalse(route.nativeCache.endpointSame!!)
        val ttl = compare(body(ttl = "1h"))
        assertEquals(2, ttl.nativeCache!!.contentCommonMessages); assertFalse(ttl.nativeCache.cacheControlSame!!)
        assertEquals("1h", ttl.nativeCache.cachePoints.single().ttl)
        for (r in listOf(system, tool, option, route, ttl)) assertFalse(Json.encodeToString(r).contains("private"))
    }

    @Test fun `only protocol annotations are ignored and bounded slots cannot retain stale baselines`() {
        val probe = ClaudePrefixProbe()
        val a = obj("""{"messages":[{"role":"assistant","content":[{"type":"tool_use","input":{"cache_control":"first-private-value"}}]}]}""")
        val b = obj(a.toString().replace("first-private-value", "changed-private-value"))
        probe.observe("one", a, request(), emptyList())
        val next = probe.observe("one", b, request(), emptyList())
        assertEquals(0, next.nativeCache!!.contentFirstChangedMessage)
        for (i in 0..12) probe.observe("slot-$i", a, request(), emptyList())
        assertFalse(probe.observe("one", a, request(), emptyList()).compared)
    }

    @Test fun `rolling context retains both source labels and does not claim the historical time changed`() {
        val clock = UIMessagePart.Text("fixed private time", obj("""{"source":"orbis_user_message_time"}"""))
        val notice = UIMessagePart.Text("private rolling notice", obj("""{"source":"orbis_model_continuity"}"""))
        val ui = listOf(UIMessage.user("x").copy(parts = listOf(clock, notice)))
        val next = body()
        val oldMessages = next["messages"]!!.jsonArray.toMutableList()
        val m = oldMessages[0].jsonObject
        oldMessages[0] = JsonObject(m + ("content" to JsonArray(m["content"]!!.jsonArray + obj("""{"type":"text","text":"private rolling notice"}"""))))
        val old = JsonObject(next + ("messages" to JsonArray(oldMessages)))
        val probe = ClaudePrefixProbe(); probe.observe("a", old, request(), ui)
        val r = probe.observe("a", next, request(), ui)
        assertEquals(listOf("message_time", "model_continuity"), r.nativeCache!!.contentPreviousChangedSources)
        assertEquals(listOf("message_time"), r.nativeCache.contentChangedSources)
        assertEquals(0, r.nativeCache.contentFirstChangedMessage)
    }

    @Test fun `real nonstream provider opt in records usage without altering request body or making extra calls`() = runBlocking {
        val wires = mutableListOf<String>(); val reports = mutableListOf<PrefixObservation>()
        val api = api(wires, response)
        val provider = ProviderSetting.Claude(baseUrl = "https://private.invalid/v1", apiKey = "private-key", promptCaching = true)
        val params = TextGenerationParams(model = Model(modelId = "claude-test"), orbisConversationId = "private-session",
            onPrefixObservation = { reports += it })
        val messages = listOf(UIMessage.system("private system"), UIMessage.user("private human"))
        api.generateText(provider, messages, params); assertTrue(reports.isEmpty())
        val result = api.generateText(provider.copy(cacheDiagnostics = true), messages, params)
        assertEquals(2, wires.size); assertEquals(wires[0], wires[1])
        assertEquals(listOf("request", "usage"), reports.map { it.phase })
        val r = reports.last()
        assertEquals(59726, r.promptTokens); assertEquals(88, r.cachedTokens)
        assertEquals(result.usage!!.promptTokens, r.promptTokens)
        assertEquals("cache_read_input_tokens", r.usageSnapshots.single().cachedTokensSource)
        assertEquals(59619, r.usageSnapshots.single().fields["cache_creation_input_tokens"])
        assertEquals(59619, r.usageSnapshots.single().fields["cache_creation.ephemeral_5m_input_tokens"])
        assertFalse(Json.encodeToString(r).contains("private"))
        val failing = params.copy(onPrefixObservation = { error("diagnostic callback") })
        assertEquals(44, api.generateText(provider.copy(cacheDiagnostics = true), messages, failing).usage!!.completionTokens)
        val noSession = params.copy(orbisConversationId = null)
        val count = reports.size
        api.generateText(provider.copy(cacheDiagnostics = true), messages, noSession)
        assertEquals(count, reports.size)
    }

    @Test fun `native stream attaches bounded ordered raw snapshots to the original request and merges output deltas`() = runBlocking {
        val start = "event: message_start\ndata: {\"message\":{\"id\":\"private\",\"usage\":{\"input_tokens\":19,\"cache_read_input_tokens\":88,\"cache_creation_input_tokens\":59619}}}\n\n"
        val deltas = (1..12).joinToString("") { "event: message_delta\ndata: {\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":$it}}\n\n" }
        val payload = start + deltas + "event: message_stop\ndata: {}\n\n"
        val wires = mutableListOf<String>(); val reports = mutableListOf<PrefixObservation>()
        val provider = ProviderSetting.Claude(baseUrl = "https://private.invalid/v1", apiKey = "private", cacheDiagnostics = true)
        val params = TextGenerationParams(model = Model(modelId = "claude-test"), orbisConversationId = "stream-case", onPrefixObservation = { reports += it })
        api(wires, payload, true).streamText(provider, listOf(UIMessage.user("hello")), params).toList()
        assertEquals(1, wires.size)
        val used = reports.filter { it.phase == "usage" }
        assertEquals(13, used.size)
        assertEquals(1, reports.map { it.slot to it.sequence }.distinct().size)
        assertEquals(59726, used.last().promptTokens); assertEquals(88, used.last().cachedTokens)
        assertEquals(12, used.last().completionTokens)
        assertEquals(listOf(null, 6, 7, 8, 9, 10, 11, 12), used.last().usageSnapshots.map { it.fields["output_tokens"] })
        assertFalse(Json.encodeToString(used.last()).contains("private"))
    }

    @Test fun `native explicit zero stays distinct from absent cache read and callback errors do not interrupt decoding`() {
        val decoder = ClaudeStreamDecoder { _, _ -> error("diagnostic failure") }
        fun usage(json: String) = (decoder.accept(SseEvent(event = "message_delta", data = json)).chunks.single() as StreamChunk.Usage).usage
        val zero = usage("""{"usage":{"cache_read_input_tokens":0}}""")
        assertTrue(zero.cachedTokensReported)
        val missing = usage("""{"usage":{"output_tokens":7}}""")
        assertFalse(missing.cachedTokensReported)
        assertEquals(0, TokenUsage(cachedTokens = 99).merge(zero).cachedTokens)
        assertTrue(zero.merge(missing).cachedTokensReported)
        assertNull(prefixUsageSnapshot(obj("""{"cached_tokens":777}"""), native = true).cachedTokensSource)
    }

    @Test fun `native diagnostics setting is off for old saved providers and persists when enabled`() {
        val old = Json.decodeFromString<ProviderSetting.Claude>("""{"apiKey":"private"}""")
        assertFalse(old.cacheDiagnostics)
        assertTrue(Json.decodeFromString<ProviderSetting.Claude>(Json.encodeToString(old.copy(cacheDiagnostics = true))).cacheDiagnostics)
    }
}
