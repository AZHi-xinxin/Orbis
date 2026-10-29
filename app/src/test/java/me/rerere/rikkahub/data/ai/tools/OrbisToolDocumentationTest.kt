package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisToolDocumentationTest {
    private val build = OrbisHelpBuild("org.orbis.agent.dev", "test", "280", "debug")

    @Test fun fullInstructionsRemainReadableOnlyForRegisteredBuiltInsWithoutRunningThem() = runBlocking {
        val names = orbisToolDocumentation.keys.toList()
        val help = createOrbisHelpTool(names, build)
        for ((name, doc) in orbisToolDocumentation) {
            val result = help.execute(buildJsonObject { put("topic", "tool"); put("tool_name", name) })
                .filterIsInstance<UIMessagePart.Text>().single().text.let(Json::parseToJsonElement).jsonObject
            assertEquals(doc.details, result["description"]!!.jsonPrimitive.content)
            assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        }
        val hidden = createOrbisHelpTool(emptyList(), build)
        for (name in listOf("orbis_body", "mcp__private__secret", "../settings")) {
            val output = hidden.execute(buildJsonObject { put("topic", "tool"); put("tool_name", name) }).toString()
            assertTrue(output.contains("orbis_help_unknown_tool"))
            assertFalse(output.contains("triggerMode"))
        }
        assertTrue(help.execute(buildJsonObject { put("topic", "tool"); put("tool_name", "orbis_body"); put("offset", 0) })
            .toString().contains("orbis_help_unknown_parameter"))
    }

    @Test fun compactDescriptionPreservesSchemaApprovalExecutionAndRunSpecificReadOnlyQualifier() = runBlocking {
        var calls = 0
        val raw = Tool(name = "orbis_memory", description = orbisToolFullDescription("orbis_memory") + "\n本次参考会话仅允许 read/history，不允许改写。",
            parameters = { InputSchema.Obj(buildJsonObject { put("body", buildJsonObject { put("type", "string") }) }) },
            hostApproval = HostToolApproval("fixture", "revision", "label"), needsApproval = { true }, isSelected = { false },
            execute = { calls++; listOf(UIMessagePart.Text("executed")) })
        val compact = compactOrbisToolDescription(raw)
        assertTrue(compact.description.endsWith("本次参考会话仅允许 read/history，不允许改写。"))
        assertTrue(compact.description.contains("tool_name=orbis_memory"))
        assertSame(raw.parameters, compact.parameters); assertSame(raw.execute, compact.execute)
        assertSame(raw.needsApproval, compact.needsApproval); assertSame(raw.isApprovalCurrent, compact.isApprovalCurrent)
        assertSame(raw.isSelected, compact.isSelected); assertEquals(raw.hostApproval, compact.hostApproval)
        assertEquals(0, calls); assertFalse(compact.isSelected())
        assertEquals(raw.parameters(), compact.parameters())
        val external = raw.copy(name = "mcp__server__tool")
        assertSame(external, compactOrbisToolDescription(external))
        val different = raw.copy(description = "runtime-specific replacement")
        assertSame(different, compactOrbisToolDescription(different))
    }

    @Test fun shorteningAfterBindingKeepsRememberedGrantAndRevocationEffective() {
        var disk: String? = null
        val store = me.rerere.rikkahub.data.ai.approval.ToolApprovalStore({ disk }, { disk = it })
        val original = Tool("orbis_study", orbisToolFullDescription("orbis_study"), needsApproval = { true }, execute = { emptyList() })
        val old = me.rerere.rikkahub.data.ai.approval.bindToolApproval(original, "owner", store)
        store.allow("owner", old.hostApproval!!)
        val updated = compactOrbisToolDescription(me.rerere.rikkahub.data.ai.approval.bindToolApproval(original, "owner", store))
        assertEquals(old.hostApproval, updated.hostApproval)
        assertFalse(updated.needsApproval(JsonObject(emptyMap())))
        val other = compactOrbisToolDescription(me.rerere.rikkahub.data.ai.approval.bindToolApproval(original, "other", store))
        assertTrue(other.needsApproval(JsonObject(emptyMap())))
        store.revoke("owner")
        assertTrue(updated.needsApproval(JsonObject(emptyMap())))
    }

    @Test fun coreInstructionsKeepMeaningAndMateriallyReduceAdvertisedText() {
        var before = 0; var after = 0
        orbisToolDocumentation.forEach { (name, doc) ->
            val compact = compactOrbisToolDescription(Tool(name, doc.details, execute = { emptyList() }))
            before += doc.details.length; after += compact.description.length
            assertTrue(name, compact.description.length < doc.details.length)
        }
        assertTrue("$before -> $after", after < before * 0.5)
        assertTrue(orbisToolDocumentation.getValue("orbis_body").summary.contains("auto禁用event/undo"))
        assertTrue(orbisToolDocumentation.getValue("orbis_backup").summary.contains("人类明确要求恢复"))
        assertTrue(orbisToolDocumentation.getValue("orbis_configure").summary.contains("全应用共享"))
        assertTrue(orbisToolFullDescription("orbis_theme").contains("schemaVersion=1"))
        assertTrue(orbisToolFullDescription("orbis_body").contains("halfLifeMinutes"))
        println("BUILTIN_DESCRIPTION_CHARACTERS before=$before after=$after")
    }
}
