package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningRepository
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningStorage
import me.rerere.rikkahub.data.ai.contextpruning.MemoryContextPruningStorage
import me.rerere.rikkahub.data.ai.contextpruning.pruningTestMessages
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class OrbisContextPruningToolTest {
    private val conversation = Conversation(assistantId = Uuid.random(), messageNodes = pruningTestMessages().map { it.toMessageNode() })
    private val storage = MemoryContextPruningStorage()
    private val repo = ContextPruningRepository(conversation.assistantId.toString(), conversation.id.toString(), storage)
    private fun tool(enabled: Boolean = true, current: Conversation? = conversation) =
        createOrbisContextPruningTool(repo, readConversation = { current }, isEnabled = { enabled })
    private suspend fun invoke(tool: Tool, json: String) = Json.parseToJsonElement(
        (tool.execute(Json.parseToJsonElement(json)).single() as UIMessagePart.Text).text).jsonObject

    @Test fun `preview does not need write approval but apply and restore do`() = runTest {
        val tool = tool()
        assertFalse(tool.needsApproval(Json.parseToJsonElement("""{"action":"preview"}""")))
        assertFalse(tool.needsApproval(Json.parseToJsonElement("""{"action":"status"}""")))
        assertTrue(tool.needsApproval(Json.parseToJsonElement("""{"action":"apply"}""")))
        assertTrue(tool.needsApproval(Json.parseToJsonElement("""{"action":"restore"}""")))
        assertNotNull(tool.hostApproval)
        val preview = invoke(tool, """{"action":"preview"}""")
        assertEquals("preview", preview["status"]!!.jsonPrimitive.content)
        assertEquals(0, storage.writes)
        assertFalse(preview.toString().contains("synthetic thinking"))
        assertFalse(preview.toString().contains("synthetic tool result"))
    }

    @Test fun `status verifies stored state without computing a new plan or exposing source content`() = runTest {
        val result = invoke(tool(), """{"action":"status"}""")
        assertEquals("ok", result["status"]!!.jsonPrimitive.content)
        assertEquals("true", result["read_only"]!!.jsonPrimitive.content)
        assertEquals("true", result["state_verified"]!!.jsonPrimitive.content)
        assertEquals("0", result["active_batches"]!!.jsonPrimitive.content)
        assertTrue(result["batches"]!!.jsonArray.isEmpty())
        assertFalse(result.containsKey("plan_id"))
        assertFalse(result.toString().contains("synthetic"))
        assertEquals(0, storage.writes)
        assertNull(storage.value)
    }

    @Test fun `status reconciles an unconfirmed publication by plan then observes restoration without replay`() = runTest {
        val tool = tool()
        val plan = invoke(tool, """{"action":"preview"}""")["plan_id"]!!.jsonPrimitive.content
        storage.failReadBack = true
        try { invoke(tool, """{"action":"apply","plan_id":"$plan"}"""); fail("uncertain write must escape") }
        catch (_: IllegalStateException) { }
        assertEquals(1, storage.writes)
        storage.failReadBack = false
        val status = invoke(tool, """{"action":"status","plan_id":"$plan"}""")
        assertEquals("applied", status["query_state"]!!.jsonPrimitive.content)
        val batch = status["batches"]!!.jsonArray.single().jsonObject["batch_id"]!!.jsonPrimitive.content
        assertEquals(1, storage.writes)
        invoke(tool, """{"action":"restore","batch_id":"$batch"}""")
        val restored = invoke(tool, """{"action":"status","batch_id":"$batch"}""")
        assertEquals("restored", restored["query_state"]!!.jsonPrimitive.content)
        assertEquals("false", restored["historical_tools_executed"]!!.jsonPrimitive.content)
        assertEquals("0", restored["active_batches"]!!.jsonPrimitive.content)
        assertEquals(2, storage.writes)
    }

    @Test fun `same plan clearly reuses its batch and restored plans are refused without another write`() = runTest {
        val tool = tool()
        val plan = invoke(tool, """{"action":"preview"}""")["plan_id"]!!.jsonPrimitive.content
        val first = invoke(tool, """{"action":"apply","plan_id":"$plan"}""")
        val again = invoke(tool, """{"action":"apply","plan_id":"$plan"}""")
        assertEquals(first["batch_id"], again["batch_id"])
        assertEquals("false", first["reused_existing_batch"]!!.jsonPrimitive.content)
        assertEquals("true", again["reused_existing_batch"]!!.jsonPrimitive.content)
        assertEquals(1, storage.writes)
        invoke(tool, """{"action":"restore","batch_id":"${first["batch_id"]!!.jsonPrimitive.content}"}""")
        val refused = invoke(tool, """{"action":"apply","plan_id":"$plan"}""")
        assertEquals("rejected", refused["status"]!!.jsonPrimitive.content)
        assertEquals("context_pruning_plan_already_restored", refused["reason"]!!.jsonPrimitive.content)
        assertEquals("false", refused["state_changed"]!!.jsonPrimitive.content)
        assertEquals("preview", refused["next_action"]!!.jsonPrimitive.content)
        assertEquals(2, storage.writes)
        val newPlan = invoke(tool, """{"action":"preview"}""")["plan_id"]!!.jsonPrimitive.content
        assertNotEquals(plan, newPlan)
        assertEquals("applied", invoke(tool, """{"action":"apply","plan_id":"$newPlan"}""")["status"]!!.jsonPrimitive.content)
    }

    @Test fun `changed preview and missing restore batch return only definite business refusals`() = runTest {
        val plan = invoke(tool(), """{"action":"preview"}""")["plan_id"]!!.jsonPrimitive.content
        val changed = invoke(tool(), """{"action":"apply","mode":"reasoning","plan_id":"$plan"}""")
        assertEquals("context_pruning_preview_changed", changed["reason"]!!.jsonPrimitive.content)
        val missing = invoke(tool(), """{"action":"restore","batch_id":"${Uuid.random()}"}""")
        assertEquals("context_pruning_batch_not_found", missing["reason"]!!.jsonPrimitive.content)
        assertEquals("false", missing["state_changed"]!!.jsonPrimitive.content)
        assertEquals(0, storage.writes)
    }

    @Test fun `storage failure with business-looking text is still uncertain not a completed refusal`() = runTest {
        val failing = object : ContextPruningStorage {
            override fun <T> locked(write: Boolean, block: () -> T): T = block()
            override fun read(): String? = null
            override fun write(value: String) { error("context_pruning_preview_changed") }
        }
        val repository = ContextPruningRepository(conversation.assistantId.toString(), conversation.id.toString(), failing)
        val tool = createOrbisContextPruningTool(repository, { conversation }, { true })
        val plan = invoke(tool, """{"action":"preview"}""")["plan_id"]!!.jsonPrimitive.content
        try { invoke(tool, """{"action":"apply","plan_id":"$plan"}"""); fail("must retain unknown outcome") }
        catch (error: IllegalStateException) { assertEquals("context_pruning_preview_changed", error.message) }
    }

    @Test fun `status distinguishes no matching durable batch from unreadable state`() = runTest {
        val result = invoke(tool(), """{"action":"status","plan_id":"${"0".repeat(64)}"}""")
        assertEquals("not_found", result["query_state"]!!.jsonPrimitive.content)
        storage.value = "synthetic damaged state"
        try { invoke(tool(), """{"action":"status"}"""); fail("cannot pretend corrupt policy is empty") }
        catch (_: IllegalStateException) { }
        assertEquals("synthetic damaged state", storage.value)
        assertEquals(0, storage.writes)
    }

    @Test fun `status rejects cross-scope unknown or conflicting selectors without writes`() = runTest {
        for (invalid in listOf("""{"action":"status","assistant_id":"other"}""",
            """{"action":"status","plan_id":false}""", """{"action":"status","batch_id":"../other"}""",
            """{"action":"status","batch_id":"${Uuid.random()}","plan_id":"${"0".repeat(64)}"}""")) {
            try { invoke(tool(), invalid); fail("must reject") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, storage.writes)
    }

    @Test fun `apply emits only short verified receipt and restore never executes a historical tool`() = runTest {
        val tool = tool()
        val preview = invoke(tool, """{"action":"preview"}""")
        val applied = invoke(tool, """{"action":"apply","plan_id":"${preview["plan_id"]!!.jsonPrimitive.content}"}""")
        assertEquals("applied", applied["status"]!!.jsonPrimitive.content)
        assertEquals("next_independent_generation", applied["effective_from"]!!.jsonPrimitive.content)
        assertTrue(applied.toString().length < 512)
        val restored = invoke(tool, """{"action":"restore","batch_id":"${applied["batch_id"]!!.jsonPrimitive.content}"}""")
        assertEquals("restored", restored["status"]!!.jsonPrimitive.content)
        assertEquals("false", restored["historical_tools_executed"]!!.jsonPrimitive.content)
        assertEquals(2, storage.writes)
    }

    @Test fun `tool disabled deleted conversation and wrong assistant or window fail without side effects`() = runTest {
        for (blocked in listOf(tool(enabled = false), tool(current = null),
            tool(current = conversation.copy(assistantId = Uuid.random())), tool(current = conversation.copy(id = Uuid.random())))) {
            try { invoke(blocked, """{"action":"preview"}"""); fail("must reject") }
            catch (_: IllegalStateException) { }
        }
        assertEquals(0, storage.writes)
    }

    @Test fun `unapproved broad arguments no preview and wrong JSON types cannot broaden scope`() = runTest {
        for (invalid in listOf("""{"action":"apply"}""", """{"action":"preview","conversation_id":"other"}""",
            """{"action":"preview","max_messages":"200"}""", """{"action":"preview","max_messages":201}""",
            """{"action":"preview","mode":true}""", """{"action":"restore","batch_id":"../other"}""")) {
            try { invoke(tool(), invalid); fail("must reject") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
        assertEquals(0, storage.writes)
    }
}
