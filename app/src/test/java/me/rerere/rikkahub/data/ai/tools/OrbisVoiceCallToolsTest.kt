package me.rerere.rikkahub.data.ai.tools

import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRecord
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallRepository
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStatus
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceCallStorage
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceTranscriptEntry
import me.rerere.rikkahub.data.orbis.voice.OrbisVoiceArchiveAuthor
import me.rerere.rikkahub.data.orbis.voice.voiceArchiveSourceDigest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class OrbisVoiceCallToolsTest {
    @Test fun `source read includes durable assistant nodes even when legacy transcript omitted them`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        val raw = record().copy(archiveStatus = OrbisVoiceArchiveStatus.PENDING, summary = null, modelTranscript = null,
            transcript = record().transcript.take(1), sourceNodesJson = JsonInstant.encodeToString(listOf(
                UIMessage.assistant("原始助手回复已保存🙂").copy(orbisVoiceCallId = "own-call", orbisVoiceCallKind = "turn").toMessageNode())))
        repo.create(raw)
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        val result = execute(tool, buildJsonObject { put("call_id", raw.id); put("section", "source") })
        assertTrue(result.getValue("content").jsonPrimitive.content.contains("原始助手回复已保存🙂"))
        assertEquals(voiceArchiveSourceDigest(raw), result.getValue("source_digest").jsonPrimitive.content)
    }

    @Test fun `assistant submit uses approval scoped ownership durable provenance and idempotent receipt`() = runTest {
        val storage = MemoryStorage(); val repo = OrbisVoiceCallRepository(storage)
        val raw = record().copy(archiveStatus = OrbisVoiceArchiveStatus.FAILED, summary = null, modelTranscript = null)
        repo.create(raw)
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_archive_submit" }
        val args = buildJsonObject {
            put("call_id", raw.id); put("source_digest", voiceArchiveSourceDigest(raw)); put("request_id", "stable-request")
            put("summary", "约好周末出发前确认天气。"); put("transcript", "用户约好去公园，助手说出门前确认天气。")
        }
        assertTrue(tool.needsApproval(args))
        assertNotNull(tool.hostApproval)
        val result = execute(tool, args)
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertFalse(result.getValue("network_request_sent").jsonPrimitive.boolean)
        assertEquals("stable-request", result.getValue("receipt_id").jsonPrimitive.content)
        val writes = storage.writes
        assertEquals(result, execute(tool, args))
        assertEquals(writes, storage.writes)
        assertEquals(OrbisVoiceArchiveAuthor.ASSISTANT_TOOL, repo.get(raw.id)!!.archiveAuthor)
        assertEquals(raw.transcript, repo.get(raw.id)!!.transcript)
        failure { execute(tool, JsonObject(args + ("assistant_id" to kotlinx.serialization.json.JsonPrimitive("assistant-b")))) }
        assertEquals(writes, storage.writes)
    }

    @Test fun `assistant submit refuses foreign records and stale source without overwriting anything`() = runTest {
        val storage = MemoryStorage(); val repo = OrbisVoiceCallRepository(storage)
        val raw = record(assistant = "assistant-b").copy(archiveStatus = OrbisVoiceArchiveStatus.PENDING, summary = null, modelTranscript = null)
        repo.create(raw)
        val tools = createOrbisVoiceCallTools(repo, "assistant-a")
        val tool = tools.single { it.name == "orbis_call_archive_submit" }
        val args = buildJsonObject {
            put("call_id", raw.id); put("source_digest", voiceArchiveSourceDigest(raw)); put("request_id", "receipt")
            put("summary", "不能跨助手补写。"); put("transcript", "不能跨助手读取或归档。")
        }
        failure { execute(tool, args) }
        assertEquals(1, storage.writes)
        assertEquals(raw, repo.get(raw.id))
    }

    @Test fun `source pagination keeps astral symbols intact even at minimum page length`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        val source = record().copy(summary = "🙂🙂结束")
        repo.create(source)
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        var offset = 0
        val text = StringBuilder()
        while (offset < source.summary!!.length) {
            val page = execute(tool, buildJsonObject { put("call_id", source.id); put("limit", 1); put("offset", offset) })
            text.append(page.getValue("content").jsonPrimitive.content)
            val next = page.getValue("next_offset").jsonPrimitive.int
            assertTrue(next > offset); offset = next
        }
        assertEquals(source.summary, text.toString())
        failure { execute(tool, buildJsonObject { put("call_id", source.id); put("offset", 1) }) }
    }

    private class MemoryStorage : OrbisVoiceCallStorage {
        override val lockKey = UUID.randomUUID().toString()
        private val records = mutableMapOf<String, String>()
        var writes = 0
        override fun ids() = records.keys.toList()
        override fun read(id: String) = records[id]
        override fun write(id: String, value: String) { records[id] = value; writes++ }
    }

    private fun record(id: String = "own-call", assistant: String = "assistant-a", conversation: String = "window-1", start: Long = 1000) =
        OrbisVoiceCallRecord(id, conversation, assistant, start,
            connectedAtMs = start + 100, endedAtMs = start + 10100, durationMs = 10000,
            status = OrbisVoiceCallStatus.ENDED, archiveStatus = OrbisVoiceArchiveStatus.READY,
            summary = "约好了周末一起去公园，出门前确认天气。", modelTranscript = "用户：周末一起去公园吧。\n助手：好，出门前确认天气。",
            transcript = listOf(OrbisVoiceTranscriptEntry("turn-1", "user", "周末一起去公园吧。", start + 1000),
                OrbisVoiceTranscriptEntry("turn-2", "assistant", "好，出门前确认天气。", start + 2000)))

    private suspend fun execute(tool: Tool, args: JsonObject = buildJsonObject {}): JsonObject {
        val output = tool.execute(args)
        assertEquals(1, output.size)
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }

    private suspend fun failure(block: suspend () -> Unit): Exception = try {
        block()
        throw AssertionError("Expected a failure")
    } catch (error: Exception) { error }

    @Test fun `listing searches across current assistant windows and ignores model supplied assistant scope`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(record("own-old"))
        repo.create(record("own-new", conversation = "window-2", start = 5000))
        repo.create(record("other-call", assistant = "assistant-b", start = 9000).copy(summary = "私密公园安排 foreign-secret"))
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_records" }
        val listed = execute(tool, buildJsonObject { put("assistant_id", "assistant-b") })
        assertEquals(listOf("own-new", "own-old"), listed.getValue("records").jsonArray.map { it.jsonObject.getValue("call_id").jsonPrimitive.content })
        assertFalse(listed.toString().contains("foreign-secret"))
        val found = execute(tool, buildJsonObject { put("query", "公园"); put("assistant_id", "assistant-b") })
        assertEquals(2, found.getValue("records").jsonArray.size)
        assertFalse(found.toString().contains("other-call"))
        assertFalse(tool.needsApproval(buildJsonObject {}))
        assertEquals(3, storage.writes)
    }

    @Test fun `known foreign call id and unknown id both refuse read without disclosing content`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(record("other-call", assistant = "assistant-b").copy(summary = "foreign-private-sentinel"))
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        val foreign = failure { execute(tool, buildJsonObject { put("call_id", "other-call"); put("assistant_id", "assistant-b"); put("section", "source") }) }
        val missing = failure { execute(tool, buildJsonObject { put("call_id", "not-present") }) }
        assertEquals(missing.message, foreign.message)
        assertFalse(foreign.message.orEmpty().contains("foreign-private-sentinel"))
        assertEquals(1, storage.writes)
    }

    @Test fun `overview paging respects offset limit and does not return transcripts`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        listOf(record("old", start = 1000), record("middle", start = 2000), record("new", start = 3000)).forEach { repo.create(it) }
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_records" }
        val page = execute(tool, buildJsonObject { put("offset", 1); put("limit", 1) })
        val overview = page.getValue("records").jsonArray.single().jsonObject
        assertEquals("middle", overview.getValue("call_id").jsonPrimitive.content)
        assertEquals(2, page.getValue("next_offset").jsonPrimitive.int)
        assertFalse(overview.containsKey("transcript"))
        assertFalse(overview.containsKey("sourceNodesJson"))
        assertTrue(execute(tool, buildJsonObject { put("offset", 50) }).getValue("records").jsonArray.isEmpty())
    }

    @Test fun `read pages only requested section and reconstructs exact written and source text`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        val source = record()
        repo.create(source)
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        val sections = mapOf("summary" to source.summary!!, "transcript" to source.modelTranscript!!,
            "source" to source.transcript.joinToString("\n\n") { "[${it.role}] ${it.content}" })
        for ((section, expected) in sections) {
            val parts = StringBuilder()
            var offset = 0
            while (offset < expected.length) {
                val page = execute(tool, buildJsonObject { put("call_id", source.id); put("section", section); put("offset", offset); put("limit", 5) })
                assertEquals(section, page.getValue("section").jsonPrimitive.content)
                assertEquals(expected.length, page.getValue("total_characters").jsonPrimitive.int)
                assertTrue(page.getValue("historical_data").jsonPrimitive.boolean)
                parts.append(page.getValue("content").jsonPrimitive.content)
                val next = page.getValue("next_offset").jsonPrimitive.int
                assertTrue(next > offset)
                offset = next
            }
            assertEquals(expected, parts.toString())
        }
        assertEquals(1, storage.writes)
        assertFalse(tool.needsApproval(buildJsonObject { put("call_id", source.id) }))
    }

    @Test fun `read beyond end stays empty and never overflows next offset to negative`() = runTest {
        val repo = OrbisVoiceCallRepository(MemoryStorage())
        val source = record()
        repo.create(source)
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        val page = execute(tool, buildJsonObject { put("call_id", source.id); put("offset", Int.MAX_VALUE); put("limit", 20000) })
        assertEquals("", page.getValue("content").jsonPrimitive.content)
        assertEquals(source.summary!!.length, page.getValue("next_offset").jsonPrimitive.int)
    }

    @Test fun `invalid section and traversal id fail read without touching archive`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(record())
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        failure { execute(tool, buildJsonObject { put("call_id", "own-call"); put("section", "delete") }) }
        failure { execute(tool, buildJsonObject { put("call_id", "../outside") }) }
        assertEquals(1, storage.writes)
        assertNotNull(repo.get("own-call"))
    }

    @Test fun `read exposes distinct end and archive failures without executing or changing records`() = runTest {
        val storage = MemoryStorage()
        val repo = OrbisVoiceCallRepository(storage)
        repo.create(record().copy(status = OrbisVoiceCallStatus.INTERRUPTED,
            archiveStatus = OrbisVoiceArchiveStatus.FAILED, summary = null, modelTranscript = null,
            endReason = "ERROR", endError = "synthetic interrupted audio", archiveError = "synthetic paused queue"))
        val tool = createOrbisVoiceCallTools(repo, "assistant-a").single { it.name == "orbis_call_read" }
        val result = execute(tool, buildJsonObject { put("call_id", "own-call") }).getValue("record").jsonObject
        assertEquals("ERROR", result.getValue("end_reason").jsonPrimitive.content)
        assertEquals("synthetic interrupted audio", result.getValue("end_error").jsonPrimitive.content)
        assertEquals("synthetic paused queue", result.getValue("archive_error").jsonPrimitive.content)
        assertEquals("INTERRUPTED", result.getValue("status").jsonPrimitive.content)
        assertEquals(1, storage.writes)
    }
}
