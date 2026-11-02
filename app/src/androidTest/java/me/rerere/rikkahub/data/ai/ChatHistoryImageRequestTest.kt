package me.rerere.rikkahub.data.ai

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Exercises the actual generation loop and OpenAI wire encoder; interceptor forbids all network. */
class ChatHistoryImageRequestTest {
    private val pixel="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV7sAAAAASUVORK5CYII="
    @Test fun historyToggleAndOnDemandImageSurviveToolContinuationWithoutChangingStoredHistory() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="org.orbis.agent.bodylab")
        val model=Model(modelId="synthetic-vision",inputModalities=listOf(Modality.TEXT,Modality.IMAGE),abilities=listOf(ModelAbility.TOOL))
        val old=(1..18).map { UIMessage.user("old $it").copy(parts=listOf(UIMessagePart.Text("old $it"),UIMessagePart.Image(pixel))) }
        val newest=UIMessage.user("look at one old photo").copy(parts=listOf(UIMessagePart.Text("new"),UIMessagePart.Image(pixel)))
        for (sendAll in listOf(true,false)) {
            val assistant=Assistant(sendHistoryImages=sendAll,streamOutput=false,chatModelId=model.id,systemPrompt="Synthetic fixture",contextMessageLimit=0)
            var conversation=Conversation(assistantId=assistant.id,messageNodes=(old+newest).map { it.toMessageNode() })
            val scope=ChatImageReadScope.capture(conversation,conversation.currentMessages.map{it.id}.toSet())
            val code=scope.images.keys.first()
            val requests=mutableListOf<JsonObject>()
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val buffer=Buffer();chain.request().body!!.writeTo(buffer)
                requests+=Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                check(requests.size<=2)
                val payload=if(requests.size==1 && !sendAll) buildJsonObject {
                    put("choices",buildJsonArray { add(buildJsonObject {
                        put("index",0);put("finish_reason","tool_calls")
                        put("message",buildJsonObject {
                            put("role","assistant");put("content",JsonNull)
                            put("tool_calls",buildJsonArray { add(buildJsonObject {
                                put("id","read-old-image");put("type","function")
                                put("function",buildJsonObject {put("name",READ_CHAT_IMAGE);put("arguments",buildJsonObject{put("image_code",code)}.toString())})
                            }) })
                        })
                    }) })
                } else Json.parseToJsonElement("""{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"image viewed"}}]}""")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body(payload.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            val provider=ProviderSetting.OpenAI(baseUrl="https://synthetic.invalid/v1",apiKey="",models=listOf(model))
            val settings=Settings(providers=listOf(provider),assistants=listOf(assistant),chatModelId=model.id,assistantId=assistant.id)
            val tool=createChatImageReadTool(scope,{conversation},{!sendAll})
            try {
                GenerationLoop(context,ProviderManager(client,context),Json).generateText(settings=settings,model=model,
                    messages=conversation.currentMessages,assistant=assistant,tools=if(sendAll) emptyList() else listOf(tool),
                    conversationId=conversation.id,maxSteps=3).collect { chunk ->
                        if(chunk is GenerationChunk.Messages)conversation=conversation.updateCurrentMessages(chunk.messages)
                    }
                fun count(request:JsonObject):Int = Regex("data:image/png;base64,").findAll(request.toString()).count()
                assertEquals(if(sendAll)1 else 2,requests.size)
                assertEquals(if(sendAll)19 else 1,count(requests.first()))
                if(!sendAll) {
                    assertEquals(2,count(requests.last())) // new photo + the one requested old photo
                    assertTrue(requests.first().toString().contains(code))
                    val next=projectHistoryImages(conversation.currentMessages+UIMessage.user("next text only"),assistant.id,conversation.id)
                    fun images(parts:List<UIMessagePart>):Int=parts.sumOf { when(it) {
                        is UIMessagePart.Image -> 1
                        is UIMessagePart.Tool -> images(it.output)
                        else -> 0
                    } }
                    assertEquals(0,next.sumOf{images(it.parts)})
                }
                assertEquals(old+newest,conversation.currentMessages.take(19))
                assertTrue(conversation.currentMessages.last().toText().contains("image viewed"))
            } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow() }
        }
    }
}
