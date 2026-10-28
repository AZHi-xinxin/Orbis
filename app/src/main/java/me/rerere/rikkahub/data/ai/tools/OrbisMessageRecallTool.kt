package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal fun createMessageRecallTool(targets: List<Uuid>, recall: suspend (Uuid) -> Boolean) = Tool(
    name = "orbis_recall_message",
    description = "撤回本对话最近 20 条 AI 回复中的一条。recent_index=1 是上一条 AI 回复，2 是再上一条；人类消息和纯工具步骤不计数。同一轮索引固定。只可撤回自己的回复，不能撤回人类消息。人类只看到撤回提示；你仍可看到原文及撤回标记。不要在参数或结果后的回复中复述撤回的原文。工具仅返回 success。",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("recent_index", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 20) })
    }, required = listOf("recent_index")) },
    needsApproval = { false },
    execute = { args ->
        val success = try {
            val obj = args as? JsonObject
            val index = (obj?.get("recent_index") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            if (obj?.keys != setOf("recent_index") || index == null || index !in 1..20) false
            else targets.getOrNull(index - 1)?.let { recall(it) } ?: false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        listOf(UIMessagePart.Text(buildJsonObject { put("success", success) }.toString()))
    },
)
