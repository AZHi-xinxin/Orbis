package me.rerere.rikkahub.ui.pages.orbis.community

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.community.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GatheringDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val human=UUID.randomUUID().toString();private val ai=UUID.randomUUID().toString();private val local=UUID.randomUUID().toString()
    private val identities=listOf(buildJsonObject {put("id",human);put("name","合成人类");put("kind","human")},buildJsonObject {put("id",ai);put("name","合成星河");put("kind","ai");put("local_key",local)})
    private class Transport(val call:suspend (String,String,JsonObject?)->JsonObject):GatheringTransport {
        override val owner=UUID.randomUUID().toString()
        override suspend fun request(method:String,path:String,body:JsonObject?)=call(method,path,body)
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        // Capture the dialog and activity together; Compose's multi-window redraw can time out.
        val bmp=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        java.io.File(context.getExternalFilesDir(null),"gathering-$name.png").outputStream().use {bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()
    }
    @Test fun durableSignupRetryKeepsOneKeyAcrossClientRestart()=runBlocking {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val keys=mutableListOf<String>();var failOnce=true
        val tx=Transport {_,_,b->keys+=b!!.ms("request_id");if(failOnce){failOnce=false;throw IOException("reply lost after signup")};buildJsonObject{put("code","PT-00129")}}
        val first=GatheringClient(context,tx)
        try {first.post("signup",human,communityBody("ai" to ai,"table" to "PT-00129"));fail("expected uncertain reply")}catch(_:IOException){}
        try {GatheringClient(context,tx).post("signup",human,communityBody("ai" to ai,"table" to "PT-00129"));fail("must not create a second request")}catch(_:IllegalStateException){}
        assertEquals("PT-00129",GatheringClient(context,tx).reconcile(human).ms("code"))
        assertEquals(2,keys.size);assertEquals(keys[0],keys[1])
    }
    @Test fun headerPlacesPinnedButtonBeforeMomentsTitle() {
        compose.setContent {CommunityTheme {Column(Modifier.width(390.dp)) {
            CommunityHeading("朋友圈",{},leading={OutlinedButton({}) {Text("拼桌大厅")}}) {Row {TextButton({}){Text("刷新")};TextButton({}){Text("＋ 发布")}}}
        }}}
        compose.onNodeWithText("拼桌大厅").assertIsDisplayed();compose.onNodeWithText("朋友圈").assertIsDisplayed()
        val entry=compose.onNodeWithText("拼桌大厅").fetchSemanticsNode().boundsInRoot
        val title=compose.onNodeWithText("朋友圈").fetchSemanticsNode().boundsInRoot
        assertTrue(entry.right<=title.left);capture("header")
    }
    @Test fun actualListOpensDetailWithCapacityNumberAndSignupEntry() {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val now=System.currentTimeMillis()/1000
        val table=buildJsonObject {
            put("code","PT-00129");put("game","drawguess");put("title","你画我猜");put("mode","ai_only");put("capacity",10)
            put("starts_at",now+3600);put("open_at",now+3900);put("state","reserved");put("revision",1);put("server_time",now)
            put("creator",identities[1]);put("mine",JsonArray(emptyList()));put("can_manage",false);put("can_join",false)
            put("members",JsonArray(listOf(buildJsonObject {put("ai",ai);put("name","合成星河");put("human","");put("human_name","")})))
        }
        val api=GatheringClient(context,Transport {_,path,_->when {
            path.contains("detail")->table
            path.contains("options")->buildJsonObject {put("items",JsonArray(emptyList()))}
            else->buildJsonObject {put("items",JsonArray(listOf(table)));put("next_offset",JsonNull)}
        }})
        val community=CommunityClient(context,object:CommunityTransport {
            override val owner=UUID.randomUUID().toString()
            override suspend fun request(method:String,path:String,body:JsonObject?,id:String?,quick:Boolean)=buildJsonObject {put("items",JsonArray(identities))}
        })
        compose.setContent {CommunityTheme {CommunityGatherings(community,identities,human,local,api) {}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("你画我猜 · AI 专场").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("1 / 10 位").assertIsDisplayed();capture("list")
        compose.onNodeWithText("你画我猜 · AI 专场").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("PT-00129").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("报名接龙").assertIsDisplayed();compose.onNodeWithText("复制桌号").assertIsDisplayed()
        compose.onNodeWithText("＋ 在这里接龙报名").performScrollTo().assertIsDisplayed();capture("detail")
    }
    private fun showCancellationTable(host:Boolean):MutableList<JsonObject> {
        val now=System.currentTimeMillis()/1000
        var table=buildJsonObject {
            put("code","PT-00130");put("game","heartprison");put("title","七席心牢");put("mode","ai_only");put("capacity",7)
            put("starts_at",now+3600);put("open_at",now+3900);put("state","reserved");put("revision",7);put("server_time",now)
            put("creator",identities[0]);put("mine",JsonArray(listOf(JsonPrimitive(ai))));put("can_manage",host);put("can_join",false)
            put("members",JsonArray((1..7).map {n->buildJsonObject {put("ai",if(n==1)ai else UUID.randomUUID().toString());put("name","合成成员$n");put("human","");put("human_name","")}}))
        }
        val writes=java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
        val api=GatheringClient(context,Transport {method,path,b->when {
            method=="POST"->{writes+=b!!;table=JsonObject(table+if(path.endsWith("cancel")) mapOf("state" to JsonPrimitive("cancelled"),"disbanded" to JsonPrimitive(true),"members" to JsonArray(emptyList()),"mine" to JsonArray(emptyList())) else mapOf("members" to JsonArray(table.ma("members").drop(1)),"mine" to JsonArray(emptyList())));table}
            path.contains("detail")->table
            path.contains("options")->buildJsonObject {put("items",JsonArray(emptyList()))}
            else->buildJsonObject {put("items",JsonArray(listOf(table)));put("next_offset",JsonNull)}
        }})
        val community=CommunityClient(context,object:CommunityTransport {
            override val owner=UUID.randomUUID().toString()
            override suspend fun request(method:String,path:String,body:JsonObject?,id:String?,quick:Boolean)=buildJsonObject {put("items",JsonArray(identities))}
        })
        compose.setContent {CommunityTheme {CommunityGatherings(community,identities,human,local,api) {}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("七席心牢 · AI 专场").fetchSemanticsNodes().isNotEmpty()}
        return writes
    }
    @Test fun ownerCanCancelFromListOrFixedFooterAndMustConfirmWholeTable() {
        val writes=showCancellationTable(true)
        compose.onNodeWithText("取消拼桌").assertIsDisplayed().performClick()
        compose.onNodeWithText("解散整桌拼桌？").assertIsDisplayed()
        compose.onNodeWithText("保留拼桌").performClick();assertTrue(writes.isEmpty())
        compose.onNodeWithText("取消拼桌 · 解散整桌").assertIsDisplayed().performClick();capture("cancel-host")
        compose.onNodeWithText("确认解散").performClick()
        compose.waitUntil(5000) {writes.isNotEmpty()}
        assertEquals(1,writes.size);assertEquals("PT-00130",writes[0].ms("table"));assertEquals(7,writes[0].mi("revision"))
        compose.onNodeWithText("房主已解散本次拼桌").assertIsDisplayed()
        compose.onNodeWithText("取消拼桌 · 解散整桌").assertDoesNotExist()
    }
    @Test fun ordinaryMemberCanCancelOwnSignupWithoutHostDissolveControl() {
        val writes=showCancellationTable(false)
        compose.onNodeWithText("取消拼桌").assertDoesNotExist()
        compose.onNodeWithText("取消我的报名").performClick()
        compose.onNodeWithText("取消哪位 AI 的报名").assertIsDisplayed()
        compose.onNodeWithText("退出接龙").performClick()
        compose.waitUntil(5000) {writes.isNotEmpty()}
        assertEquals(1,writes.size);assertEquals(ai,writes[0].ms("ai"));assertEquals("PT-00130",writes[0].ms("table"))
        compose.onNodeWithText("取消拼桌 · 解散整桌").assertDoesNotExist()
        compose.onNodeWithText("报名接龙").assertIsDisplayed()
    }

}
