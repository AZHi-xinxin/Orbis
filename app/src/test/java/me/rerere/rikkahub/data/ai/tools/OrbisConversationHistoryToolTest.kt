package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.OrbisHistoryRemovedInfo
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisConversationHistoryToolTest {
    private val old = UIMessage.assistant("old alternative")
    private val selected = UIMessage.assistant("current reply")
    private val deleted = UIMessage.user("deleted human text")
    private val baseline = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        old.toMessageNode().copy(messages = listOf(old, selected), selectIndex = 1)))
    private var current = baseline
    private var enabled = true
    private var reads = 0
    private var afterRead: () -> Unit = {}
    private var verify: (Conversation) -> Unit = {}
    private fun tool() = createOrbisConversationHistoryTool(baseline.assistantId, baseline.id,
        readConversation = { current }, listRemoved = { _, limit, offset ->
            reads++
            listOf(OrbisHistoryRemovedInfo(deleted.id.toString(), Uuid.random().toString(),
                deleted.role.name, deleted.createdAt.toString(), 1L)).drop(offset).take(limit)
        }, readRemoved = { _, id -> reads++; afterRead(); deleted.takeIf { it.id.toString() == id } },
        enabled = { enabled }, verifyCurrent = { verify(it) })
    private suspend fun invoke(args: String): JsonObject {
        val parts = tool().execute(Json.parseToJsonElement(args))
        assertTrue(parts.all { it is UIMessagePart.Text })
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
    private suspend fun rejected(args: String) {
        try { invoke(args); fail("must reject") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    @Test fun listAlternativesExcludesCurrentReplyAndDoesNotReadDeletedCache() = runTest {
        val result = invoke("""{"action":"list","source":"alternatives"}""")
        assertEquals(old.id.toString(), result["messages"]!!.jsonArray.single().jsonObject["message_id"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("old alternative"))
        assertFalse(result.toString().contains(selected.id.toString()))
        assertEquals(0, reads)
    }
    @Test fun listDeletionReturnsMetadataWithoutAutomaticallyInjectingText() = runTest {
        val result = invoke("""{"action":"list","source":"deleted"}""")
        assertEquals(deleted.id.toString(), result["messages"]!!.jsonArray.single().jsonObject["message_id"]!!.jsonPrimitive.content)
        assertFalse(result.toString().contains("deleted human text"))
        assertEquals("", tool().systemPrompt(me.rerere.ai.provider.Model(), emptyList()))
    }
    @Test fun readDeletionIsTextOnlyAndSupportsPagination() = runTest {
        val first = invoke("""{"action":"read","source":"deleted","message_id":"${deleted.id}","max_chars":12}""")
        assertEquals(12, first["quoted_json"]!!.jsonPrimitive.content.length)
        assertTrue(first["has_more"]!!.jsonPrimitive.boolean)
        val next = invoke("""{"action":"read","source":"deleted","message_id":"${deleted.id}","text_offset":12}""")
        assertTrue(next["quoted_json"]!!.jsonPrimitive.content.contains("deleted human text"))
    }
    @Test fun currentReplyCannotBeReadAsAlternativeOrDeleted() = runTest {
        assertEquals("not_found", invoke("""{"action":"read","source":"alternatives","message_id":"${selected.id}"}""")["status"]!!.jsonPrimitive.content)
        assertEquals("not_found", invoke("""{"action":"read","source":"deleted","message_id":"${selected.id}"}""")["status"]!!.jsonPrimitive.content)
        assertEquals(0, reads)
    }
    @Test fun scopeArgumentsCannotRedirectLookup() = runTest {
        rejected("""{"action":"list","source":"deleted","conversation_id":"${Uuid.random()}"}""")
        rejected("""{"action":"list","source":"deleted","assistant_id":"${Uuid.random()}"}""")
        assertEquals(0, reads)
    }
    @Test fun changedOwnerOrWindowCannotReadEvenIfToolWasPreviouslyGranted() = runTest {
        current = baseline.copy(assistantId = Uuid.random())
        rejected("""{"action":"list","source":"deleted"}""")
        current = baseline.copy(id = Uuid.random())
        rejected("""{"action":"list","source":"deleted"}""")
        assertEquals(0, reads)
    }
    @Test fun compactionCommittedDuringReadDiscardsResult() = runTest {
        afterRead = { current = current.copy(compactionEpoch = current.compactionEpoch + 1) }
        rejected("""{"action":"read","source":"deleted","message_id":"${deleted.id}"}""")
    }
    @Test fun staleDisplayedEpochCannotReadAlternativesAfterDatabaseCompaction() = runTest {
        verify = { error("history_cache_scope_changed") }
        rejected("""{"action":"read","source":"alternatives","message_id":"${old.id}"}""")
    }
    @Test fun disabledAndMidReadRevokedGrantReturnNoContents() = runTest {
        enabled = false
        rejected("""{"action":"list","source":"deleted"}""")
        assertEquals(0, reads)
        enabled = true
        afterRead = { enabled = false }
        rejected("""{"action":"read","source":"deleted","message_id":"${deleted.id}"}""")
    }
    @Test fun readingRequiresRememberableApprovalAndNeverRegistersRestoreOrExecute() {
        val tool = tool()
        assertTrue(tool.needsApproval(JsonObject(emptyMap())))
        assertTrue(tool.hostApproval!!.rememberable)
        assertEquals("orbis_current_history", tool.name)
    }
    @Test fun strictBoundedInputsRejectMisTypedAndUnboundedPagination() = runTest {
        for (value in listOf("true", "-1", "1.5", "51", "\"20\"")) {
            rejected("""{"action":"list","source":"deleted","limit":$value}""")
        }
        rejected("""{"action":"execute","source":"deleted"}""")
        rejected("""{"action":"restore","source":"deleted"}""")
    }
}
