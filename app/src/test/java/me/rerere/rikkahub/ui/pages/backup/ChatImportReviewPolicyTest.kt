package me.rerere.rikkahub.ui.pages.backup

import me.rerere.rikkahub.data.sync.importer.DeepSeekBranchPreview
import me.rerere.rikkahub.data.sync.importer.DeepSeekConversationPreview
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ChatImportReviewPolicyTest {
    @Test fun frozenTargetDoesNotFollowCurrentAssistantAndMissingTargetFailsClosed() {
        val original = Uuid.parse("11111111-1111-4111-8111-111111111111")
        val other = Uuid.parse("22222222-2222-4222-8222-222222222222")
        val names = mutableMapOf(original to "Original", other to "Other")
        assertEquals("Original", requireImportTargetName(original, names::get))
        names.remove(original)
        assertThrows(IllegalStateException::class.java) { requireImportTargetName(original, names::get) }
        assertEquals("Other", names[other])
        names[original] = ""
        assertEquals("未命名 AI", requireImportTargetName(original, names::get))
    }
    private fun preview(reason: String) = DeepSeekConversationPreview("synthetic", "Synthetic",
        Instant.EPOCH, Instant.EPOCH, 3, 2, 1,
        listOf(DeepSeekBranchPreview("selected", 2, Instant.EPOCH, true)), "selected", reason)

    @Test fun kelivoCountsKeepAllSourceVersionsDistinctFromSelectedMessages() {
        val conversation = preview("kelivo_selected_versions")
        assertEquals("导入当前回答 2 条 · 源消息（含备用版本）3 条",
            importPreviewCounts(conversation, conversation.branches.single()))
    }
    @Test fun otherImportSourcesKeepTheirExistingPathCountSemantics() {
        val conversation = preview("source_current_node")
        assertEquals("所选路径 2 条 · 原窗口 2 条 · 1 条可选路径",
            importPreviewCounts(conversation, conversation.branches.single()))
    }
    @Test fun claudeCountsDistinguishCompletePathFromAllUniqueSourceMessages() {
        for (reason in listOf("claude_complete_path", "claude_complete_path_missing_parent")) {
            val conversation = preview(reason)
            assertEquals("此完整路径 2 条 · 源会话 3 条不同消息",
                importPreviewCounts(conversation, conversation.branches.single()))
        }
    }

    @Test fun chatGptCountsExposeWholeSourceDistinctFromSelectedVisiblePath() {
        for (reason in listOf("chatgpt_complete_path", "chatgpt_complete_path_current")) {
            val conversation = preview(reason)
            assertEquals("此完整路径 2 条 · 源会话 3 条不同消息",
                importPreviewCounts(conversation, conversation.branches.single()))
        }
    }
}
