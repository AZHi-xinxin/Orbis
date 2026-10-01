package me.rerere.rikkahub.web.dto

import kotlinx.serialization.Serializable

@Serializable data class WebImportCreateRequest(val assistantId: String)
@Serializable data class WebImportSelection(val conversation: Int, val branch: Int)
@Serializable data class WebImportCommitRequest(
    val reviewToken: String, val selections: List<WebImportSelection>, val confirmed: Boolean = false,
)
@Serializable data class WebImportRowResult(val conversation: Int, val state: String, val reason: String? = null)
@Serializable data class WebImportStatusDto(
    val id: String, val state: String, val assistantId: String, val expiresAt: Long,
    val uploadedBytes: Long = 0, val maxArchiveBytes: Long, val conversationCount: Int = 0,
    val reviewToken: String? = null, val total: Int = 0, val completed: Int = 0,
    val imported: Int = 0, val skipped: Int = 0, val failed: Int = 0, val messages: Int = 0,
    val attachmentReferences: Int = 0, val rows: List<WebImportRowResult> = emptyList(), val error: String? = null,
    val warnings: List<String> = emptyList(), val skippedSummaries: Int = 0,
)
@Serializable data class WebImportConversationDto(
    val index: Int, val title: String, val totalNodes: Int, val messageCount: Int,
    val branchPointCount: Int, val branchCount: Int, val defaultBranch: Int, val defaultSelectionReason: String,
    val omittedSummaryCount: Int = 0,
)
@Serializable data class WebImportBranchDto(
    val index: Int, val messageCount: Int, val updatedAt: String, val isDefault: Boolean,
)
