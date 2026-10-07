package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class OrbisHelpToolTest {
    @Test fun retiredMemoryCapabilityIsNotAdvertised() {
        val help = createOrbisHelpTool(emptyList(), build)
        val reference = content(help, "tools").toString()
        assertFalse(reference.contains("native_memory"))
        assertFalse(reference.contains("memory_tool"))
    }

    @Test fun `local categories publish actual reading and soup names without guessing disabled writes`() {
        val names = listOf("orbis_reading_list", "orbis_reading_read_chapter", "orbis_reading_list_annotations", "orbis_soup_current")
        val tool = createOrbisHelpTool(names, build)
        assertEquals(names.take(3).toSet(), category(tool, "local_reading").getValue("tools").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertEquals(listOf("orbis_soup_current"), category(tool, "local_soup").getValue("tools").jsonArray.map { it.jsonPrimitive.content })
        val reference = content(tool, "local_tools")
        assertEquals(names.toSet(), reference.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        assertFalse(reference.toString().contains("orbis_reading_annotate"))
        assertFalse(reference.toString().contains("orbis_soup_reveal"))
        assertTrue(reference.getValue("reading_mapping").jsonPrimitive.content.contains("不能直接换成章/段编号"))
        assertTrue(reference.getValue("soup_mapping").jsonPrimitive.content.contains("保存建议后须人类"))
        assertEquals(0, content(tool, "tools").getValue("other_registered_entries").jsonPrimitive.int)
    }

    @Test fun `local tool reference shares schemas and never evaluates live tool callbacks`() {
        val local = createOrbisGardenReadingTools { error("must not execute") } +
            me.rerere.rikkahub.data.orbis.soup.createSoupTools { _, _ -> error("must not execute") }
        val help = appendOrbisHelpTool(local.map { trapTool(it.name) }, build, true).last()
        val reference = content(help, "local_tools")
        val entries = reference.getValue("tools").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject }
        local.forEach { actual ->
            val schema = actual.parameters() as InputSchema.Obj
            val entry = entries.getValue(actual.name)
            val parameters = entry.getValue("parameters").jsonObject
            assertEquals(Json.encodeToJsonElement(InputSchema.serializer(), schema), parameters)
            assertEquals(schema.properties, parameters.getValue("properties"))
            assertEquals(schema.required.orEmpty(), parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(actual.hostApproval != null, entry.getValue("requires_host_approval").jsonPrimitive.boolean)
        }
        assertTrue(reference.toString().length < 24000)
        assertFalse(reference.toString().contains("private-description-sentinel"))
        assertTrue(content(createOrbisHelpTool(emptyList(), build), "local_tools").getValue("tools").jsonArray.isEmpty())
    }

    @Test fun `unclassified native names are bounded while both MCP namespace formats remain withheld`() {
        val names = listOf("orbis_consultation_manual", "orbis_future_tool", "mcp_31eb354b_review_drift_bottles", "mcp__Private__tool", "../private", "orbis_bad/path")
        val help = createOrbisHelpTool(names, build)
        val catalog = content(help, "tools")
        assertEquals(listOf("orbis_consultation_manual", "orbis_future_tool"), catalog.getValue("other_native_tool_names").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, catalog.getValue("external_mcp_registered_entries").jsonPrimitive.int)
        assertFalse(catalog.toString().contains("review_drift_bottles"))
        assertFalse(catalog.toString().contains("Private__"))
        assertFalse(catalog.toString().contains("bad/path"))
        val bounded = content(createOrbisHelpTool(List(150) { "orbis_extra_$it" }, build), "tools")
        assertEquals(100, bounded.getValue("other_native_tool_names").jsonArray.size)
        assertTrue(bounded.getValue("other_native_names_truncated").jsonPrimitive.boolean)
    }

    @Test fun `group history is explicit membership scoped and not automatic memory sharing`() {
        val tool = createOrbisHelpTool(listOf("orbis_group_list", "orbis_group_read"), build)
        val group = category(tool, "group_reference")
        assertEquals(2, group.getValue("tools").jsonArray.size)
        assertTrue(group.getValue("use").jsonPrimitive.content.contains("不自动注入"))
        assertTrue(group.getValue("use").jsonPrimitive.content.contains("离群"))
        assertTrue(content(tool, "limits").getValue("group_history_bridge").jsonPrimitive.content.contains("host_bound_assistant"))
        assertEquals(0, content(tool, "tools").getValue("other_registered_entries").jsonPrimitive.int)
    }
    @Test fun `group manual describes explicit bounded actions without merging histories`() {
        val group = content(createOrbisHelpTool(emptyList(), build), "limits")
            .getValue("multi_ai_group").jsonPrimitive.content
        listOf("引用本群≤400字入草稿不自动发", "显式翻译", "仅单条文本", "90秒/64KiB",
            "再生成仅最新轮同成员", "保留原文/其他成员", "不共享私聊/记忆/工具", "不自动写ST").forEach {
            assertTrue(it, group.contains(it))
        }
    }
    @Test fun `sentinel manual separates automatic work from paused human queue without bypassing safety`() {
        val sentinel = content(createOrbisHelpTool(emptyList(), build), "limits")
            .getValue("sentinel_scope").jsonPrimitive.content
        listOf("独立通道", "不进人类可编辑队列", "不受其暂停阻塞", "同窗串行", "待审批",
            "恢复冲突", "工具结果未知", "保存失败仍拦截", "未知回执不可自动重投", "总开关恢复不补发").forEach {
            assertTrue(it, sentinel.contains(it))
        }
    }
    @Test fun `voice manual distinguishes local mute free opening and scoped end`() {
        val tool = createOrbisHelpTool(listOf("end_voice_call"), build)
        val voice = category(tool, "voice_calls")
        assertEquals(1, voice.getValue("tools").jsonArray.size)
        assertTrue(voice.getValue("use").jsonPrimitive.content.contains("播放时间轴继续"))
        assertTrue(voice.getValue("use").jsonPrimitive.content.contains("一次自由开场"))
        val limits = content(tool, "limits")
        assertTrue(limits.getValue("ai_end_voice_call").jsonPrimitive.content.contains("stale_generation_cannot_end_new_call"))
        assertTrue(limits.getValue("voice_archive_retry").jsonPrimitive.content.contains("no_tools_no_old_queue_resume"))
    }
    @Test fun `manual discovers games and screen capabilities on demand`() {
        val tool = createOrbisHelpTool(listOf("orbis_games_install", "orbis_games_library", "orbis_games_records", "companion_take_screenshot"), build)
        val games = category(tool, "local_games")
        assertEquals(3, games.getValue("tools").jsonArray.size)
        assertTrue(games.getValue("use").jsonPrimitive.content.contains("OrbisGame.finish"))
        assertTrue(games.getValue("use").jsonPrimitive.content.contains("include_template=true"))
        assertTrue(games.getValue("use").jsonPrimitive.content.contains("新建省略"))
        assertTrue(games.getValue("use").jsonPrimitive.content.contains("不是正在玩"))
        val limits = content(tool, "limits")
        assertTrue(limits.getValue("ai_game_install_and_match_records").jsonPrimitive.content.contains("host_timed"))
        assertTrue(limits.getValue("companion_screenshot").jsonPrimitive.content.contains("raw_image"))
        assertTrue(limits.getValue("accessibility_disconnect_notice").jsonPrimitive.content.contains("once_per_outage"))
    }
    private val build = OrbisHelpBuild("org.orbis.agent.dev", "2.5.0", "185", "debug")
    private val emptyArgs = buildJsonObject { }
    private fun topic(value: String) = buildJsonObject { put("topic", value) }
    private fun run(tool: Tool, arguments: JsonElement = emptyArgs): JsonObject = runBlocking {
        val result = tool.execute(arguments)
        assertEquals(1, result.size)
        Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    }
    private fun content(tool: Tool, section: String) = run(tool, topic(section)).getValue("content").jsonObject
    private fun category(tool: Tool, name: String) = content(tool, "tools").getValue("categories").jsonArray
        .map { it.jsonObject }.single { it.getValue("category").jsonPrimitive.content == name }
    private fun trapTool(name: String) = Tool(name = name, description = "private-description-sentinel",
        parameters = { error("Do not inspect other schemas") },
        systemPrompt = { _, _ -> error("Do not read private prompt") },
        needsApproval = { error("Do not probe another tool") },
        execute = { error("Do not execute another tool") })

    @Test fun `default request identifies compiled host without guessing presentation`() {
        val result = run(createOrbisHelpTool(emptyList(), build))
        assertTrue(result.getValue("ok").jsonPrimitive.boolean)
        assertEquals("overview", result.getValue("topic").jsonPrimitive.content)
        assertEquals("orbis-help/22", result.getValue("manual_version").jsonPrimitive.content)
        val host = result.getValue("host").jsonObject
        assertEquals(build.applicationId, host.getValue("application_id").jsonPrimitive.content)
        assertEquals("not_observed", host.getValue("presentation").jsonPrimitive.content)
        assertEquals("compiled_host_registration_not_model_inference", host.getValue("identity_source").jsonPrimitive.content)
        assertFalse(result.getValue("permissions_checked").jsonPrimitive.boolean)
        assertFalse(result.getValue("remote_health_checked").jsonPrimitive.boolean)
    }

    @Test fun `manual has no system prompt or approval side effect`() {
        val help = createOrbisHelpTool(listOf("workspace_shell"), build)
        assertEquals("", help.systemPrompt(Model(), listOf(UIMessage.user("private-chat-sentinel"))))
        assertFalse(help.needsApproval(emptyArgs))
        assertTrue(run(help).getValue("read_only").jsonPrimitive.boolean)
        assertFalse(run(help).getValue("network_requested").jsonPrimitive.boolean)
    }

    @Test fun `append leaves every existing prefix object in exact order`() {
        val original = listOf(trapTool("workspace_shell"), trapTool("get_time_info"), trapTool("mcp__PrivateAlias__read"))
        val result = appendOrbisHelpTool(original, build, true)
        assertEquals(original.size + 1, result.size)
        original.forEachIndexed { index, tool -> assertSame(tool, result[index]) }
        assertEquals(ORBIS_HELP_TOOL_NAME, result.last().name)
        run(result.last(), topic("tools")) // Trap callbacks must remain untouched.
    }

    @Test fun `disabled registration is exact passthrough and append is idempotent`() {
        val original = listOf(trapTool("workspace_shell"))
        assertSame(original, appendOrbisHelpTool(original, build, false))
        val once = appendOrbisHelpTool(original, build, true)
        assertSame(once, appendOrbisHelpTool(once, build, true))
    }

    @Test fun `schema and short description are invariant across snapshots and versions`() {
        val first = createOrbisHelpTool(emptyList(), build)
        val second = createOrbisHelpTool(listOf("workspace_shell", "mcp__Other__tool"), build.copy(versionName = "9.0"))
        assertEquals(first.description, second.description)
        assertEquals(first.parameters(), second.parameters())
        assertTrue(first.description.length < 180)
        val schema = first.parameters() as InputSchema.Obj
        assertEquals(setOf("topic", "query", "chapter", "offset", "limit"), schema.properties.keys)
        assertEquals(listOf("overview", "tools", "permissions", "limits", "local_tools", "chapters", "guide"),
            schema.properties.getValue("topic").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `registration snapshot is frozen after source mutation`() {
        val names = mutableListOf("workspace_shell")
        val help = createOrbisHelpTool(names, build)
        val original = run(help, topic("tools"))
        names.clear()
        names += "clipboard_tool"
        assertEquals(original, run(help, topic("tools")))
        assertTrue(category(help, "workspace").getValue("registered_for_this_run").jsonPrimitive.boolean)
        assertFalse(category(help, "clipboard").getValue("registered_for_this_run").jsonPrimitive.boolean)
    }

    @Test fun `same registrations in different order produce same manual output`() {
        val names = listOf("workspace_shell", "get_time_info", "mcp__Private__tool", "unrecognized")
        val first = createOrbisHelpTool(names, build)
        val second = createOrbisHelpTool(names.reversed(), build)
        listOf("overview", "tools", "permissions", "limits").forEach {
            assertEquals(run(first, topic(it)), run(second, topic(it)))
        }
    }

    @Test fun `only actual allowlisted names are published`() {
        val help = createOrbisHelpTool(listOf("workspace_read_file", "get_time_info", "private-unknown-name"), build)
        val workspace = category(help, "workspace")
        assertEquals(listOf("workspace_read_file"), workspace.getValue("tools").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, content(help, "tools").getValue("other_registered_entries").jsonPrimitive.int)
        assertFalse(run(help, topic("tools")).toString().contains("private-unknown-name"))
    }

    @Test fun `private mcp aliases endpoints and arbitrary names never escape`() {
        val secrets = listOf("mcp__PrivateAliasSentinel__secret_tool", "https://private.example/token-value", "D:/private/workspace", "private-prompt-sentinel")
        val help = createOrbisHelpTool(secrets, build)
        listOf("overview", "tools", "permissions", "limits").forEach { part ->
            val output = run(help, topic(part)).toString()
            secrets.forEach { assertFalse(output.contains(it)) }
            assertFalse(output.contains("PrivateAliasSentinel"))
        }
        assertEquals(1, content(help, "tools").getValue("external_mcp_registered_entries").jsonPrimitive.int)
    }

    @Test fun `mcp presence never implies st health or native integration`() {
        val help = createOrbisHelpTool(listOf("mcp__StillerBrain__stbrain_help"), build)
        val limits = content(help, "limits")
        assertEquals("not_checked", limits.getValue("external_st_connection").jsonPrimitive.content)
        assertTrue(limits.getValue("native_st_star_map").jsonPrimitive.content.contains("explicitly_configured"))
        assertTrue(limits.getValue("techhub_native_ui").jsonPrimitive.content.contains("dedicated_credential"))
        assertTrue(limits.getValue("native_st_star_map").jsonPrimitive.content.contains("remote_health_not_checked"))
        assertEquals("available_when_explicitly_bound_and_server_cutover_verified", limits.getValue("sentinel").jsonPrimitive.content)
        assertEquals("available_read_only_in_orbis", limits.getValue("human_manual_ui_entry").jsonPrimitive.content)
        assertEquals("ai_authored_compact_in_current_full_conversation_run; metadata_history; latest_only_rollback; no_auto_summary_or_forced_compaction", limits.getValue("reversible_context_compression").jsonPrimitive.content)
    }

    @Test fun `local star map demo is distinct from real data and remote health`() {
        listOf(emptyList(), listOf("mcp__StillerBrain__stbrain_help")).forEach { registered ->
            val help = createOrbisHelpTool(registered, build)
            val limits = content(help, "limits")
            assertEquals("available_local_synthetic_demo_only",
                limits.getValue("native_st_star_map_demo_ui").jsonPrimitive.content)
            assertTrue(limits.getValue("native_st_star_map").jsonPrimitive.content.contains("remote_health_not_checked"))
            assertEquals("not_checked", limits.getValue("external_st_connection").jsonPrimitive.content)
            val response = run(help, topic("limits"))
            assertFalse(response.getValue("network_requested").jsonPrimitive.boolean)
            assertFalse(response.getValue("remote_health_checked").jsonPrimitive.boolean)
        }
    }

    @Test fun `static and AI star map descriptions share explicit synthetic data scope`() {
        val manual = orbisPublicStaticManual()
        val limits = manual.getValue("limits").jsonObject
        val description = limits.getValue("native_st_star_map_demo_scope").jsonPrimitive.content
        assertEquals("available_local_synthetic_demo_only",
            limits.getValue("native_st_star_map_demo_ui").jsonPrimitive.content)
        assertTrue(description.contains("本地合成数据"))
        assertTrue(description.contains("示例时间、类型与无标签关联"))
        assertTrue(description.contains("不读取真实 ST 记忆"))
        val help = createOrbisHelpTool(emptyList(), build)
        val overview = content(help, "overview")
        assertEquals(description, overview.getValue("star_map_demo").jsonPrimitive.content)
        assertEquals(limits, content(help, "limits"))
        assertTrue(overview.getValue("host_ui").jsonPrimitive.content.contains("本地动态星图演示"))
        assertTrue(overview.getValue("not_integrated").jsonArray.any { it.jsonPrimitive.content.contains("多人语音通话") })
        assertTrue(limits.getValue("multi_ai_group").jsonPrimitive.content.contains("图片/表情包/文件"))
        assertTrue(limits.getValue("native_st_star_map_scope").jsonPrimitive.content.contains("无正文或摘要"))
        assertFalse(manual.getValue("registration_checked").jsonPrimitive.boolean)
        assertFalse(manual.getValue("remote_health_checked").jsonPrimitive.boolean)
    }

    @Test fun `javascript does not advertise game installation or UI DOM access`() {
        val help = createOrbisHelpTool(listOf("eval_javascript"), build)
        val usage = category(help, "javascript").getValue("use").jsonPrimitive.content
        assertTrue(usage.contains("没有 DOM"))
        assertTrue(usage.contains("不等于小游戏入库"))
        assertEquals("not_available", content(help, "permissions").getValue("native_settings_write_tool").jsonPrimitive.content)
    }

    @Test fun `unknown fields return fixed error without echoing private data`() {
        val help = createOrbisHelpTool(emptyList(), build)
        val first = run(help, buildJsonObject { put("private-key", "private-value") })
        val second = run(help, buildJsonObject { put("different-key", "different-value") })
        assertEquals(first, second)
        assertEquals("orbis_help_unknown_parameter", first.getValue("error").jsonPrimitive.content)
        assertFalse(first.toString().contains("private"))
    }

    @Test fun `invalid topics are fixed errors and never interpreted as paths or URLs`() {
        val help = createOrbisHelpTool(emptyList(), build)
        val expected = run(help, topic("invalid-topic"))
        listOf("", "../private", "https://example.com", "all", "OVERVIEW").forEach { value ->
            val error = run(help, topic(value))
            assertEquals("orbis_help_unknown_topic", error.getValue("error").jsonPrimitive.content)
            assertEquals(expected, error)
        }
    }

    @Test fun `non-string topics and non-object arguments are rejected`() {
        val help = createOrbisHelpTool(emptyList(), build)
        listOf<JsonElement>(JsonNull, JsonPrimitive(7), JsonPrimitive(false), emptyArgs, JsonArray(emptyList())).forEach { value ->
            assertEquals("orbis_help_unknown_topic", run(help, JsonObject(mapOf("topic" to value))).getValue("error").jsonPrimitive.content)
        }
        listOf<JsonElement>(JsonNull, JsonPrimitive("overview"), JsonArray(emptyList())).forEach { value ->
            assertEquals("orbis_help_invalid_parameters", run(help, value).getValue("error").jsonPrimitive.content)
        }
    }

    @Test fun `manual size is bounded independently of untrusted tool names`() {
        val help = createOrbisHelpTool(List(2000) { "mcp__PRIVATE_$it".repeat(30) }, build)
        assertTrue(run(help).toString().length < 3000)
        listOf("tools", "permissions", "limits").forEach {
            val length = run(help, topic(it)).toString().length
            assertTrue("$it response length=$length", length < 7000)
        }
    }

    @Test fun `malformed build labels are not emitted`() {
        val help = createOrbisHelpTool(emptyList(), build.copy(applicationId = "https://private/?token=secret", versionName = "private\nvalue"))
        val host = run(help).getValue("host").jsonObject
        assertEquals("unknown", host.getValue("application_id").jsonPrimitive.content)
        assertEquals("unknown", host.getValue("version_name").jsonPrimitive.content)
        assertFalse(host.toString().contains("secret"))
    }

    @Test fun `human manual is an unobserved static reference not an empty registered snapshot`() {
        val manual = orbisPublicStaticManual()
        assertEquals("public_static_reference_not_current_run", manual.getValue("scope").jsonPrimitive.content)
        listOf("registration_checked", "permissions_checked", "remote_health_checked", "network_requested").forEach {
            assertFalse(manual.getValue(it).jsonPrimitive.boolean)
        }
        assertTrue(manual.getValue("read_only").jsonPrimitive.boolean)
        assertFalse(manual.toString().contains("registered_for_this_run"))
        assertFalse(manual.containsKey("available_categories"))
        assertFalse(manual.containsKey("external_mcp_registered_entries"))
    }

    @Test fun `human and AI manual reuse identical public descriptions and limits`() {
        val manual = orbisPublicStaticManual()
        val catalog = manual.getValue("category_catalog").jsonArray.map { it.jsonObject }
        val knownNames = catalog.flatMap { item -> item.getValue("known_tool_names").jsonArray.map { it.jsonPrimitive.content } }
        val help = createOrbisHelpTool(knownNames, build)
        assertEquals(manual.getValue("host_ui"), content(help, "overview").getValue("host_ui"))
        assertEquals(manual.getValue("permissions"), content(help, "permissions"))
        assertEquals(manual.getValue("limits"), content(help, "limits"))
        catalog.forEach { item ->
            val realCategory = category(help, item.getValue("category").jsonPrimitive.content)
            assertEquals(item.getValue("use"), realCategory.getValue("use"))
            assertEquals(item.getValue("known_tool_names"), realCategory.getValue("tools"))
        }
    }

    @Test fun `static catalog does not change when real tool snapshots change`() {
        val before = orbisPublicStaticManual()
        val registered = createOrbisHelpTool(listOf("workspace_shell", "mcp__PrivateSnapshot__tool"), build)
        val unregistered = createOrbisHelpTool(emptyList(), build)
        assertTrue(category(registered, "workspace").getValue("registered_for_this_run").jsonPrimitive.boolean)
        assertFalse(category(unregistered, "workspace").getValue("registered_for_this_run").jsonPrimitive.boolean)
        assertEquals(before, orbisPublicStaticManual())
        assertFalse(before.toString().contains("PrivateSnapshot"))
    }

    @Test fun `static manual retains honest not integrated and no settings mutation limits`() {
        val manual = orbisPublicStaticManual()
        val limits = manual.getValue("limits").jsonObject
        listOf("native_st_star_map", "techhub_native_ui").forEach {
            assertTrue(limits.getValue(it).jsonPrimitive.content.contains("remote_health_not_checked"))
        }
        assertEquals("available_when_explicitly_bound_and_server_cutover_verified", limits.getValue("sentinel").jsonPrimitive.content)
        assertEquals("not_checked", limits.getValue("external_st_connection").jsonPrimitive.content)
        assertEquals("not_available", manual.getValue("permissions").jsonObject.getValue("native_settings_write_tool").jsonPrimitive.content)
        // Human-only full reference adds call/interruption, independent audio controls,
        // private notification speech, local ringtones, and Web/import boundaries in v15;
        // v16 adds local-gain mute, scoped hangup, free opening and isolated archive safety.
        // It is never automatically injected; per-topic tool response budgets stay unchanged.
        // v20 adds local schedule, voice-note and kaomoji categories. Full chapters remain separate.
        assertTrue("Human-only manual length=${manual.toString().length}", manual.toString().length < 16000)
    }

    @Test fun `calendar and call notes distinguish conditional barge in from actual device validation`() {
        val help = createOrbisHelpTool(listOf("calendar_create", "calendar_query"), build)
        val description = category(help, "calendar").getValue("use").jsonPrimitive.content
        assertTrue(description.contains("不填则不请求提醒"))
        assertTrue(description.contains("不代表实际弹窗或响铃"))
        assertTrue(description.contains("不可自动重试"))
        val limits = content(help, "limits")
        assertTrue(limits.getValue("calendar_reminders").jsonPrimitive.content.contains("user_tested_not_live_verified"))
        assertTrue(limits.getValue("voice_call_silence").jsonPrimitive.content.contains("at_least_3000ms"))
        assertTrue(limits.getValue("voice_call_phase_one").jsonPrimitive.content.contains("device_background_policy_still_applies"))
        val interruption = limits.getValue("voice_call_interruption").jsonPrimitive.content
        assertTrue(interruption.contains("implemented_conditionally"))
        assertTrue(interruption.contains("otherwise_half_duplex"))
        assertTrue(interruption.contains("device_acoustic_acceptance_pending"))
        assertFalse(interruption.contains("not_implemented"))
        val voice = category(help, "voice_calls").getValue("use").jsonPrimitive.content
        assertTrue(voice.contains("不是原始声音"))
        assertTrue(voice.contains("不每轮自动注入全文"))
        assertTrue(voice.contains("本轮真机声学效果未验收"))
        assertFalse(voice.contains("不支持说话打断"))
        assertFalse(category(help, "voice_calls").getValue("registered_for_this_run").jsonPrimitive.boolean)
    }

    @Test fun `incoming tools are discovered only when registered and publish bounded honest behavior`() {
        val names = listOf("start_voice_call", "orbis_incoming_call_records")
        val help = createOrbisHelpTool(names, build)
        val voice = category(help, "voice_calls")
        assertTrue(voice.getValue("registered_for_this_run").jsonPrimitive.boolean)
        assertEquals(names, voice.getValue("tools").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, content(help, "tools").getValue("other_registered_entries").jsonPrimitive.int)
        val description = voice.getValue("use").jsonPrimitive.content
        listOf("须写来电原因", "拒接/无人接进入冷却", "不自动回拨", "接听前不开麦").forEach {
            assertTrue(it, description.contains(it))
        }
        val incoming = content(help, "limits").getValue("incoming_voice_calls").jsonPrimitive.content
        listOf("host_owned_assistant_and_conversation", "connected_rejected_no_response_failed_logged",
            "ring_5_to_60_seconds_default_30", "cooldown_default_15_minutes", "one_notification_attempt_only",
            "no_automatic_redial_or_replay", "not_a_telephone_call").forEach { assertTrue(it, incoming.contains(it)) }
        assertFalse(category(createOrbisHelpTool(emptyList(), build), "voice_calls")
            .getValue("registered_for_this_run").jsonPrimitive.boolean)
    }

    @Test fun `manual defines mute as microphone off and distinguishes independent speaker switch`() {
        val help = createOrbisHelpTool(listOf("start_voice_call"), build)
        val controls = content(help, "limits").getValue("voice_call_controls").jsonPrimitive.content
        assertTrue(controls.contains("microphone_and_speaker_independent"))
        assertTrue(controls.contains("muted_answer_means_microphone_off_speaker_on"))
        assertTrue(category(help, "voice_calls").getValue("use").jsonPrimitive.content.contains("静音接听是关麦、保留扬声器"))
        assertFalse(run(help, topic("limits")).getValue("permissions_checked").jsonPrimitive.boolean)
    }

    @Test fun `notification reading is private opt in and receipt never proves audible delivery`() {
        val help = createOrbisHelpTool(listOf("companion_send_notification"), build)
        val reading = content(help, "limits").getValue("notification_auto_read").jsonPrimitive.content
        listOf("default_off", "lock_screen_separate_opt_in_default_off", "only_current_successfully_posted_AI_notification",
            "existing_global_TTS_provider_and_speed", "max_1000_characters_no_truncation",
            "played_is_not_proof_human_heard", "no_old_or_third_party_notification_reading").forEach {
            assertTrue(it, reading.contains(it))
        }
        val privacy = content(help, "permissions").getValue("contact_privacy").jsonPrimitive.content
        listOf("可能被旁人听见", "远程TTS", "发送朗读文本", "文件授权").forEach { assertTrue(it, privacy.contains(it)) }
        assertFalse(run(help, topic("permissions")).getValue("network_requested").jsonPrimitive.boolean)
    }

    @Test fun `local ringtones have independent user file permissions and fallback without live claim`() {
        val help = createOrbisHelpTool(emptyList(), build)
        val limits = content(help, "limits")
        val ringtones = limits.getValue("local_ringtones").jsonPrimitive.content
        listOf("incoming_call_and_alarm_music_selected_separately", "persistent_read_permission_required",
            "restore_system_default_available", "falls_back_to_available_system_sound", "no_cloud_music_search").forEach {
            assertTrue(it, ringtones.contains(it))
        }
        assertTrue(ringtones.contains("device_acceptance_not_verified_here"))
        assertFalse(run(help, topic("limits")).getValue("remote_health_checked").jsonPrimitive.boolean)
    }

    @Test fun `web manual separates browser appearance from phone powers and restrictive chat imports`() {
        val limits = content(createOrbisHelpTool(emptyList(), build), "limits")
        val appearance = limits.getValue("web_appearance").jsonPrimitive.content
        listOf("浅色、深色、跟随系统", "配色仅存本浏览器", "欢迎语读取昵称", "不是手机全功能镜像").forEach {
            assertTrue(it, appearance.contains(it))
        }
        val imports = limits.getValue("chat_import_sources").jsonPrimitive.content
        listOf("密码保护", "DeepSeek官方ZIP≤8GiB", "RikkaHub含聊天数据库ZIP≤8GiB",
            "Codex原始rollout JSONL≤64MiB", "Operit聊天JSON v2≤1GiB", "Kelivo安卓v2 ZIP≤8GiB",
            "北极星Polaris ZIP≤8GiB", "ZIP展开总量≤16GiB", "流式聊天JSON≤1GiB",
            "单条/单窗口容量", "不是无限容量", "预览→人工确认", "不覆盖现有窗口",
            "历史工具不执行", "取消保留已完成会话", "不扩大模型上下文上限").forEach { assertTrue(it, imports.contains(it)) }
        listOf("ZIP≤80MiB", "ZIP≤512MiB", "JSON v2≤64MiB").forEach { assertFalse(it, imports.contains(it)) }
        val codex = limits.getValue("codex_import_limits").jsonPrimitive.content
        listOf("末行必须完整换行", "不是ChatGPT导出", "Markdown", "不恢复执行/工具权限", "明确拒绝").forEach {
            assertTrue(it, codex.contains(it))
        }
        assertEquals(limits, orbisPublicStaticManual().getValue("limits"))
    }

    @Test fun `allow all current AI is revocable execution approval not system permission delegation`() {
        val help = createOrbisHelpTool(emptyList(), build)
        val permissions = content(help, "permissions")
        val all = permissions.getValue("always_allow_current_ai").jsonPrimitive.content
        listOf("当前 AI", "已启用工具", "可撤销", "不会自动启用工具", "授予系统权限", "目标与参数仍需核验").forEach {
            assertTrue(all.contains(it))
        }
        assertTrue(permissions.getValue("approval_revocation").jsonPrimitive.content.contains("关闭总开关不删除单项"))
        assertEquals(permissions, orbisPublicStaticManual().getValue("permissions"))
        assertFalse(run(help, topic("permissions")).getValue("permissions_checked").jsonPrimitive.boolean)
    }

    @Test fun `user test notes and conditional sentinel availability never become a live deployment claim`() {
        val help = createOrbisHelpTool(emptyList(), build)
        val limits = content(help, "limits")
        assertEquals("available_when_explicitly_bound_and_server_cutover_verified", limits.getValue("sentinel").jsonPrimitive.content)
        val scope = limits.getValue("sentinel_scope").jsonPrimitive.content
        assertTrue(scope.contains("不宣称已部署或已迁移"))
        assertTrue(scope.contains("未知回执不可自动重投"))
        val tested = limits.getValue("user_validation_notes").jsonPrimitive.content
        listOf("日历提醒", "内置手机工具调用", "通话短停顿", "不读取实时状态",
            "2.6.2 起已修复受支持的 Operit 思考内容导入", "另存修正版副本", "不覆盖已有聊天",
            "实际安装结果须核实当前版本", "不是新版本发布或真机验收通过声明").forEach { assertTrue(it, tested.contains(it)) }
        assertFalse(tested.contains("Operit 思考内容导入仍待修复"))
        assertFalse(content(help, "overview").getValue("not_integrated").jsonArray.any { it.jsonPrimitive.content == "哨兵" })
        assertFalse(run(help, topic("limits")).getValue("remote_health_checked").jsonPrimitive.boolean)
        assertEquals(limits, orbisPublicStaticManual().getValue("limits"))
    }

    @Test fun `alarm query manual discovers selected tool and distinguishes receipts from audible delivery`() {
        val help = createOrbisHelpTool(listOf("companion_get_alarms", "companion_set_alarm", "companion_cancel_alarm"), build)
        val companion = category(help, "companion_native")
        assertTrue(companion.getValue("registered_for_this_run").jsonPrimitive.boolean)
        assertEquals(setOf("companion_get_alarms", "companion_set_alarm", "companion_cancel_alarm"),
            companion.getValue("tools").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val use = companion.getValue("use").jsonPrimitive.content
        listOf("服务关闭时", "本应用台账", "不列系统时钟全部闹钟", "旧记录可能不齐", "不保证响铃或人已听到",
            "不会因查询设闹钟或触发铃声", "一次性", "同HH:mm替换", "完整日期").forEach { assertTrue(it, use.contains(it)) }
        val limit = content(help, "limits").getValue("companion_alarm_query").jsonPrimitive.content
        assertTrue(limit.contains("read_only_no_confirmation"))
        assertTrue(limit.contains("legacy_history_may_be_incomplete"))
        assertTrue(limit.contains("scheduled_is_not_proof_of_ringing_or_heard"))
        assertTrue(limit.contains("limit_1_to_100_default_30"))
        assertTrue(limit.contains("offset_0_to_10000_default_0_follow_next_offset_until_null"))
        val emptyHelp = createOrbisHelpTool(emptyList(), build)
        assertFalse(category(emptyHelp, "companion_native").getValue("registered_for_this_run").jsonPrimitive.boolean)
    }
}
