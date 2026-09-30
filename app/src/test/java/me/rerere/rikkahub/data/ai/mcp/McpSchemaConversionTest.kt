package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.validateToolSchemaReferences
import org.junit.Assert.*
import org.junit.Test

class McpSchemaConversionTest {
    @Test fun `SDK review schema retains defs through Orbis conversion and persistence`() {
        verifyFixture("ReviewDecisionIn", "review_drift_bottles")
    }

    @Test fun `SDK inbox schema retains defs independently of tool name or server`() {
        verifyFixture("InboxInput", "mail_inbox")
    }

    @Test fun `missing properties remains empty object`() {
        val converted = ToolSchema().toSchema() as InputSchema.Obj
        assertTrue(converted.properties.isEmpty())
        assertNull(converted.defs)
    }

    @Test fun `normal reconnect replaces old broken cache while preserving user switches`() {
        val raw = """{"type":"object","properties":{"input":{"DOLLARref":"#/DOLLARdefs/InboxInput"}},"DOLLARdefs":{"InboxInput":{"type":"object"}}}""".replace("DOLLAR", "\$")
        val serverSchema = Json.decodeFromString<ToolSchema>(raw)
        val old = McpTool(name = "mail_inbox", description = "old", enable = false, needsApproval = true,
            inputSchema = InputSchema.Obj(serverSchema.properties!!))
        val fresh = mergeTools(listOf(old), listOf(Tool(name = "mail_inbox", description = "new", inputSchema = serverSchema))).single()
        assertFalse(fresh.enable)
        assertTrue(fresh.needsApproval)
        assertEquals("new", fresh.description)
        assertEquals(serverSchema.defs, (fresh.inputSchema as InputSchema.Obj).defs)
        Json.parseToJsonElement(Json.encodeToString(fresh.inputSchema)).validateToolSchemaReferences(fresh.name)
    }

    private fun verifyFixture(definition: String, name: String) {
        // Synthetic tools/list input with the same structure as the two reported failures.
        val raw = """{"type":"object","DOLLARschema":"https://json-schema.org/draft/2020-12/schema","properties":{"input":{"DOLLARref":"#/DOLLARdefs/$definition"}},"required":["input"],"DOLLARdefs":{"$definition":{"type":"object","properties":{"decision":{"enum":["approve","reject"]}},"required":["decision"],"additionalProperties":false}}}""".replace("DOLLAR", "\$")
        val sdk = Json.decodeFromString<ToolSchema>(raw)
        assertNotNull(sdk.defs)
        val schema = sdk.toSchema() as InputSchema.Obj
        assertEquals(sdk.defs, schema.defs)
        assertEquals(sdk.schema, schema.schema)
        val tool = McpTool(name = name, inputSchema = schema, enable = false, needsApproval = true)
        val saved = Json.decodeFromString<McpTool>(Json.encodeToString(tool))
        assertEquals(tool, saved)
        val outgoing = Json.parseToJsonElement(Json.encodeToString(saved.inputSchema))
        assertEquals(Json.parseToJsonElement(raw).jsonObject["\$defs"], outgoing.jsonObject["\$defs"])
        outgoing.validateToolSchemaReferences("mcp__fixture__$name")
    }
}
