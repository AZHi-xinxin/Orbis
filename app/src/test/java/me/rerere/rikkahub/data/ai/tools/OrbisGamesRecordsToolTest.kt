package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.OrbisGameMatch
import me.rerere.rikkahub.data.orbis.OrbisGameRecord
import me.rerere.rikkahub.data.orbis.OrbisGameState
import me.rerere.rikkahub.data.orbis.OrbisMiniGameSession
import me.rerere.rikkahub.data.orbis.OrbisMiniGameState
import me.rerere.rikkahub.data.orbis.GomokuRules
import org.junit.Assert.*
import org.junit.Test

class OrbisGamesRecordsToolTest {
    @Test fun `filtered records do not depend on an unrelated damaged library`() {
        val native = createOrbisGamesRecordsTool({ OrbisGameState() }, { error("unrelated storage") })
        assertTrue(run(native, buildJsonObject { put("game_id", "gomoku") }).getValue("ok").jsonPrimitive.boolean)
        val html = createOrbisGamesRecordsTool({ error("unrelated storage") }, { me.rerere.rikkahub.data.orbis.OrbisMiniGameState() })
        assertTrue(run(html, buildJsonObject { put("game_id", "my-game") }).getValue("ok").jsonPrimitive.boolean)
    }
    private fun run(tool: Tool, args: JsonElement = buildJsonObject { }): JsonObject = runBlocking {
        val result = tool.execute(args)
        assertEquals(1, result.size)
        Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    }
    private fun records(count: Int) = OrbisGameState(records = List(count) { i ->
        OrbisGameRecord(OrbisGameMatch("private-id-$i", i.toLong()), i + 1L, "abandoned")
    })

