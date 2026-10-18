package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compaction.ConversationCompactionControl
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationToolSetTest {
    private fun tool(name: String) = Tool(name = name, description = name,
        parameters = { InputSchema.Obj(buildJsonObject {}) }, execute = { emptyList() })
    private fun compact(messages: List<UIMessage>) = ConversationCompactionControl(0,
        readMessages = { messages }, readHistory = { "[]" }, commit = { _, _ -> error("not invoked") })

    @Test fun `approval reconstruction keeps original attachment sources but excludes new tool reply`() {
        val source = UIMessage.user("new attachment after interruption")
        val reply = UIMessage.assistant("pending ZIP and image tools")
        val later = UIMessage.user("unrelated later turn")
        assertEquals(setOf(source.id), toolApprovalSourceMessageIds(listOf(source, reply, later), reply.id))
        assertTrue(runCatching { toolApprovalSourceMessageIds(listOf(source), reply.id) }.isFailure)
    }

    @Test fun `all selected families and current message ids use identical assembly after interruption`() = runTest {
        val messages = listOf(UIMessage.user("before"), UIMessage.assistant("interrupted"), UIMessage.user("next"))
        val families = listOf("workspace_shell", "skill_read", "mcp__ST__stbrain_open", "orbis_zip_create",
            "orbis_zip_read", "orbis_list_current_images", "orbis_sentinel_list", "orbis_device", "orbis_context_prune",
            "orbis_image_workshop", "orbis_voice_note", "start_voice_call", "orbis_schedule_list", "calendar_list",
            "orbis_memory", "orbis_garden_list", "orbis_reading_list", "web_search", "orbis_group_read")
        suspend fun assemble() = conversationToolSet(messages, compact(messages)) { ids ->
            assertEquals(messages.map { it.id }.toSet(), ids)
            families.map(::tool)
        }
        val normal = assemble(); val resumed = assemble()
        assertEquals(normal.map { it.name }, resumed.map { it.name })
        assertTrue(resumed.map { it.name }.containsAll(families + compact(messages).tools().map { it.name }))
        assertEquals(resumed.size, resumed.map { it.name }.distinct().size)
    }

    @Test fun `local preparation slower than old two second cutoff keeps entire directory`() = runTest {
        val result = async { conversationToolSet(listOf(UIMessage.user("new")), null) {
            delay(2_100); listOf(tool("workspace_shell"), tool("orbis_zip_create"), tool("mcp__ST__stbrain_open"))
        } }
        advanceUntilIdle()
        assertEquals(listOf("workspace_shell", "orbis_zip_create", "mcp__ST__stbrain_open"), result.await().map { it.name })
    }

    @Test fun `cancelled old catalogue construction cannot consume or block new one`() = runTest {
        val stuck = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val old = launch { conversationToolSet(emptyList(), null) {
            entered.complete(Unit); withContext(NonCancellable) { stuck.await() }; listOf(tool("old"))
        } }
        entered.await(); old.cancel()
        val fresh = conversationToolSet(listOf(UIMessage.user("fresh")), null) { listOf(tool("orbis_zip_create")) }
        assertEquals("orbis_zip_create", fresh.single().name)
        stuck.complete(Unit); old.join()
    }

    @Test fun `directory preparation failure is not silently converted into tools absent`() = runTest {
        val error = runCatching { conversationToolSet(emptyList(), null) { error("synthetic invalid MCP configuration") } }
        assertTrue(error.isFailure)
    }

    @Test fun `continuation ZIP tool creates actual archive without shell zip and reads newly attached ZIP`() = runTest {
        val input = UIMessage.user("zip please").copy(parts = listOf(UIMessagePart.Document(
            "file:///synthetic/new.zip", "new.zip", "application/zip")))
        val active = Conversation(assistantId = Uuid.random(), messageNodes = listOf(input.toMessageNode()))
        var bytes = byteArrayOf()
        val document = UIMessagePart.Document("file:///synthetic/output.zip", "out.zip", "application/zip")
        val tools = conversationToolSet(active.currentMessages, compact(active.currentMessages)) { ids ->
            createOrbisZipAttachmentTools(active.assistantId, active.id, ids, { active }, { error("list does not read archive") }) +
                createOrbisZipCreateTool(active.assistantId.toString(), active.id.toString(),
                    { isZipCreationScopeCurrent(active.assistantId, active.id, input.id, ids, active) },
                    { result -> bytes = result.bytes; SavedGeneratedZip(1, document) }, { error("must retain file") })
        }
        val listed = tools.single { it.name == "orbis_zip_read" }.execute(Json.parseToJsonElement("""{"action":"list_archives"}"""))
        assertTrue(listed.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }.contains("new.zip"))
        val output = tools.single { it.name == "orbis_zip_create" }.execute(Json.parseToJsonElement(
            """{"name":"out.zip","files":[{"path":"doc/readme.md","text":"kept after interruption"}]}"""))
        assertEquals(document, output.filterIsInstance<UIMessagePart.Document>().single())
        ZipInputStream(bytes.inputStream()).use { zip ->
            assertEquals("doc/readme.md", zip.nextEntry.name)
            assertEquals("kept after interruption", zip.readBytes().toString(Charsets.UTF_8))
            assertNull(zip.nextEntry)
        }
    }
}
