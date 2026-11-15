package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.core.InputSchema

/** Host-only description of the request paused at a human approval. Never an execution grant. */
@Serializable
data class ApprovalToolCatalog(
    val assistantId: String,
    val conversationId: String?,
    val providerId: String,
    val modelId: String,
    val nativeModelId: String,
    val messageId: String,
    val userMessageId: String?,
    val tools: List<ApprovalToolDefinition>,
)

@Serializable
data class ApprovalToolDefinition(
    val name: String,
    val description: String,
    val parameters: InputSchema?,
    val hostApproval: HostToolApproval?,
)
