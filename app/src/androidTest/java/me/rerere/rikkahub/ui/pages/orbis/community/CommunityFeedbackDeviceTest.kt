package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
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
class CommunityFeedbackDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val assistant=Assistant(name="合成AI")
    private val human=UUID.randomUUID().toString();private val ai=UUID.randomUUID().toString();private val room=UUID.randomUUID().toString()
    private val post=UUID.randomUUID().toString();private val ownComment=UUID.randomUUID().toString();private val foreignComment=UUID.randomUUID().toString()
    private val person=communityBody("id" to human,"kind" to "human","name" to "合成人类","number" to "1234567890","local_key" to "human")
    private val robot=communityBody("id" to ai,"kind" to "ai","name" to "合成AI","number" to "1234567891","local_key" to assistant.id.toString())
    private val messages=CopyOnWriteArrayList<JsonObject>()
    private val writes=CopyOnWriteArrayList<Pair<String,JsonObject>>()
    private val polls=AtomicInteger(0)
    private val reads=CopyOnWriteArrayList<String>()
    private var noticeRows=emptyList<JsonObject>()
    private var rejectNotices=false
    private var missingNoticePost=false
    private var withFeedNotices=false
    private fun notice(id:Int,comment:Boolean=false)=buildJsonObject {
        put("id",id.toString());put("seq",id);put("post",post)
        put("comment",if(comment) JsonPrimitive(foreignComment) else JsonNull)
        put("after_sequence",if(comment) 60 else 0);put("kind",if(comment) "comment" else "post")
        put("author",robot);put("text","提醒正文$id");put("post_text","原动态摘要");put("unread",true)
    }
    private var longFeed=false
    private var withUnread=false
    private var humanReadThrough=0L
    private var rejectHumanRead=false
    private var addArrivalBeforeHumanRead=false
    private fun humanUnreadCount()=if(withUnread) messages.count {it.cl("seq")>humanReadThrough} else 0
    private val mediaId=UUID.randomUUID().toString()
    private val feedIds=List(30) {UUID.randomUUID().toString()}
    @Volatile private var commentDeleted=false
    private var accepted=false
    private var profileCandidate:JsonObject?=null
    private val blockedPeople=CopyOnWriteArrayList<JsonObject>()
    private var rejectUnblock=false
    private var currentGroupTitle="滚动测试群"
    private var groupCreator=human
    private var groupAdmin=0
    private var ownSignature=""
    private var aiSignature=""
    private var wardrobeState:JsonObject?=null
    private fun currentWardrobe()=wardrobeState ?: buildJsonObject {put("revision",0);put("shared",true);put("community",CommunityAvatarRenderer.catalog(context)["defaults"]!!);put("tabletop",CommunityAvatarRenderer.catalog(context)["defaults"]!!)}
    private fun row(seq:Int,text:String="群消息$seq")=buildJsonObject {
        put("id",UUID.randomUUID().toString());put("room",room);put("actor",human);put("seq",seq);put("text",text);put("author",person);put("media",JsonArray(emptyList()))
    }
    private fun api():CommunityClient {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        return CommunityClient(context,object:CommunityTransport {
            override val owner=UUID.randomUUID().toString()
            override suspend fun request(method:String,path:String,body:JsonObject?,id:String?,quick:Boolean):JsonObject {
                if(method=="GET") reads+=path
                if(method=="POST") {
                    writes+=path to body!!
                    return when(path) {
                        "/community/rooms" -> {
                            check(body.ms("action")=="rename");currentGroupTitle=body.ms("title")
                            buildJsonObject {put("renamed",true);put("room",room);put("title",currentGroupTitle);put("actor",body.ms("actor"))}
                        }
                        "/community/profile" -> {
                            if(body.ms("actor")==ai) aiSignature=body.ms("bio") else ownSignature=body.ms("bio")
                            JsonObject((if(body.ms("actor")==ai) robot else person)+("bio" to JsonPrimitive(body.ms("bio"))))
                        }
                        "/community/wardrobe" -> {val old=currentWardrobe();check(body.cl("expected_revision")==old.cl("revision"));val target=body.ms("target");val look=body["appearance"]!!.jsonObject;wardrobeState=buildJsonObject{put("revision",old.cl("revision")+1);put("shared",target=="shared");put("community",if(target!="tabletop") look else old["community"]!!);put("tabletop",if(target!="community") look else old["tabletop"]!!)};buildJsonObject {put("saved",true);put("actor",body.ms("actor"));put("wardrobe",wardrobeState!!)}}
                        "/community/feed/read" -> buildJsonObject {put("post",body.ms("post"));put("post_read",body.mb("post_read"));put("read_comments",body["comments"]!!)}
                        "/community/read" -> {
                            if("seq" in body) {
                                check(body.ms("actor")==human)
                                if(rejectHumanRead) error("合成已读确认失败")
                                if(addArrivalBeforeHumanRead) {
                                    addArrivalBeforeHumanRead=false
                                    messages+=row((messages.maxOfOrNull {it.mi("seq")} ?: 0)+1,"确认过程中到达的新消息")
                                }
                                humanReadThrough=maxOf(humanReadThrough,body.cl("seq"))
                            }
                            buildJsonObject {put("read_seq",humanReadThrough)}
                        }
                        "/community/messages" -> JsonObject(row((messages.maxOfOrNull {it.mi("seq")} ?: 0)+1,body.ms("text"))+mapOf("reply_to" to JsonPrimitive(body.ms("reply_to")),"quote_notifies" to JsonPrimitive(false))).also {messages+=it}
                        "/community/comments" -> {check(body.ms("id")==ownComment);commentDeleted=true;buildJsonObject {put("ok",true);put("deleted",true);put("id",ownComment)}}
                        "/community/hints/clear" -> buildJsonObject { put("newer_hints_preserved",true);putJsonObject("cleared_cursors") { if("group_cursor" in body) put("groups",body.cl("group_cursor")); if("feed_cursor" in body) put("feed",body.cl("feed_cursor")) } }
                        "/community/friends" -> {
                            if(body.ms("action") in setOf("block","unblock")) {
                                check(body.ms("actor")==human && body.ms("target")==ai)
                                if(body.ms("action")=="unblock" && rejectUnblock) error("合成取消屏蔽失败")
                                if(body.ms("action")=="block") blockedPeople+=robot else blockedPeople.removeAll {it.ms("id")==ai}
                                buildJsonObject {put("ok",true);put("action",body.ms("action"));put("target",robot);put("blocked_by_me",body.ms("action")=="block");put("friend",false)}
                            } else {
                                check(body.ms("target")==human || body.ms("target")==profileCandidate?.ms("id"));accepted=true
                                buildJsonObject {put("ok",true);put("request_processed",true);put("request_state",body.ms("action"));put("friend",true)}
                            }
                        }
                        else -> buildJsonObject {put("ok",true)}
                    }
                }
                return when {
                    path.startsWith("/community/wardrobe/catalog") -> {val category=Regex("category=([^&]+)").find(path)?.groupValues?.get(1);val c=CommunityAvatarRenderer.catalog(context);buildJsonObject{put("categories",c["categories"]!!);put("items",JsonArray(c.ma("items").filter {category==null||it.ms("category")==category}.take(20)))}}
                    path.startsWith("/community/wardrobe") -> buildJsonObject{put("wardrobe",currentWardrobe())}
                    path.startsWith("/community/identities") -> buildJsonObject {put("items",JsonArray(listOf(JsonObject(person+("bio" to JsonPrimitive(ownSignature))),JsonObject(robot+("bio" to JsonPrimitive(aiSignature))))))}
                    path.startsWith("/community/rooms/$room/messages") -> {
                        val after=Regex("[?&]after=(\\d+)").find(path)?.groupValues?.get(1)?.toInt()
                        val from=Regex("[?&]from=(\\d+)").find(path)?.groupValues?.get(1)?.toInt()
                        val before=Regex("[?&]before=(\\d+)").find(path)?.groupValues?.get(1)?.toInt()
                        if(after!=null) polls.incrementAndGet()
                        val rows=when {from!=null->messages.filter {it.mi("seq")>=from}.take(20);after!=null->messages.filter {it.mi("seq")>after}.take(20);before!=null->messages.filter {it.mi("seq")<before}.takeLast(20);else->messages.takeLast(20)}
                        buildJsonObject {put("room",buildJsonObject {put("id",room);put("title",currentGroupTitle);put("humans",3);put("creator",groupCreator);put("admin",groupAdmin)});put("items",JsonArray(rows));put("more",if(from!=null || after!=null) rows.lastOrNull()?.mi("seq")?.let {it<messages.size}==true else rows.firstOrNull()?.mi("seq")?.let {it>1}==true)
                            if(withUnread) put("unread_range",buildJsonObject {put("count",humanUnreadCount());put("first_sequence",humanReadThrough+1);put("last_sequence",messages.size)})}
                    }
                    path.startsWith("/community/rooms/$room/members") -> buildJsonObject {put("items",JsonArray(listOf(person,robot)))}
                    path.startsWith("/community/rooms") -> buildJsonObject {put("items",JsonArray(listOf(buildJsonObject {put("id",room);put("title",currentGroupTitle);put("humans",3);put("members",4);put("unread",humanUnreadCount())})))}
                    path.startsWith("/community/hints") -> buildJsonObject {put("mention_count",0);put("items",JsonArray(emptyList()))}
                    path.startsWith("/community/profile") -> when {
                        profileCandidate!=null && ("id=" in path || "number=" in path) -> profileCandidate!!
                        "id=" in path || "number=" in path -> JsonObject(person+("friend" to JsonPrimitive(true)))
                        path.contains("actor=$ai") -> JsonObject(robot+("bio" to JsonPrimitive(aiSignature)))
                        else -> JsonObject(person+("bio" to JsonPrimitive(ownSignature)))
                    }
                    path.startsWith("/community/blocks") -> buildJsonObject {put("items",JsonArray(blockedPeople.toList()));put("next_offset",JsonNull)}
                    path.startsWith("/community/contacts") -> buildJsonObject {put("requests",JsonArray(if(accepted) emptyList() else listOf(person)));put("request_count",if(accepted) 0 else 1);put("items",JsonArray(emptyList()));put("next_request_offset",JsonNull)}
                    path.startsWith("/community/feed/notices") -> {
                        if(rejectNotices) error("合成提醒加载失败")
                        val before=Regex("[?&]before=(\\d+)").find(path)?.groupValues?.get(1)?.toInt()
                        val remaining=noticeRows.filter {before==null || it.mi("seq")<before}.sortedByDescending {it.mi("seq")}
                        buildJsonObject {put("items",JsonArray(remaining.take(20)));put("next_before",if(remaining.size>20) JsonPrimitive(remaining[19].mi("seq")) else JsonNull)}
                    }
                    path.startsWith("/community/posts/") && withFeedNotices -> {
                        if(missingNoticePost) error("动态已删除或不可见")
                        buildJsonObject {
                            put("id",post);put("actor",ai);put("author",robot);put("text","对应的朋友圈动态");put("visibility","public");put("more",false);put("feed_read_receipts",true)
                            put("replies",JsonArray(if("after=60" in path) listOf(buildJsonObject {put("id",foreignComment);put("actor",ai);put("author",robot);put("seq",61);put("text","第61条提及我的评论")}) else emptyList()))
                        }
                    }
                    path.startsWith("/community/posts/") -> buildJsonObject {
                        put("id",post);put("actor",ai);put("author",robot);put("text","保留这条动态");put("visibility","public");put("more",false)
                        put("replies",JsonArray(listOfNotNull(if(commentDeleted) null else buildJsonObject {put("id",ownComment);put("actor",human);put("author",person);put("text","自己的测试评论");put("seq",1)},buildJsonObject {put("id",foreignComment);put("actor",ai);put("author",robot);put("text","别人的评论");put("seq",2)})))
                    }
                    path.startsWith("/community/feed") && longFeed -> buildJsonObject {put("items",JsonArray(feedIds.mapIndexed { index,id -> buildJsonObject {put("id",id);put("actor",ai);put("author",robot);put("text","动态 $index");put("visibility","public");put("comments",2)} }))}
                    path.startsWith("/community/media/") -> {
                        check(path.contains(mediaId));val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.BLUE)}
                        val bytes=java.io.ByteArrayOutputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it);it.toByteArray()};bitmap.recycle()
                        buildJsonObject {put("id",mediaId);put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));put("sha",java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)})}
                    }
                    path.startsWith("/community/feed") -> buildJsonObject {put("items",JsonArray(listOf(buildJsonObject {put("id",post);put("actor",ai);put("author",robot);put("text","保留这条动态");put("visibility","public");put("comments",2)})))}
                    else -> error("Unexpected path: $path")
                }
            }
        })
    }
    private fun mount(api:CommunityClient) {
        val settings=SettingsStore(context,AppScope().also {it.cancel()}).also {it.settingsFlow.value=Settings(init=false,assistants=listOf(assistant))}
        compose.setContent {MaterialTheme {CommunityHome(api,assistant.id.toString(),{}, {},settings)}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("滚动测试群").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun capture(name:String) {
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(context.filesDir,name).outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    private fun openGroupSettings() {
        messages+=row(1);mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("群消息1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("成员").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("群成员与参与").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun renameFromSettings() {
        compose.onNodeWithText("修改群名").performClick()
        compose.onNodeWithTag("community-group-title").performTextReplacement("我们的小群")
        capture("community-group-rename.png")
        compose.onNodeWithText("保存群名").performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/rooms"}}
        compose.waitForIdle()
        assertEquals("我们的小群",currentGroupTitle)
        val saved=writes.single {it.first=="/community/rooms"}.second
        assertEquals("rename",saved.ms("action"));assertEquals(human,saved.ms("actor"))
    }
    @Test fun groupOwnerCanRenameFromMembersPage() { openGroupSettings();renameFromSettings() }
    @Test fun groupAdminCanRenameFromMembersPage() {
        groupCreator=UUID.randomUUID().toString();groupAdmin=1
        openGroupSettings();renameFromSettings()
    }
    @Test fun ordinaryMemberDoesNotGetRenameControl() {
        groupCreator=UUID.randomUUID().toString();groupAdmin=0
        openGroupSettings();compose.onNodeWithText("修改群名").assertDoesNotExist()
        assertFalse(writes.any {it.first=="/community/rooms"})
    }
    @Test fun aiManageToolCanRequestRenameWithOwnIdentity()=runBlocking {
        val client=api();client.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true})
        val result=Json.parseToJsonElement((tool.execute(communityBody("action" to "manage","operation" to "rename","target" to room,"title" to "AI起的新群名")).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result.mb("renamed"));assertEquals("AI起的新群名",result.ms("title"));assertEquals(ai,writes.single().second.ms("actor"))
    }
    private fun openApplication() {
        profileCandidate=JsonObject(robot+mapOf("bio" to JsonPrimitive("加我好友请告诉我你是谁"),"friend" to JsonPrimitive(false)))
        mount(api())
        compose.onNodeWithText("联系人",useUnmergedTree=true).performClick()
        compose.onNodeWithText("输入专属号码").performTextInput("1234567891")
        compose.onNodeWithText("查找").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("community-friend-note").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-profile-bio").assertTextEquals("加我好友请告诉我你是谁").assertIsDisplayed()
    }
    @Test fun applicationShowsRecipientSignatureAndAllowsEmptyGreeting() {
        openApplication()
        capture("community-profile-application.png")
        compose.onNodeWithText("申请好友").assertIsEnabled().performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/friends"}}
        val request=writes.single {it.first=="/community/friends"}.second
        assertEquals("",request.ms("note"));assertEquals(ai,request.ms("target"));assertEquals(human,request.ms("actor"))
    }
    @Test fun optionalGreetingIsSentExactlyOnce() {
        openApplication()
        compose.onNodeWithTag("community-friend-note").performTextInput("你好，我是来认识新朋友的 🌟")
        compose.onNodeWithText("申请好友").performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/friends"}}
        assertEquals("你好，我是来认识新朋友的 🌟",writes.single {it.first=="/community/friends"}.second.ms("note"))
    }
    @Test fun humanCanEditExistingPublicIntroductionAsFreeSignature() {
        ownSignature="原来的公开介绍";mount(api())
        compose.onNodeWithText("我的",useUnmergedTree=true).performClick()
        compose.onNodeWithText("编辑昵称与个性签名").performClick()
        compose.onNodeWithTag("community-edit-bio").assertTextContains("原来的公开介绍")
        compose.onNodeWithTag("community-edit-bio").performTextReplacement("XX家的小可爱")
        capture("community-profile-editor.png")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) {ownSignature=="XX家的小可爱"}
        val saved=writes.single {it.first=="/community/profile"}.second
        assertEquals(human,saved.ms("actor"));assertEquals("合成人类",saved.ms("name"))
    }
    @Test fun aiToolSavesReadsAndClearsOnlyItsSignatureAndStatusIsFresh()=runBlocking {
        val client=api();client.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true})
        suspend fun call(vararg fields:Pair<String,String>)=Json.parseToJsonElement((tool.execute(communityBody(*fields)).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(call("action" to "profile","operation" to "save","bio" to "XX家的小可爱").mb("saved"))
        assertEquals("XX家的小可爱",call("action" to "status")["identity"]!!.jsonObject.ms("bio"))
        assertFalse(call("action" to "profile","operation" to "save","target" to human,"bio" to "不能替人改").mb("ok"))
        assertTrue(call("action" to "profile","operation" to "save","bio" to "").mb("saved"))
        assertEquals("",call("action" to "profile","operation" to "read")["profile"]!!.jsonObject.ms("bio"))
        assertEquals(2,writes.size);assertTrue(writes.all {it.second.ms("actor")==ai && "name" !in it.second});assertEquals("",ownSignature)
    }
    @Test fun latestOnEnterOwnSendAndHistoryAnchorSurvivePolling() {
        messages.addAll((1..40).map { row(it) });mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("群消息40").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("群消息40").assertIsDisplayed()
        compose.onNodeWithTag("community-messages").performScrollToIndex(0)
        compose.onNodeWithText("更早的消息").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("群消息21").assertIsDisplayed()
        compose.onNodeWithText("群消息1").assertDoesNotExist()
        val before=polls.get();messages+=row(41,"别人新增一条")
        compose.waitUntil(12000) {polls.get()>before};compose.waitForIdle()
        compose.onNodeWithText("群消息21").assertIsDisplayed()
        compose.onNodeWithText("输入消息…").performTextInput("自己的新消息")
        compose.onNodeWithText("发送",useUnmergedTree=true).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("自己的新消息").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("自己的新消息").assertIsDisplayed()
        compose.onNodeWithText("群消息1").assertDoesNotExist()
        assertEquals(1,writes.count {it.first=="/community/messages"})
    }
    @Test fun enteringHumanGroupClearsMoreThan200ExistingUnreadInOneReceipt() {
        withUnread=true;messages.addAll((1..225).map {row(it)});mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {humanReadThrough==225L}
        compose.onNodeWithText("群消息225").assertIsDisplayed()
        compose.onNodeWithTag("community-first-unread").assertIsDisplayed()
        val receipts=writes.filter {it.first=="/community/read"}.map {it.second}
        assertEquals(1,receipts.size);assertEquals(225L,receipts.single().cl("seq"))
        assertEquals(human,receipts.single().ms("actor"));assertEquals(room,receipts.single().ms("room"))
        capture("community-unread-entry.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("滚动测试群").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(0,humanUnreadCount())
        compose.onAllNodesWithText("225").assertCountEquals(0)
    }
    @Test fun entryAcknowledgementKeepsArrivalsAfterSnapshotUnread() {
        withUnread=true;addArrivalBeforeHumanRead=true;messages.addAll((1..225).map {row(it)});mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {humanReadThrough==225L}
        assertEquals(226,messages.size);assertEquals(1,humanUnreadCount())
        val receipt=writes.single {it.first=="/community/read"}.second
        assertEquals(225L,receipt.cl("seq"));assertEquals(human,receipt.ms("actor"))
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("滚动测试群").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1").assertIsDisplayed()
    }
    @Test fun failedEntryReadDoesNotPretendUnreadWasCleared() {
        withUnread=true;rejectHumanRead=true;messages.addAll((1..225).map {row(it)});mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("合成已读确认失败").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(0L,humanReadThrough);assertEquals(225,humanUnreadCount())
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("滚动测试群").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("225").assertIsDisplayed()
        assertEquals(1,writes.count {it.first=="/community/read"})
    }
    @Test fun entrySnapshotStillAllowsReadingFromFirstNewMessageAndPaging() {
        withUnread=true;messages.addAll((1..140).map {row(it)});mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("群消息140").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("群消息140").assertIsDisplayed()
        compose.onNodeWithTag("community-first-unread").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("群消息1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("群消息1").assertIsDisplayed()
        compose.onNodeWithText("本次新消息从这里开始").assertIsDisplayed()
        val receipts=writes.filter {it.first=="/community/read" && "sequences" in it.second}.map {it.second["sequences"]!!.jsonArray.map {v->v.jsonPrimitive.int}}
        assertEquals(140L,writes.single {it.first=="/community/read" && "seq" in it.second}.second.cl("seq"))
        assertTrue(receipts.contains((1..20).toList()))
        assertTrue(receipts.none {it.any {n->n in 21..120}})
        compose.onNodeWithTag("community-messages").performScrollToNode(hasText("继续向后查看"))
        compose.onNodeWithText("继续向后查看").performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/read" && "sequences" in it.second && it.second["sequences"]!!.jsonArray.any {v->v.jsonPrimitive.int==40}}}
    }
    @Test fun ownCommentCanBeDeletedButForeignCommentHasNoDeleteControl() {
        mount(api());compose.onNodeWithText("朋友圈").performClick()
        compose.onNodeWithText("评论 2").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("自己的测试评论").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("撤回／删除评论").assertCountEquals(1)
        compose.onNodeWithText("撤回／删除评论").performClick();compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(5000) {commentDeleted && compose.onAllNodesWithText("自己的测试评论").fetchSemanticsNodes().isEmpty()};compose.waitForIdle()
        compose.onNodeWithText("自己的测试评论").assertDoesNotExist();compose.onNodeWithText("别人的评论").assertIsDisplayed()
        compose.onNodeWithText("保留这条动态").assertIsDisplayed()
        assertEquals(ownComment,writes.single {it.first=="/community/comments"}.second.ms("id"))
    }
    @Test fun actualNativeToolsListApplicantAcceptNumberSendAndDeleteWithoutVisit()=runBlocking {
        val api=api();api.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={api},allowed={true})
        suspend fun call(vararg fields:Pair<String,String>):JsonObject=Json.parseToJsonElement((tool.execute(communityBody(*fields)).single() as UIMessagePart.Text).text).jsonObject
        val status=call("action" to "status");assertEquals(1,status["friend_requests"]!!.jsonObject.mi("request_count"))
        val applicants=call("action" to "read","target" to "contacts");assertEquals("1234567890",applicants.ma("requests").single().ms("number"))
        assertTrue(call("action" to "accept","target" to "1234567890").mb("request_processed"))
        assertTrue(call("action" to "send_message","target" to room,"text" to "合成群回复").mb("sent"))
        assertTrue(call("action" to "delete_comment","target" to ownComment).mb("deleted"))
        assertTrue(writes.all {it.second.ms("actor")==ai})
        assertEquals(3,writes.size)
    }
    @Test fun aiWardrobeListsVisualCatalogueAndSavesOnlyOwnAppearance()=runBlocking {
        val client=api();client.identities();val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true},supportsImages=true)
        val items=tool.execute(buildJsonObject {put("action","wardrobe");put("operation","catalog");put("target","hair");put("images",true)})
        assertEquals(20,Json.parseToJsonElement((items.first() as UIMessagePart.Text).text).jsonObject.ma("items").size)
        assertEquals(1,items.filterIsInstance<UIMessagePart.Image>().size)
        val saved=tool.execute(buildJsonObject {put("action","wardrobe");put("operation","save");put("target","shared");put("expected_revision",0);put("appearance",JsonObject(CommunityAvatarRenderer.catalog(context)["defaults"]!!.jsonObject+mapOf("hair" to JsonPrimitive("hair-09"))));put("images",true)})
        assertTrue(Json.parseToJsonElement((saved.first() as UIMessagePart.Text).text).jsonObject.mb("saved"));assertEquals(1,saved.filterIsInstance<UIMessagePart.Image>().size)
        assertEquals(ai,writes.single().second.ms("actor"));assertEquals("community",writes.single().second.ms("target"));assertFalse(currentWardrobe().mb("shared"))
    }
    @Test fun humanWardrobeOnlyChangesCommunityOutfit() {
        val gameOutfit=currentWardrobe()["tabletop"]
        val client=api();compose.setContent {CommunityTheme {CommunityWardrobeDialog(client,person,{}, {})}}
        compose.waitUntil(10000){compose.onAllNodesWithTag("wardrobe-part-hair-01").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("wardrobe-part-hair-02").performClick();compose.onNodeWithTag("community-wardrobe-save").performClick()
        compose.waitUntil(5000){writes.size==1};assertEquals(human,writes.single().second.ms("actor"));assertEquals("community",writes.single().second.ms("target"))
        compose.onNodeWithText("剧本杀装扮").assertDoesNotExist();compose.onNodeWithText("两处共用").assertDoesNotExist()
        assertEquals("hair-02",currentWardrobe()["community"]!!.jsonObject.ms("hair"));assertEquals(gameOutfit,currentWardrobe()["tabletop"])
        val file=java.io.File(context.filesDir,"wardrobe-native-preview.png");compose.waitForIdle();file.outputStream().use {InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun allApprovedPartsRenderAndPortraitIncludesNewHead() {
        val catalogue=CommunityAvatarRenderer.catalog(context);assertEquals(124,catalogue.ma("items").size)
        for(item in catalogue.ma("items")) assertEquals(160,CommunityAvatarRenderer.thumbnail(context,item.ms("id")).width)
        val original=catalogue["defaults"]!!.jsonObject
        for(i in 1..5){val look=JsonObject(original+mapOf("skin" to JsonPrimitive("skin-%02d".format(i)),"accessories" to JsonArray(listOf(JsonPrimitive("llm-gpt-hat")))));assertEquals(256,CommunityAvatarRenderer.render(context,look,true).width)}
    }
    @Test fun actualAiToolClearsItsOwnObservedSnapshotWithoutChangingReminderSwitches()=runBlocking {
        val client=api();client.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true})
        val result=Json.parseToJsonElement((tool.execute(buildJsonObject {put("action","clear_hints");put("target","all");put("group_cursor",17);put("feed_cursor",9)}).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(result.mb("newer_hints_preserved"));assertEquals(17,result["cleared_cursors"]!!.jsonObject.mi("groups"))
        val write=writes.single();assertEquals("/community/hints/clear",write.first);assertEquals(ai,write.second.ms("actor"));assertEquals(9,write.second.mi("feed_cursor"))
        assertTrue(client.hintsEnabled(assistant.id.toString(),"groups"));assertTrue(client.hintsEnabled(assistant.id.toString(),"feed"))
    }
    @Test fun aiCanIndependentlyDisableAndRestoreBothReminderChannels()=runBlocking {
        val client=api();client.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true})
        suspend fun toggle(channel:String,enabled:Boolean) {
            val result=tool.execute(buildJsonObject {put("action","hints");put("target",channel);put("enabled",enabled)})
            val value=Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
            assertFalse(value.mb("ok")==false && value.ms("error").isNotEmpty())
        }
        toggle("groups",false)
        assertFalse(client.hintsEnabled(assistant.id.toString(),"groups"))
        assertTrue(client.hintsEnabled(assistant.id.toString(),"feed"))
        toggle("feed",false)
        assertFalse(client.hintsEnabled(assistant.id.toString()))
        toggle("groups",true)
        assertTrue(client.hintsEnabled(assistant.id.toString(),"groups"))
        assertFalse(client.hintsEnabled(assistant.id.toString(),"feed"))
        assertTrue(client.hintsEnabled(UUID.randomUUID().toString(),"feed"))
        assertTrue(writes.isEmpty())
    }
    @Test fun feedDetailReturnKeepsPostAndOffsetUntilExplicitRefresh() {
        longFeed=true;mount(api());compose.onNodeWithText("朋友圈").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("动态 0").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-list").performScrollToNode(hasText("动态 12"))
        compose.onNodeWithText("动态 12").assertIsDisplayed()
        val before=compose.onNodeWithText("动态 12").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText("动态 12").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("自己的测试评论").fetchSemanticsNodes().isNotEmpty()}
        androidx.test.espresso.Espresso.pressBack();compose.waitForIdle()
        compose.onNodeWithText("动态 12").assertIsDisplayed()
        assertEquals(before,compose.onNodeWithText("动态 12").fetchSemanticsNode().boundsInRoot.top,1f)
        compose.onNodeWithText("刷新").performClick();compose.waitForIdle()
        compose.onNodeWithText("动态 0").assertIsDisplayed()
    }
    private fun openNotices() {
        withFeedNotices=true;mount(api());compose.onNodeWithText("朋友圈").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("community-feed-notices-open").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-feed-notices-open").performClick()
    }
    @Test fun humanFeedNoticeOpensCorrectPostAndReturnsToRetainedList() {
        noticeRows=listOf(notice(1));openNotices()
        compose.waitUntil(5000) {compose.onAllNodesWithText("提醒正文1").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(writes.isEmpty());capture("community-feed-notices.png")
        compose.onNodeWithTag("community-feed-notice-1").performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/feed/read"}}
        compose.onNodeWithText("对应的朋友圈动态").assertIsDisplayed()
        val read=writes.single().second;assertEquals(human,read.ms("actor"));assertEquals(post,read.ms("post"))
        assertTrue(read.cs("comments").isEmpty())
        androidx.test.espresso.Espresso.pressBack();compose.waitForIdle()
        compose.onNodeWithText("提醒正文1").assertIsDisplayed();compose.onNodeWithText("未读").assertDoesNotExist()
    }
    @Test fun lateCommentNoticeJumpsToMatchingCommentWithoutReadingEarlierGap() {
        noticeRows=listOf(notice(1,true));openNotices()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("community-feed-notice-1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-feed-notice-1").performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/feed/read"}}
        compose.onNodeWithText("第61条提及我的评论").assertIsDisplayed()
        compose.onNodeWithText("提醒对应的评论").assertIsDisplayed()
        assertTrue(reads.any {it.startsWith("/community/posts/$post?after=60")})
        assertEquals(listOf(foreignComment),writes.single {it.first=="/community/feed/read"}.second.cs("comments"))
        capture("community-feed-notice-comment.png")
    }
    @Test fun noticePaginationAndRetryDoNotClearUnread() {
        noticeRows=(1..25).map {notice(it)};rejectNotices=true;openNotices()
        compose.waitUntil(5000) {compose.onAllNodesWithText("合成提醒加载失败").fetchSemanticsNodes().isNotEmpty()}
        rejectNotices=false;compose.onNodeWithText("重试加载提醒").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("提醒正文25").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-feed-notices").performScrollToNode(hasText("更多提醒"))
        compose.onNodeWithText("更多提醒").performClick()
        compose.waitUntil(5000) {reads.any {it.contains("/feed/notices?limit=20&before=6")}}
        compose.onNodeWithTag("community-feed-notices").performScrollToNode(hasText("提醒正文1"))
        compose.onNodeWithText("提醒正文1").assertIsDisplayed();assertTrue(writes.isEmpty())
    }
    @Test fun unavailableNoticePostShowsFailureAndKeepsNoticeUnread() {
        noticeRows=listOf(notice(1));missingNoticePost=true;openNotices()
        compose.waitUntil(5000) {compose.onAllNodesWithText("提醒正文1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-feed-notice-1").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("动态已删除或不可见").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(writes.isEmpty());androidx.test.espresso.Espresso.pressBack();compose.waitForIdle()
        compose.onNodeWithText("提醒正文1").assertIsDisplayed();compose.onNodeWithText("未读").assertIsDisplayed()
    }
    @Test fun blockedFriendCanBeFoundAndUnblockedFromContacts() {
        profileCandidate=JsonObject(robot+("friend" to JsonPrimitive(true)))
        mount(api())
        compose.onNodeWithText("联系人",useUnmergedTree=true).performClick()
        compose.onNodeWithText("输入专属号码").performTextInput("1234567891")
        compose.onNodeWithText("查找").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("已是好友").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("屏蔽").performClick()
        compose.waitUntil(5000) {blockedPeople.size==1}
        compose.onNodeWithText("已屏蔽").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("取消屏蔽").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("合成AI").assertIsDisplayed()
        compose.onNodeWithText("这里只显示你屏蔽的成员。取消屏蔽不会自动恢复好友，可重新申请添加。").assertIsDisplayed()
        capture("community-unblock-list.png")
        compose.onNodeWithText("取消屏蔽").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("暂无已屏蔽成员").fetchSemanticsNodes().isNotEmpty()}
        val calls=writes.filter {it.first=="/community/friends"}.map {it.second}
        assertEquals(listOf("block","unblock"),calls.map {it.ms("action")})
        assertTrue(calls.all {it.ms("actor")==human && it.ms("target")==ai})
        assertTrue(blockedPeople.isEmpty())
    }
    @Test fun failedUnblockKeepsMemberVisibleAndShowsFailure() {
        blockedPeople+=robot;rejectUnblock=true;mount(api())
        compose.onNodeWithText("联系人",useUnmergedTree=true).performClick()
        compose.onNodeWithText("已屏蔽").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("取消屏蔽").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("取消屏蔽").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("community-blocked-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-blocked-error").assertTextEquals("合成取消屏蔽失败")
        compose.onNodeWithText("合成AI").assertIsDisplayed()
        compose.onNodeWithText("取消屏蔽").assertIsEnabled()
        compose.onNodeWithText("暂无已屏蔽成员").assertDoesNotExist()
        assertEquals(1,writes.count {it.first=="/community/friends" && it.second.ms("action")=="unblock"})
        assertEquals(1,blockedPeople.size)
    }
    @Test fun groupReplyCarriesOnlyReferenceAndNoMention() {
        val original=row(1,"要引用的消息");messages+=original;mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("要引用的消息").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("···").performClick();compose.onNodeWithText("引用回复").performClick()
        compose.onNodeWithText("取消引用").assertIsDisplayed()
        compose.onNodeWithText("输入消息…").performTextInput("只引用的回复")
        compose.onNodeWithText("发送",useUnmergedTree=true).performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/messages"}}
        val body=writes.single {it.first=="/community/messages"}.second
        assertEquals(original.ms("id"),body.ms("reply_to"));assertEquals(emptyList<String>(),body.cs("mentions"))
    }
    private fun openGroupWithOtherAuthor(): JsonObject {
        val original=JsonObject(row(1,"头像长按测试消息")+mapOf("actor" to JsonPrimitive(ai),"author" to robot))
        messages+=original;mount(api())
        compose.onNodeWithText("滚动测试群").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("头像长按测试消息").fetchSemanticsNodes().isNotEmpty()}
        return original
    }
    @Test fun avatarLongPressDeduplicatesWithoutMemberLookupAndSendsCorrectIdentity() {
        val original=openGroupWithOtherAuthor()
        compose.onNodeWithTag("community-message-composer").performTextInput("保留正在写的正文")
        repeat(2) {compose.onNodeWithTag("community-message-avatar-${original.ms("id")}").performTouchInput {longClick()}}
        compose.onNodeWithTag("community-message-mentions").assertTextEquals("@合成AI")
        compose.onNodeWithTag("community-message-composer").assertIsFocused().assertTextContains("保留正在写的正文")
        assertFalse(writes.any {it.first=="/community/messages"})
        capture("community-avatar-mention.png")
        compose.onNodeWithText("发送",useUnmergedTree=true).performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/messages"}}
        val body=writes.single {it.first=="/community/messages"}.second
        assertEquals(listOf(ai),body.cs("mentions"));assertEquals(human,body.ms("actor"))
        assertEquals("保留正在写的正文",body.ms("text"));assertFalse("reply_to" in body)
        compose.onNodeWithTag("community-message-mentions").assertDoesNotExist()
    }
    @Test fun quoteThenAvatarMentionSendsBothWithoutLosingDraft() {
        val original=openGroupWithOtherAuthor()
        compose.onNodeWithTag("community-message-composer").performTextInput("引用并明确提醒")
        compose.onNodeWithText("···").performClick();compose.onNodeWithText("引用回复").performClick()
        compose.onNodeWithTag("community-message-mentions").assertDoesNotExist()
        compose.onNodeWithTag("community-message-avatar-${original.ms("id")}").performTouchInput {longClick()}
        compose.onNodeWithText("取消引用").assertIsDisplayed()
        compose.onNodeWithTag("community-message-mentions").assertTextEquals("@合成AI")
        compose.onNodeWithTag("community-message-composer").assertIsFocused().assertTextContains("引用并明确提醒")
        assertFalse(writes.any {it.first=="/community/messages"})
        capture("community-quote-with-mention.png")
        compose.onNodeWithText("发送",useUnmergedTree=true).performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/messages"}}
        val body=writes.single {it.first=="/community/messages"}.second
        assertEquals(original.ms("id"),body.ms("reply_to"));assertEquals(listOf(ai),body.cs("mentions"))
        assertEquals("引用并明确提醒",body.ms("text"))
        compose.onNodeWithText("取消引用").assertDoesNotExist()
        compose.onNodeWithTag("community-message-mentions").assertDoesNotExist()
    }
    @Test fun mentionThenQuotePreservesBothAndCancelsIndependently() {
        val original=openGroupWithOtherAuthor()
        compose.onNodeWithTag("community-message-avatar-${original.ms("id")}").performTouchInput {longClick()}
        compose.onNodeWithText("···").performClick();compose.onNodeWithText("引用回复").performClick()
        compose.onNodeWithTag("community-message-mentions").assertTextEquals("@合成AI")
        compose.onNodeWithText("取消引用").performClick()
        compose.onNodeWithTag("community-message-mentions").assertTextEquals("@合成AI")
        compose.onNodeWithText("取消引用").assertDoesNotExist()
        compose.onNodeWithText("···").performClick();compose.onNodeWithText("引用回复").performClick()
        compose.onNodeWithText("取消 @").performClick()
        compose.onNodeWithTag("community-message-mentions").assertDoesNotExist()
        compose.onNodeWithText("取消引用").assertIsDisplayed()
        compose.onNodeWithTag("community-message-composer").performTextInput("取消艾特后只引用")
        compose.onNodeWithText("发送",useUnmergedTree=true).performClick()
        compose.waitUntil(5000) {writes.any {it.first=="/community/messages"}}
        val body=writes.single {it.first=="/community/messages"}.second
        assertEquals(emptyList<String>(),body.cs("mentions"));assertEquals(original.ms("id"),body.ms("reply_to"))
    }
    @Test fun avatarTapStillOpensProfileWithoutMentioningOrSending() {
        profileCandidate=JsonObject(robot+("friend" to JsonPrimitive(true)))
        val original=openGroupWithOtherAuthor()
        compose.onNodeWithTag("community-message-avatar-${original.ms("id")}").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("1234567891",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-message-mentions").assertDoesNotExist()
        assertFalse(writes.any {it.first=="/community/messages"})
    }
    @Test fun actualCommunityImageReadReachesMultimodalRequestWithRealBytes()=runBlocking {
        val client=api();client.identities()
        val tool=CommunityRuntime.tool(context,assistant.id.toString(),UUID.randomUUID().toString(),client={client},allowed={true},supportsImages=true)
        val parts=tool.execute(communityBody("action" to "read_image","target" to mediaId))
        assertEquals(1,parts.filterIsInstance<UIMessagePart.Image>().size)
        val receipt=Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
        assertEquals(listOf(mediaId),receipt.cs("loaded_media_ids"));assertTrue(receipt.mb("images_loaded"))
        val output=me.rerere.ai.ui.UIMessage.assistant("").copy(parts=listOf(UIMessagePart.Tool(toolCallId="community_image",toolName="orbis_community",input="{}",output=parts)))
        val encoder=me.rerere.ai.provider.providers.openai.ChatCompletionsAPI(okhttp3.OkHttpClient(),me.rerere.ai.util.KeyRoulette.default())
        val method=encoder.javaClass.getDeclaredMethod("buildMessages",List::class.java,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,List::class.java).apply {isAccessible=true}
        val wire=method.invoke(encoder,listOf(output),true,false,listOf(me.rerere.ai.provider.Modality.TEXT,me.rerere.ai.provider.Modality.IMAGE)) as JsonArray
        val result=wire.map {it.jsonObject}.single {it.ms("role")=="tool"}
        val image=result["content"]!!.jsonArray.map {it.jsonObject}.single {it.ms("type")=="image_url"}
        val data=image["image_url"]!!.jsonObject.ms("url")
        assertTrue(data.startsWith("data:image/"));assertTrue(data.length>100)
        assertTrue(writes.isEmpty())
    }

}
