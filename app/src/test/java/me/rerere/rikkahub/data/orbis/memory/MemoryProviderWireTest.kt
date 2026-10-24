package me.rerere.rikkahub.data.orbis.memory

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.body.projectBodyStatus
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

/** Real protocol serializers; synthetic data only, with networking forbidden. */
@RunWith(Parameterized::class)
class MemoryProviderWireTest(private val protocol: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun protocols() = listOf("chat", "responses", "claude", "google")
    }
    private val client = OkHttpClient.Builder().addInterceptor { error("No network in memory wire tests") }.build()
    private val params = TextGenerationParams(model = Model(modelId = "synthetic-text-model"))
    private val stored = listOf(UIMessage.system("stable instructions"), UIMessage.user("old question"),
        UIMessage.assistant("old answer"), UIMessage.user("current question"))
    private val marker = "[本机历史资料；"
    private val pin = OrbisMemorySelected("pin", "stable pinned note", 1, "pinned")
    private fun selection(text: String) = OrbisMemorySelection(listOf(pin,
        OrbisMemorySelected("dynamic", text, 1, "summary"),
        OrbisMemorySelected("excerpt", "synthetic historical excerpt", 1, "compaction_excerpt", "event-1", 100)))

    /** Consumed by the ST regression harness: REAL provider serialization, synthetic text only. */
    @Test fun exportSyntheticWireFixturesForGatewayBoundary() {
        if (protocol != "chat") return
        val directory = java.io.File("build/reports/memory-wire").apply { mkdirs() }
        fun save(name: String, input: List<UIMessage>, memory: OrbisMemorySelection) {
            java.io.File(directory, "$name.json").writeText(request(projectOrbisMemory(input, memory)).toString())
        }
        save("plain", stored, OrbisMemorySelection(listOf(pin)))
        save("recall-a", stored, selection("memory A"))
        save("recall-b", stored, selection("memory B"))
        save("first-turn", listOf(stored.first(), stored.last()), selection("first recall"))
        save("image-only", stored.dropLast(1) + UIMessage.user("").copy(parts = listOf(
            UIMessagePart.Image("https://example.invalid/synthetic.png"))), selection("image recall"))
        val completed = UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool(
            "synthetic-call", "synthetic_tool", "{}", listOf(UIMessagePart.Text("synthetic result")))))
        save("tool-continuation", stored + completed, selection("memory A"))
    }

    @Test fun changingRecallPreservesWireSystemPinsAndEarlierHistory() {
        val pinnedOnly = request(projectOrbisMemory(stored, OrbisMemorySelection(listOf(pin))))
        val a = request(projectOrbisMemory(stored, selection("memory A")))
        val b = request(projectOrbisMemory(stored, selection("memory B")))
        assertEquals(system(pinnedOnly), system(a))
        assertEquals(system(a), system(b))
        assertTrue(system(a).contains(pin.text))
        assertFalse(system(a).contains(marker))
        val prefixCount = wireMessages(pinnedOnly).size - 1
        assertEquals(JsonArray(wireMessages(pinnedOnly).take(prefixCount)), JsonArray(wireMessages(a).take(prefixCount)))
        assertEquals(JsonArray(wireMessages(a).take(prefixCount)), JsonArray(wireMessages(b).take(prefixCount)))
        val dynamicIndex = if (protocol == "chat") wireMessages(a).lastIndex - 1 else wireMessages(a).lastIndex
        assertNotEquals(wireMessages(a)[dynamicIndex], wireMessages(b)[dynamicIndex])
        assertTrue(wireMessages(a)[dynamicIndex].toString().contains("不是当年逐字原话"))
        if (protocol == "chat") {
            assertEquals(wireMessages(pinnedOnly).last(), wireMessages(a).last())
            assertEquals("system", wireMessages(a)[dynamicIndex].jsonObject["role"]?.jsonPrimitive?.content)
        }
        assertEquals(1, Regex(Regex.escape(marker)).findAll(a.toString()).count())
        assertEquals("current question", stored.last().toText())
    }

    @Test fun frozenToolContinuationWithObservationDoesNotMoveOrDuplicateMemory() = runTest {
        val frozen = selection("one wake")
        val snapshot = GenerationInputSnapshot(observation = "{\"count\":1}")
        val first = projectOrbisMemory(snapshot.input { projectOrbisMemory(stored, frozen) }, frozen)
        val call = UIMessagePart.Tool("synthetic-call", "synthetic_tool", "{}")
        val response = UIMessage.assistant("").copy(parts = listOf(call))
        val result = call.copy(output = listOf(UIMessagePart.Text("synthetic result")), approvalState = ToolApprovalState.Auto)
        snapshot.appendCompletedResponse(response, listOf(result))
        val next = projectOrbisMemory(snapshot.input { error("Cannot rerender a tool continuation") }, frozen)
        val firstWire = request(first)
        val nextWire = request(next)
        assertEquals(system(firstWire), system(nextWire))
        assertEquals(wireMessages(firstWire).toString(),
            JsonArray(wireMessages(nextWire).take(wireMessages(firstWire).size)).toString())
        assertEquals(1, Regex(Regex.escape(marker)).findAll(nextWire.toString()).count())
        assertTrue(nextWire.toString().contains("synthetic result"))
        assertEquals(next, projectOrbisMemory(next, frozen))
    }

    @Test fun compactionWithoutUserKeepsCallResultPairAndCleanupRestoresOriginal() = runTest {
        val frozen = selection("one wake")
        val snapshot = GenerationInputSnapshot(observation = "{}")
        snapshot.input { projectOrbisMemory(stored, frozen) }
        val call = UIMessagePart.Tool("compact-id", "compact", "{\"keep_recent\":0}")
        val response = UIMessage.assistant("").copy(parts = listOf(call))
        val result = call.copy(output = listOf(UIMessagePart.Text("durable compact receipt")), approvalState = ToolApprovalState.Auto)
        val bubble = response.copy(parts = listOf(result))
        val summary = UIMessage.assistant("retained summary").copy(parts = listOf(
            UIMessagePart.Text("retained summary", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })))
        val base = snapshot.prepareCompactionInput(listOf(summary, bubble), response, listOf(result), bubble.id)
        val projected = projectOrbisMemory(base, frozen)
        assertFalse(projected.any { it.role == MessageRole.USER })
        val tool = projected.last().getTools().single()
        assertEquals(result.toolCallId, tool.toolCallId)
        assertEquals(result.input, tool.input)
        assertEquals(result.output, tool.output.take(result.output.size))
        val baseline = request(base)
        val wire = request(projected)
        assertEquals(system(baseline), system(wire))
        assertEquals(wireMessages(baseline).size, wireMessages(wire).size)
        assertEquals(JsonArray(wireMessages(baseline).dropLast(1)).toString(),
            JsonArray(wireMessages(wire).dropLast(1)).toString())
        assertTrue(wireMessages(wire).last().toString().contains(marker))
        assertEquals(projected, projectOrbisMemory(projected, frozen))
        assertEquals(projectOrbisMemory(base, OrbisMemorySelection()),
            projectOrbisMemory(projected, OrbisMemorySelection()))
        assertEquals(listOf(result), bubble.parts)
    }

    @Test fun disabledMemoryRestoresExactSerializationAndNoCarrierDoesNotInventAWake() {
        assertEquals(request(stored), request(projectOrbisMemory(projectOrbisMemory(stored, selection("A")), OrbisMemorySelection())))
        val noCarrier = listOf(UIMessage.system("base"), UIMessage.assistant("summary"))
        assertEquals(noCarrier, projectOrbisMemory(noCarrier,
            OrbisMemorySelection(listOf(OrbisMemorySelected("hit", "data", 1, "summary")))))
    }

    @Test fun bodyStatusAndRecallChangeOnlyLateHostDataAcrossAllProtocols() {
        val input = projectOrbisMemory(stored, selection("memory A"))
        val a = request(projectBodyStatus(input, "【当前身体状态：心情：58】"))
        val b = request(projectBodyStatus(input, "【当前身体状态：心情：66】"))
        assertEquals(system(a), system(b))
        val tail = if (protocol == "chat") 2 else 1
        assertEquals(JsonArray(wireMessages(a).dropLast(tail)), JsonArray(wireMessages(b).dropLast(tail)))
        assertNotEquals(a, b)
        if (protocol == "chat") {
            assertEquals(wireMessages(request(stored)).last(), wireMessages(a).last())
            assertEquals("system", wireMessages(a)[wireMessages(a).lastIndex - 1].jsonObject["role"]?.jsonPrimitive?.content)
            val directory = java.io.File("build/reports/memory-wire").apply { mkdirs() }
            java.io.File(directory, "body-a.json").writeText(a.toString())
            java.io.File(directory, "body-b.json").writeText(b.toString())
        }
        assertEquals(request(input), request(projectBodyStatus(projectBodyStatus(input, "status"), null)))
        assertFalse(stored.toString().contains("当前身体状态"))
    }

    @Test fun bodySnapshotPreservesEntireFirstRequestAcrossToolContinuation() = runTest {
        val report = "【当前身体状态：心情：58；自定义：37.3（私密）】"
        val snapshot = GenerationInputSnapshot(bodyStatus = report)
        val frozen = selection("one wake")
        val first = snapshot.input { projectOrbisMemory(stored, frozen) }
        val call = UIMessagePart.Tool("body-call", "synthetic_tool", "{}")
        val response = UIMessage.assistant("").copy(parts = listOf(call))
        val result = call.copy(output = listOf(UIMessagePart.Text("synthetic result")), approvalState = ToolApprovalState.Auto)
        snapshot.appendCompletedResponse(response, listOf(result))
        val next = projectBodyStatus(projectOrbisMemory(snapshot.input { error("Cannot rerender") }, frozen), report)
        val firstWire = request(projectBodyStatus(projectOrbisMemory(first, frozen), report))
        val nextWire = request(next)
        assertEquals(system(firstWire), system(nextWire))
        assertEquals(wireMessages(firstWire), JsonArray(wireMessages(nextWire).take(wireMessages(firstWire).size)))
        assertEquals(1, Regex("当前身体状态").findAll(nextWire.toString()).count())
        if (protocol == "chat") {
            val directory = java.io.File("build/reports/memory-wire").apply { mkdirs() }
            directory.resolve("body-first.json").writeText(firstWire.toString())
            directory.resolve("body-tool.json").writeText(nextWire.toString())
        }
    }

    @Test fun compactionRetainsFrozenBodyInCompletedToolWithoutInventingHuman() = runTest {
        val report = "【当前身体状态：心情：58】"
        val snapshot = GenerationInputSnapshot(bodyStatus = report)
        snapshot.input { stored }
        val call = UIMessagePart.Tool("compact-body", "compact", "{}")
        val response = UIMessage.assistant("").copy(parts = listOf(call))
        val result = call.copy(output = listOf(UIMessagePart.Text("compact receipt")), approvalState = ToolApprovalState.Auto)
        val bubble = response.copy(parts = listOf(result))
        val summary = UIMessage.assistant("summary").copy(parts = listOf(
            UIMessagePart.Text("summary", buildJsonObject { put(COMPACTION_SUMMARY_MARKER, true) })))
        val rebased = snapshot.prepareCompactionInput(listOf(summary, bubble), response, listOf(result), bubble.id)
        assertFalse(rebased.any { it.role == MessageRole.USER })
        assertEquals(result.output, rebased.last().getTools().single().output.take(1))
        val wire = request(rebased)
        assertEquals(1, Regex("当前身体状态").findAll(wire.toString()).count())
        assertTrue(wireMessages(wire).last().toString().contains("compact receipt"))
        assertEquals(rebased, projectBodyStatus(rebased, report))
        assertFalse(projectBodyStatus(rebased, null).toString().contains("当前身体状态"))
        assertEquals(listOf(result), bubble.parts)
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
        "chat" -> wireMessages(body).firstOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "system" }.toString()
        "responses" -> body["instructions"].toString()
        "claude" -> body["system"].toString()
        "google" -> body["systemInstruction"].toString()
        else -> error("unknown synthetic protocol")
    }
}
