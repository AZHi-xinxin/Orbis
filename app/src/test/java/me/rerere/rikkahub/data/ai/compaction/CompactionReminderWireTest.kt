package me.rerere.rikkahub.data.ai.compaction

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The real app reminder, through the final provider serializer. No network or real chat. */
class CompactionReminderWireTest {
    @Test fun nativeThresholdNoticeHasCacheCompatibleWireAndNeverRewritesSourceHistory() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val params = TextGenerationParams(Model(modelId = "deepseek-v4-pro", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(Tool("compact", "synthetic", execute = { error("must not execute") })))
        val history = listOf(UIMessage.system("stable instructions"), UIMessage.user("old question"),
            UIMessage.assistant("old answer"), UIMessage.user("current question"))
        assertNull(compactionReminder(323999, 360000))
        val reminder = compactionReminder(324450, 360000)!!
        val messages = history + reminder
        for (host in listOf("api.deepseek.com", "synthetic-st.invalid")) for (stream in listOf(false, true)) {
            val body = method.invoke(api, messages, params, ProviderSetting.OpenAI(baseUrl = "https://$host/v1"), stream) as JsonObject
            val wire = body["messages"]!!.jsonArray
            if (host == "api.deepseek.com") {
                assertEquals(history.size, wire.size)
                val parts = wire.last().jsonObject["content"]!!.jsonArray
                assertEquals("current question", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
                assertEquals(reminder.toText(), parts[1].jsonObject["text"]!!.jsonPrimitive.content)
            } else {
                assertEquals(history.size + 1, wire.size)
                assertEquals(JsonPrimitive("orbis_host_data_v1"), wire.last().jsonObject["name"])
                assertEquals(reminder.toText(), wire.last().jsonObject["content"]!!.jsonPrimitive.content)
                System.getenv("ORBIS_REMINDER_WIRE_FIXTURES")?.let { directory ->
                    File(directory).apply { mkdirs() }.resolve("reminder-$stream.json").writeText(body.toString())
                }
            }
            assertEquals(listOf(UIMessagePart.Text("current question")), history.last().parts)
            assertEquals(reminder, messages.last())
        }
    }
}
