package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.checkDeepSeekFilesTarget
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class DeepSeekFileRequestTest {
    private val document=UIMessagePart.Document("file:///private/synthetic.pdf","synthetic.pdf",metadata=buildJsonObject {put("deepseek_file_id","file-api-synthetic")})
    private val messages=listOf(UIMessage(role=MessageRole.USER,parts=listOf(document)))
    @Test fun fileOnlyMessagesReachAllThreeWireFormatsWithoutLocalPaths() {
        val chat=ChatCompletionsAPI(OkHttpClient(),KeyRoulette.default())
        val cm=ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages",List::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,List::class.java).apply{isAccessible=true}
        val c=cm.invoke(chat,messages,true,false,listOf(Modality.TEXT,Modality.IMAGE)) as JsonArray
        assertEquals(Json.parseToJsonElement("""{"type":"file","file_id":"file-api-synthetic"}"""),c[0].jsonObject["content"]!!.jsonArray.single())
        val r=ResponseAPI(OkHttpClient()).buildMessages(messages)
        assertEquals(Json.parseToJsonElement("""{"type":"input_file","file_id":"file-api-synthetic"}"""),r[0].jsonObject["content"]!!.jsonArray.single())
        val provider=ClaudeProvider(OkHttpClient())
        val mm=ClaudeProvider::class.java.getDeclaredMethod("buildMessages",List::class.java,Boolean::class.javaPrimitiveType,ClaudePromptCacheTtl::class.java).apply{isAccessible=true}
        val m=mm.invoke(provider,messages,false,ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
        assertEquals(Json.parseToJsonElement("""{"type":"document","source":{"type":"file","file_id":"file-api-synthetic"}}"""),m[0].jsonObject["content"]!!.jsonArray.single())
        for(wire in listOf(c,r,m)) {assertFalse(wire.toString().contains("private"));assertFalse(wire.toString().contains("file:///"))}
        assertEquals("file:///private/synthetic.pdf",document.url)
    }
    @Test fun uploadedIdsCannotLeakToGatewaysOrDifferentModels() {
        checkDeepSeekFilesTarget(messages,"https://api.deepseek.com/v1","deepseek-flash")
        for(url in listOf("https://example.invalid","http://api.deepseek.com","https://api.deepseek.com.evil.invalid","https://user@api.deepseek.com"))
            assertThrows(IllegalArgumentException::class.java){checkDeepSeekFilesTarget(messages,url,"deepseek-flash")}
        assertThrows(IllegalArgumentException::class.java){checkDeepSeekFilesTarget(messages,"https://api.deepseek.com","deepseek-pro")}
        checkDeepSeekFilesTarget(listOf(UIMessage.user("ordinary text")),"https://example.invalid","other")
    }
}
