package me.rerere.rikkahub.data.orbis.boundary

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.createBoundaryTool
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BoundaryHostDeviceTest {
    @Test fun threeRequestsAreDurableSharedAndNewPauseResetsQuota() = fixture { context, initial ->
        var host = initial
        host.grant("one", BOUNDARY_CATEGORIES)
        fun renew(scope: String) = host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "",
            allowRequest = true, until = System.currentTimeMillis()+120_000, now = System.currentTimeMillis(), scope = scope) }
        renew("assistant")
        val revision = host.pause("one", "chat")!!.revision
        repeat(3) { index ->
            host.claimRecovery("one", "chat-$index", revision)
            assertEquals(2-index, host.pause("one", "any-window")!!.requestsRemaining)
            if (index == 1) { stop(host); host = fresh(context) }
            else host.finishRecovery("one", "chat-$index", revision)
        }
        val spent = host.state("one")
        assertTrue(runCatching { host.claimRecovery("one", "new-window", revision) }.isFailure)
        assertEquals(spent, host.state("one"))
        renew("assistant")
        assertEquals(3, host.pause("one", "new-window")!!.requestsRemaining)
        renew("conversation")
        assertEquals(0, host.state("one").conversations.getValue("chat").requestCount)
    }

    @Test fun claimedWakeCanFinishAcrossExpiryButNotAnotherPauseOrOwner() = fixture { _, host ->
        host.grant("one", BOUNDARY_CATEGORIES)
        host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "",
            until=System.currentTimeMillis()+120_000, now=System.currentTimeMillis(), scope="assistant") }
        val permit = BoundaryWakePermit.capture(host.state("one"), "one", "chat", System.currentTimeMillis())
        host.requireInput("one", "chat", wake=permit)
        host.requestContext("one", "chat", wake=permit).check("m")
        assertTrue(runCatching { host.requireInput("one", "other", wake=permit) }.isFailure)
        assertTrue(runCatching { host.requireInput("one", "chat") }.isFailure)
        host.update("one") { it.copy(assistantPause=it.assistantPause!!.copy(until=System.currentTimeMillis()-1)) }
        host.requestContext("one", "chat", wake=permit).check("m")
        host.update("one") { changeBoundary(it,it.revision,"chat","pause","conversation","",
            until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis()) }
        assertTrue(runCatching { host.requireInput("one", "chat", wake=permit) }.isFailure)
        assertFalse(host.permitsWake(permit, "two", "chat"))
    }

    @Test fun completedRequestReleasesOnlyItsOwnClaimAcrossScopes() = fixture { _, host ->
        host.grant("one", BOUNDARY_CATEGORIES)
        fun pause(scope: String) = host.update("one") { changeBoundary(it, it.revision, "chat", "pause",
            "conversation", "", allowRequest = true, until = System.currentTimeMillis()+120_000,
            now = System.currentTimeMillis(), scope = scope) }
        pause("conversation")
        val original = host.pause("one", "chat")!!.revision
        host.claimRecovery("one", "chat", original)
        host.finishRecovery("one", "chat", original)
        assertFalse(host.pause("one", "chat")!!.requestUsed)
        host.claimRecovery("one", "chat", original)
        // A new global pause covers the old local pause while this request finishes.
        pause("assistant")
        val global = host.pause("one", "chat")!!
        host.claimRecovery("one", "another-chat", global.revision)
        host.finishRecovery("one", "chat", original)
        assertFalse(host.state("one").conversations.getValue("chat").requestUsed)
        assertTrue(host.pause("one", "another-chat")!!.requestUsed)
        host.finishRecovery("one", "another-chat", global.revision)
        assertFalse(host.pause("one", "chat")!!.requestUsed)
        host.claimRecovery("one", "new-chat", global.revision)
        assertTrue(runCatching { host.claimRecovery("one", "chat", global.revision) }.isFailure)
        // A replacement pause is not altered by completion of the old job.
        pause("assistant")
        val replacement = host.state("one")
        host.finishRecovery("one", "new-chat", global.revision)
        assertEquals(replacement, host.state("one"))
    }

    @Test fun processRestartReleasesPendingClaimWithoutReplayingOrExtendingPause() = fixture { context, host ->
        host.grant("one", BOUNDARY_CATEGORIES)
        host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "",
            allowRequest = true, until = System.currentTimeMillis()+120_000, now = System.currentTimeMillis(), scope = "assistant") }
        val pause = host.pause("one", "chat")!!
        host.claimRecovery("one", "chat", pause.revision)
        stop(host)
        val restored = fresh(context)
        assertEquals(pause.copy(requestCount = 1), restored.pause("one", "another-chat"))
        assertTrue(runCatching { restored.requireInput("one", "another-chat") }.isFailure)
        restored.claimRecovery("one", "another-chat", pause.revision)
        assertTrue(restored.pause("one", "chat")!!.requestUsed)
    }

    @Test fun globalExpiryRestoresOtherWindowsButKeepsIndependentLocalPause() = fixture { _, host -> runBlocking {
        host.grant("one", setOf("conversation"))
        val now = System.currentTimeMillis()
        host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "",
            until = now+120_000, now = now) }
        host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "",
            until = now+200, now = now-60_000, scope = "assistant") }
        withTimeout(3000) { host.states.first { it["one"]?.events?.lastOrNull()?.action == "timed_resume" } }
        assertNull(host.pause("one", "other-chat"))
        assertEquals("conversation", host.pause("one", "chat")!!.scope)
    } }
    @Test fun stateExplainsActualToolOriginAndErrorsReturnCurrentRevision() = fixture { _, host -> runBlocking {
        host.grant("one", BOUNDARY_CATEGORIES)
        val tool = createBoundaryTool(host, "one", "chat", catalog = { mapOf(
            "mcp" to mapOf("garden" to "Garden"),
            "tool" to mapOf("mcp__Garden__read" to "read", "cloud_reading_list" to "native cloud")) },
            bindingCurrent = { true }, toolSources = { mapOf("mcp__Garden__read" to "garden") })
        suspend fun invoke(vararg fields: Pair<String, JsonElement>) = Json.parseToJsonElement(
            (tool.execute(JsonObject(fields.toMap())).single() as UIMessagePart.Text).text).jsonObject
        val previous = host.state("one").revision
        invoke("action" to JsonPrimitive("disable"), "kind" to JsonPrimitive("mcp"), "id" to JsonPrimitive("garden"), "revision" to JsonPrimitive(previous))
        val response = invoke("action" to JsonPrimitive("state"), "kind" to JsonPrimitive("mcp"))
        val choices = response.getValue("choices").jsonObject
        assertTrue(choices.getValue("mcp").jsonArray.single().jsonObject.getValue("tool_ids").toString().contains("mcp__Garden__read"))
        val tools = invoke("action" to JsonPrimitive("state"), "kind" to JsonPrimitive("tool"))
            .getValue("choices").jsonObject.getValue("tool").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject }
        assertFalse(tools.getValue("mcp__Garden__read").getValue("effective_enabled").jsonPrimitive.boolean)
        assertTrue(tools.getValue("cloud_reading_list").getValue("effective_enabled").jsonPrimitive.boolean)
        assertEquals("native_cloud", tools.getValue("cloud_reading_list").getValue("source_kind").jsonPrimitive.content)
        val stale = invoke("action" to JsonPrimitive("enable"), "kind" to JsonPrimitive("mcp"), "id" to JsonPrimitive("garden"), "revision" to JsonPrimitive(previous))
        assertEquals("boundary_revision_changed", stale.getValue("code").jsonPrimitive.content)
        assertEquals(host.state("one").revision, stale.getValue("revision").jsonPrimitive.long)
        val wrong = invoke("action" to JsonPrimitive("select_model"))
        assertTrue(wrong.getValue("help").jsonPrimitive.content.contains("orbis_configure(action=select_model)"))
        assertFalse(host.permits("one", "mcp", "garden"))
    } }

    @Test fun pauseRequiresAnExplicitRequestChoiceAndReturnsACompactReceipt() = fixture { _, host -> runBlocking {
        host.grant("one", BOUNDARY_CATEGORIES)
        val tool = createBoundaryTool(host, "one", "chat", catalog = { error("Conversation control must not build a catalog") }, bindingCurrent = { true })
        suspend fun invoke(vararg fields: Pair<String, JsonElement>): JsonObject {
            val text = (tool.execute(JsonObject(fields.toMap())).single() as UIMessagePart.Text).text
            assertTrue(text.length < 2000)
            return Json.parseToJsonElement(text).jsonObject
        }
        val current = invoke("action" to JsonPrimitive("state"))
        assertFalse(current.containsKey("choices"))
        val revision = current.getValue("revision")
        assertEquals("boundary_revision_missing", invoke("action" to JsonPrimitive("pause"))
            .getValue("code").jsonPrimitive.content)
        assertEquals("boundary_allow_request_missing", invoke("action" to JsonPrimitive("pause"), "revision" to revision)
            .getValue("code").jsonPrimitive.content)
        assertNull(host.pause("one", "chat"))
        assertEquals(revision.jsonPrimitive.long, host.state("one").revision)
        val missingTime = invoke("action" to JsonPrimitive("pause"), "revision" to revision, "allow_request" to JsonPrimitive(true))
        assertEquals("boundary_deadline_missing", missingTime.getValue("code").jsonPrimitive.content)
        assertNull(host.pause("one", "chat"))
        val short = invoke("action" to JsonPrimitive("pause"), "revision" to revision, "allow_request" to JsonPrimitive(true), "until_epoch_ms" to JsonPrimitive(System.currentTimeMillis()+1000))
        assertEquals("boundary_deadline_out_of_range", short.getValue("code").jsonPrimitive.content)
        val open = invoke("action" to JsonPrimitive("pause"), "revision" to revision, "allow_request" to JsonPrimitive(true), "until_epoch_ms" to JsonPrimitive(System.currentTimeMillis()+120_000))
        assertTrue(open.getValue("ok").jsonPrimitive.boolean)
        assertTrue(open.getValue("conversation").jsonObject.getValue("allow_request").jsonPrimitive.boolean)
        assertTrue(host.pause("one", "chat")!!.allowRequest)
        assertFalse(open.containsKey("choices"))
    } }

    @Test fun oldHistoryIsTrimmedOnDiskWithoutChangingThePauseOrDisabledSettings() = fixture { context, _ ->
        val events = (1..30).map { BoundaryEvent(it.toLong(), "ai", "disable", "synthetic-$it") }
        val original = AssistantBoundary(grants = BOUNDARY_CATEGORIES, revision = 31,
            conversations = mapOf("chat" to ConversationBoundary(allowRequest = true, revision = 31, until = System.currentTimeMillis()+120_000, startedAt = System.currentTimeMillis())),
            mcps = mapOf("garden" to BoundaryChoice(enabled = false)), events = events)
        val file = File(context.filesDir, "orbis-boundaries/state.json")
        file.parentFile!!.mkdirs()
        file.writeText(Json.encodeToString(mapOf("one" to original)))
        val restored = fresh(context).state("one")
        assertEquals(original.copy(events = events.takeLast(5)), restored)
        assertEquals(restored, Json.decodeFromString<Map<String, AssistantBoundary>>(file.readText()).getValue("one"))
        assertEquals(restored, fresh(context).state("one"))
    }

    @Test fun ledgerSurvivesRecreationAndHumanResetIsDistinct() = fixture { context, host ->
        host.grant("one", BOUNDARY_CATEGORIES)
        host.update("one") { changeBoundary(it, it.revision, "chat", "pause", "conversation", "", reason="synthetic pause", until=System.currentTimeMillis()+120_000, now=System.currentTimeMillis()) }
        val restored = fresh(context)
        assertNotNull(restored.pause("one", "chat"))
        assertNull(restored.pause("two", "chat"))
        restored.reset("one", "chat")
        assertNull(restored.pause("one", "chat"))
        assertEquals("human", restored.state("one").events.last().actor)
        assertEquals("reset", restored.state("one").events.last().action)
    }
    @Test fun oneRecoveryPermitDoesNotOpenOrdinaryInputAndExpiresWithRevision() = fixture { _, host ->
        host.grant("one", setOf("conversation", "model"))
        host.update("one") { changeBoundary(it,it.revision,"chat","pause","conversation","",allowRequest=true,until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis()) }
        val revision = host.pause("one","chat")!!.revision
        host.claimRecovery("one","chat",revision)
        assertTrue(runCatching { host.claimRecovery("one","chat",revision) }.isFailure)
        assertTrue(runCatching { host.requestContext("one","chat").check("m") }.isFailure)
        host.requestContext("one","chat",revision).check("m")
        host.update("one") { changeBoundary(it,it.revision,"chat","pause","conversation","",until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis()) }
        assertTrue(runCatching { host.requestContext("one","chat",revision).check("m") }.isFailure)
    }
    @Test fun pauseDeadlineRestoresInputButDoesNotInventNewModelRequest() = fixture { _, host -> runBlocking {
        host.grant("one",setOf("conversation"))
        val now=System.currentTimeMillis()
        host.update("one") { changeBoundary(it,it.revision,"chat","pause","conversation","",until=now+200,now=now-60_000) }
        withTimeout(3000) { host.states.first { it["one"]?.events?.lastOrNull()?.action=="timed_resume" } }
        assertNull(host.pause("one","chat"))
    } }
    @Test fun toolCannotWriteAnotherOwnerAndChoiceNeedsNoSecondApproval() = fixture { _, host -> runBlocking {
        host.grant("one",BOUNDARY_CATEGORIES)
        var binding=true
        val tool=createBoundaryTool(host,"one","chat",{mapOf("model" to mapOf("m" to "Synthetic"))},{true},{binding})
        fun args()=buildJsonObject { put("action","disable");put("kind","model");put("id","m");put("revision",host.state("one").revision) }
        assertFalse(tool.needsApproval(args()))
        binding=false
        assertTrue(tool.execute(args()).toString().contains("boundary_owner_changed"))
        assertTrue(host.permits("one","model","m"))
        binding=true; tool.execute(args())
        assertFalse(host.permits("one","model","m"));assertTrue(host.permits("two","model","m"))
    } }
    @Test fun actualProviderEntryGatesNonstreamStreamAndCapturedAuxiliaryOwners() = fixture { context, host -> runBlocking {
        val client=OkHttpClient();val manager=ProviderManager(client,context)
        var calls=0
        manager.registerProvider("openai",object:Provider<ProviderSetting.OpenAI> {
            override suspend fun listModels(providerSetting:ProviderSetting.OpenAI)=emptyList<Model>()
            override suspend fun generateText(providerSetting:ProviderSetting.OpenAI,messages:List<UIMessage>,params:TextGenerationParams):TextGenerationResult {
                calls++;return TextGenerationResult("synthetic","synthetic",UIMessage.assistant("synthetic"))
            }
            override suspend fun streamText(providerSetting:ProviderSetting.OpenAI,messages:List<UIMessage>,params:TextGenerationParams)=flow<StreamChunk> { calls++ }
        })
        val setting=ProviderSetting.OpenAI();val model=Model(modelId="synthetic")
        val provider=manager.getProviderByType(setting)
        val bound=manager.getProviderByType(setting,host.requestContext("one","chat").check)
        host.grant("one",BOUNDARY_CATEGORIES)
        provider.generateText(setting,emptyList(),TextGenerationParams(model)); assertEquals(1,calls)
        val beforeDisable=bound.streamText(setting,emptyList(),TextGenerationParams(model))
        host.update("one") { changeBoundary(it,it.revision,"chat","disable","model",model.id.toString(),now=1) }
        assertTrue(runCatching { beforeDisable.toList() }.isFailure)
        assertTrue(runCatching { withContext(host.requestContext("one","chat")) { provider.generateText(setting,emptyList(),TextGenerationParams(model)) } }.isFailure)
        assertEquals(1,calls)
        withContext(host.requestContext("two","chat")) { provider.streamText(setting,emptyList(),TextGenerationParams(model)).toList() }
        assertEquals(2,calls)
        host.reset("one")
        bound.streamText(setting,emptyList(),TextGenerationParams(model)).toList()
        assertEquals(3,calls)
        client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll()
    } }
    private val hosts = mutableListOf<BoundaryHost>()
    private fun fresh(context:Context)=BoundaryHost::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }.newInstance(context).also { hosts += it }
    private fun stop(host: BoundaryHost) {
        (BoundaryHost::class.java.getDeclaredField("timer").apply { isAccessible=true }.get(host) as java.util.concurrent.ScheduledExecutorService).shutdownNow()
    }
    private fun fixture(block:(Context,BoundaryHost)->Unit) {
        val inst=InstrumentationRegistry.getInstrumentation();check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.packageName.endsWith(".bodylab"))
        val dir=Files.createTempDirectory(inst.targetContext.cacheDir.toPath(),"boundary-fixture-").toFile()
        val context=object:ContextWrapper(inst.targetContext) { override fun getFilesDir()=dir;override fun getApplicationContext():Context=this }
        try { block(context,fresh(context)) } finally { hosts.forEach(::stop); hosts.clear(); dir.deleteRecursively() }
    }
}
