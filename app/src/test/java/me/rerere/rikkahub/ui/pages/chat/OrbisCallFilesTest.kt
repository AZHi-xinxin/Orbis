package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class OrbisCallFilesTest {
    @Test fun toolArtifactsAreIndependentOfCollapsedDetailsButNotPermissionOrFailure() {
        val doc = UIMessagePart.Document("file:///app/files/upload/gift.zip", "gift.zip", "application/zip")
        val success = UIMessagePart.Tool("zip", "orbis_zip_create", "{}", output = listOf(doc))
        assertEquals(doc, orbisToolDocuments(listOf(success), "message").single().part)
        assertTrue(orbisToolDocuments(listOf(success.copy(output = emptyList())), "message").isEmpty())
        assertTrue(orbisToolDocuments(listOf(success.copy(approvalState = ToolApprovalState.Denied("no"))), "message").isEmpty())
        assertTrue(orbisToolDocuments(listOf(success.copy(metadata = buildJsonObject {
            put("orbis_host_tool_failure", "tool_execution_interrupted")
        })), "message").isEmpty())
        assertEquals(1, orbisToolDocuments(listOf(success, success), "message").size)
        assertTrue(orbisToolDocuments(listOf(UIMessagePart.Text("gift.zip")), "message").isEmpty())
    }
    private fun message(vararg parts: UIMessagePart) = UIMessage.assistant("").copy(
        parts = parts.toList(), orbisVoiceCallId = "call-1", orbisVoiceCallKind = "turn",
    ).toMessageNode()

    private fun write(path: String = "/workspace/gift.html", workspace: String? = "workspace-A",
        text: String = "saved text", id: String = "write", size: Long = 10, updated: Long = 1234,
    ) = UIMessagePart.Tool(
        toolCallId = id, toolName = "workspace_write_file",
        input = buildJsonObject { put("path", path); put("text", text) }.toString(),
        output = listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path); put("name", path.substringAfterLast('/')); put("isDirectory", false)
            put("sizeBytes", size); put("updatedAt", updated)
        }.toString())),
        hostApproval = workspace?.let { HostToolApproval("workspace:$it:workspace_write_file", "revision", "workspace") },
    )

    private fun edit(path: String = "/workspace/gift.html", workspace: String? = "workspace-A",
        replacements: Long = 1,
    ) = UIMessagePart.Tool(
        toolCallId = "edit", toolName = "workspace_edit_file",
        input = buildJsonObject { put("path", path); put("old_text", "old"); put("new_text", "new") }.toString(),
        output = listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path); put("replacements", replacements); put("matchStrategy", "line_trimmed")
            put("sizeBytes", 12); put("updatedAt", 2345)
        }.toString())),
        hostApproval = workspace?.let { HostToolApproval("workspace:$it:workspace_edit_file", "revision", "workspace") },
    )

    @Test fun actualWriteAndEditReceiptsProduceScopedLinksWithTheLatestMetadata() {
        val first = message(write())
        val last = message(edit())
        val files = orbisCallFiles(listOf(first, last))
        val file = files.single() as OrbisCallFile.Workspace
        assertEquals("/workspace/gift.html", file.path)
        assertEquals("gift.html", file.name)
        assertEquals("workspace-A", file.workspaceId)
        assertEquals(12L, file.sizeBytes)
        assertEquals(2345L, file.updatedAt)
        assertEquals(last.currentMessage.id.toString(), file.sourceMessageId)
        assertEquals("edit", file.toolCallId)
        assertFalse(file.hasWriteSnapshot)
        assertNull(file.writtenTextOrNull())
    }

    @Test fun samePathInDifferentWorkspacesOrWithoutBindingCannotBeMerged() {
        val files = orbisCallFiles(listOf(message(write(workspace = "A"), write(workspace = "B"), write(workspace = null))))
        assertEquals(3, files.size)
        assertEquals(setOf("A", "B", null), files.filterIsInstance<OrbisCallFile.Workspace>().map { it.workspaceId }.toSet())
        assertEquals(3, files.map { it.key }.toSet().size)
    }

    @Test fun legacyWriteProvidesExplicitWriteTimeCopyButNeverGuessesCurrentWorkspace() {
        val file = orbisCallFiles(listOf(message(write(workspace = null, text = "原文\n\"quoted\""))))
            .single() as OrbisCallFile.Workspace
        assertNull(file.workspaceId)
        assertTrue(file.hasWriteSnapshot)
        assertEquals("原文\n\"quoted\"", file.writtenTextOrNull())
        val altered = write(workspace = null).copy(input = "{\"path\":\"/workspace/different\",\"text\":\"wrong\"}")
        assertNull((orbisCallFiles(listOf(message(altered))).single() as OrbisCallFile.Workspace).writtenTextOrNull())
    }

    @Test fun deniedPendingFailedOrMerelyMentionedPathsDoNotCreateArtifacts() {
        val good = write()
        val failed = good.copy(output = listOf(UIMessagePart.Text("{\"status\":\"failed\",\"error\":\"no space\"}")))
        val denied = good.copy(approvalState = ToolApprovalState.Denied("not allowed"))
        val pending = good.copy(output = emptyList(), approvalState = ToolApprovalState.Pending)
        val unexecuted = good.copy(output = emptyList())
        val text = UIMessagePart.Text("I wrote /workspace/gift.html successfully")
        assertTrue(orbisCallFiles(listOf(message(failed, denied, pending, unexecuted, text))).isEmpty())
    }

    @Test fun readOnlyAndShellOutputsAreNotTreatedAsProducedFileReceipts() {
        val successful = write()
        assertTrue(orbisCallFiles(listOf(message(
            successful.copy(toolName = "workspace_read_file"), successful.copy(toolName = "workspace_shell"),
            successful.copy(toolName = "mcp_workspace_write_file"),
        ))).isEmpty())
    }

    @Test fun unsafeRootfsPathsAreRefusedRatherThanNormalizedIntoDifferentFiles() {
        val paths = listOf("/workspace/../private", "/workspace/./gift", "/workspace//gift", "relative.txt",
            "//server/file", "/workspace/gift/", "/workspace/gift\u0000.txt", "/workspace/a\\b", "/workspace/line\nfile")
        paths.forEach { path -> assertFalse(path, safeRootfsFilePath(path)) }
        assertTrue(orbisCallFiles(paths.map { path -> message(write(path)) }).isEmpty())
        assertTrue(safeRootfsFilePath("/tmp/中文 礼物.html"))
        assertEquals("/tmp/gift.html", (orbisCallFiles(listOf(message(write("/tmp/gift.html")))).single() as OrbisCallFile.Workspace).path)
    }

    @Test fun malformedOrContradictoryWorkspaceReceiptsAreNotSuccessfulArtifacts() {
        val original = write()
        val bad = listOf(
            "{}", "[1]", "not JSON",
            "{\"path\":\"/workspace/gift.html\",\"name\":\"gift.html\",\"isDirectory\":true,\"sizeBytes\":10,\"updatedAt\":1234}",
            "{\"path\":\"/workspace/gift.html\",\"name\":\"gift.html\",\"isDirectory\":false,\"sizeBytes\":\"10\",\"updatedAt\":1234}",
            "{\"path\":\"/workspace/gift.html\",\"name\":\"wrong\",\"isDirectory\":false,\"sizeBytes\":10,\"updatedAt\":1234}",
            "{\"path\":\"/workspace/gift.html\",\"name\":\"gift.html\",\"isDirectory\":false,\"sizeBytes\":10,\"updatedAt\":1234,\"error\":\"failed\"}",
        )
        bad.forEach { output ->
            assertTrue(output, orbisCallFiles(listOf(message(original.copy(output = listOf(UIMessagePart.Text(output)))))).isEmpty())
        }
        assertTrue(orbisCallFiles(listOf(message(write(size = -1), write(updated = -1), edit(replacements = 0)))).isEmpty())
    }

    @Test fun directAndStructuredToolDocumentsRemainVisibleAndAreDeduplicatedByUrl() {
        val doc = UIMessagePart.Document("file:///data/user/0/test/files/upload/report.pdf", "report.pdf", "application/pdf")
        val tool = UIMessagePart.Tool("document", "read_document", "{}", output = listOf(doc))
        val files = orbisCallFiles(listOf(message(doc), message(tool)))
        assertEquals(1, files.size)
        assertEquals(doc, (files.single() as OrbisCallFile.Document).part)
        assertEquals("report.pdf", files.single().name)
    }

    @Test fun documentUrlsCannotBeExecutableSchemesOrEscapingFilesystemPaths() {
        val unsafe = listOf("javascript:alert(1)", "data:text/html,test", "intent://app/path", "file:///data/../secret",
            "file:///data/%2e%2e/secret", "file://remotehost/share/file.txt", "content://provider/path/%00secret", "https://user:password@example.com/file")
        assertTrue(orbisCallFiles(unsafe.map { message(UIMessagePart.Document(it, "file")) }).isEmpty())
        val safe = listOf("content://provider/document/123", "https://example.com/file.pdf", "http://example.com/file.txt")
        assertEquals(3, orbisCallFiles(safe.map { message(UIMessagePart.Document(it, "../../safe.pdf")) }).size)
        assertTrue(orbisCallFiles(safe.map { message(UIMessagePart.Document(it, "../../safe.pdf")) }).all { it.name == "safe.pdf" })
    }

    @Test fun inactiveMessageBranchesNeverLeakTheirFileCardsAndSourcesAreUnchanged() {
        val initial = message(write())
        val inactive = initial.copy(messages = initial.messages + UIMessage.assistant("no file"), selectIndex = 1)
        assertTrue(orbisCallFiles(listOf(inactive)).isEmpty())
        val before = initial.copy()
        val files = orbisCallFiles(listOf(initial))
        assertEquals(1, files.size)
        assertEquals(before, initial)
        assertSame(before.currentMessage.parts, initial.currentMessage.parts)
    }

    @Test fun successPathComesFromHostReceiptNotModelInputOrNarration() {
        val actual = write().copy(input = "{\"path\":\"/workspace/fake.txt\",\"text\":\"fake\"}")
        val file = orbisCallFiles(listOf(message(actual, UIMessagePart.Text("download /workspace/fake.txt"))))
            .single() as OrbisCallFile.Workspace
        assertEquals("/workspace/gift.html", file.path)
        assertNull(file.writtenTextOrNull())
    }

    @Test fun hugeMalformedSnapshotsAreNotParsedAndEditNeverReturnsAPretendFullFile() {
        val huge = write(workspace = null).copy(input = "x".repeat(16 * 1024 * 1024 + 1))
        val file = orbisCallFiles(listOf(message(huge))).single() as OrbisCallFile.Workspace
        assertTrue(file.hasWriteSnapshot)
        assertNull(file.writtenTextOrNull())
        val edited = orbisCallFiles(listOf(message(edit(workspace = null)))).single() as OrbisCallFile.Workspace
        assertFalse(edited.hasWriteSnapshot)
        assertNull(edited.writtenTextOrNull())
    }
}
