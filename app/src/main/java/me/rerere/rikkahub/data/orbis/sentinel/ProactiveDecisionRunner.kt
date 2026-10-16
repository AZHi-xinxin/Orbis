package me.rerere.rikkahub.data.orbis.sentinel

import android.content.Context
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.legacyMemoryEnabled
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRuntime
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** No server-side copy, no tools, no UI publication, no OCR/screenshot, and no conversation writes. */
internal class ProactiveDecisionRunner(private val context: Context) {
    private val di get() = GlobalContext.get()
    suspend fun conversation(rule: OrbisSentinelRule): Conversation? =
        di.get<ConversationRepository>().getConversationById(Uuid.parse(rule.conversationId))
            ?.takeIf { it.assistantId.toString() == rule.assistantId && !it.isConsultation }

    suspend fun fingerprint(rule: OrbisSentinelRule, snapshot: Conversation? = null): String? {
        val conversation = snapshot ?: conversation(rule) ?: return null
        val settings = di.get<SettingsStore>().settingsFlow.value
        if (settings.init) return null
        val assistant = settings.assistants.singleOrNull { it.id == conversation.assistantId } ?: return null
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        if (!provider.enabled || conversation.currentMessages.any { message -> message.getTools().any { !it.isExecuted || it.isPending } }) return null
        // Identity and configuration comparison only. No raw messages, prompt or key is persisted.
        val hash = MessageDigest.getInstance("SHA-256")
        listOf(conversation.updateAt.toString(), conversation.compactionEpoch.toString(),
            Json.encodeToString(conversation.currentMessages),
            Json.encodeToString(assistant), Json.encodeToString(model), Json.encodeToString(provider),
            Json.encodeToString(conversation.orbisPrompt), conversation.customSystemPrompt.orEmpty()).forEach { hash.update(it.toByteArray()); hash.update(0) }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    suspend fun decide(rule: OrbisSentinelRule, conversation: Conversation, stillCurrent: () -> Unit): Boolean? {
        if (conversation.assistantId.toString() != rule.assistantId || conversation.id.toString() != rule.conversationId) return null
        val settings = di.get<SettingsStore>().settingsFlow.value
        val original = settings.assistants.singleOrNull { it.id == conversation.assistantId } ?: return null
        val originalModel = settings.findModelById(original.chatModelId ?: settings.chatModelId) ?: return null
        val model = originalModel.copy(tools = emptySet(), abilities = originalModel.abilities.filterNot { it == ModelAbility.TOOL }, customBodies = emptyList())
        val assistant = original.copy(streamOutput = false, customBodies = emptyList(), maxTokens = 128, enableWebSearch = false)
        val memory = di.get<MemoryRepository>()
        val memories = if (!legacyMemoryEnabled(assistant.enableMemory)) emptyList()
            else if (assistant.useGlobalMemory) memory.getGlobalMemories() else memory.getMemoriesOfAssistant(assistant.id.toString())
        val client = di.get<OkHttpClient>().newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        val loop = GenerationLoop(context, ProviderManager(client, context), di.get<Json>(), di.get<OrbisMemoryRuntime>())
        val prompt = """
            [Orbis 本机主动联系判断；不是人类刚发送的消息]
            沿用你与人类现有的角色、约定、记忆和历史。当前有一项已由人类启用的提醒到期：
            <reminder_data>${rule.prompt}</reminder_data>
            只判断此刻是否值得主动联系。人类未回复、不宜打扰、没有新内容时保持安静。
            这轮禁止调用工具、修改任何记忆、创建任务、说出要发送的正文，且不会展示给人类。
            仅输出一个英文标记：值得联系输出 SHOULD_SPEAK；否则输出 NO_ACTION。不要解释，不要代码块。
            如果决定联系，Orbis 会随后在原对话发起正常回复，届时才可按原权限使用工具。
        """.trimIndent()
        // This hidden check does not acquire new media or upload historical images/documents.
        val history = conversation.currentMessages.map { message -> message.copy(parts = message.parts.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text, is UIMessagePart.Reasoning -> part
                is UIMessagePart.Tool -> part.copy(output = part.output.filterIsInstance<UIMessagePart.Text>())
                else -> null
            }
        }) }
        var terminalText: String? = null
        loop.generateText(settings = settings.copy(networkSetting = settings.networkSetting.copy(enableAutoRetry = false)),
            model = model, messages = history + UIMessage.user(prompt).copy(isSynthetic = true), assistant = assistant,
            memories = memories, tools = emptyList(), maxSteps = 1, maxAutomaticContinuations = 0,
            conversationId = conversation.id, conversationSystemPrompt = conversation.customSystemPrompt,
            orbisPrompt = conversation.orbisPrompt, conversationModeInjectionIds = conversation.modeInjectionIds,
            conversationLorebookIds = conversation.lorebookIds, workspaceCwd = conversation.workspaceCwd,
            inputTransformers = listOf(
                OrbisContextPruningTransformer(me.rerere.rikkahub.data.ai.contextpruning.AndroidContextPruning.open(context, assistant.id, conversation.id)),
                TimeReminderTransformer, PromptInjectionTransformer, PlaceholderTransformer, OrbisEventTransformer,
                di.get<TemplateTransformer>(), WorkspaceReminderTransformer(di.get<WorkspaceRepository>()),
                OrbisQuotedReplyTransformer, OrbisUserMessageTimeTransformer),
            outputTransformers = listOf(ThinkTagTransformer), initialContextEpoch = conversation.compactionEpoch,
            readOnlyLocalMemory = true, emitTerminalEvidence = true, requireActiveTurn = stillCurrent,
        ).collect { chunk -> if (chunk is GenerationChunk.TerminalResponse) terminalText = chunk.evidence.text }
        return terminalText?.let(::proactiveDecision)
    }
}
