package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.sync.importer.ArchiveCapacity
import org.junit.Assert.*
import org.junit.Test

class OrbisManualChaptersTest {
    private val build = OrbisHelpBuild("org.orbis.agent", "1.0", "1", "release")
    private fun call(tool: Tool, args: String) = runBlocking {
        Json.parseToJsonElement((tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text).text).jsonObject
    }
    private fun guide(id: String) = orbisManualReadChapter(id, emptySet(), limit = 10).toString()

    @Test fun `chapter IDs unique and all sections stay within per call size budget`() {
        assertEquals(orbisManualChapters.size, orbisManualChapters.map { it.id }.distinct().size)
        orbisManualChapters.forEach {
            assertTrue(it.id.matches(Regex("[a-z_]{1,40}")))
            assertTrue(it.sections.isNotEmpty())
            assertTrue(it.title.isNotBlank())
            assertTrue(it.uiPath.isNotBlank())
            assertTrue("${it.id} too large", guide(it.id).length < 6000)
        }
    }

    @Test fun `index search and paging are local deterministic and complete`() {
        val first = orbisManualChapterIndex(limit = 5)
        assertEquals(5, first.getValue("chapters").jsonArray.size)
        assertEquals(5, first.getValue("next_offset").jsonPrimitive.int)
        val ids = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = orbisManualChapterIndex(offset = offset, limit = 5)
            ids += page.getValue("chapters").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            val next = page.getValue("next_offset")
            if (next is JsonNull) break
            offset = next.jsonPrimitive.int
        }
        assertEquals(orbisManualChapters.map { it.id }, ids)
        assertTrue(orbisManualChapterIndex(query = "课表").getValue("chapters").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == "schedule" })
        assertEquals(0, orbisManualChapterIndex(query = "synthetic-no-such-topic").getValue("total").jsonPrimitive.int)
    }

    @Test fun `guide registration is exact and does not expose private aliases`() {
        val names = setOf("orbis_schedule_list", "mcp__PRIVATE__secret", "https://private.invalid/key")
        val result = orbisManualReadChapter("schedule", names)
        assertEquals(listOf("orbis_schedule_list"), result.getValue("registered_tools").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(result.toString().contains("PRIVATE"))
        assertFalse(result.toString().contains("private.invalid"))
        assertEquals(3, result.getValue("sections").jsonArray.size)
        assertEquals(3, result.getValue("next_offset").jsonPrimitive.int)
        assertEquals(1, orbisManualReadChapter("schedule", names, 3).getValue("sections").jsonArray.size)
    }

    @Test fun `manual execution never invokes tools or private callbacks and does not inject whole book`() {
        val trap = Tool("orbis_schedule_list", "private-description",
            parameters = { error("private schema") }, systemPrompt = { _, _ -> error("private prompt") }, execute = { error("must not run") })
        val help = appendOrbisHelpTool(listOf(trap), build, true).last()
        val result = call(help, """{"topic":"guide","chapter":"schedule"}""")
        assertTrue(result.getValue("read_only").jsonPrimitive.boolean)
        assertFalse(result.getValue("network_requested").jsonPrimitive.boolean)
        assertFalse(result.getValue("remote_health_checked").jsonPrimitive.boolean)
        assertFalse(result.toString().contains("private-description"))
        assertFalse(orbisPublicStaticManual().toString().contains("public_static_chapters_not_live_status"))
        val overview = call(help, "{}")
        assertTrue(overview.toString().length < 3000)
        assertFalse(overview.toString().contains("Android 通常拒绝"))
    }

    @Test fun `chapter parameters cannot become paths requests or raw exception output`() {
        val help = createOrbisHelpTool(emptyList(), build)
        listOf(
            """{"topic":"guide"}""",
            """{"topic":"guide","chapter":"../private"}""",
            """{"topic":"guide","chapter":"https://private.invalid"}""",
            """{"topic":"chapters","query":null}""",
            """{"topic":"chapters","query":"bad\nquery"}""",
            """{"topic":"chapters","limit":0}""",
            """{"topic":"chapters","limit":11}""",
            """{"topic":"chapters","offset":-1}""",
            """{"topic":"chapters","offset":"1"}""",
            """{"topic":"overview","offset":1}""",
            """{"topic":"guide","chapter":"voice","query":"secret"}""",
        ).forEach {
            val result = call(help, it)
            assertFalse(result.getValue("ok").jsonPrimitive.boolean)
            assertFalse(result.toString().contains("private.invalid"))
            assertFalse(result.toString().contains("secret"))
        }
    }

    @Test fun `context guide explains stepped truncation cache memory gaps and model capacity`() {
        val text = guide("context")
        listOf("0 表示不限制", "非零至少 20", "阶梯式截取", "本地还保留聊天不等于模型仍收到", "缓存命中率", "不会扩大", "最近一次压缩").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun `voice and expression guides distinguish synthesis from playback and human click from AI storage`() {
        val voice = guide("voice")
        listOf("orbis_voice_note", "不自动播放", "1–4000", "原始转写", "让人类确认", "不自动重试计费请求").forEach { assertTrue(it, voice.contains(it)) }
        val expressions = guide("expressions")
        listOf("保存不发送", "人类点选时立即独立发送", "原草稿不变", "管理入口不发送", "删除由人类", "expected_revision").forEach { assertTrue(it, expressions.contains(it)) }
    }

    @Test fun `imports do not force password or promise absent workspace recovery`() {
        val text = guide("imports")
        listOf("未开启密码保护的模式也可", "仍须正常登录", "可信网络", "Operit", "JSON v2", "不覆盖", "不执行历史工具", "未打包的工作区文件无法", "不承诺自动恢复").forEach { assertTrue(it, text.contains(it)) }
        val limits = call(createOrbisHelpTool(emptyList(), build), """{"topic":"limits"}""").getValue("content").jsonObject
        assertTrue(limits.getValue("chat_import_sources").jsonPrimitive.content.contains("无需强制开启密码"))
    }

    @Test fun `import guide documents current disk and stream limits without promising unlimited migration`() {
        val text = guide("imports")
        val zipLimit = "ZIP≤${ArchiveCapacity.MAX_ZIP_BYTES / ArchiveCapacity.GIB}GiB"
        listOf("DeepSeek 官方 $zipLimit", "RikkaHub $zipLimit", "Kelivo 安卓 v2 $zipLimit",
            "北极星 Polaris 备份 $zipLimit",
            "JSON v2≤${ArchiveCapacity.MAX_STREAM_JSON_BYTES / ArchiveCapacity.GIB}GiB",
            "展开总量上限为${ArchiveCapacity.MAX_EXPANDED_BYTES / ArchiveCapacity.GIB}GiB",
            "Codex 原始 rollout JSONL≤64MiB", "单条消息", "单窗口", "设备可用空间检查",
            "不靠截断原文", "没有无限容量", "不导入群聊、附件实体或应用配置").forEach {
            assertTrue(it, text.contains(it))
        }
        listOf("ZIP≤80MiB", "ZIP≤512MiB", "JSON v2≤64MiB").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test fun `import guide records Operit reasoning fix and protects earlier imported originals`() {
        val text = guide("imports")
        listOf("2.6.2 起已修复受支持的 Operit 思考内容导入", "另存修正版副本",
            "不覆盖已有聊天", "不凭空找回原包没有的内容", "务必保管原件").forEach {
            assertTrue(it, text.contains(it))
        }
        assertFalse(text.contains("Operit 思考内容导入仍待修复"))
    }

    @Test fun `updates and consultation preserve honest unfinished boundaries`() {
        val updates = guide("updates")
        listOf("versionCode", "不自动卸载", "签名", "先只读核实", "真机功能通过是四回事", "真实发布包端到端自更新仍待验收").forEach { assertTrue(it, updates.contains(it)) }
        val consultation = guide("consultation")
        listOf("正在开发，暂未开放", "不自动恢复生成", "不公开内部正文", "不宣称已修好").forEach { assertTrue(it, consultation.contains(it)) }
    }

    @Test fun `new local tools expose actual pure factory schemas only when registered`() {
        val local = me.rerere.rikkahub.data.orbis.schedule.createOrbisScheduleTools { error("must not run") } +
            createOrbisKaomojiTools { error("must not open storage") }
        val help = createOrbisHelpTool(local.map { it.name }, build)
        val data = call(help, """{"topic":"local_tools"}""").getValue("content").jsonObject
        assertEquals(local.map { it.name }.toSet(), data.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        val readOnly = createOrbisHelpTool(listOf("orbis_schedule_list"), build)
        assertEquals(1, call(readOnly, """{"topic":"local_tools"}""").getValue("content").jsonObject.getValue("tools").jsonArray.size)
    }
}
