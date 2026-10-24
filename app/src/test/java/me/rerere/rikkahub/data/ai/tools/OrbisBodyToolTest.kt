package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.executeGenerationTool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.activities.ActivityStorage
import me.rerere.rikkahub.data.orbis.body.*
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolInvocationContext
import org.junit.Assert.*
import org.junit.Test

class OrbisBodyToolTest {
    private fun repo() = BodyRepository(object : ActivityStorage {
        override fun read(): String? = null; override fun write(text: String) {}
    }) { BodyClock("p", 0, 1000) }
    @Test fun settingsDefaultOff() { assertFalse(Assistant().orbisBodyEnabled); assertFalse(Assistant().orbisBodyKeywordsEnabled) }
    @Test fun disabledToolNeverOpensStore() = runBlocking {
        val tool = bodyTool("chat", { false }) { error("opened") }
        val text = (tool.execute(Json.parseToJsonElement("{\"action\":\"rules\"}")).single() as UIMessagePart.Text).text
        assertTrue(text.contains("body_disabled")); assertFalse(text.contains("opened"))
    }
    @Test fun noAuthorityInModelArgumentsAndRulesExcludeValues() = runBlocking {
        val repo = repo(); val tool = bodyTool("chat", { true }) { repo }
        val bad = tool.execute(Json.parseToJsonElement("{\"action\":\"rules\",\"owner\":\"foreign\"}"))
        assertTrue((bad.single() as UIMessagePart.Text).text.contains("body_parameters_invalid"))
        val read = (tool.execute(Json.parseToJsonElement("{\"action\":\"rules\"}")).single() as UIMessagePart.Text).text
        assertFalse(read.contains("persistent")); assertFalse(read.contains("snapshot")); assertTrue(read.contains("definition"))
    }
    @Test fun wrongConversationCannotWrite() = runBlocking {
        val repo = repo(); val tool = bodyTool("chat", { true }) { repo }
        val result = withContext(CloudToolInvocationContext("tool", "msg", "different")) {
            tool.execute(Json.parseToJsonElement("{\"action\":\"draft\",\"expected_revision\":0,\"definition\":{}}"))
        }
        assertTrue((result.single() as UIMessagePart.Text).text.contains("body_invocation_missing")); assertNull(repo.snapshot().draft)
    }

    @Test fun generationDispatchCanDefinePublishAndRecordWithoutHumanEnablingAnEmptyDefinition() = runBlocking {
        var durable: String? = null
        val storage = object : ActivityStorage {
            override fun read(): String? = durable
            override fun write(text: String) { durable = text }
        }
        val repo = BodyRepository(storage) { BodyClock("p", 0, 1000) }
        val tool = bodyTool("chat", { true }) { repo }
        suspend fun call(id: String, args: String): JsonObject = Json.parseToJsonElement(
            (executeGenerationTool(tool, Json.parseToJsonElement(args), id, "model-message", "chat").single()
                as UIMessagePart.Text).text).jsonObject
        val definition = """{"dimensions":[{"id":"energy","label":"活力","scope":"session","baseline":50,"min":0,"max":100,"maxDelta":10,"halfLifeMinutes":30}],"rules":[{"event":"rest","deltas":{"energy":8},"keywords":["休息"]}]}"""
        val draftArgs = """{"action":"draft","expected_revision":0,"definition":$definition}"""
        val draft = call("draft-call", draftArgs)
        assertTrue(draft.getValue("ok").jsonPrimitive.boolean)
        assertNotNull(repo.snapshot().draft)
        assertEquals("auto", repo.snapshot().draft!!.triggerMode)
        assertFalse(repo.snapshot().running)
        assertEquals(draft, call("draft-call", draftArgs))
        val published = call("activate-call", """{"action":"activate","expected_revision":${draft.getValue("revision")}}""")
        assertTrue(published.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, repo.snapshot().versions.size)
        assertNull(repo.snapshot().draft)
        assertFalse(repo.snapshot().running)
        assertTrue(call("resume-call", """{"action":"resume","expected_revision":2}""")["ok"]!!.jsonPrimitive.boolean)
        val eventArgs = """{"action":"event","event":"rest","version":1,"strength":0,"visible":true}"""
        val event = call("event-call", eventArgs)
        assertEquals("body_manual_event_disabled", event["code"]!!.jsonPrimitive.content)
        assertFalse(event.containsKey("values"))
        assertEquals(event, call("event-call", eventArgs))
        repo.human("chat", "new-human", "休息", false)
        val after = repo.snapshot()
        repo.human("chat", "new-human", "休息", false)
        assertEquals(after, repo.snapshot())
        assertTrue(repo.snapshot().snapshot.sessions.getValue("chat").visible)
        assertEquals(repo.snapshot().versions, BodyRepository(storage) { BodyClock("p2", 0, 2000) }.snapshot().versions)
    }

