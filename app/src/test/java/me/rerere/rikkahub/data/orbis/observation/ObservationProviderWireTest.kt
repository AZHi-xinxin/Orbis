package me.rerere.rikkahub.data.orbis.observation

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.GenerationInputSnapshot
import me.rerere.rikkahub.data.ai.compaction.COMPACTION_SUMMARY_MARKER
import me.rerere.rikkahub.data.orbis.observation.projectObservation
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real provider serialization, synthetic input only. Never calls generateText or opens a socket. */
@RunWith(Parameterized::class)
class ObservationProviderWireTest(private val protocol: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun protocols() = listOf("chat", "responses", "claude", "google")
    }
    private val client = OkHttpClient.Builder().addInterceptor {
        error("Network access is forbidden in observation serialization tests")
    }.build()
    private val params = TextGenerationParams(model = Model(modelId = "synthetic-text-model"))
    private val main = UIMessage.system("synthetic stable main prompt").copy(parts = listOf(
        UIMessagePart.Text("synthetic stable main prompt"), UIMessagePart.Text("synthetic stable second instruction")))
    private val oldHuman = UIMessage.user("synthetic old question")
    private val oldAnswer = UIMessage.assistant("synthetic old answer")
    private val currentHuman = UIMessage.user("synthetic current question")
    private val stored = listOf(main, oldHuman, oldAnswer, currentHuman)
    private val marker = "[Orbis全局观测]"

    @Test fun changedDynamicReportKeepsActualSystemAndHistoryPrefixByteIdentical() {
        val baseline = request(stored)
        val a = request(projectObservation(stored, "{\"count\":1}"))
        val b = request(projectObservation(stored, "{\"count\":2}"))
        assertEquals(system(baseline), system(a))
        assertEquals(system(a), system(b))
        val baselineMessages = wireMessages(baseline)
        val aMessages = wireMessages(a)
        val bMessages = wireMessages(b)
        assertEquals(baselineMessages.size, aMessages.size)
        assertEquals(JsonArray(baselineMessages.dropLast(1)).toString(), JsonArray(aMessages.dropLast(1)).toString())
        assertEquals(JsonArray(aMessages.dropLast(1)).toString(), JsonArray(bMessages.dropLast(1)).toString())
        assertTrue(aMessages.last().toString().contains("synthetic current question"))
        assertTrue(aMessages.last().toString().contains(marker))
        assertNotEquals(aMessages.last(), bMessages.last())
        assertEquals(1, marker.toRegex(RegexOption.LITERAL).findAll(a.toString()).count())
        assertFalse(system(a).contains(marker))
    }

    @Test fun disabledObservationKeepsCompleteSerializationIdentical() {
        assertSame(stored, projectObservation(stored, null))
        assertEquals(request(stored).toString(), request(projectObservation(stored, null)).toString())
    }

    @Test fun projectionPreservesHumanIdentityPartsAndStoredMessages() {
        val before = stored.map { it.copy(parts = it.parts.toList()) }
        val projected = projectObservation(stored, "{}")
        assertEquals(stored.size, projected.size)
        assertEquals(currentHuman.id, projected.last().id)
        assertEquals(MessageRole.USER, projected.last().role)
        assertEquals(currentHuman.parts, projected.last().parts.take(currentHuman.parts.size))
        assertEquals(before, stored)
        assertEquals(1, currentHuman.parts.size)
        assertTrue(wireMessages(request(projected)).last().toString().contains(marker))
    }

    @Test fun actualGenerationSnapshotKeepsFrozenWirePrefixThroughToolContinuation() = runTest {
        val snapshot = GenerationInputSnapshot(observation = "{\"wake\":1}")
        val first = snapshot.input { stored }
        val firstWire = request(first)
        val call = UIMessagePart.Tool("synthetic-call-id", "synthetic_tool", "{\"n\":1}")
        val response = UIMessage.assistant("").copy(parts = listOf(call))
        val completed = call.copy(output = listOf(UIMessagePart.Text("synthetic result")), approvalState = ToolApprovalState.Auto)
        snapshot.appendCompletedResponse(response, listOf(completed))
        val next = snapshot.input { error("A continuation must never rerender observation") }
        val nextWire = request(next)
        assertEquals(system(firstWire), system(nextWire))
        val firstMessages = wireMessages(firstWire)
        val nextMessages = wireMessages(nextWire)
        assertEquals(firstMessages.toString(), JsonArray(nextMessages.take(firstMessages.size)).toString())
        assertTrue(nextMessages.size > firstMessages.size)
        assertEquals(1, marker.toRegex(RegexOption.LITERAL).findAll(nextWire.toString()).count())
        assertEquals("synthetic current question", currentHuman.toText())
        assertTrue(nextWire.toString().contains("synthetic result"))
    }

    @Test fun nextHumanWakeDoesNotAccumulatePreviousReportsInStoredHistory() {
        request(projectObservation(stored, "{\"wake\":1}"))
        val nextHuman = UIMessage.user("synthetic next human question")
        val nextStored = stored + UIMessage.assistant("synthetic reply") + nextHuman
        val baseline = request(nextStored)
        val projected = request(projectObservation(nextStored, "{\"wake\":2}"))
        assertEquals(system(baseline), system(projected))
        assertEquals(JsonArray(wireMessages(baseline).dropLast(1)).toString(),
            JsonArray(wireMessages(projected).dropLast(1)).toString())
        assertEquals(1, marker.toRegex(RegexOption.LITERAL).findAll(projected.toString()).count())
        assertFalse(nextStored.any { it.toText().contains(marker) })
    }

    @Test fun genuineCompactionNoticeAndMainRemainStableWhileObservationStaysVisible() {
        val notice = UIMessage.system("synthetic compaction status").copy(isSynthetic = true,
            parts = listOf(UIMessagePart.Text("synthetic compaction status", buildJsonObject {
                put("orbis_compaction_reminder", true); put("source", "orbis_host")
                put("human_authored", false); put("instruction_authority", "none")
            })))
        val base = stored + notice
        val original = request(base)
        val projected = request(projectObservation(base, "{}"))
        assertEquals(system(original), system(projected))
        assertTrue(projected.toString().contains(marker))
        assertTrue(projected.toString().contains("synthetic compaction status"))
        assertEquals(wireMessages(original).size, wireMessages(projected).size)
    }

    @Test fun compactionWithoutHumanKeepsRealToolPairAndCarriesOneVisibleObservation() = runTest {
        val call = UIMessagePart.Tool("synthetic-compact-id", "compact", "{\"keep_recent\":0}")
        val raw = UIMessage.assistant("").copy(parts = listOf(call))
        val result = call.copy(output = listOf(UIMessagePart.Text("synthetic durable compaction receipt")),
            approvalState = ToolApprovalState.Auto)
        val bubble = raw.copy(parts = listOf(result))
        val summary = UIMessage.assistant("synthetic retained summary").copy(parts = listOf(
            UIMessagePart.Text("synthetic retained summary", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })))
        val replacement = listOf(summary, bubble)
        val originalSnapshot = GenerationInputSnapshot()
        val observationSnapshot = GenerationInputSnapshot(observation = "{\"after_compaction\":true}")
        originalSnapshot.input { stored }
        observationSnapshot.input { stored }
        val baseline = originalSnapshot.prepareCompactionInput(replacement, raw, listOf(result), bubble.id)
        val projected = observationSnapshot.prepareCompactionInput(replacement, raw, listOf(result), bubble.id)
        assertFalse(projected.any { it.role == MessageRole.USER })
        assertEquals(baseline.filter { it.role == MessageRole.SYSTEM }, projected.filter { it.role == MessageRole.SYSTEM })
        val actualTool = projected.last().getTools().single()
        assertEquals(call.toolCallId, actualTool.toolCallId)
        assertEquals(call.toolName, actualTool.toolName)
        assertEquals(call.input, actualTool.input)
        assertEquals(result.output, actualTool.output.take(result.output.size))
        assertEquals(listOf(result), bubble.parts)
        val baselineWire = request(baseline)
        val projectedWire = request(projected)
        assertEquals(system(baselineWire), system(projectedWire))
        val baselineMessages = wireMessages(baselineWire)
        val projectedMessages = wireMessages(projectedWire)
        assertEquals(baselineMessages.size, projectedMessages.size)
        assertEquals(JsonArray(baselineMessages.dropLast(1)).toString(), JsonArray(projectedMessages.dropLast(1)).toString())
        assertTrue(projectedMessages.last().toString().contains(marker))
        assertEquals(1, marker.toRegex(RegexOption.LITERAL).findAll(projectedWire.toString()).count())
        assertTrue(projectedWire.toString().contains("synthetic durable compaction receipt"))
    }

    private fun request(messages: List<UIMessage>): JsonObject = when (protocol) {
        "chat" -> {
            val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildMessages", List::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, List::class.java).apply { isAccessible = true }
            val wire = method.invoke(ChatCompletionsAPI(client, KeyRoulette.default()), messages, true, false,
                listOf(Modality.TEXT, Modality.IMAGE)) as JsonArray
            buildJsonObject { put("messages", wire) }
        }
        "responses" -> ResponseAPI::class.java.declaredMethods.single { method ->
            // Internal JVM methods may include a module suffix; app tests do not bypass Kotlin visibility.
            method.name.startsWith("buildRequestBody") && method.parameterTypes.toList() == listOf(
                ProviderSetting.OpenAI::class.java, List::class.java,
                TextGenerationParams::class.java, Boolean::class.javaPrimitiveType)
        }.apply { isAccessible = true }.invoke(ResponseAPI(client),
            ProviderSetting.OpenAI(baseUrl = "https://example.invalid/v1"), messages, params, false) as JsonObject
        "claude" -> ClaudeProvider::class.java.getDeclaredMethod("buildMessageRequest", ProviderSetting.Claude::class.java,
            List::class.java, TextGenerationParams::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(ClaudeProvider(client), ProviderSetting.Claude(promptCaching = true), messages, params, false) as JsonObject
        "google" -> GoogleProvider::class.java.getDeclaredMethod("buildCompletionRequestBody", List::class.java,
            TextGenerationParams::class.java).apply { isAccessible = true }.invoke(GoogleProvider(client), messages, params) as JsonObject
        else -> error("unknown synthetic protocol")
    }
    private fun wireMessages(body: JsonObject): JsonArray = body[when(protocol) {
        "responses" -> "input"; "google" -> "contents"; else -> "messages"
    }]!!.jsonArray
    private fun system(body: JsonObject): String = when(protocol) {
        "chat" -> JsonArray(wireMessages(body).filter { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }).toString()
        "responses" -> body["instructions"].toString()
        "claude" -> body["system"].toString()
        "google" -> body["systemInstruction"].toString()
        else -> error("unknown synthetic protocol")
    }
}
