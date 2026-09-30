package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import org.junit.Assert.*
import org.junit.Test

class OrbisEndVoiceCallToolTest {
    @Test fun `late old voice generation never binds to newly started call`() {
        assertEquals("call-a", bindEndVoiceCallId("call-a", "call-b", true))
        assertEquals("call-a", bindEndVoiceCallId("call-a", "call-b", false))
        assertNull(bindEndVoiceCallId(null, "call-b", false))
        assertEquals("call-b", bindEndVoiceCallId(null, "call-b", true))
    }
    private class Fixture {
        var bound: String? = "call-a"
        var record: OrbisVoiceCallRecord? = OrbisVoiceCallRecord(
            id = "call-a", assistantId = "assistant", conversationId = "conversation",
            startedAtMs = 1, connectedAtMs = 2, status = OrbisVoiceCallStatus.ACTIVE,
        )
        val events = mutableListOf<String>()
        var failure: Throwable? = null
        var claimTransform: ((OrbisVoiceCallRecord) -> OrbisVoiceCallRecord)? = null
        var exactResult = true
        var endedReason: String? = null
        fun run(args: JsonElement = buildJsonObject {}): JsonObject = runBlocking {
            val tool = createOrbisEndVoiceCallTool("assistant", "conversation", bound,
                load = { id -> events += "load:$id"; record },
                claim = { id, reason ->
                    events += "claim:$id"
                    failure?.let { throw it }
                    val previous = requireNotNull(record)
                    val claimed = if (previous.aiEndRequestedAtMs != null) previous else previous.copy(
                        aiEndRequestedAtMs = 3, aiEndReasonText = reason,
                    )
                    (claimTransform?.invoke(claimed) ?: claimed).also { record = it }
                },
                endExact = { id, conversation, reason ->
                    events += "end:$id:$conversation"
                    endedReason = reason
                    exactResult
                },
            )
            Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        }
    }
    private fun JsonObject.code() = getValue("reason_code").jsonPrimitive.content

    @Test fun `omitted id uses captured call and persists intent before stop`() {
        val f = Fixture()
        val result = f.run()
        assertEquals("ended", result.getValue("outcome").jsonPrimitive.content)
        assertEquals(listOf("load:call-a", "claim:call-a", "end:call-a:conversation"), f.events)
        assertEquals(3L, f.record!!.aiEndRequestedAtMs)
        assertEquals("not_checked", result.getValue("archive_status").jsonPrimitive.content)
        assertFalse(result.containsKey("archive_completed"))
    }
    @Test fun `explicit captured id and optional trimmed reason work`() {
        val f = Fixture()
        assertEquals("ai_ended", f.run(buildJsonObject { put("call_id", "call-a"); put("reason", " 晚安 ") }).code())
        assertEquals("晚安", f.endedReason)
    }
    @Test fun `no bound call never acquires a later call`() {
        val f = Fixture().apply { bound = null }
        assertEquals("no_bound_call", f.run().code())
        assertTrue(f.events.isEmpty())
    }
    @Test fun `different explicit call is rejected before storage access`() {
        val f = Fixture()
        assertEquals("call_scope_mismatch", f.run(buildJsonObject { put("call_id", "new-call") }).code())
        assertTrue(f.events.isEmpty())
    }
    @Test fun `other assistant or conversation is never modified`() {
        listOf("assistant", "conversation").forEach { field ->
            val f = Fixture().apply {
                record = if (field == "assistant") record!!.copy(assistantId = "other")
                    else record!!.copy(conversationId = "other")
            }
            assertEquals("call_not_owned", f.run().code())
            assertEquals(listOf("load:call-a"), f.events)
        }
    }
    @Test fun `missing call is not treated as success`() {
        val f = Fixture().apply { record = null }
        assertEquals("call_not_owned", f.run().code())
        assertEquals(listOf("load:call-a"), f.events)
    }
    @Test fun `connecting or unconfirmed call cannot be ended by tool`() {
        listOf(false, true).forEach { active ->
            val f = Fixture().apply { record = record!!.copy(
                status = if (active) OrbisVoiceCallStatus.ACTIVE else OrbisVoiceCallStatus.CONNECTING,
                connectedAtMs = null,
            ) }
            assertEquals("call_not_connected", f.run().code())
            assertEquals(listOf("load:call-a"), f.events)
        }
    }
    @Test fun `terminal record is idempotent without changing archive state`() {
        listOf(OrbisVoiceCallStatus.ENDED, OrbisVoiceCallStatus.INTERRUPTED).forEach { status ->
            val f = Fixture().apply { record = record!!.copy(status = status, archiveStatus = OrbisVoiceArchiveStatus.READY) }
            assertEquals("already_ended", f.run().getValue("outcome").jsonPrimitive.content)
            assertEquals(listOf("load:call-a"), f.events)
            assertEquals(OrbisVoiceArchiveStatus.READY, f.record!!.archiveStatus)
        }
    }
    @Test fun `failed durable write prevents device stop and hides exception details`() {
        val f = Fixture().apply { failure = IllegalStateException("private-sentinel") }
        val result = f.run()
        assertEquals("end_request_failed", result.code())
        assertFalse(result.toString().contains("private-sentinel"))
        assertEquals(listOf("load:call-a", "claim:call-a"), f.events)
    }
    @Test fun `record race between read and claim prevents stop`() {
        val mutations: List<(OrbisVoiceCallRecord) -> OrbisVoiceCallRecord> = listOf(
            { it.copy(id = "new-call") }, { it.copy(assistantId = "other") },
            { it.copy(conversationId = "other") }, { it.copy(status = OrbisVoiceCallStatus.ENDED) },
            { it.copy(connectedAtMs = null) }, { it.copy(aiEndRequestedAtMs = null) },
        )
        mutations.forEach { mutation ->
            val f = Fixture().apply { claimTransform = mutation }
            assertEquals("call_changed", f.run().code())
            assertFalse(f.events.any { it.startsWith("end:") })
        }
    }
    @Test fun `runtime replacing call returns failure without retargeting`() {
        val f = Fixture().apply { exactResult = false }
        assertEquals("call_not_active", f.run().code())
        assertEquals("end:call-a:conversation", f.events.last())
    }
    @Test fun `invalid parameters never read or modify a call`() {
        listOf<JsonElement>(
            JsonNull, JsonPrimitive("x"), buildJsonObject { put("extra", "x") },
            buildJsonObject { put("call_id", 42) }, buildJsonObject { put("call_id", " ") },
            buildJsonObject { put("call_id", "x".repeat(129)) },
            buildJsonObject { put("reason", true) }, buildJsonObject { put("reason", "x".repeat(2001)) },
        ).forEach { args ->
            val f = Fixture()
            assertFalse(f.run(args).getValue("ok").jsonPrimitive.boolean)
            assertTrue(f.events.isEmpty())
        }
    }
    @Test fun `prior durable intent cannot be replaced by duplicate tool reason`() {
        val f = Fixture().apply { record = record!!.copy(aiEndRequestedAtMs = 10, aiEndReasonText = "原理由") }
        f.run(buildJsonObject { put("reason", "新理由") })
        assertEquals(10L, f.record!!.aiEndRequestedAtMs)
        assertEquals("原理由", f.endedReason)
    }
    @Test fun `cancellation propagates and prevents device stop`() {
        val f = Fixture().apply { failure = CancellationException("cancel") }
        try { f.run(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(f.events.any { it.startsWith("end:") })
    }
}