    @Test fun legacyManualReadsAndWritesExplicitlyExplainSetupAndCannotSilentlySwitchRules() = runBlocking {
        val repo = repo(); val tool = bodyTool("chat", { true }) { repo }
        val legacy = BodyRules.json.decodeFromString<BodyDefinition>("""{"dimensions":[{"id":"energy","label":"活力"}],"rules":[{"event":"rest","deltas":{"energy":5}}]}""")
        repo.define(legacy, 0, "old-draft"); repo.activate(1, "old-publish");repo.setRunningFromTool("chat", true, 2, "old-resume")
        suspend fun call(id: String, args: String): JsonObject = Json.parseToJsonElement(
            (executeGenerationTool(tool, Json.parseToJsonElement(args), id, "message", "chat").single() as UIMessagePart.Text).text).jsonObject
        val before = repo.snapshot()
        for (action in listOf("rules", "state")) {
            val result = call(action, """{"action":"$action"}""")
            assertEquals("manual", result["triggerMode"]!!.jsonPrimitive.content)
            assertEquals("manual_mode", result["setup"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertTrue(result["setup"]!!.jsonObject["needs_attention"]!!.jsonPrimitive.boolean)
            if (action == "rules") assertEquals("manual", result["definition"]!!.jsonObject["triggerMode"]!!.jsonPrimitive.content)
        }
        assertEquals(before, repo.snapshot())
        val event = call("event", """{"action":"event","event":"rest","version":1}""")
        assertTrue(event["ok"]!!.jsonPrimitive.boolean)
        assertEquals("manual_mode", event["setup"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun missingHostIdentityReportsApplicationFaultNotAMissingHumanRunPermission() = runBlocking {
        val repo = repo()
        val tool = bodyTool("chat", { true }) { repo }
        val result = executeGenerationTool(tool, Json.parseToJsonElement("""{"action":"draft","expected_revision":0,"definition":{}}"""),
            "call", "message", null)
        val text = (result.single() as UIMessagePart.Text).text
        assertTrue(text.contains("body_invocation_missing"))
        assertTrue(text.contains("这不是人类未开启运行"))
        assertNull(repo.snapshot().draft)
    }

    @Test fun aiCanManageRuntimeButCannotSetHumanPrivateVisibilityOrDowngradePrivateDimension() = runBlocking {
        val repo = repo(); val tool = bodyTool("chat", { true }) { repo }
        suspend fun call(id: String, args: String): JsonObject = Json.parseToJsonElement(
            (executeGenerationTool(tool, Json.parseToJsonElement(args), id, "message", "chat").single() as UIMessagePart.Text).text).jsonObject
        val definition = """{"dimensions":[{"id":"intensity","label":"强烈情绪","private":true}],"rules":[{"event":"moment","deltas":{"intensity":5}}]}"""
        assertTrue(call("draft", """{"action":"draft","expected_revision":0,"definition":$definition}""")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(call("activate", """{"action":"activate","expected_revision":1}""")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(call("resume", """{"action":"resume","expected_revision":2}""")["ok"]!!.jsonPrimitive.boolean)
        val before = repo.snapshot()
        for (args in listOf("""{"action":"rules","privateVisible":true}""", """{"action":"resume","expected_revision":3,"privateVisible":true}""",
            """{"action":"privateVisible","visible":true}""")) {
            assertFalse(call("forbidden-$args", args)["ok"]!!.jsonPrimitive.boolean)
        }
        assertEquals(before, repo.snapshot())
        assertTrue(call("pause", """{"action":"pause","expected_revision":3}""")["ok"]!!.jsonPrimitive.boolean)
        val lowered = definition.replace("\"private\":true", "\"private\":false")
        val result = call("lower", """{"action":"draft","expected_revision":4,"definition":$lowered}""")
        assertEquals("body_private_downgrade_forbidden", result["code"]!!.jsonPrimitive.content)
    }

    @Test fun autoToolAllowsOwnStateAndSharingButRejectsFabricatedEventsAndOtherConversation() = runBlocking {
        val repo = repo(); val tool = bodyTool("chat", { true }) { repo }
        suspend fun call(id: String, args: String): JsonObject = Json.parseToJsonElement(
            (executeGenerationTool(tool, Json.parseToJsonElement(args), id, "message", "chat").single() as UIMessagePart.Text).text).jsonObject
        repo.define(BodyDefinition(listOf(BodyDimension("focus", "专注", isPrivate = true)),
            listOf(BodyRule("idea", mapOf("focus" to 4.0), listOf("想法"))), triggerMode = "auto"), 0, "draft")
        repo.activate(1, "publish");repo.setRunningFromTool("chat", true, 2, "start")
        repo.human("chat", "human", "想法", false)
        val state = call("state", """{"action":"state"}""")
        assertEquals(54.0, state["values"]!!.jsonObject["focus"]!!.jsonPrimitive.double, .0)
        assertFalse(state.containsKey("privateVisible"))
        val before = repo.snapshot()
        assertEquals("body_manual_event_disabled", call("fake-event", """{"action":"event","event":"idea","version":1}""")["code"]!!.jsonPrimitive.content)
        assertEquals("body_auto_undo_disabled", call("fake-undo", """{"action":"undo","seq":4}""")["code"]!!.jsonPrimitive.content)
        assertEquals(before, repo.snapshot())
        assertFalse(call("foreign", """{"action":"state","conversation":"other"}""")["ok"]!!.jsonPrimitive.boolean)
        val result = call("share", """{"action":"visibility","expected_revision":${before.revision},"shared":true}""")
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(before.snapshot.sessions["chat"]!!.values, repo.snapshot().snapshot.sessions["chat"]!!.values)
        assertTrue(repo.snapshot().snapshot.sessions["chat"]!!.privateShared)
    }
}
