package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.extractContextForMatching
import me.rerere.rikkahub.data.model.isTriggered
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.ai.provider.ProviderSetting
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/**
 * 提示词注入转换器
 *
 * 根据 Assistant 关联的 ModeInjection 和 Lorebook 进行提示词注入
 */
object PromptInjectionTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return transformMessages(
            messages = messages,
            assistant = ctx.assistant,
            modeInjections = ctx.settings.modeInjections,
            lorebooks = ctx.settings.lorebooks,
            conversationModeInjectionIds = ctx.conversationModeInjectionIds,
            conversationLorebookIds = ctx.conversationLorebookIds,
            deferConditionalSystemInjections = supportsLateSystemInjections(
                ctx.model.findProvider(ctx.settings.providers),
                (ctx.assistant.customBodies + ctx.model.customBodies).lastOrNull { it.key == "model" }
                    ?.value?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ctx.model.modelId,
            ),
        )
    }
}

/** Enable only the verified Flash family. OpenAI-compatible transports may flatten SYSTEM
 * messages (notably Claude relays); wire compatibility alone does not prove cache compatibility.
 * All other routes, including the already accepted Claude and Pro paths, keep their layout. */
internal fun supportsLateSystemInjections(provider: ProviderSetting?, modelId: String): Boolean =
    provider is ProviderSetting.OpenAI && !provider.useResponseApi && provider.chatGptAccountId == null &&
        modelId.contains("deepseek", ignoreCase = true) && modelId.contains("flash", ignoreCase = true) &&
        !modelId.contains("pro", ignoreCase = true)

/**
 * 核心注入逻辑（可测试的纯函数）
 */
internal fun transformMessages(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    deferConditionalSystemInjections: Boolean = false,
): List<UIMessage> {
    // 收集所有需要注入的内容
    val injections = collectInjections(
        messages = messages,
        assistant = assistant,
        modeInjections = modeInjections,
        lorebooks = lorebooks,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
    )

    if (injections.isEmpty()) {
        return messages
    }

    // 按位置和优先级分组
    val byPosition = injections
        .sortedByDescending { it.priority }
        .groupBy { it.position }

    // Keep the entire AFTER group in its original priority order. If a conditional entry is
    // configured, placement must stay the same even on turns where that entry is inactive.
    // Never move BEFORE entries or change USER/ASSISTANT injections and their depth semantics.
    val deferAfter = deferConditionalSystemInjections && messages.any { it.role == MessageRole.USER } &&
        configuredInjections(assistant, modeInjections, lorebooks, conversationModeInjectionIds,
            conversationLorebookIds).any {
            it is PromptInjection.RegexInjection && !it.constantActive && it.keywords.isNotEmpty() &&
                it.position == InjectionPosition.AFTER_SYSTEM_PROMPT
        }
    val applied = applyInjections(messages,
        if (deferAfter) byPosition - InjectionPosition.AFTER_SYSTEM_PROMPT else byPosition)
    val after = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty().joinToString("\n") { it.content }
    if (!deferAfter || after.isEmpty()) return applied
    val insertion = applied.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
        .takeIf { it >= 0 } ?: applied.indexOfLast { it.role == MessageRole.USER }
    val safe = findSafeInsertIndex(applied, insertion)
    // This is a real system instruction, not host retrieval data or a fabricated human turn.
    // GenerationInputSnapshot freezes this projection once for the entire tool sequence.
    return applied.take(safe) + injectionSystemMessage(after,
        byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty()) + applied.drop(safe)
}

/**
 * 收集需要注入的内容
 */
internal fun collectInjections(
    messages: List<UIMessage>,
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
): List<PromptInjection> {
    val nonSystemMessages = messages.filter { it.role != MessageRole.SYSTEM }
    return configuredInjections(assistant, modeInjections, lorebooks, conversationModeInjectionIds,
        conversationLorebookIds).filter { injection ->
        injection !is PromptInjection.RegexInjection || injection.constantActive ||
            injection.isTriggered(extractContextForMatching(
                if (injection.scanUserOnly) nonSystemMessages.filter { it.role == MessageRole.USER && !it.isSynthetic }
                else nonSystemMessages,
                injection.scanDepth.coerceAtLeast(0)))
    }
}

