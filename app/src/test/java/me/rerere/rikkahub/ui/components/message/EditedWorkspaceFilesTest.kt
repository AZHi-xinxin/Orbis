package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class EditedWorkspaceFilesTest {
    private val path = "/workspace/gift.html"
    private fun receipt(edit: Boolean = false, filePath: String = path): JsonObject = buildJsonObject {
        put("path", filePath)
        if (edit) put("replacements", 1) else {
            put("name", filePath.substringAfterLast('/'))
            put("isDirectory", false)
        }
        put("sizeBytes", 0)
        put("updatedAt", 1234)
    }
    private fun tool(edit: Boolean = false, workspace: String = "original-workspace", filePath: String = path): UIMessagePart.Tool {
        val name = if (edit) "workspace_edit_file" else "workspace_write_file"
        return UIMessagePart.Tool(
            toolCallId = "call", toolName = name, input = "not JSON: input must never supply the displayed path",
            output = listOf(UIMessagePart.Text(receipt(edit, filePath).toString())),
            hostApproval = HostToolApproval("workspace:$workspace:$name", "revision", "workspace"),
        )
    }
    private fun UIMessagePart.Tool.withReceipt(receipt: JsonObject) = copy(output = listOf(UIMessagePart.Text(receipt.toString())))
    private fun assertHidden(vararg tools: UIMessagePart) = assertTrue(editedWorkspaceFiles(tools.toList()).isEmpty())

    @Test fun successfulWriteAndEditUseHostReceiptNotInputAndDeduplicateByOriginalBinding() {
        val write = tool()
        val edit = tool(edit = true)
        assertEquals(listOf(EditedWorkspaceFile("original-workspace", path)), editedWorkspaceFiles(listOf(write, edit)))
        assertEquals("gift.html", editedWorkspaceFiles(listOf(write)).single().name)
        assertEquals(write, write.copy())
    }

    @Test fun samePathInDifferentWorkspacesStaysSeparate() {
        val files = editedWorkspaceFiles(listOf(tool(workspace = "A"), tool(workspace = "B")))
        assertEquals(listOf("A", "B"), files.map { it.workspaceId })
        assertEquals(listOf(path, path), files.map { it.path })
    }

    @Test fun pickerSavedStateRoundTripsTheOriginalWorkspaceAndPath() {
        val original = editedWorkspaceFiles(listOf(tool(workspace = "old-workspace"))).single()
        val saved = original.saveExportState()
        val current = editedWorkspaceFiles(listOf(tool(workspace = "new-workspace"))).single()
        val restored = restoreEditedWorkspaceExport(saved)
        assertEquals(original, restored)
        assertNotEquals(current, restored)
        assertEquals(listOf("old-workspace", path), saved)
    }

    @Test fun missingOrUnsafeSavedStateDoesNotGuessAnotherWorkspace() {
        listOf(emptyList(), listOf("old-workspace"), listOf("", path), listOf("../other", path),
            listOf("old-workspace", "/workspace/../other"), listOf("old-workspace", path, "extra"))
            .forEach { assertNull(restoreEditedWorkspaceExport(it)) }
    }

    @Test fun unboundHistoryNeverFallsBackToCurrentAssistantWorkspace() {
        assertHidden(tool().copy(hostApproval = null))
        listOf("workspace::workspace_write_file", "workspace:../other:workspace_write_file",
            "workspace:A:workspace_edit_file", "mcp:workspace:A:workspace_write_file",
            "workspace:A:unexpected:workspace_write_file").forEach { binding ->
            assertHidden(tool().copy(hostApproval = HostToolApproval(binding, "revision", "workspace")))
        }
    }

    @Test fun unexecutedDeniedPendingAndAnsweredCallsDoNotShowEvenWithPlausibleSuccessText() {
        val successful = tool()
        assertHidden(successful.copy(output = emptyList()),
            successful.copy(approvalState = ToolApprovalState.Denied("no")),
            successful.copy(approvalState = ToolApprovalState.Pending),
            successful.copy(approvalState = ToolApprovalState.Answered("not a file approval")))
        assertEquals(1, editedWorkspaceFiles(listOf(successful.copy(approvalState = ToolApprovalState.Approved))).size)
    }

    @Test fun structuredFailureUnknownResultAndHostFailureMarkersNeverBecomeFiles() {
        val successful = tool()
        listOf("{\"status\":\"failed\",\"reason_code\":\"workspace_file_error\",\"path\":\"$path\"}",
            "{\"status\":\"unknown\",\"path\":\"$path\"}", "permission denied", "Error: shell unavailable")
            .forEach { result -> assertHidden(successful.copy(output = listOf(UIMessagePart.Text(result)))) }
        listOf("tool_execution_interrupted", "generation_cancelled_by_user", "future_unknown_failure").forEach { code ->
            assertHidden(successful.copy(metadata = buildJsonObject { put("orbis_host_tool_failure", code) }))
        }
        assertHidden(successful.withReceipt(JsonObject(receipt() + ("status" to JsonPrimitive("failed")))))
        assertHidden(successful.withReceipt(JsonObject(receipt() + ("error" to JsonPrimitive("denied")))))
    }

    @Test fun malformedIncompleteContradictoryAndWrongTypeReceiptsReject() {
        val good = receipt()
        val bad = listOf(
            JsonObject(emptyMap()), JsonObject(good - "updatedAt"), JsonObject(good - "sizeBytes"),
            JsonObject(good + ("sizeBytes" to JsonPrimitive("0"))),
            JsonObject(good + ("updatedAt" to JsonPrimitive(-1))),
            JsonObject(good + ("sizeBytes" to JsonPrimitive(-1))),
            JsonObject(good + ("name" to JsonPrimitive("different.html"))),
            JsonObject(good + ("isDirectory" to JsonPrimitive(true))),
            JsonObject(good + ("isDirectory" to JsonPrimitive("false"))),
        )
        bad.forEach { assertHidden(tool().withReceipt(it)) }
        listOf("not JSON", "[]", "null", "x".repeat(16_385)).forEach {
            assertHidden(tool().copy(output = listOf(UIMessagePart.Text(it))))
        }
    }

    @Test fun editRequiresPositiveReplacementCountAndKnownOptionalStrategy() {
        val edit = tool(edit = true)
        val good = receipt(edit = true)
        assertHidden(edit.withReceipt(JsonObject(good + ("replacements" to JsonPrimitive(0)))))
        assertHidden(edit.withReceipt(JsonObject(good + ("replacements" to JsonPrimitive("1")))))
        assertHidden(edit.withReceipt(JsonObject(good + ("matchStrategy" to JsonPrimitive("unknown")))))
        listOf("exact", "line_trimmed", "block_anchor").forEach { strategy ->
            assertEquals(1, editedWorkspaceFiles(listOf(edit.withReceipt(JsonObject(good + ("matchStrategy" to JsonPrimitive(strategy)))))).size)
        }
    }

    @Test fun onlyOneTextReceiptFromActualWriteOrEditIsAccepted() {
        val good = tool()
        assertHidden(good.copy(output = good.output + UIMessagePart.Text("extra")))
        listOf("workspace_read_file", "workspace_shell", "mcp_workspace_write_file").forEach {
            assertHidden(good.copy(toolName = it))
        }
        assertHidden(UIMessagePart.Text("I wrote $path"))
    }

    @Test fun unsafePathsAreRejectedWithoutNormalizingIntoAnotherFile() {
        listOf("/workspace/../secret", "/workspace/./gift", "/workspace//gift", "relative.txt", "//host/file",
            "/workspace/gift/", "/workspace/a\\b", "/workspace/line\nfile", "/workspace/null\u0000file").forEach {
            assertHidden(tool(filePath = it))
        }
        assertEquals("/tmp/中文 礼物.html", editedWorkspaceFiles(listOf(tool(filePath = "/tmp/中文 礼物.html"))).single().path)
    }
}
