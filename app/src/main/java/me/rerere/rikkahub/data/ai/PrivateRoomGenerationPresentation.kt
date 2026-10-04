package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolPart
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.data.orbis.privateroom.isPrivateRoomToolName
import me.rerere.rikkahub.data.orbis.privateroom.isPotentialPrivateRoomToolName

/**
 * Mark private tool/reasoning presentation without hiding the assistant's public reply text.
 * Tool availability is not a private operation. Pending metadata only covers an actual streamed
 * tool whose incomplete name could be private. Never mask normal reasoning while merely waiting
 * for a response, or apply the human projection to model input or persisted parts.
 */
internal class PrivateRoomGenerationPresentation(private val enabled: Boolean) {
    private var privateTurn = false
    val hasPrivateOperation: Boolean get() = privateTurn

    fun protectTool(tool: UIMessagePart.Tool): UIMessagePart.Tool =
        if (!privateTurn || isPrivateRoomToolName(tool.toolName)) tool else tool.copy(
            approvalState = ToolApprovalState.Denied(
                "本轮包含隐私室操作，不能转交普通工具或外发；请在下一次普通聊天中单独处理。"),
        )

    fun protectOutstandingTools(messages: List<UIMessage>): List<UIMessage> {
        if (!privateTurn) return messages
        val last = messages.lastOrNull() ?: return messages
        val parts = last.parts.map { if (it is UIMessagePart.Tool && !it.isExecuted) protectTool(it) else it }
        return if (parts == last.parts) messages else messages.dropLast(1) + last.copy(parts = parts)
    }

    fun classify(messages: List<UIMessage>, completed: Boolean): List<UIMessage> {
        val last = messages.lastOrNull() ?: return messages
        if (last.role != MessageRole.ASSISTANT) return messages
        privateTurn = privateTurn || last.privateRoomContentHidden ||
            last.parts.any { it.isPrivateRoomToolPart() } ||
            last.deletedToolRecords.any { it.tool.isPrivateRoomToolPart() }
        val hidden = privateTurn
        val pending = !completed && (hidden || enabled && last.getTools().any {
            isPotentialPrivateRoomToolName(it.toolName)
        })
        if (last.privateRoomContentHidden == hidden && last.privateRoomPendingPresentation == pending) return messages
        return messages.dropLast(1) + last.copy(
            privateRoomContentHidden = hidden,
            privateRoomPendingPresentation = pending,
        )
    }
}
