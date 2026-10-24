package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ORBIS_BODY_TOOL
import me.rerere.rikkahub.data.ai.tools.ORBIS_MEMORY_TOOL
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext

/** Called after approval and durable execution checks; identity comes only from the host. */
internal suspend fun executeGenerationTool(tool: Tool, arguments: JsonElement,
    callId: String, messageId: String, conversationId: String?): List<UIMessagePart> {
    val needsIdentity = tool.name.startsWith("cloud_orbis_") || tool.name.startsWith("cloud_reading_") ||
        tool.name.startsWith("cloud_turtlesoup_") || tool.name == ORBIS_MEMORY_TOOL || tool.name == ORBIS_BODY_TOOL
    return if (needsIdentity) withContext(CloudToolInvocationContext(callId, messageId, conversationId)) {
        tool.execute(arguments)
    } else tool.execute(arguments)
}