    @Test fun `default empty query is read only and does not invent an opponent or ruleset`() {
        var calls = 0
        val tool = createOrbisGamesRecordsTool { calls++; OrbisGameState() }
        val result = run(tool)
        assertEquals(1, calls)
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("total").jsonPrimitive.int)
        assertTrue(result.getValue("records").jsonArray.isEmpty())
        assertTrue(result.getValue("read_only").jsonPrimitive.boolean)
        assertFalse(result.getValue("network_requested").jsonPrimitive.boolean)
        assertTrue(result.getValue("opponent_note").jsonPrimitive.content.contains("不是实时模型"))
        assertFalse(result.getValue("opponent_note").jsonPrimitive.content.contains("阿止"))
        assertEquals("no_records", result.getValue("opponent").jsonPrimitive.content)
        assertEquals("no_records", result.getValue("ruleset").jsonPrimitive.content)
    }

    @Test fun `default and explicit limits bound newest records without changing totals`() {
        val state = records(105)
        val tool = createOrbisGamesRecordsTool { state }
        val default = run(tool)
        assertEquals(20, default.getValue("returned").jsonPrimitive.int)
        assertEquals(105, default.getValue("total").jsonPrimitive.int)
        for (limit in listOf(1, 100)) {
            val result = run(tool, buildJsonObject { put("limit", limit) })
            assertEquals(limit, result.getValue("records").jsonArray.size)
            assertEquals(105, result.getValue("statistics").jsonObject.getValue("abandoned").jsonPrimitive.int)
            assertEquals(104, result.getValue("records").jsonArray.first().jsonObject.getValue("started_at_epoch_ms").jsonPrimitive.int)
        }
        assertEquals(105, state.records.size)
    }

    @Test fun `unknown keys and invalid values never read storage or echo input`() {
        val tool = createOrbisGamesRecordsTool { error("read must not happen") }
        for (raw in listOf("null", "[]", "true", "{\"limit\":0}", "{\"limit\":101}",
                "{\"limit\":1.5}", "{\"limit\":true}", "{\"limit\":null}", "{\"limit\":\"2\"}",
                "{\"limit\":999999999999999999}", "{\"private-path-sentinel\":\"secret-token\"}")) {
            val result = run(tool, Json.parseToJsonElement(raw))
            assertFalse(result.getValue("ok").jsonPrimitive.boolean)
            assertTrue(result.getValue("error").jsonPrimitive.content.startsWith("orbis_games_records_"))
            assertFalse(result.toString().contains("private-path"))
            assertFalse(result.toString().contains("secret-token"))
        }
    }

    @Test fun `private identifiers moves paths and source are never exported`() {
        val state = records(2)
        val result = run(createOrbisGamesRecordsTool { state })
        assertFalse(result.toString().contains("private-id"))
        val record = result.getValue("records").jsonArray.first().jsonObject
        assertEquals(setOf("game_id", "game_title", "ruleset", "started_at_epoch_ms", "finished_at_epoch_ms", "duration_ms", "result", "move_count", "verification", "opponent", "opponent_label"), record.keys)
        assertFalse(record.containsKey("moves"))
        assertFalse(record.containsKey("source_code"))
    }

    @Test fun `read failures return fixed error not raw path or success`() {
        val result = run(createOrbisGamesRecordsTool { error("C:/private-path-sentinel key=secret-token") })
        assertEquals(setOf("ok", "error"), result.keys)
        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("orbis_games_records_unavailable", result.getValue("error").jsonPrimitive.content)
        assertFalse(result.toString().contains("secret"))
    }

    @Test fun `cancellation is not converted into a tool result`() {
        val tool = createOrbisGamesRecordsTool { throw CancellationException("cancel") }
        assertThrows(CancellationException::class.java) { run(tool) }
    }

    @Test fun `unfinished game is not counted as a loss or exit`() {
        val state = OrbisGameState(active = OrbisGameMatch("hidden-id", 100))
        val result = run(createOrbisGamesRecordsTool { state })
        assertTrue(result.getValue("unfinished_match_present").jsonPrimitive.boolean)
        assertEquals(0, result.getValue("total").jsonPrimitive.int)
        assertTrue(result.getValue("statistics").jsonObject.values.all { it.jsonPrimitive.int == 0 })
    }

    @Test fun `unsupported metadata does not masquerade as verified results`() {
        val state = records(1).let { it.copy(records = listOf(it.records.single().copy(opponent = "remote-ai"))) }
        val result = run(createOrbisGamesRecordsTool { state })
        assertFalse(result.getValue("ok").jsonPrimitive.boolean)
    }
    @Test fun `html and mixed records label rules and opponents per record`() {
        val html = OrbisMiniGameSession("session", "tic-tac-toe", "井字棋", "a".repeat(64), 10, 20, "win", 5)
        val tool = createOrbisGamesRecordsTool({ records(1) }, { OrbisMiniGameState(sessions = listOf(html)) })
        val mixed = run(tool)
        assertEquals("see_each_record", mixed.getValue("ruleset").jsonPrimitive.content)
        assertEquals("see_each_record", mixed.getValue("opponent").jsonPrimitive.content)
        val entries = mixed.getValue("records").jsonArray.map { it.jsonObject }.associateBy { it.getValue("game_id").jsonPrimitive.content }
        assertEquals(GomokuRules.RULESET, entries.getValue("gomoku").getValue("ruleset").jsonPrimitive.content)
        assertEquals("game_defined", entries.getValue("tic-tac-toe").getValue("ruleset").jsonPrimitive.content)
        val filtered = run(tool, buildJsonObject { put("game_id", "tic-tac-toe") })
        assertEquals("game_defined", filtered.getValue("ruleset").jsonPrimitive.content)
        assertEquals("game_defined", filtered.getValue("opponent").jsonPrimitive.content)
        assertFalse(filtered.toString().contains(GomokuRules.RULESET))
    }
    @Test fun `empty filtered html query does not imply a local rule opponent`() {
        val result = run(createOrbisGamesRecordsTool { OrbisGameState() }, buildJsonObject { put("game_id", "tic-tac-toe") })
        assertEquals("game_defined", result.getValue("ruleset").jsonPrimitive.content)
        assertEquals("no_records", result.getValue("opponent").jsonPrimitive.content)
    }
    @Test fun `unfinished counts distinguish native resumable state and unsettled html without live presence claim`() {
        val native = OrbisGameState(active = OrbisGameMatch("private", 100))
        val sessions = listOf(
            OrbisMiniGameSession("old-page", "tic-tac-toe", "井字棋", "a".repeat(64), 10),
            OrbisMiniGameSession("another-page", "other", "其他游戏", "b".repeat(64), 20),
        )
        val tool = createOrbisGamesRecordsTool({ native }, { OrbisMiniGameState(sessions = sessions) })
        val all = run(tool)
        assertTrue(all.getValue("unfinished_match_present").jsonPrimitive.boolean)
        val counts = all.getValue("unfinished_counts").jsonObject
        assertEquals(1, counts.getValue("native_resumable_matches").jsonPrimitive.int)
        assertEquals(2, counts.getValue("html_unsettled_sessions").jsonPrimitive.int)
        assertEquals(3, counts.getValue("total").jsonPrimitive.int)
        assertTrue(all.getValue("unfinished_match_meaning").jsonPrimitive.content.contains("不是用户正在玩"))
        assertTrue(all.getValue("unfinished_match_meaning").jsonPrimitive.content.contains("不保证可继续"))
        val filtered = run(tool, buildJsonObject { put("game_id", "tic-tac-toe") })
        assertEquals(1, filtered.getValue("unfinished_counts").jsonObject.getValue("total").jsonPrimitive.int)
        assertEquals(0, filtered.getValue("total").jsonPrimitive.int)
        assertTrue(filtered.getValue("statistics").jsonObject.values.all { it.jsonPrimitive.int == 0 })
    }
}
