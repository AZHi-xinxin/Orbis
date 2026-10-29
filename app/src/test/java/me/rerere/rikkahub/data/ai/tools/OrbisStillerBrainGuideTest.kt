package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class OrbisStillerBrainGuideTest {
    private fun help() = createOrbisHelpTool(emptyList(), OrbisHelpBuild("org.orbis.agent", "test", "280", "debug"))

    @Test fun READMEIsCompleteAndReachableThroughPagedToolCalls() = runBlocking {
        val tool = help()
        val text = StringBuilder()
        var offset = 0
        var pages = 0
        do {
            val result = tool.execute(buildJsonObject {
                put("topic", "guide"); put("chapter", "st_readme"); put("offset", offset); put("limit", 3)
            })
            val body = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
            assertTrue(body["ok"]!!.jsonPrimitive.boolean)
            val content = body["content"]!!.jsonObject
            assertTrue(content["registered_tools"]!!.jsonArray.isEmpty())
            content["sections"]!!.jsonArray.forEach { text.append(it.jsonObject["text"]!!.jsonPrimitive.content) }
            offset = content["next_offset"]?.jsonPrimitive?.intOrNull ?: -1
            pages++
        } while (offset >= 0 && pages <= stillerBrainReadmeSections.size)
        assertEquals(-1, offset)
        assertTrue(pages > 1)
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(STILLERBRAIN_README_SHA256, hash)
        assertTrue(text.contains("关于遗忘：做主动整理"))
        assertTrue(text.contains("ST 的路径是主动整理"))
        assertFalse(text.contains("记忆渐隐策略属于后续方向"))
        assertFalse(text.contains("MEMORY-DECAY-DESIGN.md"))
    }

    @Test fun fullReadmeAndPhilosophyOnlyAppearAfterExplicitLookup() = runBlocking {
        val tool = help()
        assertEquals("", tool.systemPrompt(Model(), emptyList()))
        val marker = "一千个人心中有一千个哈姆雷特"
        assertFalse(tool.description.contains(marker))
        assertFalse(tool.parameters().toString().contains(marker))
        assertTrue(tool.description.length < 180)
        assertTrue(tool.description.contains("guide/st_overview"))
        assertTrue(tool.description.contains("guide/st_readme"))
        val overview = tool.execute(buildJsonObject { put("topic", "overview") }).toString()
        assertFalse(overview.contains(marker))
        val index = orbisManualChapterIndex("StillerBrain", 0, 10)
        assertTrue(index.toString().contains("st_overview"))
        assertTrue(index.toString().contains("st_readme"))
        assertFalse(index.toString().contains(marker))
    }

    @Test fun introductionExplainsActualAccessChoicesAndIntegrationBeforeInstallation() {
        val sections = orbisManualReadChapter("st_overview", emptySet(), 0, 3)["sections"]!!.jsonArray.toString()
        assertTrue(sections.contains("只接 MCP"))
        assertTrue(sections.contains("回复开始前"))
        assertTrue(sections.contains("亲自归纳"))
        assertTrue(sections.contains("原文与版本历史"))
        assertTrue(sections.contains("不建设遗忘层"))
        val install = orbisManualReadChapter("st_install", emptySet(), 0, 1).toString()
        assertTrue(install.contains("st_overview"))
        assertTrue(install.contains("st_readme"))
    }
}
