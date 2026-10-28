package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.st.StRuntimeState
import org.junit.Assert.*
import org.junit.Test

class OrbisStRuntimeToolTest {
    @Test fun missingTargetWrongOwnerRevocationAndExtraSecretAreRejected() = runBlocking {
        var enabled = true; var starts = 0
        val tool = createOrbisStRuntimeTool({ enabled }, { starts++; StRuntimeState("starting") }, { StRuntimeState() }, {})
        val input = buildJsonObject { put("action", "start"); put("target", "this_phone_orbis"); put("source", "/workspace/ST"); put("python", "/root/st/venv/bin/python"); put("config", "/root/st/config.env") }
        tool.execute(JsonObject(input - "target")); assertEquals(0, starts)
        tool.execute(JsonObject(input + ("api_key" to JsonPrimitive("synthetic-secret")))); assertEquals(0, starts)
        enabled = false; tool.execute(input); assertEquals(0, starts)
        enabled = true; assertTrue(tool.needsApproval(input)); val receipt = tool.execute(input).toString()
        assertEquals(1, starts); assertTrue(receipt.contains("starting")); assertFalse(receipt.contains("synthetic-secret"))
        assertFalse(tool.needsApproval(buildJsonObject { put("action", "stop") }))
    }
}
