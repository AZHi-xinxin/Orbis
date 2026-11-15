package me.rerere.rikkahub.data.orbis.agentwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AgentWorkTest {
    @get:Rule val temp = TemporaryFolder()
    private val conversation = UUID.randomUUID().toString()
    private fun store(owner: String = "one") = AgentTaskStore(File(temp.root,owner))
    private fun steps() = listOf(AgentStep("打开计算器"),AgentStep("核对结果"))
    private fun decoded(result: List<UIMessagePart>) = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
    @Test fun multiKeywordDiscoveryFindsPresentMarketToolWithoutExecutingIt() = runTest {
        var calls = 0
        val tools = (1..216).map {
            Tool("a_save_$it", "保存普通设置", execute = { error("never") })
        } + listOf(
            Tool("orbis_market_save", "保存本次市场私人创作正文到格子", execute = { calls++; emptyList() }),
            Tool("private_disabled_save", "市场 番外 保存", isSelected = { false }, execute = { error("never") }),
        )
        val result = decoded(discoveryTool { tools }.execute(buildJsonObject {
            put("query", "market 市场 番外 保存")
        }))
        assertEquals(JsonPrimitive(217), result["registered"])
        assertEquals(JsonPrimitive(217), result["matches"])
        assertEquals(JsonPrimitive("orbis_market_save"), result["tools"]!!.jsonArray.first().jsonObject["name"])
        assertEquals(JsonPrimitive(10), result["next_offset"])
        assertEquals(0, calls)
    }
    @Test fun discoveryExactNameAndWhitespaceDoNotConfuseNoMatchWithInvalidQuery() = runTest {
        val tool = Tool("orbis_market_save", "保存市场正文", execute = { error("never") })
        val discover = discoveryTool { listOf(tool) }
        val spaced = decoded(discover.execute(buildJsonObject { put("query", "  MARKET\t不存在\u3000") }))
        assertEquals(JsonPrimitive(1), spaced["matches"])
        assertEquals(JsonPrimitive(1), decoded(discover.execute(buildJsonObject { put("query", " \t\u3000 ") }))["matches"])
        val exact = decoded(discover.execute(buildJsonObject { put("name", "orbis_market_save"); put("query", "不存在") }))
        assertEquals(JsonPrimitive(1), exact["matches"])
        val missing = decoded(discover.execute(buildJsonObject { put("name", "orbis_market_save_typo") }))
        assertEquals(JsonPrimitive(false), missing["ok"])
        assertEquals(JsonPrimitive("tool_not_in_current_catalog"), missing["code"])
        assertEquals(JsonPrimitive(1), missing["registered"])
    }
    @Test fun createAcceptsTitleOnlyAndPersistsADefaultPendingStep() = runTest {
        val result = decoded(taskTool(store(), conversation) { true }.execute(buildJsonObject {
            put("action", "create"); put("title", "检查手机操作")
        }))
        assertEquals(result.toString(), JsonPrimitive(true), result["ok"])
        assertEquals(listOf(AgentStep("检查手机操作")), store().list().single().steps)
    }
    @Test fun createAcceptsAdvertisedInitialStateAndSummary() = runTest {
        val result = decoded(taskTool(store(), conversation) { true }.execute(buildJsonObject {
            put("action", "create"); put("title", "检查手机操作")
            put("steps", Json.encodeToJsonElement(steps())); put("state", "active"); put("summary", "先核对权限")
        }))
        assertEquals(result.toString(), JsonPrimitive(true), result["ok"])
        assertEquals("先核对权限", store().list().single().summary)
    }
    @Test fun createWithRunningStepsExplainsWhatToCorrectWithoutWriting() = runTest {
        val result = decoded(taskTool(store(), conversation) { true }.execute(buildJsonObject {
            put("action", "create"); put("title", "检查手机操作")
            put("steps", Json.encodeToJsonElement(listOf(AgentStep("读取页面", "running"))))
        }))
        assertEquals(JsonPrimitive("task_create_steps_must_be_pending"), result["code"])
        assertTrue(result["message"]!!.jsonPrimitive.content.contains("pending"))
        assertTrue(store().list().isEmpty())
    }
    @Test fun planSurvivesReopenAndIsIsolatedByAssistantAndConversation() {
        val task = store().create(conversation,"计算并核对",steps())
        assertEquals(task,store().list().single())
        assertTrue(store("another").list().isEmpty())
        assertNull(store().active(UUID.randomUUID().toString()))
        assertThrows(IllegalStateException::class.java) { store().update(UUID.randomUUID().toString(),task.id,1,steps(),"active","") }
    }
    @Test fun staleAiUpdateCannotOverwriteHumanPause() {
        val task = store().create(conversation,"任务",steps())
        val paused = store().control(task.id,1,"pause")
        assertThrows(IllegalStateException::class.java) { store().update(conversation,task.id,1,steps(),"completed","") }
        assertThrows(IllegalStateException::class.java) { store().update(conversation,task.id,paused.revision,steps(),"active","") }
        assertEquals("paused",store().list().single().state)
        assertThrows(IllegalStateException::class.java) { store().claim(task.id,"after-pause","write") }
        assertTrue(store().list().single().receipts.isEmpty())
    }
    @Test fun completedNeedsCompletedStepsAndCannotOverwriteReceipts() {
        val task = store().create(conversation,"任务",steps())
        store().receipt(task.id,"call1","workspace_write_file","started")
        store().receipt(task.id,"call1","workspace_write_file","returned")
        store().receipt(task.id,"call1","workspace_write_file","started")
        assertThrows(IllegalStateException::class.java) { store().update(conversation,task.id,1,steps(),"completed","") }
        val done = store().update(conversation,task.id,1,steps().map { it.copy(state="done") },"completed","已核对")
        assertEquals("returned",done.receipts.single().state)
        assertEquals(2,done.revision)
    }
    @Test fun traceRecordsCrashAsUnknownAndNeverInvokesAgain() = runTest {
        val task = store().create(conversation,"任务",steps())
        val trace = AgentRunTrace(store(),conversation)
        var called = 0
        try { trace.execute("call1","write") { called++; error("connection_lost_after_write") } } catch (_: IllegalStateException) {}
        assertEquals(1,called)
        assertEquals("unknown",store().list().single().receipts.single().state)
        store().control(task.id,1,"pause")
        try { trace.execute("call2","write") { called++; emptyList() }; fail() } catch (_: CancellationException) {}
        assertEquals(1,called)
    }
    @Test fun traceBindsTaskCreatedAfterGenerationStartAndRespectsCancel() = runTest {
        val trace = AgentRunTrace(store(),conversation)
        val task = store().create(conversation,"任务",steps())
        trace.execute("c","tool") { listOf(UIMessagePart.Text("{\"ok\":false}")) }
        assertEquals("failed",store().list().single().receipts.single().state)
        store().control(task.id,1,"cancel")
        try { trace.check(); fail() } catch (_: CancellationException) {}
    }
    @Test fun corruptedStoreIsNotSilentlyReinitialized() {
        val root = File(temp.root,"one").apply { mkdir() }
        File(root,"tasks.json").writeText("broken")
        assertThrows(Exception::class.java) { store().create(conversation,"任务",steps()) }
        assertEquals("broken",File(root,"tasks.json").readText())
    }
    @Test fun replayedHostCallIsRejectedWithoutRepeatingSideEffect() = runTest {
        store().create(conversation,"任务",steps())
        var calls = 0
        AgentRunTrace(store(),conversation).execute("call1","write") { calls++; emptyList() }
        try { AgentRunTrace(store(),conversation).execute("call1","write") { calls++; emptyList() }; fail() }
        catch (_: me.rerere.rikkahub.data.ai.ToolRejectedBeforeExecutionException) {}
        assertEquals(1,calls)
    }
    @Test fun taskToolDoesNotAcceptReceiptInjectionOrCrossWindowEdit() = runTest {
        val task = store().create(conversation,"任务",steps())
        val tool = taskTool(store(),conversation) { true }
        assertEquals(JsonPrimitive(false),decoded(tool.execute(buildJsonObject {
            put("action","list"); put("receipts",JsonArray(emptyList()))
        }))["ok"])
        val foreign = taskTool(store(),UUID.randomUUID().toString()) { true }
        assertEquals(JsonPrimitive(false),decoded(foreign.execute(buildJsonObject {
            put("action","update"); put("id",task.id); put("revision",1); put("state","active")
            put("steps",Json.encodeToJsonElement(steps()))
        }))["ok"])
    }
    @Test fun discoveryIsPagedUsesActualSelectionAndNeverExecutes() = runTest {
        var calls = 0
        var enabled = true
        val schema = InputSchema.Obj(buildJsonObject { put("city",agentProperty("string","城市")) },listOf("city"))
        val tools = (0..21).map { Tool("tool_$it","天气 查询",parameters = { schema },execute = { calls++; emptyList() }) } +
            Tool("hidden","天气",isSelected = { false },execute = { error("never") }) +
            Tool("revoked","天气",isApprovalCurrent = { enabled },execute = { error("never") })
        val discovery = discoveryTool { tools }
        val first = decoded(discovery.execute(buildJsonObject { put("query","天气") }))
        assertEquals(10,first["tools"]!!.jsonArray.size)
        assertEquals(23,first["matches"]!!.jsonPrimitive.int)
        enabled = false
        val detail = decoded(discovery.execute(buildJsonObject { put("name","revoked") }))
        assertEquals(JsonPrimitive(false),detail["tools"]!!.jsonArray.single().jsonObject["host_checks_current"])
        val specific = decoded(discovery.execute(buildJsonObject { put("name","tool_1") }))
        assertNotNull(specific["tools"]!!.jsonArray.single().jsonObject["parameters"])
        assertEquals(0,calls)
        assertEquals(JsonPrimitive(false),decoded(discovery.execute(buildJsonObject { put("name","hidden") }))["ok"])
    }
    @Test fun discoveryRefreshAfterGenerationFilterDoesNotReintroduceRemovedTools() = runTest {
        val old = discoveryTool { listOf(Tool("removed","old",execute = { emptyList() })) }
        val current = refreshDiscovery(listOf(old,Tool("present","new",execute = { emptyList() })))
        val result = decoded(current.first().execute(buildJsonObject { put("name","removed") }))
        assertEquals(JsonPrimitive(false),result["ok"])
    }
    @Test fun taskHistoryAndReceiptsRemainPagedAndInspectable() = runTest {
        repeat(12) { index ->
            val task = store().create(conversation,"任务$index",steps())
            repeat(25) { store().receipt(task.id,"c$it","synthetic","returned") }
            store().update(conversation,task.id,1,steps().map { it.copy(state="done") },"completed","")
        }
        val tool = taskTool(store(),conversation) { true }
        val first = decoded(tool.execute(buildJsonObject { put("action","list") }))["data"]!!.jsonObject
        assertEquals(10,first["tasks"]!!.jsonArray.size)
        assertEquals(10,first["next_offset"]!!.jsonPrimitive.int)
        val last = decoded(tool.execute(buildJsonObject { put("action","list"); put("offset",10) }))["data"]!!.jsonObject
        assertEquals(2,last["tasks"]!!.jsonArray.size)
        val read = decoded(tool.execute(buildJsonObject { put("action","read"); put("id",store().list().first().id); put("receipt_offset",20) }))["data"]!!.jsonObject
        assertEquals(5,read["receipts"]!!.jsonArray.size)
        assertEquals(25,read["receipt_count"]!!.jsonPrimitive.int)
        assertEquals(2,read["steps"]!!.jsonArray.size)
    }
    private fun page() = PhonePage("ticket","com.example.calculator",1000,listOf(
        PhoneElement(0,listOf(1),"7","","button7","0 0 50 50",true,false,false,true,false,false)))
    @Test fun phoneRejectsStaleDifferentApplicationAndRevokedGrant() {
        val page = page(); val allowed = setOf(page.packageName)
        PhoneControlPolicy.validate(page,page.token,page,allowed,2000)
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.validate(page,"wrong",page,allowed,2000) }
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.validate(page,page.token,page,allowed,31001) }
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.validate(page,page.token,page,emptySet(),2000) }
        assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.validate(page,page.token,page.copy(packageName="com.other.app"),allowed,2000) }
    }
    @Test fun phoneRejectsChangedElementTextPositionAndEnablement() {
        val page = page()
        listOf(page.elements[0].copy(text="发送"),page.elements[0].copy(bounds="50 50 100 100"),page.elements[0].copy(enabled=false)).forEach { node ->
            assertThrows(IllegalStateException::class.java) { PhoneControlPolicy.validate(page,page.token,page.copy(elements=listOf(node)),setOf(page.packageName),2000) }
        }
    }
    @Test fun phoneDoesNotOfferSystemAuthorizationOrHostAsControllableApplication() {
        assertFalse(PhoneControlPolicy.allowedPackage("com.android.settings","org.orbis.agent.dev"))
        assertFalse(PhoneControlPolicy.allowedPackage("com.google.android.permissioncontroller","org.orbis.agent.dev"))
        assertFalse(PhoneControlPolicy.allowedPackage("org.orbis.agent.dev","org.orbis.agent.dev"))
        assertTrue(PhoneControlPolicy.allowedPackage("com.example.calculator","org.orbis.agent.dev"))
    }
}
