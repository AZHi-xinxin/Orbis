package me.rerere.rikkahub.data.orbis.plugins

import android.app.Application
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.AppScope
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PluginSandboxDeviceTest {
    private class Fixture : AutoCloseable {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        init {
            check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
            check(context.packageName.endsWith(".bodylab") && context.applicationContext.javaClass==Application::class.java)
        }
        val scope=AppScope().also {it.cancel()}
        val assistant=Assistant(localTools=listOf(LocalToolOption.PersonalPlugins))
        val settings=SettingsStore(context,scope).also {it.settingsFlow.value=Settings(assistants=listOf(assistant),assistantId=assistant.id)}
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).addCallback(object: androidx.room.RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE message_fts (text TEXT,node_id TEXT,message_id TEXT,conversation_id TEXT,title TEXT,update_at TEXT)")
            }
        }).build()
        var ownsKoin=false
        val workspaceRoot=File(context.cacheDir,"plugin-test-${Uuid.random()}")
        val manager=WorkspaceManager(workspaceRoot)
        val workspaces=WorkspaceRepository(db.workspaceDao(),manager,RootfsInstaller(manager),settings)
        val host=PluginHost(context,assistant.id.toString(),settings,workspaces)
        fun body(code: String,permissions: List<String> = emptyList(),hosts: List<String> = emptyList())=
            PluginManifest("probe","隔离测试","只用于自动化测试","合成数据，测试结束后删除。",code,permissions=permissions,networkHosts=hosts)
        suspend fun install(body: PluginManifest,permissions: Set<String> = body.permissions.toSet()): PluginEntry {
            val e=host.repository.propose(body,null,0)
            host.approve(e.id,e.revision,1,permissions)
            return host.repository.snapshot().entries.single()
        }
        suspend fun run(e: PluginEntry,args: JsonElement=JsonObject(emptyMap()))=host.execute(e.id,e.active()!!.hash,args)
        suspend fun bindConversation(): PluginHost {
            check(org.koin.core.context.GlobalContext.getOrNull()==null)
            val files=me.rerere.rikkahub.data.files.FilesManager(context,me.rerere.rikkahub.data.repository.FilesRepository(db.managedFileDao()),scope)
            val repo=me.rerere.rikkahub.data.repository.ConversationRepository(db.conversationDao(),db.messageNodeDao(),db.favoriteDao(),db,files,me.rerere.rikkahub.data.db.fts.MessageFtsManager(db))
            val conversation=me.rerere.rikkahub.data.model.Conversation(assistantId=assistant.id,messageNodes=emptyList())
            repo.insertConversation(conversation)
            org.koin.core.context.startKoin { modules(org.koin.dsl.module {
                single {settings};single {workspaces};single {repo};single {me.rerere.rikkahub.data.orbis.memory.OrbisMemoryRepository(db)}
            }) };ownsKoin=true
            return PluginHost(context,assistant.id.toString(),settings,workspaces,conversation.id.toString())
        }
        override fun close() {
            if(ownsKoin) {
                pluginJobs(context,assistant.id.toString()).list().forEach { runCatching {PluginJobScheduler.cancel(context,assistant.id.toString(),it.id)} }
                org.koin.core.context.stopKoin()
            }
            File(context.noBackupFilesDir,"personal-plugin-jobs/${assistant.id}").deleteRecursively()
            host.repository.root.deleteRecursively()
            File(context.noBackupFilesDir,"personal-plugin-grants/${assistant.id}").deleteRecursively()
            workspaceRoot.deleteRecursively();db.close()
        }
    }

    @Test fun realQuickJsNamedToolReturnsResultAndCountsAfterSuccess()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("console.log('ready'); return {answer: 6 * 7, node: typeof require, dom: typeof document};"))
            val tools=personalPluginTools(f.host)
            val named=tools.single {it.name=="orbis_plugin_probe"}
            val text=(named.execute(JsonObject(emptyMap())).single() as me.rerere.ai.ui.UIMessagePart.Text).text
            val result=pluginJson.parseToJsonElement(text).jsonObject
            assertTrue(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            assertEquals(42,result["result"]!!.jsonObject["answer"]!!.jsonPrimitive.int)
            assertEquals("undefined",result["result"]!!.jsonObject["node"]!!.jsonPrimitive.content)
            assertEquals(1L,f.host.repository.snapshot().entries.single().totalUses)
            assertTrue(result["logs"]!!.jsonPrimitive.content.contains("ready"))
        }
    }
    @Test fun nativeSdkDeniesUncheckedPermissionAndRecordsNoDispatch()=runBlocking {
        Fixture().use { f ->
            val e=f.install(f.body("return orbis.notify.send('合成通知，不应发送');",listOf("notify")),emptySet())
            assertEquals("plugin_permission_denied",f.run(e)["error"]!!.jsonPrimitive.content)
            val call=f.host.repository.snapshot().entries.single().runs.single().calls.single()
            assertEquals("notify.send",call.method);assertEquals("not_started",call.outcome)
        }
    }
    @Test fun automaticCheckerReceivesDraftAndCannotUseGrantedWriteCapabilities()=runBlocking {
        Fixture().use { f ->
            val body=f.body("if(args.event==='before_response') return {pass:args.text.length>3,feedback:'字数不足'}; return {};",listOf("response.check")).copy(beforeResponse=true)
            val e=f.install(body)
            val result=f.host.execute(e.id,e.active()!!.hash,buildJsonObject {put("event","before_response");put("text","短")},true)
            assertFalse(pluginCheckVerdict(result,"短").passed)
            assertEquals("plugin_response_check_failed",f.host.repository.snapshot().entries.single().runs.single().error)
        }
        Fixture().use { f ->
            val e=f.install(f.body("orbis.files.write('should-not-exist.txt','x'); return {pass:true};",listOf("response.check","filesystem.write")).copy(beforeResponse=true))
            val result=f.host.execute(e.id,e.active()!!.hash,buildJsonObject {put("event","before_response");put("text","正文")},true)
            assertEquals("plugin_hook_read_only",result["error"]!!.jsonPrimitive.content)
            assertFalse(File(f.host.repository.root,"data/${e.id}/should-not-exist.txt").exists())
        }
    }
    @Test fun generationBuffersStreamingDraftUntilAutomaticPluginCheck()=runBlocking {
        Fixture().use {f ->
            f.bindConversation()
            f.install(f.body("return {pass:false,feedback:'合成检查：需要修改'};",listOf("response.check")).copy(beforeResponse=true))
            val response="data: {\"id\":\"synthetic\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"UNCHECKED_DRAFT\"}}]}\n\ndata: {\"id\":\"synthetic\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            PluginLoopbackServer(listOf(response)).use {server ->
                val model=me.rerere.ai.provider.Model(modelId="synthetic")
                val provider=me.rerere.ai.provider.ProviderSetting.OpenAI(models=listOf(model),baseUrl=server.url+"/v1",apiKey="")
                val assistant=f.assistant.copy(streamOutput=true)
                val settings=f.settings.settingsFlow.value.copy(providers=listOf(provider),assistants=listOf(assistant),chatModelId=model.id)
                f.settings.settingsFlow.value=settings
                val client=okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(10,java.util.concurrent.TimeUnit.SECONDS).build()
                try {
                    val loop=me.rerere.rikkahub.data.ai.GenerationLoop(f.context,me.rerere.ai.provider.ProviderManager(client,f.context),Json)
                    val chunks=loop.generateText(settings,model,listOf(me.rerere.ai.ui.UIMessage.user("合成测试")),assistant=assistant,
                        conversationId=Uuid.random(),maxSteps=1).toList()
                    val texts=chunks.filterIsInstance<me.rerere.rikkahub.data.ai.GenerationChunk.Messages>()
                        .map {it.messages.last().toText()}.filter {it.contains("UNCHECKED_DRAFT")}
                    assertTrue(texts.toString(),texts.isNotEmpty())
                    assertTrue(texts.toString(),texts.all {it.contains("待修改草稿") && it.contains("需要修改")})
                    server.verify()
                } finally {client.dispatcher.cancelAll();client.connectionPool.evictAll()}
            }
        }
    }

    @Test fun realBackgroundJobRunsApprovedScriptWithoutModelAndRecordsCompletion()=runBlocking {
        Fixture().use {f ->
            val bound=f.bindConversation()
            // Production initializes WorkManager via RikkaHubApp/Koin. The isolated Application deliberately does not.
            if(runCatching {androidx.work.WorkManager.getInstance(f.context)}.isFailure)
                androidx.work.WorkManager.initialize(f.context,androidx.work.Configuration.Builder().build())
            val e=f.install(f.body("if(args.mode==='check') {orbis.files.write('background-result.txt','done');return {done:true};} return orbis.jobs.add({due_at_ms:Date.now()+1500,arguments:{mode:'check'}});",listOf("background.run","filesystem.write")))
            val result=bound.execute(e.id,e.active()!!.hash,JsonObject(emptyMap()))
            assertTrue(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            assertTrue(result.toString(),result["result"]!!.jsonObject["scheduler_requested"]!!.jsonPrimitive.boolean)
            val jobId=result["result"]!!.jsonObject["job_id"]!!.jsonPrimitive.content
            withTimeout(30_000) {while(pluginJobs(f.context,f.assistant.id.toString()).list().first {it.id==jobId}.status!="completed") delay(100)}
            assertEquals("done",File(f.host.repository.root,"data/${e.id}/background-result.txt").readText())
            assertEquals(2L,f.host.repository.snapshot().entries.single().totalUses)
        }
    }
    @Test fun nativeMemoryAndScheduleUseBoundOwnerAndReturnRealReceipts()=runBlocking {
        Fixture().use {f ->
            val bound=f.bindConversation()
            val e=f.install(f.body("const s=orbis.schedule.list();const n=orbis.schedule.add({expected_revision:s.revision,entry:{kind:'date',date:'2030-01-01',title:'合成日程',all_day:true}});const d=orbis.schedule.delete({id:n.entry.id,expected_revision:n.revision});const m=orbis.memory.save('合成插件记忆');return {schedule:n,deleted:d,memory:m,found:orbis.memory.search('合成插件记忆')};",listOf("schedule.read","schedule.write","memory.read","memory.write")))
            val result=bound.execute(e.id,e.active()!!.hash,JsonObject(emptyMap()))
            assertTrue(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            val value=result["result"]!!.jsonObject
            assertFalse(value["schedule"]!!.jsonObject["reminder_scheduled"]!!.jsonPrimitive.boolean)
            assertTrue(value["memory"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
            assertTrue(value["found"]!!.toString().contains("合成插件记忆"))
        }
    }

    @Test fun infiniteLoopIsKilledThenFreshWorkerStillWorks()=runBlocking {
        Fixture().use {f ->
            var e=f.install(f.body("while(true) {}"))
            val start=SystemClock.elapsedRealtime();val result=f.run(e)
            assertFalse(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            assertEquals("plugin_timeout",result["error"]!!.jsonPrimitive.content)
            assertTrue("deadline ${SystemClock.elapsedRealtime()-start}",SystemClock.elapsedRealtime()-start<9_500)
            assertEquals(0L,f.host.repository.snapshot().entries.single().totalUses)
            e=f.host.repository.propose(f.body("return 'alive';"),e.id,e.revision)
            f.host.approve(e.id,e.revision,2,emptySet());e=f.host.repository.snapshot().entries.single()
            assertEquals("alive",f.run(e)["result"]!!.jsonPrimitive.content)
        }
    }
    @Test fun undeclaredNetworkFailsBeforeAnyNetworkRequest()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("return orbis.network.request({url:'https://example.invalid/never'});"))
            val result=f.run(e)
            assertFalse(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            assertEquals("plugin_permission_denied",result["error"]!!.jsonPrimitive.content)
            assertEquals(0L,f.host.repository.snapshot().entries.single().totalUses)
        }
    }
    @Test fun declaredButUncheckedPermissionAlsoFails()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("return orbis.files.write('test.txt','value');",listOf("filesystem.write")),emptySet())
            assertEquals("plugin_permission_denied",f.run(e)["error"]!!.jsonPrimitive.content)
            assertFalse(File(f.host.repository.root,"data/${e.id}/test.txt").exists())
        }
    }
    @Test fun approvedFilesPersistButTraversalIsRefused()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("orbis.files.write('note.txt','hello'); return orbis.files.read('note.txt');",listOf("filesystem.read","filesystem.write")))
            assertEquals("hello",f.run(e)["result"]!!.jsonPrimitive.content)
            val next=f.host.repository.propose(f.body("return orbis.files.read('../plugins.json');",listOf("filesystem.read")),e.id,e.revision)
            f.host.approve(e.id,next.revision,2,setOf("filesystem.read"))
            val result=f.run(f.host.repository.snapshot().entries.single())
            assertEquals("plugin_file_name_invalid",result["error"]!!.jsonPrimitive.content)
        }
    }
    @Test fun outputLimitAndScriptErrorsLeaveHonestFailedRecords()=runBlocking {
        Fixture().use {f ->
            var e=f.install(f.body("return 'x'.repeat(40000);"))
            assertEquals("plugin_output_limit",f.run(e)["error"]!!.jsonPrimitive.content)
            e=f.host.repository.propose(f.body("throw new Error('synthetic failure');"),e.id,e.revision)
            f.host.approve(e.id,e.revision,2,emptySet());e=f.host.repository.snapshot().entries.single()
            assertEquals("plugin_script_error",f.run(e)["error"]!!.jsonPrimitive.content)
            assertEquals(0L,f.host.repository.snapshot().entries.single().totalUses)
        }
    }
    @Test fun disabledOwnerHasNoCatalogueAndFrozenToolCannotRun()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("return 1;"));val tool=personalPluginTools(f.host).single {it.name=="orbis_plugin_probe"}
            f.settings.settingsFlow.value=f.settings.settingsFlow.value.copy(assistants=listOf(f.assistant.copy(localTools=emptyList())))
            assertTrue(personalPluginTools(f.host).isEmpty());assertFalse(tool.isSelected())
            val text=(tool.execute(JsonObject(emptyMap())).single() as me.rerere.ai.ui.UIMessagePart.Text).text
            assertTrue(text,text.contains("plugin_disabled"));assertTrue(f.host.repository.snapshot().entries.single().runs.isEmpty())
        }
    }
    @Test fun audioF0ExampleCanAnalyzeSyntheticSamplesWithoutExternalCapability()=runBlocking {
        Fixture().use {f ->
            val code="""
                const rate=8000, n=800, samples=Array.from({length:n},(_,i)=>Math.sin(2*Math.PI*200*i/rate));
                let best=-Infinity, lag=0;
                for(let k=20;k<=100;k++) {let a=0,b=0,c=0;for(let i=0;i<n-k;i++){a+=samples[i]*samples[i+k];b+=samples[i]**2;c+=samples[i+k]**2;}
                  let corr=a/Math.sqrt(b*c);if(corr>best+1e-9){best=corr;lag=k;}}
                return {hz:rate/lag,confidence:best};
            """.trimIndent()
            val e=f.install(f.body(code).copy(name="audio_f0",title="音高小尺"))
            val result=f.run(e)
            assertTrue(result.toString(),result["ok"]!!.jsonPrimitive.boolean)
            assertEquals(200.0,result["result"]!!.jsonObject["hz"]!!.jsonPrimitive.double,.1)
        }
    }
    @Test fun liveRevocationPreventsWriteAndDoesNotCountSuccess()=runBlocking {
        Fixture().use {f ->
            val e=f.install(f.body("const until=Date.now()+1200; while(Date.now()<until){}; return orbis.files.write('late.txt','blocked');",listOf("filesystem.write")))
            val pending=async {f.run(e)}
            withTimeout(3_000) {while(f.host.repository.snapshot().entries.single().runs.isEmpty()) delay(10)}
            f.host.setEnabled(e.id,e.revision,false)
            assertEquals("plugin_disabled",pending.await()["error"]!!.jsonPrimitive.content)
            assertFalse(File(f.host.repository.root,"data/${e.id}/late.txt").exists())
            assertEquals(0L,f.host.repository.snapshot().entries.single().totalUses)
        }
    }
    @Test fun cancellingBusyWorkerAllowsNextInvocationAndRecordsInterruption()=runBlocking {
        Fixture().use {f ->
            var e=f.install(f.body("while(true){}"))
            val pending=launch {f.run(e)}
            withTimeout(3_000) {while(f.host.repository.snapshot().entries.single().runs.isEmpty()) delay(10)}
            delay(300);pending.cancelAndJoin()
            assertEquals("cancelled",f.host.repository.snapshot().entries.single().runs.single().status)
            e=f.host.repository.propose(f.body("return 7;"),e.id,e.revision)
            f.host.approve(e.id,e.revision,2,emptySet())
            assertEquals(7,f.run(f.host.repository.snapshot().entries.single())["result"]!!.jsonPrimitive.int)
        }
    }
}
