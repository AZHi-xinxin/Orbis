package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.OrbisKaomojiRepository
import me.rerere.rikkahub.data.orbis.OrbisKaomojiStorage
import org.junit.Assert.*
import org.junit.Test

class OrbisKaomojiToolsTest {
    private fun repository() = OrbisKaomojiRepository(object : OrbisKaomojiStorage {
        var raw: String? = null
        override fun read() = raw
        override fun write(value: String) { raw = value }
    })
    private fun call(tool: Tool, raw: String = "{}") = runBlocking {
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text).jsonObject
    }

    @Test fun `list is bounded local read and writes require host approval`() {
        val repository = repository()
        val tools = createOrbisKaomojiTools { repository }
        assertEquals(listOf("orbis_kaomoji_list", "orbis_kaomoji_add", "orbis_kaomoji_update"), tools.map { it.name })
        val page = call(tools[0], """{"limit":2}""")
        assertEquals(2, page.getValue("entries").jsonArray.size)
        assertEquals(2, page.getValue("next_offset").jsonPrimitive.int)
        assertTrue(page.getValue("read_only").jsonPrimitive.boolean)
        assertNull(tools[0].hostApproval)
        assertNotNull(tools[1].hostApproval)
        assertNotNull(tools[2].hostApproval)
    }

    @Test fun `add and update save only with current revision and no deletion tool`() {
        val repository = repository()
        val tools = createOrbisKaomojiTools { repository }
        val added = call(tools[1], """{"label":"test","text":"(o_o)","tags":["test"]}""")
        assertTrue(added.getValue("saved_not_sent").jsonPrimitive.boolean)
        val entry = added.getValue("entry").jsonObject
        val id = entry.getValue("id").jsonPrimitive.content
        val update = """{"id":"$id","expected_revision":1,"label":"updated","text":"(O_O)"}"""
        assertTrue(call(tools[2], update).getValue("ok").jsonPrimitive.boolean)
        assertEquals("kaomoji_revision_conflict", call(tools[2], update).getValue("error").jsonPrimitive.content)
        assertEquals("(O_O)", repository.readSnapshot().entries.last().text)
        assertFalse(tools.any { it.name.contains("delete") })
    }

    @Test fun `malformed inputs and private exceptions are not reflected`() {
        val tools = createOrbisKaomojiTools { error("secret private path") }
        listOf("[]", """{"limit":"2"}""", """{"limit":31}""", """{"query":true}""", """{"unexpected":"secret"}""")
            .forEach { raw ->
                val result = call(tools[0], raw)
                assertEquals("kaomoji_invalid_parameters", result.getValue("error").jsonPrimitive.content)
                assertFalse(result.toString().contains("secret"))
            }
        assertEquals("kaomoji_storage_unavailable", call(tools[0]).getValue("error").jsonPrimitive.content)
        assertFalse(call(tools[1], """{"label":"a","text":5}""").getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun `cancellation remains cancellation`() {
        val tools = createOrbisKaomojiTools { throw CancellationException("cancelled") }
        assertThrows(CancellationException::class.java) { call(tools[0]) }
    }
}
