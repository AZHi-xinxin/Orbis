package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val assistant=Assistant(name="合成AI")
    private val human=UUID.randomUUID().toString(); private val ai=UUID.randomUUID().toString(); private val rid=UUID.randomUUID().toString()
    private val actor=buildJsonObject { put("id",human); put("kind","human"); put("name","合成人类"); put("number","1234567890"); put("local_key","human"); put("bio","") }
    private val media=List(2) { UUID.randomUUID().toString() }
    private val aiActor=buildJsonObject { put("id",ai); put("kind","ai"); put("name","合成AI"); put("number","1234567891"); put("local_key",assistant.id.toString()); put("bio","") }
    private class Transport(override val owner: String=UUID.randomUUID().toString(),val handle:suspend (String,String,JsonObject?,String?)->JsonObject): CommunityTransport {
        override suspend fun request(method: String,path: String,body: JsonObject?,id: String?,quick: Boolean)=handle(method,path,body,id)
    }
    private fun mount(): Pair<CommunityClient,MutableList<Pair<String,JsonObject?>>> {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val calls=java.util.Collections.synchronizedList(mutableListOf<Pair<String,JsonObject?>>())
        val api=CommunityClient(context,Transport { method,path,body,_ ->
            calls+=path to body
            when {
                method=="POST" -> buildJsonObject { put("ok",true) }
                path.startsWith("/community/identities") -> buildJsonObject { put("items",JsonArray(listOf(actor,aiActor))) }
                path.startsWith("/community/rooms/$rid/members") -> buildJsonObject { put("items",JsonArray(listOf(actor,aiActor))); put("next_offset",JsonNull) }
                path.startsWith("/community/rooms/$rid/messages") -> buildJsonObject {
                    put("room",buildJsonObject { put("id",rid); put("title","合成三人群"); put("creator",human); put("humans",3); put("muted",0); put("admin",0) })
                    put("items",JsonArray(listOf(buildJsonObject { put("id","message1"); put("room",rid); put("actor",human); put("seq",1); put("text","合成群消息"); put("author",actor); put("media",JsonArray(emptyList())) }))); put("more",false)
                }
                path.startsWith("/community/rooms") -> buildJsonObject { put("items",JsonArray(listOf(buildJsonObject { put("id",rid); put("title","合成三人群"); put("members",4); put("humans",3); put("unread",1) }))); put("next_offset",JsonNull) }
                path.startsWith("/community/feed") -> buildJsonObject { put("items",JsonArray(listOf(buildJsonObject { put("id","post1"); put("text","只有一个朋友圈信息流"); put("actor",human); put("author",aiActor); put("created",System.currentTimeMillis()/1000-600); put("visibility","public"); put("seq",1); put("media",JsonArray(media.map(::JsonPrimitive))) }))); put("next_before",JsonNull) }
                path.startsWith("/community/contacts") -> buildJsonObject { put("items",JsonArray(listOf(aiActor))); put("requests",JsonArray(emptyList())); put("next_offset",JsonNull) }
                path.startsWith("/community/hints") -> buildJsonObject { put("mention_count",9); put("items",JsonArray(listOf(buildJsonObject { put("room",rid); put("count",9); put("cursor",12) }))) }
                path.startsWith("/community/profile") -> actor
                else -> error("Unexpected $path")
            }
        })
        // Synthetic local preview copies of the same artwork used in the approved design.
        media.zip(listOf(me.rerere.rikkahub.R.drawable.market_cover_cafe,me.rerere.rikkahub.R.drawable.market_cover_letter)).forEach { (id,res) ->
            val bitmap=android.graphics.BitmapFactory.decodeResource(context.resources,res)
            java.io.File(context.filesDir,"orbis-community/${api.owner}/$human/$id.jpg").also { it.parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,85,it) }; bitmap.recycle()
        }
        val avatarFile=java.io.File(context.cacheDir,"synthetic-community-avatar.png")
        context.resources.openRawResource(me.rerere.rikkahub.R.drawable.market_cover_cafe).use { source -> avatarFile.outputStream().use { source.copyTo(it) } }
        val settings=SettingsStore(context,AppScope().also { it.cancel() }).also { it.settingsFlow.value=Settings(init=false,assistants=listOf(assistant.copy(avatar=me.rerere.rikkahub.data.model.Avatar.Image(avatarFile.toURI().toString())))) }
        compose.setContent { MaterialTheme(colorScheme=androidx.compose.material3.darkColorScheme()) { CommunityStatusBar(); CommunityHome(api,assistant.id.toString(),{}, {},settings) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("合成三人群").fetchSemanticsNodes().isNotEmpty() }
        return api to calls
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.getExternalFilesDir(null),"community-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
    }
    @Test fun fourTabsDoNotReuseRowsAndThereIsNoDirectChatEntry() {
        mount(); capture("chat")
        compose.onNodeWithText("朋友圈").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("只有一个朋友圈信息流").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("社区图片").fetchSemanticsNodes().size==2 }
        Thread.sleep(250)
        capture("feed")
        compose.onNodeWithText("合成三人群").assertDoesNotExist()
        compose.onNodeWithText("联系人").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("输入专属号码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("只有一个朋友圈信息流").assertDoesNotExist()
        capture("contacts")
        compose.onNodeWithText("私聊",substring=true).assertDoesNotExist()
        compose.onNodeWithText("我的").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("我的名片").fetchSemanticsNodes().isNotEmpty() }
        capture("me")
        compose.onNodeWithText("聊天").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("合成三人群").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun exitGroupRequiresNoOwnerApprovalAndUsesCurrentActor() {
        val (_,calls)=mount()
        compose.onNodeWithText("合成三人群").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("合成群消息").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("成员").performClick()
        compose.onNodeWithText("退出群聊").performClick()
        compose.waitUntil(5000) { calls.any { it.first=="/community/rooms" && it.second?.ms("action")=="leave" } }
        assertEquals(human,calls.last { it.second?.ms("action")=="leave" }.second?.ms("actor"))
    }
    @Test fun clearCounterDoesNotDeleteOrMarkMessagesRead() {
        val (_,calls)=mount()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("清空群聊艾特提示（保留群消息）").performScrollTo().performClick()
        compose.waitUntil(5000) { calls.any { it.first=="/community/hints/clear" } }
        assertEquals(12,calls.last { it.first=="/community/hints/clear" }.second!!.mi("cursor"))
        assertFalse(calls.any { it.first=="/community/read" || it.second?.ms("action")=="remove" })
    }
    @Test fun durableOutboxRetriesSameIdAfterAmbiguousFailure()=runBlocking {
        val ids=mutableListOf<String>(); var simulateTimeout=true
        val api=CommunityClient(context,Transport { method,path,_,id ->
            if(method=="GET") buildJsonObject { put("found",false) }
            else { ids+=id!!; if(simulateTimeout) { simulateTimeout=false; throw IOException("synthetic timeout") }; buildJsonObject { put("id","same-publication") } }
        })
        try { api.post("/posts",human,communityBody("text" to "合成内容")); fail("Expected timeout") } catch(_:IOException) { }
        assertTrue(api.pending(human))
        try { api.post("/posts",human,communityBody("text" to "重复内容")); fail("Should block second id") } catch(_:IllegalStateException) { }
        assertEquals("same-publication",api.reconcile(human).ms("id"))
        assertEquals(2,ids.size); assertEquals(ids[0],ids[1]); assertFalse(api.pending(human))
    }
    @Test fun aiReminderPreferenceIndependentOfAbilityAndDurable()=runBlocking {
        val transport=Transport { _,_,_,_ -> buildJsonObject {} }
        val api=CommunityClient(context,transport)
        assertTrue(api.hintsEnabled(assistant.id.toString()))
        api.setHintsEnabled(assistant.id.toString(),false)
        assertFalse(CommunityClient(context,transport).hintsEnabled(assistant.id.toString()))
        api.post("/rooms",ai,communityBody("action" to "leave","room" to rid))
        api.setHintsEnabled(assistant.id.toString(),true)
        assertTrue(api.hintsEnabled(assistant.id.toString()))
    }
    @Test fun pendingPublicationCannotTrapMemberInGroup()=runBlocking {
        val calls=mutableListOf<String>()
        val api=CommunityClient(context,Transport { _,path,_,_ ->
            calls+=path
            if(path=="/community/posts") throw IOException("unknown publication result")
            buildJsonObject { put("ok",true) }
        })
        try { api.post("/posts",ai,communityBody("text" to "合成内容")); fail("Expected timeout") } catch(_:IOException) { }
        api.post("/rooms",ai,communityBody("action" to "leave","room" to rid))
        assertTrue(calls.contains("/community/rooms")); assertTrue(api.pending(ai))
    }

    @Test fun actualCommunityToolPublishesTextWithoutQueuingAndRejectsForeignActor()=runBlocking {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val writes=mutableListOf<Pair<String,JsonObject>>()
        val api=CommunityClient(context,Transport { method,path,body,_ ->
            if(method=="GET") buildJsonObject { put("items",JsonArray(listOf(actor,aiActor))) }
            else { writes+=path to body!!; buildJsonObject { put("id",UUID.randomUUID().toString()) } }
        })
        api.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={ api },allowed={ true })
        val result=tool.execute(communityBody("action" to "publish","text" to "合成AI主动发表","visibility" to "public"))
        val receipt=Json.parseToJsonElement((result.single() as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject
        assertTrue(receipt.mb("published")); assertEquals("community",receipt.ms("scope"))
        assertEquals("/community/posts",writes.single().first); assertEquals(ai,writes.single().second.ms("actor"))
        val denied=tool.execute(communityBody("action" to "publish","text" to "不能冒充人类","visibility" to "public","actor" to human))
        assertFalse(Json.parseToJsonElement((denied.single() as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject.mb("ok"))
        assertEquals(1,writes.size)
        val disabled=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={ error("Disabled tool must not open client") },allowed={ false })
        val off=disabled.execute(communityBody("action" to "publish","text" to "不可发表","visibility" to "public"))
        assertFalse(Json.parseToJsonElement((off.single() as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject.mb("ok"))
    }

    @Test fun actualCommunityToolReadsPostCommentsForExplicitAndLegacyCalls()=runBlocking {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val pid=UUID.randomUUID().toString();val gets=mutableListOf<String>();val writes=mutableListOf<String>()
        val api=CommunityClient(context,Transport { method,path,_,_ ->
            if(method!="GET") { writes+=path; error("Post reads must not write") }
            gets+=path
            when {
                path=="/community/identities" -> buildJsonObject {put("items",JsonArray(listOf(actor,aiActor)))}
                path.startsWith("/community/rooms/$pid/") -> throw MarketProblem(403,communityBody("error" to "room_unavailable","message" to "你已不在这个聊天中"))
                path.startsWith("/community/posts/$pid?") -> {
                    check(path.endsWith("actor=$ai"))
                    buildJsonObject {put("id",pid);put("text","合成朋友圈");put("comments",1);put("more",false)
                        put("replies",JsonArray(listOf(buildJsonObject {put("seq",7);put("text","合成评论正文");put("post",pid)})))}
                }
                else -> error("Unexpected $path")
            }
        })
        api.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={api},allowed={true})
        for(action in listOf("read_post","read")) {
            val result=tool.execute(communityBody("action" to action,"target" to pid))
            val body=Json.parseToJsonElement((result.single() as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject
            assertTrue(body.mb("ok"));assertEquals("合成评论正文",body.ma("replies").single().ms("text"));assertEquals("post",body.ms("target_kind"))
        }
        assertTrue(writes.isEmpty());assertEquals(2,gets.count {it.startsWith("/community/posts/")})
        val denied=tool.execute(communityBody("action" to "read_post","target" to pid,"actor" to human))
        assertFalse(Json.parseToJsonElement((denied.single() as me.rerere.ai.ui.UIMessagePart.Text).text).jsonObject.mb("ok"))
        assertEquals(2,gets.count {it.startsWith("/community/posts/")})
    }

    @Test fun membersAndComposerMatchLightPageLayoutEvenUnderDarkOuterTheme() {
        mount()
        compose.onNodeWithText("合成三人群").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("合成群消息").fetchSemanticsNodes().isNotEmpty() }
        capture("conversation")
        compose.onNodeWithText("成员").performClick()
        compose.onNodeWithText("我的 AI 如何参与").assertExists()
        compose.onNodeWithText("群成员与参与").assertIsDisplayed()
        capture("members")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("朋友圈").performClick()
        compose.onNodeWithText("＋ 发布").performClick()
        compose.onNodeWithText("谁可以看见").assertExists()
        compose.onNodeWithText("以 合成人类 的身份发布").assertExists()
        capture("composer")
    }

}
