package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.OrbisGardenKind
import org.junit.Assert.*
import org.junit.Test

class OrbisGardenToolsTest {
    private fun call(tool: Tool, raw: String = "{}") = runBlocking {
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text).jsonObject
    }
    @Test fun newAssistantsDoNotImplicitlyGrantGardenAccess() {
        assertFalse(LocalToolOption.LocalGarden in Assistant().localTools)
        assertEquals(LocalToolOption.LocalGarden, Json.decodeFromString<LocalToolOption>(Json.encodeToString(LocalToolOption.serializer(), LocalToolOption.LocalGarden)))
    }
    @Test fun catalogHasOnlyReadReadCreateWithWriteApproval() {
        val tools = createOrbisGardenTools { _, _ -> JsonObject(emptyMap()) }
        assertEquals(listOf("orbis_garden_list", "orbis_garden_read", "orbis_garden_create"), tools.map { it.name })
        assertFalse(tools[0].needsApproval(JsonObject(emptyMap())))
        assertFalse(tools[1].needsApproval(JsonObject(emptyMap())))
        assertTrue(tools[2].needsApproval(JsonObject(emptyMap())))
        assertNotNull(tools[2].hostApproval)
    }
    @Test fun validCreateOnlyCallsInjectedLocalStoreOnce() {
        var count = 0
        val tools = createOrbisGardenTools { action, args ->
            count++; assertEquals("create", action); assertEquals(OrbisGardenKind.LETTER, args.kind)
            assertEquals("正文", args.body); buildJsonObject { put("id", "synthetic") }
        }
        val result = call(tools[2], """{"kind":"LETTER","title":"标题","body":"正文"}""")
        assertTrue(result["ok"]!!.jsonPrimitive.boolean); assertFalse(result["network_requested"]!!.jsonPrimitive.boolean)
        assertEquals(1, count)
    }
    @Test fun rejectsUnknownFieldsAndDeleteUpdateRequestsBeforeAnyStorage() {
        var count = 0
        val tool = createOrbisGardenTools { _, _ -> count++; JsonObject(emptyMap()) }[2]
        listOf("[]", "{}", """{"kind":"DIARY","title":"x","body":"y","id":"overwrite"}""",
            """{"kind":"DELETE","title":"x","body":"y"}""", """{"kind":"DIARY","title":true,"body":"y"}""")
            .forEach { assertFalse(call(tool, it)["ok"]!!.jsonPrimitive.boolean) }
        assertEquals(0, count)
    }
    @Test fun paginationStrictTypesAndBounds() {
        var count = 0
        val tool = createOrbisGardenTools { _, args -> count++; assertEquals(20, args.limit); JsonObject(emptyMap()) }[0]
        assertTrue(call(tool)["ok"]!!.jsonPrimitive.boolean)
        listOf("""{"limit":31}""", """{"limit":0}""", """{"limit":"2"}""", """{"limit":2.5}""",
            """{"offset":-1}""", """{"offset":1000001}""", """{"kind":"all"}""")
            .forEach { assertFalse(call(tool, it)["ok"]!!.jsonPrimitive.boolean) }
        assertEquals(1, count)
    }
    @Test fun remoteModeFailsInsteadOfFallingBackOrEchoingPrivateErrors() {
        val mode = createOrbisGardenTools { _, _ -> error("garden_local_mode_required") }
        assertEquals("garden_local_mode_required", call(mode[0])["error"]!!.jsonPrimitive.content)
        val private = createOrbisGardenTools { _, _ -> error("secret path token synthetic") }
        val output = call(private[0]).toString()
        assertFalse(output.contains("secret")); assertFalse(output.contains("token")); assertFalse(output.contains("synthetic"))
    }
    @Test fun unreadSettingsDoNotTellUserToSwitchRoutesOrPretendToolExecuted() {
        val tools = createOrbisGardenTools { _, _ -> error("garden_configuration_unavailable") }
        val result = call(tools[0])
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("garden_configuration_unavailable", result["error"]!!.jsonPrimitive.content)
        val note = result["note"]!!.jsonPrimitive.content
        assertTrue(note.contains("本次未执行"))
        assertTrue(note.contains("重试读取"))
        assertFalse(note.contains("选择本地路线"))
    }
    @Test fun readIdCannotBeAPathAndCreateUsesUtf8ByteLimit() {
        var count = 0
        val tools = createOrbisGardenTools { _, _ -> count++; JsonObject(emptyMap()) }
        assertFalse(call(tools[1], """{"id":"../../private"}""")["ok"]!!.jsonPrimitive.boolean)
        val request = buildJsonObject { put("kind", "DIARY"); put("title", "x"); put("body", "中".repeat(11000)) }
        assertFalse(call(tools[2], request.toString())["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, count)
    }
    @Test fun cancellationIsNotConvertedIntoSuccessOrAutomaticRetry() {
        var calls = 0
        val tools = createOrbisGardenTools { _, _ -> calls++; throw CancellationException() }
        try { call(tools[0]); fail("cancelled") } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }
}
