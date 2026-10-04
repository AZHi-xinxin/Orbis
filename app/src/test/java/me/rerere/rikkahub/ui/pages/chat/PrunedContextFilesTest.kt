package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.HostToolApproval
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningDisplayProjection
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test

class PrunedContextFilesTest {
    private fun node(vararg parts: UIMessagePart) = UIMessage.assistant("").copy(parts = parts.toList()).toMessageNode()
    private fun hidden(vararg indexes: Int) = ContextPruningDisplayProjection(hiddenToolIndexes = indexes.toSet())
    private fun write(path: String = "/workspace/kept.txt", workspace: String? = "historical-workspace") = UIMessagePart.Tool(
        toolCallId = "synthetic-write", toolName = "workspace_write_file",
        input = buildJsonObject { put("path", path); put("text", "synthetic saved body") }.toString(),
        output = listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path); put("name", path.substringAfterLast('/')); put("isDirectory", false)
            put("sizeBytes", 20); put("updatedAt", 1234)
        }.toString())),
        hostApproval = workspace?.let { HostToolApproval("workspace:$it:workspace_write_file", "revision", "workspace") },
    )

    @Test fun `successful hidden write keeps a historical file link and original source identity`() {
        val source = node(UIMessagePart.Text("retained prose"), write())
        val originalParts = source.currentMessage.parts
        val file = prunedContextFiles(source, hidden(1)).single() as OrbisCallFile.Workspace

        assertEquals("/workspace/kept.txt", file.path)
        assertEquals("historical-workspace", file.workspaceId)
        assertEquals(source.currentMessage.id.toString(), file.sourceMessageId)
        assertSame(originalParts, source.currentMessage.parts)
        assertEquals(2, source.currentMessage.parts.size)
    }

    @Test fun `failed denied pending or unsafe writes never gain a file entry`() {
        val original = write()
        val source = node(
            original.copy(output = listOf(UIMessagePart.Text("{\"status\":\"failed\"}"))),
            original.copy(approvalState = ToolApprovalState.Denied("synthetic denial")),
            original.copy(output = emptyList(), approvalState = ToolApprovalState.Pending),
            write("/workspace/../outside.txt"),
        )
        assertTrue(prunedContextFiles(source, hidden(0, 1, 2, 3)).isEmpty())
    }

    @Test fun `unbound historical write never guesses current workspace but retains explicit write time copy`() {
        val file = prunedContextFiles(node(write(workspace = null)), hidden(0)).single() as OrbisCallFile.Workspace
        assertNull(file.workspaceId)
        assertTrue(file.hasWriteSnapshot)
        assertEquals("synthetic saved body", file.writtenTextOrNull())
    }

    @Test fun `only selected hidden tools contribute and control or retained tools are not duplicated`() {
        val source = node(write("/workspace/hidden.txt"), write("/workspace/visible.txt"))
        val projection = ContextPruningDisplayProjection(hiddenToolIndexes = setOf(0), controlToolIndexes = setOf(1))
        assertEquals(listOf("hidden.txt"), prunedContextFiles(source, projection).map { it.name })
        assertTrue(prunedContextFiles(source, null).isEmpty())
        assertTrue(prunedContextFiles(source, ContextPruningDisplayProjection()).isEmpty())
        val anotherBranch = source.copy(messages = source.messages + UIMessage.assistant("selected branch has no files"), selectIndex = 1)
        assertTrue(prunedContextFiles(anotherBranch, hidden(0, 1)).isEmpty())
    }

    @Test fun `structured tool documents remain accessible without repeating top level documents`() {
        val direct = UIMessagePart.Document("file:///synthetic/upload/direct.pdf", "direct.pdf", "application/pdf")
        val nested = UIMessagePart.Document("file:///synthetic/upload/nested.pdf", "nested.pdf", "application/pdf")
        val tool = UIMessagePart.Tool("synthetic-docs", "read_document", "{}", listOf(direct, nested, nested))
        val source = node(direct, tool)
        val files = prunedContextFiles(source, hidden(1))
        assertEquals(1, files.size)
        assertEquals(nested, (files.single() as OrbisCallFile.Document).part)
        assertEquals(source.currentMessage.id.toString(), files.single().sourceMessageId)
        assertTrue(prunedContextFiles(source, hidden(0)).isEmpty())
    }

    @Test fun `the actual host receipt wins over a model supplied different input path`() {
        val tool = write().copy(input = "{\"path\":\"/workspace/not-written.txt\",\"text\":\"invented\"}")
        val file = prunedContextFiles(node(tool), hidden(0)).single() as OrbisCallFile.Workspace
        assertEquals("/workspace/kept.txt", file.path)
        assertNull(file.writtenTextOrNull())
    }

    @Test fun `cleared image tool still exposes its actual image but not text reasoning or tool bodies`() {
        val image = UIMessagePart.Image("file:///synthetic/upload/kept.png")
        val nestedTool = UIMessagePart.Tool("not-for-display", "write", "{\"secret\":\"synthetic input\"}")
        val tool = UIMessagePart.Tool("image-read", "workspace_read_file", "{\"path\":\"/workspace/kept.png\"}",
            listOf(image, UIMessagePart.Text("old tool output"), UIMessagePart.Reasoning("old thought"), nestedTool))
        val source = node(UIMessagePart.Text("retained prose"), tool)
        assertEquals(listOf(image), prunedContextMedia(source, hidden(1)))
        assertEquals(4, source.currentMessage.getTools().single().output.size)
        assertTrue(prunedContextMedia(source, hidden(0)).isEmpty())
    }

    @Test fun `media has no pending denied inactive or duplicated direct entry`() {
        val image = UIMessagePart.Image("file:///synthetic/upload/kept.png")
        val executed = UIMessagePart.Tool("image-read", "read", "{}", listOf(image, image))
        assertEquals(listOf(image), prunedContextMedia(node(executed), hidden(0)))
        assertTrue(prunedContextMedia(node(image, executed), hidden(1)).isEmpty())
        assertTrue(prunedContextMedia(node(executed.copy(approvalState = ToolApprovalState.Denied("no"))), hidden(0)).isEmpty())
        assertTrue(prunedContextMedia(node(executed.copy(output = emptyList(), approvalState = ToolApprovalState.Pending)), hidden(0)).isEmpty())
        assertTrue(prunedContextMedia(node(executed), null).isEmpty())
        val alternate = node(executed).let { it.copy(messages = it.messages + UIMessage.assistant("selected branch"), selectIndex = 1) }
        assertTrue(prunedContextMedia(alternate, hidden(0)).isEmpty())
    }

    @Test fun `ordinary audio and video survive but independent voice note is not repeated`() {
        val audio = UIMessagePart.Audio("file:///synthetic/upload/audio.mp3")
        val video = UIMessagePart.Video("file:///synthetic/upload/video.mp4")
        val voiceNote = UIMessagePart.Audio("file:///synthetic/upload/voice-note.mp3",
            buildJsonObject { put("orbis_voice_note", true) })
        val tool = UIMessagePart.Tool("media", "media_output", "{}", listOf(audio, video, voiceNote))
        assertEquals(listOf(audio, video), prunedContextMedia(node(tool), hidden(0)))
    }
}
