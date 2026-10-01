package me.rerere.rikkahub.data.orbis.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisScheduleToolsTest {
    private class Memory : OrbisSchedulePersistence {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) { this.bytes = bytes.copyOf(); writes++ }
    }
    private fun catalog(store: OrbisScheduleStore) = createOrbisScheduleTools { executeOrbisScheduleRequest(store, it) }.associateBy { it.name.removePrefix("orbis_schedule_") }
    private fun call(tool: Tool, raw: String = "{}") = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(raw))
        assertEquals(1, parts.size)
        Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
    private fun createJson(revision: Int = 0, title: String = "合成课表") = """{"expected_revision":$revision,"entry":{"kind":"weekly","title":"$title","weekdays":[1,3],"start_time":"08:00","end_time":"09:00","notes":"合成备注","important":true}}"""

    @Test fun `five tools offer normal mutation approval and reads require no setup gate`() {
        var calls = 0
        val tools = createOrbisScheduleTools { calls++; buildJsonObject { put("ok", true) } }
        assertEquals(listOf("orbis_schedule_list", "orbis_schedule_read", "orbis_schedule_create", "orbis_schedule_update", "orbis_schedule_delete"), tools.map { it.name })
        assertEquals(0, calls)
        tools.forEach { tool ->
            val write = tool.name.endsWith("create") || tool.name.endsWith("update") || tool.name.endsWith("delete")
            assertEquals(write, tool.needsApproval(JsonObject(emptyMap())))
            assertEquals(write, tool.hostApproval != null)
            if (write) {
                assertEquals("local-shared-schedule-v1", tool.hostApproval?.revision)
                assertTrue(tool.hostApproval?.rememberable == true)
            }
            assertTrue(tool.parameters() is InputSchema.Obj)
        }
    }

    @Test fun `catalog specifies bounded page size and complete replacement fields`() {
        val tools = createOrbisScheduleTools { error("not executed") }.associateBy { it.name }
        val list = tools.getValue("orbis_schedule_list").parameters() as InputSchema.Obj
        assertEquals(20, list.properties.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.int)
        val update = tools.getValue("orbis_schedule_update").parameters() as InputSchema.Obj
        assertEquals(listOf("id", "expected_revision", "entry"), update.required)
        val entry = update.properties.getValue("entry").jsonObject
        assertFalse(entry.getValue("additionalProperties").jsonPrimitive.boolean)
        assertTrue(entry.getValue("properties").jsonObject.keys.containsAll(listOf("valid_from", "valid_until", "weekdays", "all_day", "important", "notes")))
    }

    @Test fun `empty catalog returns a local empty list with no write`() {
        val disk = Memory()
        val tools = catalog(OrbisScheduleStore(disk))
        val result = call(tools.getValue("list"))
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("revision").jsonPrimitive.int)
        assertTrue(result.getValue("entries").jsonArray.isEmpty())
        assertFalse(result.getValue("network_requested").jsonPrimitive.boolean)
        assertFalse(result.getValue("reminder_scheduled").jsonPrimitive.boolean)
        assertEquals(0, disk.writes)
        assertNull(disk.bytes)
    }

    @Test fun `tools and human store share create read update delete state`() = runBlocking {
        val disk = Memory()
        val human = OrbisScheduleStore(disk, { 100L }, { "synthetic-entry" })
        val tools = catalog(human)
        val created = call(tools.getValue("create"), createJson())
        assertTrue(created.getValue("ok").jsonPrimitive.boolean)
        assertTrue(human.load().entries.single().details.important)
        assertTrue(created.getValue("entry").jsonObject.getValue("no_end_date").jsonPrimitive.boolean)
        val listed = call(tools.getValue("list"), """{"date":"2026-09-30"}""")
        assertEquals(1, listed.getValue("entries").jsonArray.size)
        assertFalse("notes" in listed.getValue("entries").jsonArray.single().jsonObject)
        val read = call(tools.getValue("read"), """{"id":"synthetic-entry"}""")
        assertEquals("合成备注", read.getValue("entry").jsonObject.getValue("notes").jsonPrimitive.content)
        val updated = call(tools.getValue("update"), """{"id":"synthetic-entry","expected_revision":1,"entry":{"kind":"date","title":"合成临时安排","date":"2026-10-01","all_day":true,"location":"合成地点"}}""")
        assertTrue(updated.getValue("ok").jsonPrimitive.boolean)
        val state = human.load()
        assertEquals(OrbisScheduleKind.DATE, state.entries.single().details.kind)
        assertEquals("合成地点", state.entries.single().details.location)
        assertEquals("", state.entries.single().details.notes)
        assertFalse(state.entries.single().details.important)
        assertTrue(call(tools.getValue("delete"), """{"id":"synthetic-entry","expected_revision":2}""").getValue("ok").jsonPrimitive.boolean)
        assertTrue(human.load().entries.isEmpty())
        assertEquals(3, disk.writes)
    }

    @Test fun `pagination is bounded and binds revision across pages`() = runBlocking {
        val disk = Memory()
        var nextId = 0
        val store = OrbisScheduleStore(disk, { 100L }, { "synthetic-${++nextId}" })
        repeat(21) { store.create(it, OrbisScheduleDraft(OrbisScheduleKind.DATE, "合成事项 $it", date = "2026-09-30", allDay = true)) }
        val tools = catalog(store)
        val first = call(tools.getValue("list"))
        assertEquals(20, first.getValue("entries").jsonArray.size)
        assertEquals(20, first.getValue("next_offset").jsonPrimitive.int)
        assertEquals("schedule_invalid_revision", call(tools.getValue("list"), """{"offset":20}""").getValue("error").jsonPrimitive.content)
        assertEquals(1, call(tools.getValue("list"), """{"offset":20,"expected_revision":21}""").getValue("entries").jsonArray.size)
        store.delete(21, "synthetic-1")
        assertEquals("schedule_revision_changed", call(tools.getValue("list"), """{"offset":20,"expected_revision":21}""").getValue("error").jsonPrimitive.content)
    }

    @Test fun `unknown keys wrong JSON types nulls and pathlike IDs never execute`() {
        var calls = 0
        val tools = createOrbisScheduleTools { calls++; buildJsonObject { put("ok", true) } }.associateBy { it.name.removePrefix("orbis_schedule_") }
        listOf("[]", "null", "{\"limit\":\"1\"}", "{\"limit\":0}", "{\"limit\":21}", "{\"offset\":-1}", "{\"date\":null}", "{\"kind\":\"unknown\"}", "{\"url\":\"https://example.invalid\"}").forEach {
            assertFalse(call(tools.getValue("list"), it).getValue("ok").jsonPrimitive.boolean)
        }
        listOf("../file", "/absolute", "not a stable id").forEach {
            assertFalse(call(tools.getValue("read"), """{"id":"$it"}""").getValue("ok").jsonPrimitive.boolean)
        }
        listOf(
            createJson().replace("\"expected_revision\":0", "\"expected_revision\":\"0\""),
            createJson().replace("\"important\":true", "\"important\":\"true\""),
            createJson().replace("[1,3]", "[1,1]"),
            createJson().replace("[1,3]", "[\"1\",3]"),
            createJson().replace("\"important\":true", "\"important\":true,\"token\":\"synthetic\""),
            """{"entry":{"kind":"date","title":"合成","date":"2026-09-30","all_day":true}}""",
        ).forEach { assertFalse(call(tools.getValue("create"), it).getValue("ok").jsonPrimitive.boolean) }
        assertEquals(0, calls)
    }

    @Test fun `stale and nonexistent targets preserve all data and do not retry`() = runBlocking {
        val disk = Memory()
        val store = OrbisScheduleStore(disk, { 100L }, { "synthetic-entry" })
        val tools = catalog(store)
        call(tools.getValue("create"), createJson())
        val before = disk.bytes!!.copyOf()
        assertEquals("schedule_revision_changed", call(tools.getValue("delete"), """{"id":"synthetic-entry","expected_revision":0}""").getValue("error").jsonPrimitive.content)
        assertEquals("schedule_not_found", call(tools.getValue("delete"), """{"id":"missing","expected_revision":1}""").getValue("error").jsonPrimitive.content)
        assertArrayEquals(before, disk.bytes)
        assertEquals(1, disk.writes)
    }

    @Test fun `weekly dates obey validity and kind filter without exposing unrelated notes`() = runBlocking {
        val disk = Memory()
        val store = OrbisScheduleStore(disk, { 100L }, { "weekly" })
        store.create(0, OrbisScheduleDraft(OrbisScheduleKind.WEEKLY, "合成周课", notes = "not returned in list", weekdays = listOf(3), allDay = true, validFrom = "2026-09-30", validUntil = "2026-10-07"))
        val tools = catalog(store)
        assertEquals(1, call(tools.getValue("list"), """{"date":"2026-10-07","kind":"weekly"}""").getValue("entries").jsonArray.size)
        assertEquals(0, call(tools.getValue("list"), """{"date":"2026-10-14"}""").getValue("entries").jsonArray.size)
        assertEquals(0, call(tools.getValue("list"), """{"date":"2026-10-07","kind":"date"}""").getValue("entries").jsonArray.size)
    }

    @Test fun `exception details are redacted and one invocation does not retry`() {
        var calls = 0
        val tool = createOrbisScheduleTools { calls++; error("synthetic secret payload") }.first()
        val result = call(tool)
        assertEquals("schedule_storage_unavailable", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.toString().contains("secret"))
        assertEquals(1, calls)
    }

    @Test fun `cancellation is propagated not converted to success or retried`() {
        var calls = 0
        val tool = createOrbisScheduleTools { calls++; throw CancellationException("synthetic cancellation") }.first()
        assertTrue(runCatching { call(tool) }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
    }
}
