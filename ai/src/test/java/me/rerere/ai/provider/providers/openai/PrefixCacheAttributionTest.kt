package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.util.KeyRoulette
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class PrefixCacheAttributionTest {
    private fun host(text: String, key: String, version: JsonPrimitive, source: String) = UIMessagePart.Text(text,
        buildJsonObject {
            put(key, version); put("source", source); put("human_authored", false)
            put("instruction_authority", "none"); put("request_only", true)
        })
    private val provider = ProviderSetting.OpenAI(baseUrl="https://synthetic.invalid/v1",
        apiKey="private-key-not-for-report", cacheDiagnostics=true)
    private val model = Model(modelId="claude-synthetic-private-alias")
    private fun api(wires: MutableList<JsonObject>, response: String, stream: Boolean) = ChatCompletionsAPI(
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer=Buffer(); chain.request().body!!.writeTo(buffer)
            wires.add(Json.parseToJsonElement(buffer.readUtf8()).jsonObject)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody((if(stream) "text/event-stream" else "application/json").toMediaType())).build()
        }.build(), KeyRoulette.default())

    @Test fun `a historical clock can be unchanged while rolling host context changes its system block`() = runBlocking {
        val reports=mutableListOf<PrefixObservation>(); val wires=mutableListOf<JsonObject>()
        val api=api(wires,"""{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":60000,"prompt_tokens_details":{"cached_tokens":13}}}""",false)
        val params=TextGenerationParams(model=model,orbisConversationId="synthetic-clock",onPrefixObservation={reports.add(it)})
        val clock=host("private historical clock fixed at 2026-11-16T10:00:00+08:00",
            "orbis_user_message_time_projection",JsonPrimitive(true),"orbis_user_message_time")
        val notice=host("private configured model notice", "orbis_model_continuity_projection",
            JsonPrimitive("v1"),"orbis_model_continuity")
        val prefix=listOf(UIMessage.system("stable system"))+(0 until 36).flatMap {
            listOf(UIMessage.user("past human $it"),UIMessage.assistant("past assistant $it")) }
        val oldHuman=UIMessage.user("first original human text").copy(parts=listOf(UIMessagePart.Text("first original human text"),clock))
        val first=prefix+oldHuman.copy(parts=oldHuman.parts+notice)
        val nextHuman=UIMessage.user("next original human text").copy(parts=listOf(UIMessagePart.Text("next original human text"),
            clock.copy(text="a newer historical clock"),notice))
        val next=prefix+oldHuman+UIMessage.assistant("answer")+nextHuman
        api.generateText(provider,first,params); api.generateText(provider,next,params)
        val previous=wires[0]["messages"]!!.jsonArray
        val current=wires[1]["messages"]!!.jsonArray
        assertEquals(75,previous.size); assertEquals(78,current.size)
        assertEquals(previous.take(73),current.take(73))
        // The clock text never changes. Only the ephemeral neighbour disappears from this block.
        assertEquals(previous[73].jsonObject["content"]!!.jsonArray[0].jsonObject["text"],current[73].jsonObject["content"])
        val report=reports.last()
        assertEquals(73,report.commonMessages); assertEquals(73,report.firstChangedMessage)
        assertEquals(listOf("message_time"),report.firstChangedSources)
        assertEquals(listOf("message_time","model_continuity"),report.previousChangedSources)
        assertEquals(previous[73].toString().toByteArray().size,report.previousChangedMessageBytes)
        assertEquals(current[73].toString().toByteArray().size,report.changedMessageBytes)
        val serialized=Json.encodeToString(report)
        for(secret in listOf("private historical", "private configured", "original human", "private-key",model.modelId))
            assertFalse(serialized.contains(secret))
    }

    @Test fun `native and compatible usage counts remain distinguishable without reinterpreting the bill`() = runBlocking {
        for(stream in listOf(false,true)) {
            val reports=mutableListOf<PrefixObservation>()
            val raw="""{"prompt_tokens":60000,"completion_tokens":44,"input_tokens":13,"cache_read_input_tokens":59000,"cache_creation_input_tokens":987,"prompt_tokens_details":{"cached_tokens":13,"private_note":"secret"},"cached_tokens":777,"request_id":"private-id","private_secret":123}"""
            val payload="""{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":$raw}"""
            val api=api(mutableListOf(),if(stream) "data: $payload\n\ndata: [DONE]\n\n" else payload,stream)
            val params=TextGenerationParams(model=model,orbisConversationId="synthetic-usage-$stream",onPrefixObservation={reports.add(it)})
            if(stream) api.streamText(provider,listOf(UIMessage.user("private input")),params).toList()
            else api.generateText(provider,listOf(UIMessage.user("private input")),params)
            val report=reports.last(); val snapshot=report.usageSnapshots.single()
            assertEquals(13,report.cachedTokens)
            assertEquals("prompt_tokens_details.cached_tokens",snapshot.cachedTokensSource)
            assertEquals(13,snapshot.fields["input_tokens"])
            assertEquals(59000,snapshot.fields["cache_read_input_tokens"])
            assertEquals(987,snapshot.fields["cache_creation_input_tokens"])
            assertEquals(777,snapshot.fields["cached_tokens"])
            assertFalse(Json.encodeToString(report).contains("private"))
        }
    }

    @Test fun `multiple usage snapshots in one SSE event stay ordered and bounded`() = runBlocking {
        val reports=mutableListOf<PrefixObservation>()
        val payload=(1..12).joinToString("\n") {
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":60000,\"completion_tokens\":$it,\"cached_tokens\":${50000+it}}}" }
        val api=api(mutableListOf(),"$payload\n\ndata: [DONE]\n\n",true)
        val params=TextGenerationParams(model=model,orbisConversationId="synthetic-multi-usage",onPrefixObservation={reports.add(it)})
        api.streamText(provider,listOf(UIMessage.user("hello")),params).toList()
        val used=reports.filter { it.phase=="usage" }
        assertEquals((1..12).toList(),used.map { it.completionTokens })
        assertEquals(50001,used.first().usageSnapshots.single().fields["cached_tokens"])
        assertEquals(listOf(1,6,7,8,9,10,11,12),used.last().usageSnapshots.map { it.fields["completion_tokens"] })
        assertEquals(50012,used.last().cachedTokens)
    }

    @Test fun `diagnostic provenance labels cannot invalidate unchanged wire bytes`() {
        val probe=DeepSeekPrefixProbe(); val body=Json.parseToJsonElement("""{"model":"claude-test","messages":[{"role":"system","content":"same"}]}""").jsonObject
        probe.observe("a",body,mapOf("\"same\"" to listOf("message_time")))
        val next=probe.observe("a",body,mapOf("\"same\"" to listOf("model_continuity")))
        assertEquals(1,next.commonMessages); assertNull(next.firstChangedMessage)
    }

    @Test fun `zero missing and conflicting fields are preserved and diagnostic callback errors never block usage`() {
        val zero=prefixUsageSnapshot(Json.parseToJsonElement("""{"prompt_tokens_details":{"cached_tokens":0},"cached_tokens":900}""").jsonObject)
        assertEquals("prompt_tokens_details.cached_tokens",zero.cachedTokensSource)
        assertEquals(0,zero.fields[zero.cachedTokensSource])
        val missing=prefixUsageSnapshot(Json.parseToJsonElement("""{"completion_tokens":12,"cached_tokens":null,"request_id":"private"}""").jsonObject)
        assertNull(missing.cachedTokensSource); assertEquals(mapOf("completion_tokens" to 12),missing.fields)
        val decoder=ChatCompletionsStreamDecoder { _, _ -> error("diagnostic failure") }
        val result=decoder.accept(SseEvent(data="""{"choices":[],"usage":{"cached_tokens":13}}"""))
        assertEquals(13,(result.chunks.single() as StreamChunk.Usage).usage.cachedTokens)
    }
}
