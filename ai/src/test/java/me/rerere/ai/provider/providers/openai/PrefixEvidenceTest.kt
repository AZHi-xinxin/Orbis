package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PrefixEvidenceTest {
    @Test fun `inline images count as serialized bytes without leaking image content`() {
        val probe=DeepSeekPrefixProbe()
        fun request(text:String)=buildJsonObject {
            put("model","gpt-private-alias")
            putJsonArray("messages") { add(buildJsonObject {put("role","system");put("content",text)})
                add(buildJsonObject {put("role","user");putJsonArray("content") {
                    add(buildJsonObject {put("type","image_url");putJsonObject("image_url") {put("url","data:image/png;base64,PRIVATE_IMAGE")}})
                }})
            }
        }
        probe.observe("private-slot",request("old"))
        val body=request("new-private-text")
        val result=probe.observe("private-slot",body,mapOf(JsonPrimitive("new-private-text").toString() to listOf("worldbook")))
        assertEquals(listOf("worldbook"),result.firstChangedSources)
        assertEquals(1,result.imagePartCount)
        assertEquals("data:image/png;base64,PRIVATE_IMAGE".toByteArray().size,result.inlineMediaBytes)
        assertEquals(body["messages"]!!.jsonArray.sumOf { it.toString().toByteArray().size },result.messageBytes)
        assertEquals("gpt",result.model)
        val report=Json.encodeToString(result)
        for(secret in listOf("private-slot","new-private-text","PRIVATE_IMAGE","gpt-private-alias")) assertFalse(report.contains(secret))
    }
    @Test fun `source matching is exact and reports only allowlisted provenance`() {
        val part=UIMessagePart.Text("private content",metadata=buildJsonObject {
            put("source","orbis_prompt_injection");putJsonArray("diagnostic_sources") {add("worldbook");add("private entry name")}
        })
        val map=prefixSystemSourceHints(listOf(UIMessage.system("").copy(parts=listOf(part))))
        assertEquals(listOf("worldbook"),map[JsonPrimitive("private content").toString()])
        assertNull(map[JsonPrimitive("overridden by custom body").toString()])
    }
    @Test fun `usage stays bound to original request even when another observation intervenes`() {
        val probe=DeepSeekPrefixProbe();val body=buildJsonObject {put("model","deepseek-chat");putJsonArray("messages") {}}
        val first=probe.observe("a",body);val second=probe.observe("a",body);val other=probe.observe("b",body)
        val used=first.withUsage(TokenUsage(promptTokens=100,cachedTokens=0,cachedTokensReported=true))
        assertEquals(first.sequence,used.sequence);assertEquals(first.slot,used.slot)
        assertNotEquals(other.slot,used.slot);assertNotEquals(second.sequence,used.sequence)
        assertEquals(0,used.cachedTokens);assertNull(second.withUsage(TokenUsage(promptTokens=100)).cachedTokens)
    }
    @Test fun `real nonstream and stream paths correlate observed request with parsed usage`() = runBlocking {
        for(stream in listOf(false,true)) {
            val observations=mutableListOf<PrefixObservation>()
            val json="""{"id":"synthetic","model":"gpt-test","choices":[{"message":{"role":"assistant","content":"ok"},"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":90}}}"""
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body((if(stream) "data: $json\n\ndata: [DONE]\n\n" else json)
                        .toResponseBody((if(stream) "text/event-stream" else "application/json").toMediaType())).build()
            }.build()
            val api=ChatCompletionsAPI(client,KeyRoulette.default())
            val provider=ProviderSetting.OpenAI(baseUrl="https://synthetic.invalid/v1",apiKey="synthetic",cacheDiagnostics=true)
            val params=TextGenerationParams(model=Model(modelId="gpt-test"),orbisConversationId="test-${stream}",onPrefixObservation={observations.add(it)})
            if(stream) api.streamText(provider,listOf(UIMessage.user("hello")),params).toList()
            else api.generateText(provider,listOf(UIMessage.user("hello")),params)
            assertEquals("request",observations.first().phase)
            val usage=observations.last();assertEquals("usage",usage.phase)
            assertEquals(observations.first().sequence,usage.sequence)
            assertEquals(observations.first().slot,usage.slot)
            assertEquals(100,usage.promptTokens);assertEquals(90,usage.cachedTokens)
        }
    }
}
