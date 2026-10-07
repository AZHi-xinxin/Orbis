package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException
import me.rerere.rikkahub.data.orbis.sentinel.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class OrbisSentinelToolsTest {
    private class Fixture {
        var persisted: String? = null
        var writes = 0
        var scheduleRequests = 0
        var now = 10_000L
        val store = OrbisSentinelRuleStore(read = { persisted }, write = { persisted = it; writes++ }, lock = Any())
        fun tools(assistant: String = AI, conversation: String = CURRENT,
            onChanged: () -> Unit = { scheduleRequests++ },
            editRule: suspend (String, OrbisSentinelBinding, () -> OrbisSentinelRule?) -> OrbisSentinelRule? = { _, _, edit -> edit() },
            runtimeStatus: () -> OrbisSentinelRuntimeState? = { null }) =
            createOrbisSentinelTools(store, assistant, conversation, onChanged = onChanged, now = { now },
                editRule = editRule, runtimeStatus = runtimeStatus)
        fun existing(id: String = "own-rule", assistant: String = AI, window: String = OLD) =
            store.create(OrbisSentinelRule(id = id, assistantId = assistant, conversationId = window,
                type = OrbisSentinelType.CHAT_IDLE, prompt = "private raw $id", enabled = true,
                thresholdMs = 120_000L, createdAtMs = 1_000L))
    }

    private suspend fun call(tools: List<Tool>, short: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val parts = tools.single { it.name == "orbis_sentinel_$short" }.execute(args)
        assertEquals(1, parts.size)
        return Json.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }
    private suspend fun failed(block: suspend () -> Unit): Exception = try {
        block(); throw AssertionError("Expected operation to fail")
    } catch (failure: Exception) { failure }
    private fun id(value: String) = buildJsonObject { put("rule_id", value) }
    private fun createArgs(type: String = "chat_idle", prompt: String = "  原文（不改）\nsecond line  ") = buildJsonObject {
        put("type", type); put("prompt", prompt)
        if (type == "once") put("due_at_ms", 20_000L) else put("duration_seconds", 120)
        if (type == "app_usage") put("app_package", "test.synthetic.app")
    }

    @Test fun `factory exposes seven rule tools plus readonly guide and opening causes no writes or runtime request`() {
        val fixture = Fixture()
        val tools = fixture.tools()
        assertEquals(setOf("guide", "list", "read", "create", "update", "pause", "resume", "delete"),
            tools.map { it.name.removePrefix("orbis_sentinel_") }.toSet())
        tools.forEach {
            assertFalse(it.needsApproval(buildJsonObject {}))
            assertFalse(it.requiresFreshApproval(buildJsonObject {}))
            val keys = (it.parameters() as InputSchema.Obj).properties.keys
            assertFalse(keys.contains("assistant_id"))
            assertFalse(keys.contains("conversation_id"))
            assertFalse(keys.contains("human_master_enabled"))
        }
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
    }

    private fun assertPortableProbabilitySchema(schema: JsonObject) {
        val probability = schema["properties"]!!.jsonObject["probability_percent"]!!.jsonObject
        assertEquals("integer", probability["type"]!!.jsonPrimitive.content)
        assertEquals(10, probability["minimum"]!!.jsonPrimitive.int)
        assertEquals(100, probability["maximum"]!!.jsonPrimitive.int)
        assertFalse("Numeric enums must not reach Google-converting relays", probability.containsKey("enum"))
        assertTrue(probability["description"]!!.jsonPrimitive.content.contains("10、30、50、70、90、100"))
    }

    @Test fun `create and update declare integer probability without numeric wire enums`() {
        val fixture = Fixture()
        fixture.tools().filter { it.name in setOf("orbis_sentinel_create", "orbis_sentinel_update") }.forEach { tool ->
            val schema = tool.parameters() as InputSchema.Obj
            assertPortableProbabilitySchema(buildJsonObject { put("properties", schema.properties) })
            assertFalse(schema.required.orEmpty().contains("probability_percent"))
        }
        assertEquals(0, fixture.writes)
    }

    @Test fun `ordinary chat sends real create and update schemas safely through OpenAI and Google builders`() {
        val fixture = Fixture()
        val tools = fixture.tools().filter { it.name in setOf("orbis_sentinel_create", "orbis_sentinel_update") }
        assertEquals(2, tools.size)
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test", abilities = listOf(ModelAbility.TOOL)), tools = tools,
        )
        val messages = listOf(UIMessage.user("hello"))
        val client = OkHttpClient()
        val openAiMethod = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest", List::class.java, TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val openAiBody = openAiMethod.invoke(
            ChatCompletionsAPI(client, KeyRoulette.default()), messages, params,
            ProviderSetting.OpenAI(baseUrl = "https://relay.invalid/v1"), false,
        ) as JsonObject
        val openAiFunctions = openAiBody["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject }
        assertEquals(tools.map { it.name }, openAiFunctions.map { it["name"]!!.jsonPrimitive.content })
        openAiFunctions.forEach { assertPortableProbabilitySchema(it["parameters"]!!.jsonObject) }

        val googleMethod = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody", List::class.java, TextGenerationParams::class.java,
        ).apply { isAccessible = true }
        val googleBody = googleMethod.invoke(GoogleProvider(client), messages, params) as JsonObject
        val googleFunctions = googleBody["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(tools.map { it.name }, googleFunctions.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        googleFunctions.forEach { assertPortableProbabilitySchema(it.jsonObject["parameters"]!!.jsonObject) }
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
    }

    @Test fun `create enables own rule by default while preserving a disabled human master and exact prompt`() = runTest {
        val fixture = Fixture()
        fixture.store.setHumanEnabled(false, 2_000L)
        val args = createArgs()
        val result = call(fixture.tools(), "create", args)
        val stored = fixture.store.list().single()
        assertTrue(stored.enabled)
        assertFalse(fixture.store.refresh().enabled)
        assertEquals(AI, stored.assistantId)
        assertEquals(CURRENT, stored.conversationId)
        assertEquals(args["prompt"]!!.jsonPrimitive.content, stored.prompt)
        assertEquals(stored.prompt, result["rule"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertFalse(result["human_master_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(result["runtime_reschedule_requested"]!!.jsonPrimitive.boolean)
        assertEquals(1, fixture.scheduleRequests)
    }

    @Test fun `all five trigger types map conditions without unused condition fields`() = runTest {
        listOf("once", "interval", "chat_idle", "chat_left", "app_usage").forEach { type ->
            val fixture = Fixture()
            call(fixture.tools(), "create", createArgs(type))
            val rule = fixture.store.list().single()
            assertEquals(type, rule.type.name.lowercase())
            assertEquals(if (type == "once") 20_000L else null, rule.dueAtMs)
            assertEquals(if (type == "interval") 120_000L else null, rule.intervalMs)
            assertEquals(if (type in listOf("chat_idle", "chat_left", "app_usage")) 120_000L else null, rule.thresholdMs)
            assertEquals(if (type == "app_usage") "test.synthetic.app" else null, rule.appPackage)
        }
    }

    @Test fun `create can explicitly pause rule and configure observation notifications cooldown and rearm`() = runTest {
        val fixture = Fixture()
        call(fixture.tools(), "create", JsonObject(createArgs() + buildJsonObject {
            put("enabled", false); put("action", "screenshot"); put("notification_level", "strong")
            put("cooldown_seconds", 0.25); put("rearm", "after_cooldown")
        }))
        val rule = fixture.store.list().single()
        assertFalse(rule.enabled)
        assertEquals(OrbisSentinelAction.SCREENSHOT, rule.action)
        assertEquals(OrbisSentinelNotificationLevel.STRONG, rule.notificationLevel)
        assertEquals(250L, rule.cooldownMs)
        assertEquals(OrbisSentinelRearm.AFTER_COOLDOWN, rule.rearm)
    }

    @Test fun `list crosses own windows paginates and omits prompt and other assistant data`() = runTest {
        val fixture = Fixture()
        fixture.existing("own-old", window = OLD)
        fixture.existing("own-current", window = CURRENT)
        fixture.existing("foreign", assistant = OTHER)
        val writes = fixture.writes
        val tools = fixture.tools()
        val result = call(tools, "list")
        val rules = result["rules"]!!.jsonArray
        assertEquals(2, rules.size)
        assertEquals(setOf(OLD, CURRENT), rules.map { it.jsonObject["conversation_id"]!!.jsonPrimitive.content }.toSet())
        assertFalse(result.toString().contains("foreign"))
        assertTrue(rules.none { it.jsonObject.containsKey("prompt") })
        val page = call(tools, "list", buildJsonObject { put("offset", 1); put("limit", 1) })
        assertEquals(1, page["rules"]!!.jsonArray.size)
        assertEquals(2, page["next_offset"]!!.jsonPrimitive.int)
        assertEquals(writes, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
    }

    @Test fun `foreign known id and unknown id refuse read identically without disclosing original prompt`() = runTest {
        val fixture = Fixture()
        fixture.existing("foreign", assistant = OTHER)
        val tools = fixture.tools()
        val foreign = failed { call(tools, "read", id("foreign")) }
        val missing = failed { call(tools, "read", id("missing")) }
        assertEquals(missing.message, foreign.message)
        assertFalse(foreign.message.orEmpty().contains("private raw"))
    }

    @Test fun `foreign rule mutations refuse before the atomic edit callback`() = runTest {
        val fixture = Fixture()
        fixture.existing("foreign", assistant = OTHER)
        val before = fixture.persisted
        var callbacks = 0
        val tools = fixture.tools(editRule = { _, _, action -> callbacks++; action() })
        listOf("update", "pause", "resume", "delete").forEach { name ->
            val args = if (name == "update") buildJsonObject { put("rule_id", "foreign"); put("prompt", "replacement") } else id("foreign")
            assertTrue(failed { call(tools, name, args) } is ToolRejectedBeforeExecutionException)
        }
        assertEquals(0, callbacks)
        assertEquals(before, fixture.persisted)
    }

    @Test fun `caller cannot pass any master or identity mutation as a rule argument`() = runTest {
        val fixture = Fixture()
        fixture.existing()
        val before = fixture.persisted
        val tools = fixture.tools()
        listOf("assistant_id", "conversation_id", "human_master_enabled", "master_enabled", "runtime_state").forEach { forbidden ->
            failed { call(tools, "create", JsonObject(createArgs() + (forbidden to JsonPrimitive("forged")))) }
            failed { call(tools, "update", buildJsonObject { put("rule_id", "own-rule"); put(forbidden, true) }) }
        }
        assertEquals(before, fixture.persisted)
        assertEquals(0, fixture.scheduleRequests)
    }

    @Test fun `update from new window keeps old fixed target and does not trim prompt`() = runTest {
        val fixture = Fixture()
        val initial = fixture.existing()
        val prompt = "  不代笔\n  [保持空格]  "
        call(fixture.tools(conversation = CURRENT), "update", buildJsonObject {
            put("rule_id", initial.id); put("prompt", prompt); put("duration_seconds", 180); put("action", "device_context")
        })
        val updated = fixture.store.get(initial.id)!!
        assertEquals(initial.binding, updated.binding)
        assertEquals(prompt, updated.prompt)
        assertEquals(180_000L, updated.thresholdMs)
        assertEquals(OrbisSentinelAction.DEVICE_CONTEXT, updated.action)
        assertEquals(initial.createdAtMs, updated.createdAtMs)
        assertEquals(fixture.now, updated.updatedAtMs)
    }

    @Test fun `changing type requires new compatible conditions and clears obsolete fields`() = runTest {
        val fixture = Fixture()
        fixture.existing()
        val tools = fixture.tools()
        failed { call(tools, "update", buildJsonObject { put("rule_id", "own-rule"); put("type", "interval") }) }
        call(tools, "update", buildJsonObject { put("rule_id", "own-rule"); put("type", "once"); put("due_at_ms", 40_000L) })
        val result = fixture.store.get("own-rule")!!
        assertEquals(OrbisSentinelType.ONCE, result.type)
        assertEquals(40_000L, result.dueAtMs)
        assertNull(result.intervalMs)
        assertNull(result.thresholdMs)
        assertNull(result.appPackage)
    }

    @Test fun `malformed values never write a rule or request service execution`() = runTest {
        val fixture = Fixture()
        val tools = fixture.tools()
        val invalid = listOf(
            createArgs(prompt = "   "),
            JsonObject(createArgs() - "duration_seconds"),
            JsonObject(createArgs() + ("duration_seconds" to JsonPrimitive(0))),
            JsonObject(createArgs() + ("duration_seconds" to JsonPrimitive(0.0001))),
            JsonObject(createArgs() + ("duration_seconds" to JsonPrimitive(Long.MAX_VALUE))),
            JsonObject(createArgs() + ("type" to JsonPrimitive("unknown"))),
            JsonObject(createArgs() + ("action" to JsonPrimitive("shell"))),
            JsonObject(createArgs() + ("enabled" to JsonPrimitive("false"))),
            JsonObject(createArgs() + ("due_at_ms" to JsonPrimitive(20_000L))),
            JsonObject(createArgs("once") + ("due_at_ms" to JsonPrimitive(-1))),
            JsonObject(createArgs("app_usage") + ("app_package" to JsonPrimitive("../bad"))),
        )
        invalid.forEach { failed { call(tools, "create", it) } }
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
    }

    @Test fun `malformed update is rejected before any pending event can be settled`() = runTest {
        val fixture = Fixture()
        fixture.existing()
        fixture.store.reserveFire("own-rule", "pending:1", fixture.now)
        val before = fixture.persisted
        var callbacks = 0
        val tools = fixture.tools(editRule = { _, _, action -> callbacks++; action() })
        val failure = failed { call(tools, "update", buildJsonObject { put("rule_id", "own-rule"); put("prompt", "") }) }
        assertTrue(failure is ToolRejectedBeforeExecutionException)
        assertEquals(0, callbacks)
        assertEquals(before, fixture.persisted)
    }

    @Test fun `update of a deleted own rule is rejected before edit write or reschedule`() = runTest {
        val fixture = Fixture()
        var callbacks = 0
        val tools = fixture.tools(editRule = { _, _, action -> callbacks++; action() })
        call(tools, "create", buildJsonObject { put("type", "night_usage") })
        val ruleId = fixture.store.list().single().id
        call(tools, "delete", id(ruleId))
        assertNull(fixture.store.get(ruleId))

        // Isolate the attempted update from the expected create/delete effects above.
        fixture.writes = 0
        fixture.scheduleRequests = 0
        callbacks = 0
        val failure = failed {
            call(tools, "update", buildJsonObject {
                put("rule_id", ruleId)
                put("window_start_local", "23:30")
                put("window_end_local", "07:30")
            })
        }

        assertTrue(failure is ToolRejectedBeforeExecutionException)
        val rejection = failure as ToolRejectedBeforeExecutionException
        assertEquals(
            "该哨兵不存在或不属于当前 AI；未修改任何配置。",
            rejection.publicReason,
        )
        assertFalse(rejection.publicReason.contains(ruleId))
        assertEquals(0, callbacks)
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
        assertNull(fixture.store.get(ruleId))
    }

    @Test fun `night usage update accepts reported cross midnight window and persists once`() = runTest {
        val fixture = Fixture()
        call(fixture.tools(), "create", buildJsonObject { put("type", "night_usage") })
        val ruleId = fixture.store.list().single().id
        val writesBefore = fixture.writes
        val schedulesBefore = fixture.scheduleRequests

        val result = call(fixture.tools(), "update", buildJsonObject {
            put("rule_id", ruleId)
            put("window_start_local", "23:30")
            put("window_end_local", "07:30")
        })

        val updated = fixture.store.get(ruleId)!!
        assertEquals("23:30", updated.windowStartLocal)
        assertEquals("07:30", updated.windowEndLocal)
        assertEquals("23:30", result["rule"]!!.jsonObject["window_start_local"]!!.jsonPrimitive.content)
        assertEquals("07:30", result["rule"]!!.jsonObject["window_end_local"]!!.jsonPrimitive.content)
        assertEquals(writesBefore + 1, fixture.writes)
        assertEquals(schedulesBefore + 1, fixture.scheduleRequests)
    }

    @Test fun `unsettled pending event refuses update with readable query path and keeps original`() = runTest {
        val fixture = Fixture()
        fixture.existing()
        fixture.store.reserveFire("own-rule", "pending:1", fixture.now)
        val before = fixture.persisted
        val error = failed { call(fixture.tools(), "update", buildJsonObject { put("rule_id", "own-rule"); put("prompt", "new") }) }
        assertTrue(error.message.orEmpty().contains("orbis_sentinel_read"))
        assertEquals(before, fixture.persisted)
    }

    @Test fun `atomic callback settles old reservation and edits once without creating another occurrence`() = runTest {
        val fixture = Fixture()
        val initial = fixture.existing()
        fixture.store.reserveFire(initial.id, "pending:1", fixture.now)
        var callbacks = 0
        val tools = fixture.tools(editRule = { ruleId, binding, action ->
            callbacks++
            assertEquals(initial.binding, binding)
            val pending = fixture.store.get(ruleId)!!.pendingEventId!!
            fixture.store.abandonPending(ruleId, pending, fixture.now, "configuration_changed")
            fixture.store.rearm(ruleId, fixture.now)
            action()
        })
        call(tools, "update", buildJsonObject { put("rule_id", initial.id); put("prompt", "updated exactly") })
        assertEquals(1, callbacks)
        assertEquals("updated exactly", fixture.store.get(initial.id)!!.prompt)
        assertNull(fixture.store.get(initial.id)!!.pendingEventId)
        assertEquals(1, fixture.store.executions().size)
        assertEquals(OrbisSentinelExecutionStatus.CANCELLED, fixture.store.executions().single().status)
    }

    @Test fun `pause and resume change only selected rule and never turn on human master`() = runTest {
        val fixture = Fixture()
        val selected = fixture.existing("selected")
        val sibling = fixture.existing("sibling")
        fixture.store.setHumanEnabled(false, 3_000L)
        val tools = fixture.tools()
        call(tools, "pause", id(selected.id))
        assertFalse(fixture.store.get(selected.id)!!.enabled)
        call(tools, "resume", id(selected.id))
        assertTrue(fixture.store.get(selected.id)!!.enabled)
        assertFalse(fixture.store.refresh().enabled)
        assertEquals(sibling, fixture.store.get(sibling.id))
        assertTrue(fixture.store.executions().isEmpty())
    }

    @Test fun `deleting rule keeps execution archive readable only by its original assistant`() = runTest {
        val fixture = Fixture()
        val selected = fixture.existing()
        fixture.store.reserveFire(selected.id, "archived:1", fixture.now)
        fixture.store.stageEventText(selected.id, "archived:1", selected.prompt)
        fixture.store.completeFire(selected.id, "archived:1", fixture.now + 1)
        val tools = fixture.tools()
        call(tools, "delete", id(selected.id))
        assertNull(fixture.store.get(selected.id))
        val archive = call(tools, "read", id(selected.id))
        assertTrue(archive["rule_deleted"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, archive["rule"])
        assertEquals("archived:1", archive["executions"]!!.jsonArray.single().jsonObject["event_id"]!!.jsonPrimitive.content)
        failed { call(fixture.tools(assistant = OTHER), "read", id(selected.id)) }
        assertTrue(call(tools, "list")["rules"]!!.jsonArray.isEmpty())
    }

    @Test fun `saved rule survives a scheduling exception and reply does not falsely report running`() = runTest {
        val fixture = Fixture()
        val result = call(fixture.tools(onChanged = { error("synthetic_start_blocked") }), "create", createArgs())
        assertTrue(result["saved"]!!.jsonPrimitive.boolean)
        assertFalse(result["runtime_reschedule_requested"]!!.jsonPrimitive.boolean)
        assertEquals(1, fixture.store.list().size)
        assertFalse(result["runtime"]!!.jsonObject["status_known"]!!.jsonPrimitive.boolean)
        assertFalse(result["runtime"]!!.jsonObject.containsKey("running"))
        assertFalse(result.containsKey("runtime_reschedule_confirmed"))
    }

    @Test fun `runtime failure remains visible even when reschedule call itself returned normally`() = runTest {
        val fixture = Fixture()
        val tools = fixture.tools(runtimeStatus = { OrbisSentinelRuntimeState(false, "background_start_not_allowed") })
        val result = call(tools, "create", createArgs())
        assertTrue(result["runtime_reschedule_requested"]!!.jsonPrimitive.boolean)
        val runtime = result["runtime"]!!.jsonObject
        assertTrue(runtime["status_known"]!!.jsonPrimitive.boolean)
        assertFalse(runtime["running"]!!.jsonPrimitive.boolean)
        assertEquals("background_start_not_allowed", runtime["error"]!!.jsonPrimitive.content)
        assertEquals(runtime, call(tools, "list")["runtime"])
    }

    @Test fun `read execution pagination is scoped preserves exact prompt and does not mutate`() = runTest {
        val fixture = Fixture()
        val selected = fixture.existing()
        listOf("event:1", "event:2", "event:3").forEachIndexed { index, event ->
            val time = fixture.now + index * 100_000L
            fixture.store.reserveFire(selected.id, event, time)
            fixture.store.stageEventText(selected.id, event, selected.prompt)
            fixture.store.completeFire(selected.id, event, time + 1)
            fixture.store.rearm(selected.id, time + 2)
        }
        val writes = fixture.writes
        val result = call(fixture.tools(), "read", buildJsonObject { put("rule_id", selected.id); put("offset", 1); put("limit", 1) })
        assertEquals(selected.prompt, result["rule"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals(3, result["total_executions"]!!.jsonPrimitive.int)
        assertEquals("event:2", result["executions"]!!.jsonArray.single().jsonObject["event_id"]!!.jsonPrimitive.content)
        assertEquals(2, result["next_offset"]!!.jsonPrimitive.int)
        assertEquals(writes, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
    }

    @Test fun `bad host identities and bad pagination fail instead of silently choosing latest`() = runTest {
        val fixture = Fixture()
        failed { fixture.tools(assistant = "") }
        failed { fixture.tools(conversation = "not-a-uuid") }
        val tools = fixture.tools()
        failed { call(tools, "list", buildJsonObject { put("offset", -1) }) }
        failed { call(tools, "list", buildJsonObject { put("limit", 51) }) }
        assertEquals(0, fixture.writes)
    }

    @Test fun `guide covers every type with directly usable example and never starts observation`() = runTest {
        val fixture = Fixture()
        val guide = call(fixture.tools(), "guide")
        val entries = guide["categories"]!!.jsonArray
        assertEquals(OrbisSentinelType.entries.map { it.name.lowercase() }.toSet(), entries.map { it.jsonObject["type"]!!.jsonPrimitive.content }.toSet())
        assertTrue(guide["common"]!!.jsonPrimitive.content.contains("时区"))
        assertEquals(0, fixture.writes)
        assertEquals(0, fixture.scheduleRequests)
        entries.forEach { entry ->
            val sampleFixture = Fixture()
            val args = entry.jsonObject["create_example"]!!.jsonObject
            call(sampleFixture.tools(), "create", args)
            assertEquals(1, sampleFixture.store.list().size)
        }
    }

    @Test fun `system fact categories need only type use simple defaults and never accept authored text`() = runTest {
        listOf("screen_observation", "night_usage", "screen_on", "low_battery", "geofence").forEach { type ->
            val fixture = Fixture()
            val result = call(fixture.tools(), "create", buildJsonObject { put("type", type) })
            val rule = fixture.store.list().single()
            assertEquals("", rule.prompt)
            assertEquals(OrbisSentinelAction.WAKE, rule.action)
            assertTrue(rule.enabled)
            assertTrue(result.containsKey("usage"))
            assertEquals("system_observed_facts", result["rule"]!!.jsonObject["content_source"]!!.jsonPrimitive.content)
            val before = fixture.persisted
            failed { call(fixture.tools(), "update", buildJsonObject { put("rule_id", rule.id); put("prompt", "invented observations") }) }
            failed { call(fixture.tools(), "update", buildJsonObject { put("rule_id", rule.id); put("action", "screenshot") }) }
            assertEquals(before, fixture.persisted)
            when (type) {
                "screen_observation" -> assertEquals(1_800_000L, rule.intervalMs)
                "night_usage" -> { assertEquals(300_000L, rule.thresholdMs); assertEquals("00:00", rule.windowStartLocal); assertEquals("07:30", rule.windowEndLocal) }
                "screen_on" -> assertEquals(600_000L, rule.thresholdMs)
                "low_battery" -> assertEquals(100, rule.probabilityPercent)
            }
        }
    }

    @Test fun `agreement creates one call defaults allows opt out quiet and preserves authored escalation`() = runTest {
        val fixture = Fixture()
        val result = call(fixture.tools(), "create", buildJsonObject { put("type", "agreement"); put("prompt", "  原文  ") })
        val rule = fixture.store.list().single()
        assertEquals(1_800_000L, rule.thresholdMs)
        assertEquals(50, rule.probabilityPercent)
        assertEquals("23:30", rule.quietStartLocal)
        assertEquals("07:30", rule.quietEndLocal)
        assertEquals("  原文  ", rule.prompt)
        assertEquals(JsonNull, result["rule"]!!.jsonObject["next_due_at_ms"])
        call(fixture.tools(), "update", buildJsonObject {
            put("rule_id", rule.id); put("quiet_enabled", false); put("escalation_after", 3); put("escalation_prompt", "  留给自己的提示  ")
        })
        val updated = fixture.store.get(rule.id)!!
        assertNull(updated.quietStartLocal); assertNull(updated.quietEndLocal)
        assertEquals(3, updated.escalationAfter)
        assertEquals("  留给自己的提示  ", updated.escalationPrompt)
        call(fixture.tools(), "update", buildJsonObject { put("rule_id", rule.id); put("name", "不重开静默"); put("escalation_after", 0) })
        assertNull(fixture.store.get(rule.id)!!.quietStartLocal)
        assertNull(fixture.store.get(rule.id)!!.escalationPrompt)
    }

    @Test fun `ritual supports fixed daily and random end midnight without editing prompt`() = runTest {
        val fixture = Fixture()
        call(fixture.tools(), "create", buildJsonObject {
            put("type", "ritual"); put("name", "晚安"); put("prompt", "  晚安内容  ")
            put("daily_at_local", "23:30"); put("daily_window_end_local", "24:00"); put("timezone", "Asia/Shanghai")
        })
        val rule = fixture.store.list().single()
        assertEquals("23:30", rule.dailyAtLocal); assertEquals("24:00", rule.dailyWindowEndLocal)
        assertEquals("Asia/Shanghai", rule.timezone)
        call(fixture.tools(), "update", buildJsonObject { put("rule_id", rule.id); put("daily_window_end_local", ""); put("timezone", "") })
        assertNull(fixture.store.get(rule.id)!!.dailyWindowEndLocal)
        assertNull(fixture.store.get(rule.id)!!.timezone)
        assertEquals("  晚安内容  ", fixture.store.get(rule.id)!!.prompt)
    }

    @Test fun `invalid category settings fail before pending callback and cause no writes`() = runTest {
        val fixture = Fixture()
        call(fixture.tools(), "create", buildJsonObject { put("type", "agreement"); put("prompt", "original") })
        val rule = fixture.store.list().single()
        val before = fixture.persisted
        var callbacks = 0
        val tools = fixture.tools(editRule = { _, _, edit -> callbacks++; edit() })
        listOf(
            buildJsonObject { put("probability_percent", 20) },
            buildJsonObject { put("duration_seconds", 599) },
            buildJsonObject { put("duration_seconds", 86401) },
            buildJsonObject { put("quiet_start_local", "07:30") },
            buildJsonObject { put("escalation_after", 3) },
            buildJsonObject { put("daily_at_local", "08:00") },
            buildJsonObject { put("timezone", "made/up") },
        ).forEach { patch -> failed { call(tools, "update", JsonObject(patch + ("rule_id" to JsonPrimitive(rule.id)))) } }
        assertEquals(before, fixture.persisted)
        assertEquals(0, callbacks)
    }

    @Test fun `probability and observation options accept only documented discrete values`() = runTest {
        listOf(10,30,50,70,90,100).forEach { percent ->
            val fixture = Fixture()
            call(fixture.tools(), "create", buildJsonObject { put("type", "low_battery"); put("probability_percent", percent) })
            assertEquals(percent, fixture.store.list().single().probabilityPercent)
            call(fixture.tools(), "update", buildJsonObject {
                put("rule_id", fixture.store.list().single().id); put("probability_percent", percent)
            })
            assertEquals(percent, fixture.store.list().single().probabilityPercent)
        }
        listOf(1800,3600,5400,7200,9000,10800).forEach { seconds ->
            call(Fixture().tools(), "create", buildJsonObject { put("type", "screen_observation"); put("duration_seconds", seconds) })
        }
        listOf(1799,1801,10801).forEach { seconds ->
            val fixture = Fixture()
            failed { call(fixture.tools(), "create", buildJsonObject { put("type", "screen_observation"); put("duration_seconds", seconds) }) }
            assertEquals(0, fixture.writes)
        }
    }

    @Test fun `portable probability declaration does not relax create or update validation`() = runTest {
        val fixture = Fixture()
        call(fixture.tools(), "create", buildJsonObject { put("type", "low_battery") })
        val ruleId = fixture.store.list().single().id
        val before = fixture.persisted
        val writesBefore = fixture.writes
        val schedulesBefore = fixture.scheduleRequests
        listOf(JsonPrimitive(20), JsonPrimitive(0), JsonPrimitive(101), JsonPrimitive(50.5),
            JsonPrimitive("50"), JsonPrimitive(true), JsonNull).forEach { value ->
            failed { call(fixture.tools(), "create", buildJsonObject {
                put("type", "low_battery"); put("probability_percent", value)
            }) }
            failed { call(fixture.tools(), "update", buildJsonObject {
                put("rule_id", ruleId); put("probability_percent", value)
            }) }
        }
        assertEquals(before, fixture.persisted)
        assertEquals(writesBefore, fixture.writes)
        assertEquals(schedulesBefore, fixture.scheduleRequests)
    }

    @Test fun `changing authored rule to fact rule clears prompt and changing back requires authored content`() = runTest {
        val fixture = Fixture()
        fixture.existing()
        call(fixture.tools(), "update", buildJsonObject { put("rule_id", "own-rule"); put("type", "low_battery") })
        assertEquals("", fixture.store.get("own-rule")!!.prompt)
        failed { call(fixture.tools(), "update", buildJsonObject { put("rule_id", "own-rule"); put("type", "touch") }) }
        call(fixture.tools(), "update", buildJsonObject { put("rule_id", "own-rule"); put("type", "touch"); put("prompt", "  触摸原文  ") })
        assertEquals("  触摸原文  ", fixture.store.get("own-rule")!!.prompt)
    }

    private companion object {
        const val AI = "00000000-0000-4000-8000-000000000001"
        const val OTHER = "00000000-0000-4000-8000-000000000002"
        const val CURRENT = "00000000-0000-4000-8000-000000000003"
        const val OLD = "00000000-0000-4000-8000-000000000004"
    }
}
