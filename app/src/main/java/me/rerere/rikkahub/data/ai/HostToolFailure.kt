package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools

private const val HOST_TOOL_FAILURE_METADATA_KEY = "orbis_host_tool_failure"

/** Facts known by the host, not an interpretation of arbitrary MCP output. */
enum class HostToolFailure(
    val code: String,
    val message: String,
    val executionPerformed: Boolean?,
) {
    NOT_PROVIDED(
        code = "tool_not_provided",
        message = "该工具不存在或本轮未提供，未执行。",
        executionPerformed = false,
    ),
    INTERRUPTED(
        code = "tool_execution_interrupted",
        message = "生成被中断，未收到完整工具结果；是否执行未知。",
        executionPerformed = null,
    ),
    USER_CANCELLED(
        code = "generation_cancelled_by_user",
        message = "你已停止生成，未收到完整工具结果；是否执行未知。",
        executionPerformed = null,
    ),
}

/** Keep completed results and existing call identity/approval/metadata intact. */
fun UIMessagePart.Tool.withHostToolFailure(reason: HostToolFailure): UIMessagePart.Tool {
    if (isExecuted) return this
    val result = buildJsonObject {
        put("status", "failed")
        put("reason_code", reason.code)
        put("message", reason.message)
        put("execution_performed", reason.executionPerformed?.let(::JsonPrimitive) ?: JsonNull)
    }
    return copy(
        output = listOf(UIMessagePart.Text(result.toString())),
        metadata = buildJsonObject {
            metadata?.forEach { (key, value) -> put(key, value) }
            put(HOST_TOOL_FAILURE_METADATA_KEY, reason.code)
        },
    )
}

/** Only the explicit host marker on a finished result can drive host error UI. */
fun UIMessagePart.Tool.hostToolFailure(): HostToolFailure? {
    if (!isExecuted) return null
    val marker = metadata?.get(HOST_TOOL_FAILURE_METADATA_KEY) as? JsonPrimitive ?: return null
    if (!marker.isString) return null
    return HostToolFailure.entries.firstOrNull { it.code == marker.content }
}

/** Neutral by default: a missing result alone never proves a user cancellation. */
fun UIMessage.finishInterruptedHostTools(
    reason: HostToolFailure = HostToolFailure.INTERRUPTED,
): UIMessage = finishPendingTools { it.withHostToolFailure(reason) }

/**
 * Exact dispatch against the tools provided for this generation, without lookup
 * refresh, argument parsing, alternative selection or retry on a missing name.
 * Existing execution/cancellation handling remains in the caller's callback.
 */
internal suspend fun UIMessagePart.Tool.dispatchProvidedTool(
    tools: List<Tool>,
    executeProvided: suspend (Tool) -> UIMessagePart.Tool,
): UIMessagePart.Tool {
    if (isExecuted) return this
    val definition = tools.firstOrNull { it.name == toolName }
        ?: return withHostToolFailure(HostToolFailure.NOT_PROVIDED)
    return executeProvided(definition)
}