private fun configuredInjections(
    assistant: Assistant,
    modeInjections: List<PromptInjection.ModeInjection>,
    lorebooks: List<Lorebook>,
    conversationModeInjectionIds: Set<Uuid>,
    conversationLorebookIds: Set<Uuid>,
): List<PromptInjection> {
    val injections = mutableListOf<PromptInjection>()
    val effectiveModeInjectionIds = if (assistant.allowConversationPromptInjection) {
        conversationModeInjectionIds
    } else {
        assistant.modeInjectionIds
    }
    val effectiveLorebookIds = if (assistant.allowConversationPromptInjection) {
        conversationLorebookIds
    } else {
        assistant.lorebookIds
    }

    // 1. 获取关联的 ModeInjection
    modeInjections
        .filter { it.enabled && effectiveModeInjectionIds.contains(it.id) }
        .forEach { injections.add(it) }

    // 2. 获取关联的 Lorebook 中被触发的 RegexInjection
    val enabledLorebooks = lorebooks.filter {
        it.enabled && effectiveLorebookIds.contains(it.id)
    }
    enabledLorebooks.forEach { lorebook -> injections.addAll(lorebook.entries.filter { it.enabled }) }

    return injections
}

/**
 * 应用注入到消息列表
 */
internal fun applyInjections(
    messages: List<UIMessage>,
    byPosition: Map<InjectionPosition, List<PromptInjection>>
): List<UIMessage> {
    val result = messages.toMutableList()

    // 找到系统消息的索引（通常是第一条）
    val systemIndex = result.indexOfFirst { it.role == MessageRole.SYSTEM }

    // 处理 BEFORE_SYSTEM_PROMPT 和 AFTER_SYSTEM_PROMPT
    if (systemIndex >= 0) {
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        if (beforeContent.isNotEmpty() || afterContent.isNotEmpty()) {
            val systemMessage = result[systemIndex]
            val originalText = systemMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("") { it.text }

            val newText = buildString {
                if (beforeContent.isNotEmpty()) {
                    append(beforeContent)
                    appendLine()
                }
                append(originalText)
                if (afterContent.isNotEmpty()) {
                    appendLine()
                    append(afterContent)
                }
            }

            result[systemIndex] = systemMessage.copy(
                parts = listOf(injectionText(newText,
                    byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty() +
                        byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty(), includeBaseSystem = true)),
                isSynthetic = true,
            )
        }
    } else {
        // 没有系统消息时，创建一个新的系统消息
        val beforeContent = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""
        val afterContent = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT]
            ?.joinToString("\n") { it.content } ?: ""

        val combinedContent = buildString {
            if (beforeContent.isNotEmpty()) {
                append(beforeContent)
            }
            if (afterContent.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                append(afterContent)
            }
        }

        if (combinedContent.isNotEmpty()) {
            result.add(0, injectionSystemMessage(combinedContent,
                byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty() +
                    byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty()))
        }
    }

    // 处理 TOP_OF_CHAT：在第一条用户消息之前插入
    val topInjections = byPosition[InjectionPosition.TOP_OF_CHAT]
    if (!topInjections.isNullOrEmpty()) {
        // 重新计算索引（因为可能插入了系统消息）
        var insertIndex = result.indexOfFirst { it.role == MessageRole.USER }
            .takeIf { it >= 0 } ?: result.size
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(topInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 BOTTOM_OF_CHAT：在最后一条消息之前插入
    val bottomInjections = byPosition[InjectionPosition.BOTTOM_OF_CHAT]
    if (!bottomInjections.isNullOrEmpty()) {
        var insertIndex = (result.size - 1).coerceAtLeast(0)
        insertIndex = findSafeInsertIndex(result, insertIndex)
        createMergedInjectionMessages(bottomInjections).forEach { message ->
            result.add(insertIndex, message)
            insertIndex++
        }
    }

    // 处理 AT_DEPTH：在指定深度位置插入（从最新消息往前数）
    // 按 injectDepth 分组，相同深度的合并，按深度从大到小处理（避免索引变化问题）
    val atDepthInjections = byPosition[InjectionPosition.AT_DEPTH]
    if (!atDepthInjections.isNullOrEmpty()) {
        val byDepth = atDepthInjections.groupBy { it.injectDepth }
        byDepth.keys.sortedDescending().forEach { depth ->
            val injections = byDepth[depth] ?: return@forEach
            // 计算插入位置：result.size - depth，但要确保在有效范围内
            // depth=1 表示在最后一条消息之前，depth=2 表示在倒数第二条之前...
            var insertIndex = (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
            insertIndex = findSafeInsertIndex(result, insertIndex)
            createMergedInjectionMessages(injections).forEach { message ->
                result.add(insertIndex, message)
                insertIndex++
            }
        }
    }

    // This explicit placement is anchored to the last authored human turn, never a tool result
    // or a synthetic USER injection. Without a human carrier keep the rule as SYSTEM near the
    // beginning (e.g. compacted context); never drop a selected rule or fabricate a human.
    val nearHuman = byPosition[InjectionPosition.BEFORE_LATEST_USER_SYSTEM].orEmpty()
    val humanIndex = result.indexOfLast { it.role == MessageRole.USER && !it.isSynthetic }
    if (nearHuman.isNotEmpty()) {
        val target = if (humanIndex >= 0) humanIndex else
            (result.indexOfFirst { it.role == MessageRole.SYSTEM } + 1).coerceAtLeast(0)
        result.add(findSafeInsertIndex(result, target),
            injectionSystemMessage(nearHuman.joinToString("\n") { it.content }, nearHuman))
    }
    return result
}

/** Local provenance only. Text metadata is never serialized as provider content. */
private fun injectionText(content: String, entries: List<PromptInjection>, includeBaseSystem: Boolean = false) =
    UIMessagePart.Text(content, metadata = buildJsonObject {
        put("source", "orbis_prompt_injection")
        put("diagnostic_sources", buildJsonArray {
            if (includeBaseSystem) add(JsonPrimitive("system_prompt"))
            if (entries.any { it is PromptInjection.RegexInjection }) add(JsonPrimitive("worldbook"))
            if (entries.any { it is PromptInjection.ModeInjection }) add(JsonPrimitive("mode_injection"))
        })
    })

private fun injectionSystemMessage(content: String, entries: List<PromptInjection>) =
    UIMessage.system("").copy(isSynthetic = true, parts = listOf(injectionText(content, entries)))

/**
 * 将同一 role 的注入合并成消息列表
 * 按 role 分组后合并内容，返回合并后的消息列表
 */
private fun createMergedInjectionMessages(injections: List<PromptInjection>): List<UIMessage> {
    return injections
        .groupBy { it.role }
        .map { (role, grouped) ->
            val mergedContent = grouped.joinToString("\n") { it.content }
            when (role) {
                MessageRole.ASSISTANT -> UIMessage.assistant(mergedContent)
                else -> UIMessage.user(mergedContent)
            }.copy(
                isSynthetic = true,
            )
        }
}

/**
 * 查找安全的插入位置，避免注入到 USER → ASSISTANT(含Tool) 之间
 *
 * 某些提供商（如 deepseek）要求 USER 之后紧跟带工具的 ASSISTANT，
 * 在两者之间插入消息会导致报错或破坏推理连续性。
 */
internal fun findSafeInsertIndex(messages: List<UIMessage>, targetIndex: Int): Int {
    var index = targetIndex.coerceIn(0, messages.size)

    // 向前查找，直到找到一个安全的位置
    while (index > 0) {
        val prevMessage = messages.getOrNull(index - 1)
        val currentMessage = messages.getOrNull(index)

        // 不能插入到 USER → ASSISTANT(含Tool) 之间
        val isPrevUser = prevMessage?.role == MessageRole.USER
        val isCurrentAssistantWithTools = currentMessage?.role == MessageRole.ASSISTANT
            && currentMessage.getTools().isNotEmpty()

        if (isPrevUser && isCurrentAssistantWithTools) {
            index--
        } else {
            break
        }
    }

    return index
}
