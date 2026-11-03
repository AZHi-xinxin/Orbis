package me.rerere.rikkahub.data.orbis.health

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HealthMcpResultTest {
    private val day = LocalDate.parse("2026-11-01")
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun result(json: String, isError: Boolean = false) = CallToolResult(
        content = listOf(TextContent("# Synthetic summary, not JSON")),
        structuredContent = Json.parseToJsonElement(json).jsonObject, isError = isError,
    )
    private fun parse(result: CallToolResult) = parseHealthMcpResult(result, zone, day.minusDays(6), day)

    @Test fun `daily structured result imports steps at source date not aggregate heart as measurement`() {
        val batch = parse(result("""{"data":{"2026-11-01":{"steps":1539,"samples":1365},"2026-10-31":{"steps":253,"hr_avg":72.2,"hr_min":72,"hr_max":73,"hr_samples":5}}}"""))
        assertEquals(1539, batch.days.getValue("2026-11-01").getValue("steps").int)
        assertEquals(setOf("steps"), batch.days.getValue("2026-10-31").keys)
        assertTrue(batch.dailySummary)
        assertTrue(batch.provenance.contains("来源标注日期"))
        assertTrue(batch.notice.contains("日均心率"))
    }

    @Test fun `missing and null totals are absent but explicit zero stays zero`() {
        val batch = parse(result("""{"data":{"2026-11-01":{"steps":0},"2026-10-31":{"hr_avg":70},"2026-10-30":{"steps":null},"2026-11-02":{"steps":200}}}"""))
        assertEquals(mapOf("2026-11-01" to mapOf("steps" to JsonPrimitive(0))), batch.days)
    }

    @Test fun `source day is not shifted when phone has different timezone`() {
        val r = result("""{"data":{"2026-11-01":{"steps":7}}}""")
        assertEquals(parse(r).days, parseHealthMcpResult(r, ZoneId.of("America/Los_Angeles"), day, day).days)
    }

    @Test fun `repeat snapshots replace device field while human corrections remain protected`() {
        var storage: String? = null
        val repo = HealthRepository(object : HealthStorage {
            override fun read() = storage
            override fun write(v: String) { storage = v }
        })
        val batch = parse(result("""{"data":{"2026-11-01":{"steps":100}}}"""))
        fun sync() {
            val old = repo.day(day.toString())
            val patch = healthSyncPatch(old, batch.days.getValue(day.toString()))
            if (patch.isNotEmpty()) repo.write(day.toString(), old.revision, patch, "mcp", "device:hcmcp", batch.provenance)
        }
        sync(); sync()
        assertEquals(1, repo.day(day.toString()).revision)
        repo.write(day.toString(), 1, mapOf("steps" to JsonPrimitive(90)), "manual", "human", "manual correction")
        sync()
        assertEquals(90, repo.day(day.toString()).fields.getValue("steps").value.jsonPrimitive.int)
    }

    @Test fun `legacy dated observations work as structured and text results`() {
        val json = """{"data":[{"steps":[{"start_time":"2026-10-31T16:00:00Z","end_time":"2026-11-01T16:00:00Z","count":15}]}]}"""
        assertEquals(parse(result(json)), parse(CallToolResult(content = listOf(TextContent(json)))))
        assertEquals(15, parse(result(json)).days.getValue(day.toString()).getValue("steps").int)
        assertFalse(parse(result(json)).dailySummary)
    }

    @Test fun `error envelope never imports even plausible data`() {
        assertEquals("health_sync_tool_error", runCatching { parse(result("""{"data":{}}""", true)) }.exceptionOrNull()?.message)
    }

    @Test fun `malformed structured data cannot fall back to a contradictory text receipt`() {
        val invalid = result("""{"data":"broken"}""").copy(content = listOf(TextContent("""{"data":[]}""")))
        assertThrows(IllegalStateException::class.java) { parse(invalid) }
        assertThrows(IllegalStateException::class.java) { parse(CallToolResult(listOf(TextContent("# steps: 123")))) }
    }

    @Test fun `bad dates fractions strings and unbounded totals do not write partial days`() {
        for (body in listOf("-1", "200001", "1.5", "\"40\"", "{}", "true")) {
            assertEquals("health_cloud_format", runCatching { parse(result("""{"data":{"2026-11-01":{"steps":$body}}}""")) }.exceptionOrNull()?.message)
        }
        assertThrows(IllegalArgumentException::class.java) { parse(result("""{"data":{"2026-99-01":{"steps":7}}}""")) }
    }
}
