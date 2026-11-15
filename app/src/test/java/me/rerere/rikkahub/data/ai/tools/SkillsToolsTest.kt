package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.orbis.market.ms
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillsToolsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `use_skill reads metadata directory when display name differs`() = runBlocking {
        val skillDir = tempFolder.newFolder("directory-name")
        skillDir.resolve("SKILL.md").writeText(
            """
                ---
                name: Display Name
                description: Test skill
                ---
                Skill instructions
            """.trimIndent()
        )
        val tool = createSkillTools(
            enabledSkills = setOf("Display Name"),
            allSkills = listOf(
                SkillMetadata(
                    name = "Display Name",
                    description = "Test skill",
                    skillDir = skillDir,
                )
            ),
        ).single()

        val result = tool.execute(
            buildJsonObject {
                put("name", "Display Name")
            }
        )

        assertEquals("Skill instructions", (result.single() as UIMessagePart.Text).text)
    }

    @Test fun `unavailable names missing files and invalid references return actionable receipts`() = runBlocking {
        val directory=tempFolder.newFolder("enabled")
        val source=directory.resolve("SKILL.md").apply { writeText("---\nname: enabled\ndescription: synthetic\n---\nOriginal body.") }
        val original=source.readBytes()
        val tool=createSkillTools(setOf("enabled"),listOf(SkillMetadata("enabled","synthetic",skillDir=directory))).single()
        suspend fun failed(name:String,path:String?=null)=Json.parseToJsonElement((tool.execute(buildJsonObject {
            put("name",name);path?.let {put("path",it)}
        }).single() as UIMessagePart.Text).text).jsonObject
        assertEquals("skill_not_available",failed("adb-wireless").ms("reason_code"))
        assertEquals("skill_not_available",failed("leto").ms("reason_code"))
        assertEquals("skill_file_missing",failed("enabled","references/missing.md").ms("reason_code"))
        assertEquals("skill_path_denied",failed("enabled","../private.txt").ms("reason_code"))
        assertTrue(original.contentEquals(source.readBytes()))
        assertEquals("Original body.",(tool.execute(buildJsonObject {put("name","enabled")}).single() as UIMessagePart.Text).text)
        source.delete()
        val missing=failed("enabled")
        assertEquals("skill_file_missing",missing.ms("reason_code"))
        assertEquals("use_skill",missing.ms("tool_name"))
        assertFalse(missing.ms("diagnostic_id").isBlank())
    }
}
