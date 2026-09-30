package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

class OrbisGameLibraryToolsTest {
    private class Fixture {
        val repo = OrbisMiniGameRepository(object : OrbisGameStorage {
            var raw: String? = null
            override fun read() = raw
            override fun write(value: String) { raw = value }
        })
        val tools = createOrbisGameLibraryTools(repo::readSnapshot) { r ->
            repo.install("ai", "synthetic", r.title, r.description, r.html, r.gameId, r.expectedSha256)
        }
        fun install() = call(tools[0], """{"title":"Tic tac toe","html":"<html><button>Play</button></html>"}""")
    }
    companion object {
        private fun call(tool: Tool, raw: String = "{}") = runBlocking {
            Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(raw)).single() as UIMessagePart.Text).text).jsonObject
        }
    }
    @Test fun `install then list does not execute code or expose private configuration`() {
        val f = Fixture(); assertTrue(f.install().getValue("ok").jsonPrimitive.boolean)
        val games = call(f.tools[1]).getValue("games").jsonArray
        assertEquals(1, games.size)
        assertFalse(games.single().jsonObject.containsKey("html"))
        assertFalse(games.single().jsonObject.containsKey("authorId"))
        assertTrue(f.repo.readSnapshot().sessions.isEmpty())
        assertTrue(f.tools[0].hostApproval!!.rememberable)
        assertFalse(f.tools[0].requiresFreshApproval(JsonObject(emptyMap())))
    }
    @Test fun `single game source read is opt in`() {
        val f = Fixture(); f.install(); val game = f.repo.readSnapshot().games.single()
        assertFalse(call(f.tools[1], """{"include_html":true}""").getValue("ok").jsonPrimitive.boolean)
        val detailed = call(f.tools[1], """{"include_html":true,"game_id":"${game.id}"}""")
        assertEquals(game.html, detailed.getValue("games").jsonArray.single().jsonObject.getValue("html").jsonPrimitive.content)
    }
    @Test fun `result flows from host session into same records tool with game filtering`() {
        val f = Fixture(); f.install(); val game = f.repo.readSnapshot().games.single()
        val session = f.repo.start(game.id); f.repo.finish(session.id, "win", 5)
        val recordsTool = createOrbisGamesRecordsTool({ OrbisGameState() }, f.repo::readSnapshot)
        val result = call(recordsTool, """{"game_id":"${game.id}"}""")
        val record = result.getValue("records").jsonArray.single().jsonObject
        assertEquals("win", record.getValue("result").jsonPrimitive.content)
        assertEquals(5, record.getValue("move_count").jsonPrimitive.int)
        assertTrue(record.getValue("duration_ms").jsonPrimitive.long >= 0)
        assertEquals("game_reported_host_timed", record.getValue("verification").jsonPrimitive.content)
        assertEquals(0, call(recordsTool, """{"game_id":"gomoku"}""").getValue("total").jsonPrimitive.int)
    }
    @Test fun `invalid requests do not call storage and do not echo credentials or paths`() {
        val tools = createOrbisGameLibraryTools({ error("private path secret token") }, { error("private path secret token") })
        listOf("[]", "{\"title\":true}", "{\"title\":\"x\",\"html\":4}", "{\"secret\":\"token\"}").forEach { raw ->
            val result = call(tools[0], raw)
            assertFalse(result.getValue("ok").jsonPrimitive.boolean)
            assertFalse(result.toString().contains("secret"))
        }
        assertEquals("game_storage_unavailable", call(tools[1]).getValue("error").jsonPrimitive.content)
    }
    @Test fun `new game must omit id and revision and errors explain the correction`() {
        val f = Fixture()
        val missing = call(f.tools[0], """{"title":"New","html":"<html>play</html>","game_id":"made-up-id","expected_sha256":"${"0".repeat(64)}"}""")
        assertEquals("game_not_found", missing.getValue("error").jsonPrimitive.content)
        assertTrue(missing.getValue("note").jsonPrimitive.content.contains("新建请省略game_id和expected_sha256"))
        val onlyId = call(f.tools[0], """{"title":"New","html":"<html>play</html>","game_id":"made-up-id"}""")
        assertEquals("update_requires_current_revision", onlyId.getValue("error").jsonPrimitive.content)
        assertTrue(onlyId.getValue("note").jsonPrimitive.content.contains("若是新建"))
        val onlyHash = call(f.tools[0], """{"title":"New","html":"<html>play</html>","expected_sha256":"${"0".repeat(64)}"}""")
        assertEquals("new_game_has_no_revision", onlyHash.getValue("error").jsonPrimitive.content)
        assertTrue(f.repo.readSnapshot().games.isEmpty())
        assertTrue(f.install().getValue("ok").jsonPrimitive.boolean)
    }
    @Test fun `update uses exact current revision without changing original on rejected input`() {
        val f = Fixture(); f.install()
        val original = f.repo.readSnapshot().games.single()
        val session = f.repo.start(original.id); f.repo.finish(session.id, "draw", 9)
        fun update(hash: String?) = call(f.tools[0], buildJsonObject {
            put("title", "Updated"); put("html", "<html>new source exactly</html>"); put("game_id", original.id)
            if (hash != null) put("expected_sha256", hash)
        }.toString())
        assertEquals("update_requires_current_revision", update(null).getValue("error").jsonPrimitive.content)
        assertEquals("game_revision_changed_query_library_first", update("0".repeat(64)).getValue("error").jsonPrimitive.content)
        assertEquals(original, f.repo.readSnapshot().games.single())
        assertTrue(update(original.sha256).getValue("ok").jsonPrimitive.boolean)
        val after = f.repo.readSnapshot()
        assertEquals(original.id, after.games.single().id)
        assertEquals("<html>new source exactly</html>", after.games.single().html)
        assertEquals(original.sha256, after.sessions.single().gameSha256)
    }
    @Test fun `template is returned only on demand without installation or prompt injection`() {
        val f = Fixture()
        assertFalse(call(f.tools[1]).containsKey("starter_template"))
        val template = call(f.tools[1], """{"include_template":true}""").getValue("starter_template").jsonObject
        assertEquals(OrbisMiniGameTemplates.ID, template.getValue("id").jsonPrimitive.content)
        assertEquals(OrbisMiniGameTemplates.html, template.getValue("html").jsonPrimitive.content)
        assertFalse(template.getValue("installed").jsonPrimitive.boolean)
        assertTrue(f.repo.readSnapshot().games.isEmpty())
        assertTrue(f.repo.readSnapshot().sessions.isEmpty())
        assertEquals("", f.tools[1].systemPrompt(me.rerere.ai.provider.Model(), emptyList()))
    }
    @Test fun `library flags require real booleans and unknown fields are rejected before storage`() {
        var reads = 0
        val tools = createOrbisGameLibraryTools({ reads++; OrbisMiniGameState() }, { error("must not write") })
        listOf("""{"include_template":"true"}""", """{"include_html":"false"}""", """{"include_template":null}""", """{"unknown":true}""").forEach {
            assertEquals("invalid_game_parameters", call(tools[1], it).getValue("error").jsonPrimitive.content)
        }
        assertEquals(0, reads)
    }
}
