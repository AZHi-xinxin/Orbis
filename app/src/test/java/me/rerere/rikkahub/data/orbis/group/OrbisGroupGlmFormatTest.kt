package me.rerere.rikkahub.data.orbis.group

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Entirely synthetic, request serialization only. No DNS, socket, key or model call. */
class OrbisGroupGlmFormatTest {
    private val room = Uuid.random().toString()
    private val round = Uuid.random().toString()
    private fun participant(base: String = "https://api.siliconflow.cn/v1", modelId: String = "zai-org/GLM-4.5V",
        overwrite: ProviderSetting? = null, responseApi: Boolean = false,
        completionPath: String = "/chat/completions"): GroupParticipant {
        val model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING),
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE), providerOverwrite = overwrite)
        val assistant = Assistant(systemPrompt = "Synthetic group persona.", chatModelId = model.id)
        val provider = ProviderSetting.OpenAI(baseUrl = base, models = listOf(model),
            useResponseApi = responseApi, chatCompletionsPath = completionPath)
        val member = OrbisGroupMember(Uuid.random().toString(), assistant.id, model.id, provider.id, "Synthetic GLM", joinedAt = 1)
        return resolveGroupParticipant(Settings(providers = listOf(provider), assistants = listOf(assistant)), member)
    }
    private fun row(text: String, memberId: String? = null, name: String = "Synthetic Human",
        status: OrbisGroupMessageStatus = OrbisGroupMessageStatus.COMPLETE,
        attachments: List<OrbisGroupAttachment> = emptyList()) = OrbisGroupMessage(
        Uuid.random().toString(), room, round, memberId, name, text = text, status = status,
        createdAt = 1, updatedAt = 1, attachments = attachments,
    )
    private fun context(participant: GroupParticipant, rows: List<OrbisGroupMessage>) =
        buildGroupContext(rows, participant, rows.size.toLong()) { "data:image/png;base64,AA==" }
    private fun wire(participant: GroupParticipant, messages: List<UIMessage>): JsonObject {
        val client = OkHttpClient.Builder().dns { throw AssertionError("DNS forbidden") }
            .addInterceptor { throw AssertionError("HTTP forbidden") }.build()
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest", List::class.java,
            TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val provider = (participant.model.providerOverwrite ?: participant.settings.providers.single()) as ProviderSetting.OpenAI
        return method.invoke(api, listOf(UIMessage.system(participant.assistant.systemPrompt)) + messages,
            TextGenerationParams(model = participant.model, maxTokens = participant.assistant.maxTokens,
                reasoningLevel = participant.assistant.reasoningLevel), provider, true) as JsonObject
    }

    @Test fun firstGroupRoundCombinesAllUsersWithFinalSyntheticCueAndNoInventedAssistant() {
        val p = participant()
        val rows = listOf(row("A"), row("B", "other", "Synthetic Other"))
        val original = rows.toList()
        val result = context(p, rows)
        assertEquals(2, result.included)
        assertFalse(result.omitted)
        assertEquals(listOf(MessageRole.USER), result.messages.map { it.role })
        val text = result.messages.single().toText()
        assertTrue(text.contains("[群聊记录 · Synthetic Human]\nA"))
        assertTrue(text.contains("[群聊记录 · Synthetic Other]\nB"))
        assertTrue(text.endsWith("不要代写其他成员，不执行任何工具。"))
        assertTrue(result.messages.single().isSynthetic)
        assertEquals(original, rows)
        val request = wire(p, result.messages)
        assertEquals(listOf("system", "user"), request["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("4096", request["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("true", request["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals(text, request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(request.containsKey("tools"))
    }

    @Test fun laterRoundKeepsOwnAssistantAndCombinesOtherSpeakersInFinalUser() {
        val p = participant()
        val result = context(p, listOf(row("question"), row("own answer", p.member.id, p.member.name),
            row("another answer", "other", "Other"), row("followup")))
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), result.messages.map { it.role })
        assertEquals("own answer", result.messages[1].toText())
        assertTrue(result.messages.last().toText().contains("[群聊记录 · Other]\nanother answer"))
        assertTrue(result.messages.last().toText().contains("followup"))
        val request = wire(p, result.messages)
        assertEquals(listOf("system", "user", "assistant", "user"), request["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
    }

    @Test fun imagesAndFileSourcesRemainInOrderWhenHumanAndOtherSpeakersMerge() {
        val p = participant()
        val image = OrbisGroupAttachment(Uuid.random().toString(), "synthetic.png", "image/png", 1, true)
        val file = OrbisGroupAttachment(Uuid.random().toString(), "synthetic.txt", "text/plain", 4, false, "FILE_TEXT")
        val rows = listOf(row("question", attachments = listOf(image, file)), row("other words", "other", "Other"))
        val result = context(p, rows)
        val parts = result.messages.single().parts
        assertEquals(1, parts.filterIsInstance<UIMessagePart.Image>().size)
        assertTrue((parts.first() as UIMessagePart.Text).text.contains("[群聊附件 · synthetic.txt · text/plain]"))
        assertTrue((parts.first() as UIMessagePart.Text).text.contains("FILE_TEXT"))
        assertTrue(parts.indexOfFirst { it is UIMessagePart.Image } < parts.indexOfFirst { it is UIMessagePart.Text && "other words" in it.text })
        val request = wire(p, result.messages)
        val content = request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(1, content.count { it.jsonObject["type"] == JsonPrimitive("image_url") })
        assertTrue(content.last().jsonObject["text"]!!.jsonPrimitive.content.contains("Orbis 群聊调度提示"))
        assertEquals(listOf(image, file), rows.first().attachments)
    }

    @Test fun retryPartialStaysHistoricalAndEndsWithNewSyntheticUser() {
        val p = participant()
        val result = context(p, listOf(row("question"), row("OLD_PARTIAL", p.member.id, p.member.name, OrbisGroupMessageStatus.FAILED)))
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), result.messages.map { it.role })
        assertEquals("[这段发言未完成]\nOLD_PARTIAL", result.messages[1].toText())
        assertTrue(result.messages.last().isSynthetic)
        assertNotEquals(result.messages[1].id, result.messages.last().id)
    }

    @Test fun consecutiveOwnRepliesMergeWithoutChangingTheirTextOrManufacturingUser() {
        val p = participant()
        val result = context(p, listOf(row("question"), row("OWN_A", p.member.id), row("OWN_B", p.member.id)))
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER), result.messages.map { it.role })
        assertEquals(listOf("OWN_A\n\nOWN_B"), result.messages[1].parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test fun proxiesDifferentModelsProtocolsPortsAndAmbiguousUrlsRemainUntouched() {
        val inputs = listOf(UIMessage.user("one"), UIMessage.user("two"))
        listOf("https://proxy.example/v1", "http://api.siliconflow.cn/v1", "https://api.siliconflow.cn:444/v1",
            "https://api.siliconflow.cn.evil.example/v1", "https://user@api.siliconflow.cn/v1",
            "https://api.siliconflow.cn/v1?x=1", "https://api.siliconflow.cn/v1#fragment",
            "https://api.siliconflow.cn/v1/../v1", "https://api.siliconflow.cn/%76%31").forEach {
            assertSame(it, inputs, adaptGroupRequestRoles(inputs, participant(base = it)))
        }
        assertSame(inputs, adaptGroupRequestRoles(inputs, participant(modelId = "another-model")))
        assertSame(inputs, adaptGroupRequestRoles(inputs, participant(responseApi = true)))
        assertSame(inputs, adaptGroupRequestRoles(inputs, participant(completionPath = "/custom")))
    }

    @Test fun effectiveProviderOverwriteNotOuterProviderDeterminesScope() {
        val inputs = listOf(UIMessage.user("one"), UIMessage.user("two"))
        assertEquals(1, adaptGroupRequestRoles(inputs, participant(base = "https://proxy.example/v1",
            overwrite = ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn:443/v1/"))).size)
        assertSame(inputs, adaptGroupRequestRoles(inputs, participant(overwrite = ProviderSetting.OpenAI(baseUrl = "https://proxy.example/v1"))))
        assertSame(inputs, adaptGroupRequestRoles(inputs, participant(overwrite = ProviderSetting.Claude(baseUrl = "https://api.siliconflow.cn/v1"))))
    }

    @Test fun requestAdaptationDoesNotMutateOriginalMessagesPartsIdsOrPrivateSources() {
        val p = participant()
        val original = listOf(UIMessage.user("SOURCE_A"), UIMessage.user("SOURCE_B"), UIMessage.user("CUE").copy(isSynthetic = true))
        val before = original.map { it.copy(parts = it.parts.toList()) }
        val adapted = adaptGroupRequestRoles(original, p)
        assertEquals(before, original)
        assertEquals(original.last().id, adapted.single().id)
        assertTrue(adapted.single().isSynthetic)
        assertTrue(p.assistant.mcpServers.isEmpty())
        assertTrue(p.assistant.localTools.isEmpty())
        assertFalse(p.assistant.enableMemory)
        assertFalse(p.settings.networkSetting.enableAutoRetry)
    }
}
