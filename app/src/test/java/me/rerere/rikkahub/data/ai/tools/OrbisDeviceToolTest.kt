package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.OrbisDeviceSnapshot
import me.rerere.rikkahub.data.orbis.OrbisObservationState
import me.rerere.rikkahub.data.orbis.OrbisObservationSummary
import org.junit.Assert.*
import org.junit.Test

class OrbisDeviceToolTest {
    private val device = OrbisDeviceSnapshot("test", "phone", "10", 29, 50, false, "not_granted")
    private fun run(tool: Tool, raw: String = "{}") = runBlocking {
        val output = tool.execute(Json.parseToJsonElement(raw))
        assertEquals(1, output.size)
        Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
    }
    @Test fun `default returns truthful capabilities and zero observer model calls`() {
        val result = run(createOrbisDeviceTool({ device }, { OrbisObservationState() }))
        assertTrue(result.getValue("read_only").jsonPrimitive.boolean)
        val capabilities = result.getValue("capabilities").jsonObject
        for (name in listOf("location", "accessibility_control", "screen_capture", "notification_reading"))
            assertEquals("not_implemented", capabilities.getValue(name).jsonPrimitive.content)
        val observation = result.getValue("observation").jsonObject
        assertEquals(0, observation.getValue("observer_model_requests").jsonPrimitive.int)
        assertFalse(observation.getValue("running").jsonPrimitive.boolean)
        assertEquals(JsonNull, observation["summary"])
    }
    @Test fun `section selection reads only requested source`() {
        var devices = 0; var summaries = 0
        val tool = createOrbisDeviceTool({ devices++; device }, { summaries++; OrbisObservationState() })
        assertFalse(run(tool, """{"section":"device"}""").containsKey("observation"))
        assertEquals(1, devices); assertEquals(0, summaries)
        assertFalse(run(tool, """{"section":"observation"}""").containsKey("device"))
        assertEquals(1, devices); assertEquals(1, summaries)
    }
    @Test fun `unknown actions parameters and wrong types cannot read or grant`() {
        val tool = createOrbisDeviceTool({ error("must not read") }, { error("must not read") })
        for (raw in listOf("[]", "null", "true", """{"section":null}""", """{"section":1}""",
            """{"section":"start"}""", """{"grant":true}""", """{"path":"private-sentinel"}""",
            """{"section":"all","limit":1}""")) {
            val result = run(tool, raw)
            assertEquals("orbis_device_invalid_parameters", result.getValue("error").jsonPrimitive.content)
            assertFalse(result.toString().contains("private"))
        }
    }
    @Test fun `only latest summary returned not history or device snapshots`() {
        val history = (5L downTo 1).map { OrbisObservationSummary(it, 1, "stopped", 10, 1, 0) }
        val state = OrbisObservationState(history = history, latestSample = device)
        val result = run(createOrbisDeviceTool({ device }, { state }), """{"section":"observation"}""")
        val observation = result.getValue("observation").jsonObject
        assertEquals(5L, observation.getValue("summary").jsonObject.getValue("run_number").jsonPrimitive.long)
        assertFalse(result.toString().contains("battery")); assertFalse(result.toString().contains("history"))
    }
    @Test fun `storage failure and unknown OS values are not fabricated`() {
        val unknown = device.copy(batteryPercent = null, charging = null, usageAccess = "unavailable")
        val result = run(createOrbisDeviceTool({ unknown }, { OrbisObservationState(storageBlocked = true, error = "private-sentinel") }))
        assertEquals(JsonNull, result.getValue("device").jsonObject["battery_percent"])
        assertFalse(result.getValue("observation").jsonObject.getValue("storage_available").jsonPrimitive.boolean)
        assertFalse(result.toString().contains("sentinel"))
    }
    @Test fun `errors never expose underlying paths or credentials`() {
        val tool = createOrbisDeviceTool({ error("private-path credential sentinel") }, { OrbisObservationState() })
        assertEquals("orbis_device_unavailable", run(tool).getValue("error").jsonPrimitive.content)
        val invalid = createOrbisDeviceTool({ device }, { OrbisObservationState(history =
            listOf(OrbisObservationSummary(1, 1, "private-sentinel"))) })
        assertFalse(run(invalid).toString().contains("sentinel"))
    }
    @Test fun `cancellation propagates instead of becoming a success or error tool result`() {
        val tool = createOrbisDeviceTool({ throw CancellationException("cancelled") }, { OrbisObservationState() })
        assertThrows(CancellationException::class.java) { run(tool) }
    }
}
