package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.OrbisGardenExtrasSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrbisGardenReadingToolsTest {
    private class Fixture(text: String = "第一段。\n\n第二段。", revision: Int = 7) {
        var snapshot = OrbisGardenExtrasSnapshot(revision, library(text))
        var loads = 0
        var saves = 0
        val tools = createOrbisGardenReadingTools { request ->
            executeOrbisGardenReadingRequest(
                request = request,
                load = { loads++; snapshot },
                save = { expected, data ->
                    saves++
                    check(snapshot.revision == expected) { "garden_extras_revision_changed" }
                    OrbisGardenExtrasSnapshot(expected + 1, data).also { snapshot = it }
                },
                companionName = "伙伴",
                now = { 1234L },
                newId = { "note_fixed" },
            )
        }
    }

    companion object {
        private fun call(tool: Tool, raw: String = "{}"): JsonObject = runBlocking {
            Json.parseToJsonElement(
                (tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text,
            ).jsonObject
        }

        private fun library(text: String): JsonObject = buildJsonObject {
            put("version", 1)
            put("books", buildJsonArray {
                add(buildJsonObject {
                    put("id", "book_one")
                    put("title", "本机测试书")
                    put("text", text)
                    put("chapters", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "第一章")
                            put("start", 0)
                            put("end", text.length)
                        })
                    })
                    put("progress", buildJsonObject {
                        put("chapter", 0); put("offset", 1); put("updatedAt", 99L)
                    })
                    put("font", buildJsonObject { put("size", 18); put("lineHeight", 1.9) })
                    put("bookmarks", JsonArray(emptyList()))
                    put("notes", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "legacy_note")
                            put("chapter", 0)
                            put("offset", 0)
                            put("text", "旧的人类笔记")
                            put("createdAt", 88L)
                        })
                    })
                    put("importedAt", 77L)
                })
            })
        }
    }

    @Test fun `catalog has bounded reads and only annotation needs approval`() {
        val tools = Fixture().tools
        assertEquals(listOf("orbis_reading_list", "orbis_reading_read_chapter", "orbis_reading_annotate", "orbis_reading_list_annotations"), tools.map { it.name })
        assertFalse(tools[0].needsApproval(JsonObject(emptyMap())))
        assertFalse(tools[1].needsApproval(JsonObject(emptyMap())))
        assertTrue(tools[2].needsApproval(JsonObject(emptyMap())))
        assertNotNull(tools[2].hostApproval)
        assertFalse(tools[3].needsApproval(JsonObject(emptyMap())))
        assertEquals(null, tools[3].hostApproval)
    }

    @Test fun `directory exposes metadata revision and no private book text`() {
        val result = call(Fixture("PRIVATE-CONTENT").tools[0])
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertFalse(result.toString().contains("PRIVATE-CONTENT"))
        assertFalse(result.getValue("automatic_book_upload").jsonPrimitive.boolean)
        val payload = result.getValue("result").jsonObject
        assertEquals(7, payload.getValue("library_revision").jsonPrimitive.int)
        assertEquals("book_one", payload.getValue("books").jsonArray.single().jsonObject.getValue("book_id").jsonPrimitive.content)
        assertEquals(0, payload.getValue("books").jsonArray.single().jsonObject.getValue("companion_annotation_count").jsonPrimitive.int)
    }

    @Test fun `chapter read returns stable ids and never exceeds 32KiB`() {
        val fixture = Fixture("中".repeat(12_000))
        val first = call(fixture.tools[1], """{"book_id":"book_one","chapter":0}""")
            .getValue("result").jsonObject
        assertTrue(first.getValue("text_bytes").jsonPrimitive.int <= READING_MAX_TEXT_BYTES)
        assertEquals("c0", first.getValue("chapter_id").jsonPrimitive.content)
        val paragraph = first.getValue("paragraphs").jsonArray.single().jsonObject
        assertEquals("c0:p0", paragraph.getValue("paragraph_id").jsonPrimitive.content)
        assertFalse(paragraph.getValue("complete").jsonPrimitive.boolean)
        assertEquals(0, first.getValue("next_paragraph_offset").jsonPrimitive.int)
        val nextText = first.getValue("next_text_offset").jsonPrimitive.int
        assertTrue(nextText in 1 until 12_000)
        val second = call(fixture.tools[1],
            """{"book_id":"book_one","chapter":0,"paragraph_offset":0,"text_offset":$nextText}""")
        assertTrue(second.getValue("ok").jsonPrimitive.boolean)
        assertEquals(2, fixture.loads)
        assertEquals(0, fixture.saves)
    }

    @Test fun `read rejects path ids unknown fields and surrogate split before leaking storage`() {
        val fixture = Fixture("😀后续")
        listOf(
            """{"book_id":"../../private","chapter":0}""",
            """{"book_id":"book_one","chapter":0,"secret":"x"}""",
            """{"book_id":"book_one","chapter":"0"}""",
        ).forEach { assertEquals("reading_invalid_parameters", call(fixture.tools[1], it).getValue("error").jsonPrimitive.content) }
        assertEquals(0, fixture.loads)
        val split = call(fixture.tools[1],
            """{"book_id":"book_one","chapter":0,"paragraph_offset":0,"text_offset":1}""")
        assertEquals("reading_text_offset_invalid", split.getValue("error").jsonPrimitive.content)
        assertEquals(1, fixture.loads)
    }

    @Test fun `approved annotation uses CAS and companion identity without changing human progress`() {
        val fixture = Fixture()
        val result = call(fixture.tools[2],
            """{"book_id":"book_one","chapter":0,"paragraph":1,"expected_revision":7,"text":"伙伴的看法"}""")
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, fixture.saves)
        assertEquals(8, fixture.snapshot.revision)
        val book = fixture.snapshot.data.getValue("books").jsonArray.single().jsonObject
        assertEquals(99L, book.getValue("progress").jsonObject.getValue("updatedAt").jsonPrimitive.long)
        val notes = book.getValue("notes").jsonArray
        assertEquals(2, notes.size)
        val ai = notes.last().jsonObject
        assertEquals("companion", ai.getValue("author").jsonPrimitive.content)
        assertEquals("伙伴", ai.getValue("authorName").jsonPrimitive.content)
        assertEquals("伙伴的看法", ai.getValue("text").jsonPrimitive.content)
        assertFalse(notes.first().jsonObject.containsKey("author"))
    }

    @Test fun `caller cannot forge user author and stale revision writes nothing`() {
        val fixture = Fixture()
        val forged = call(fixture.tools[2],
            """{"book_id":"book_one","chapter":0,"paragraph":0,"expected_revision":7,"text":"x","author":"human"}""")
        assertEquals("reading_invalid_parameters", forged.getValue("error").jsonPrimitive.content)
        assertEquals(0, fixture.loads)
        val stale = call(fixture.tools[2],
            """{"book_id":"book_one","chapter":0,"paragraph":0,"expected_revision":6,"text":"x"}""")
        assertEquals("reading_revision_changed", stale.getValue("error").jsonPrimitive.content)
        assertEquals(1, fixture.loads)
        assertEquals(0, fixture.saves)
        assertEquals(7, fixture.snapshot.revision)
    }

    @Test fun `invalid stored shapes and private failures are redacted`() {
        val malformed = OrbisGardenExtrasSnapshot(1, buildJsonObject {
            put("version", 1); put("books", JsonArray(emptyList())); put("chat", "do not expose")
        })
        val invalid = createOrbisGardenReadingTools { request ->
            executeOrbisGardenReadingRequest(request, { malformed }, { _, _ -> error("must not save") }, "伙伴")
        }
        assertEquals("reading_invalid_library", call(invalid[0]).getValue("error").jsonPrimitive.content)
        val private = createOrbisGardenReadingTools { error("private path secret token") }
        val output = call(private[0]).toString()
        assertEquals("reading_storage_unavailable", call(private[0]).getValue("error").jsonPrimitive.content)
        assertFalse(output.contains("private")); assertFalse(output.contains("secret")); assertFalse(output.contains("token"))
    }

    @Test fun `pagination is strict and empty fresh library is safe`() {
        var calls = 0
        val tools = createOrbisGardenReadingTools { request ->
            calls++
            executeOrbisGardenReadingRequest(
                request,
                { OrbisGardenExtrasSnapshot(0, JsonObject(emptyMap())) },
                { _, _ -> error("must not write") },
                "伙伴",
            )
        }
        val empty = call(tools[0])
        assertEquals(0, empty.getValue("result").jsonObject.getValue("total").jsonPrimitive.int)
        listOf("""{"limit":31}""", """{"limit":"2"}""", """{"offset":-1}""").forEach {
            assertEquals("reading_invalid_parameters", call(tools[0], it).getValue("error").jsonPrimitive.content)
        }
        assertEquals(1, calls)
    }

    @Test fun `cancellation propagates and is never retried`() {
        var calls = 0
        val tools = createOrbisGardenReadingTools { calls++; throw CancellationException() }
        try {
            call(tools[0])
            fail("cancelled")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `annotation read preserves legacy human and companion authors without book text or writes`() {
        val fixture = Fixture("BOOK-BODY-NOT-REQUESTED\n\nSECOND-PARAGRAPH")
        call(fixture.tools[2], """{"book_id":"book_one","chapter":0,"paragraph":1,"expected_revision":7,"text":"伙伴的批注"}""")
        val before = fixture.snapshot
        val result = call(fixture.tools[3], """{"book_id":"book_one"}""")
        val payload = result.getValue("result").jsonObject
        val notes = payload.getValue("annotations").jsonArray
        assertEquals(2, notes.size)
        assertEquals("human", notes[0].jsonObject.getValue("author_role").jsonPrimitive.content)
        assertEquals(JsonNull, notes[0].jsonObject.getValue("author_name"))
        assertEquals("旧的人类笔记", notes[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("companion", notes[1].jsonObject.getValue("author_role").jsonPrimitive.content)
        assertEquals("伙伴", notes[1].jsonObject.getValue("author_name").jsonPrimitive.content)
        assertEquals("c0:p1", notes[1].jsonObject.getValue("paragraph_id").jsonPrimitive.content)
        assertEquals(1234L, notes[1].jsonObject.getValue("created_at_millis").jsonPrimitive.long)
        assertFalse(result.toString().contains("BOOK-BODY-NOT-REQUESTED"))
        assertFalse(result.getValue("network_requested").jsonPrimitive.boolean)
        assertEquals(before, fixture.snapshot)
        assertEquals(1, fixture.saves) // Only the explicitly approved fixture annotation wrote.
    }

    @Test fun `annotation filters enforce single book chapter paragraph and author scope`() {
        val fixture = Fixture()
        call(fixture.tools[2], """{"book_id":"book_one","chapter":0,"paragraph":1,"expected_revision":7,"text":"AI-only"}""")
        val book = fixture.snapshot.data.getValue("books").jsonArray.single().jsonObject
        val secondBook = JsonObject(book.toMutableMap().apply {
            put("id", JsonPrimitive("book_two"))
            put("notes", buildJsonArray { add(JsonObject(book.getValue("notes").jsonArray.first().jsonObject.toMutableMap().apply {
                put("text", JsonPrimitive("OTHER-BOOK-NOTE"))
            })) })
        })
        fixture.snapshot = fixture.snapshot.copy(data = JsonObject(fixture.snapshot.data.toMutableMap().apply {
            put("books", JsonArray(listOf(book, secondBook)))
        }))
        val filtered = call(fixture.tools[3], """{"book_id":"book_one","chapter":0,"paragraph":1,"author_role":"companion"}""")
        val notes = filtered.getValue("result").jsonObject.getValue("annotations").jsonArray
        assertEquals(1, notes.size)
        assertEquals("AI-only", notes.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(filtered.toString().contains("旧的人类笔记"))
        assertFalse(filtered.toString().contains("OTHER-BOOK-NOTE"))
        val empty = call(fixture.tools[3], """{"book_id":"book_one","chapter":0,"paragraph":1,"author_role":"human"}""")
        assertTrue(empty.getValue("result").jsonObject.getValue("annotations").jsonArray.isEmpty())
        assertEquals("reading_book_not_found", call(fixture.tools[3], """{"book_id":"missing"}""").getValue("error").jsonPrimitive.content)
    }

    @Test fun `annotation paging respects byte budget complete notes and immutable revision`() {
        val fixture = Fixture()
        val book = fixture.snapshot.data.getValue("books").jsonArray.single().jsonObject
        val largeText = "中".repeat(4_000)
        val notes = JsonArray((0 until 5).map { index -> buildJsonObject {
            put("id", "note_$index"); put("chapter", 0); put("offset", 0)
            put("text", largeText); put("createdAt", index)
        } })
        fixture.snapshot = fixture.snapshot.copy(data = JsonObject(fixture.snapshot.data.toMutableMap().apply {
            put("books", JsonArray(listOf(JsonObject(book.toMutableMap().apply { put("notes", notes) }))))
        }))
        val first = call(fixture.tools[3], """{"book_id":"book_one","limit":20}""").getValue("result").jsonObject
        assertEquals(2, first.getValue("returned").jsonPrimitive.int)
        assertTrue(first.getValue("annotation_bytes").jsonPrimitive.int <= READING_MAX_TEXT_BYTES)
        assertEquals(2, first.getValue("next_offset").jsonPrimitive.int)
        first.getValue("annotations").jsonArray.forEach { assertEquals(largeText, it.jsonObject.getValue("text").jsonPrimitive.content) }
        val second = call(fixture.tools[3], """{"book_id":"book_one","offset":2,"limit":1,"expected_revision":7}""").getValue("result").jsonObject
        assertEquals("note_2", second.getValue("annotations").jsonArray.single().jsonObject.getValue("annotation_id").jsonPrimitive.content)
        val last = call(fixture.tools[3], """{"book_id":"book_one","offset":4,"expected_revision":7}""").getValue("result").jsonObject
        assertEquals(JsonNull, last.getValue("next_offset"))
        fixture.snapshot = fixture.snapshot.copy(revision = 8)
        assertEquals("reading_revision_changed", call(fixture.tools[3], """{"book_id":"book_one","offset":2,"expected_revision":7}""").getValue("error").jsonPrimitive.content)
        assertEquals(0, fixture.saves)
    }

    @Test fun `annotation malformed or cross scope parameters never load library`() {
        val fixture = Fixture()
        listOf("{}", """{"book_id":"../private"}""", """{"book_id":"book_one","paragraph":0}""",
            """{"book_id":"book_one","author_role":"system"}""", """{"book_id":"book_one","limit":21}""",
            """{"book_id":"book_one","offset":1}""", """{"book_id":"book_one","chapter":"0"}""",
            """{"book_id":"book_one","include_all":true}""", """{"book_id":"book_one","expected_revision":-1}""").forEach {
            assertEquals(it, "reading_invalid_parameters", call(fixture.tools[3], it).getValue("error").jsonPrimitive.content)
        }
        assertEquals(0, fixture.loads)
        assertEquals(0, fixture.saves)
    }
}
