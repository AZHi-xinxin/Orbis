package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.MarketProblem
import org.junit.Assert.*
import org.junit.Test

class DrawGuessReconnectTest {
    private fun state(task:String="task-1",image:String="old")=buildJsonObject {
        put("id","room");put("stage","playing");put("phase_version",3)
        put("task",buildJsonObject {put("id",task);put("kind","guess");put("source",buildJsonObject {put("image",image)})})
        put("live_image",image);put("allowed_actions",buildJsonArray {add("guess")})
    }
    private inner class Api:TabletopGameApi {
        override val assistantId="own-assistant";override val room="room";override val gameKind="drawguess"
        override var latestStatus=state();var next=state(image="new");var restores=0;var reads=0;var writes=0;var reconciles=0
        var restoreError:Exception?=null;var hasPending=true;var denied=setOf("old");var imageCode="image_private";val imageReads=mutableListOf<String>()
        override fun pending(asAi:Boolean)=hasPending
        override suspend fun restoreConnection(){restores++;restoreError?.let {throw it}}
        override suspend fun reconcilePending(){reconciles++}
        override suspend fun roleImage(name:String):String {
            imageReads.add(name)
            if(name in denied)throw MarketProblem(403,buildJsonObject {put("error",imageCode);put("message","当前不能查看这幅画")})
            return "file:///isolated/$name.png"
        }
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            if(body!=null){writes++;error("must not replay game writes")}
            assertTrue(asAi);assertEquals("script_status",path);reads++;latestStatus=next;return next
        }
    }
    private fun json(parts:List<UIMessagePart>)=Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
    @Test fun reconnectRenewsOnlyOwnConnectionThenReadsStateWithoutReplayingPending()=runTest {
        val api=Api();val result=json(drawGuessTools(api,true){}.single{it.name=="drawguess_reconnect"}.execute(buildJsonObject {}))
        assertTrue(result["reconnected"]!!.jsonPrimitive.boolean);assertTrue(result["pending_submission"]!!.jsonPrimitive.boolean)
        assertEquals(api.next,result["status"]);assertEquals(1,api.restores);assertEquals(1,api.reads);assertEquals(0,api.writes);assertEquals(0,api.reconciles)
    }
    @Test fun changedBindingDoesNotReconnectStaleRoom()=runTest {
        val api=Api();val result=json(drawGuessTools(api,true){error("binding changed")}.single{it.name=="drawguess_reconnect"}.execute(buildJsonObject {}))
        assertFalse(result["ok"]!!.jsonPrimitive.boolean);assertEquals(0,api.restores);assertEquals(0,api.reads)
    }
    @Test fun reconnectFailureAndCancellationDoNotWriteOrClaimSuccess()=runTest {
        val api=Api();api.restoreError=java.io.IOException("secret endpoint")
        val tool=drawGuessTools(api,true){}.single{it.name=="drawguess_reconnect"};val result=json(tool.execute(buildJsonObject {}));assertFalse(result["ok"]!!.jsonPrimitive.boolean);assertFalse(result.toString().contains("secret endpoint"));assertEquals(0,api.reads)
        api.restoreError=CancellationException();try{tool.execute(buildJsonObject {});fail("must cancel")}catch(_:CancellationException){}
        assertEquals(0,api.writes)
    }
    @Test fun staleImageOfSameTaskRefreshesOnlyOnceFromAuthorizedStatus()=runTest {
        val api=Api();val result=drawGuessReadImage(api,"old");assertEquals(listOf("old","new"),api.imageReads);assertEquals(1,api.reads);assertTrue(result.last() is UIMessagePart.Image);assertEquals(0,api.writes)
    }
    @Test fun changedTaskDoesNotReadNewQuestionAsOldImage()=runTest {
        val api=Api();api.next=state("task-2","new");val result=json(drawGuessReadImage(api,"old"));assertFalse(result["ok"]!!.jsonPrimitive.boolean);assertEquals(listOf("old"),api.imageReads);assertEquals(api.next,result["status"]);assertTrue(result.toString().contains("drawguess_reconnect"))
    }
    @Test fun arbitraryImageCannotRedirectToAnotherChainOrRetryRepeatedly()=runTest {
        val api=Api();api.denied=setOf("foreign");val result=json(drawGuessReadImage(api,"foreign"));assertFalse(result["ok"]!!.jsonPrimitive.boolean);assertEquals(listOf("foreign"),api.imageReads)
        api.imageReads.clear();api.latestStatus=state();api.denied=setOf("old","new")
        val tool=drawGuessTools(api,true){}.single{it.name=="drawguess_read"};val reply=json(tool.execute(buildJsonObject{put("kind","image");put("target","old")}));assertFalse(reply["ok"]!!.jsonPrimitive.boolean);assertEquals(listOf("old","new"),api.imageReads);assertTrue(reply.toString().contains("drawguess_reconnect"))
    }
    @Test fun lobbyRecoveryUsesRecoveryHandlerNotJoinOrPublicList()=runTest {
        var recovered=0
        val tool=drawGuessLobbyTool(allowed={true},catalog={error("not list")},join={error("not join")},reconnect={recovered++;buildJsonObject{put("reconnected",true)}})
        assertTrue(json(tool.execute(buildJsonObject{put("action","reconnect")}))["reconnected"]!!.jsonPrimitive.boolean);assertEquals(1,recovered)
    }
    @Test fun savedEndingStaysEndedWithoutRestoringSeat()=runTest {
        val api=DrawGuessEndingApi("own-assistant","room",buildJsonObject{put("stage","ended");put("mode","mixed")})
        val result=json(drawGuessTools(api,true){}.single{it.name=="drawguess_reconnect"}.execute(buildJsonObject {}));assertFalse(result["reconnected"]!!.jsonPrimitive.boolean);assertTrue(result["ended"]!!.jsonPrimitive.boolean)
    }
    @Test fun unpublishedPictureWaitsWithoutReconnectOrPass()=runTest {
        val api=Api();api.imageCode="image_not_ready"
        val result=json(drawGuessReadImage(api,"old"));assertEquals(listOf("old"),api.imageReads)
        assertEquals(0,api.restores);assertEquals(0,api.writes);assertTrue(result.toString().contains("等待成品公开"))
    }
    @Test fun changedImageUsesSameTaskGuard()=runTest {
        val api=Api();api.imageCode="image_changed"
        assertTrue(drawGuessReadImage(api,"old").last() is UIMessagePart.Image)
        assertEquals(listOf("old","new"),api.imageReads)
    }
    @Test fun reminderNamesRoundPhaseAndActualAutopickOnlyForArtist() {
        val common=buildJsonObject {put("game_kind","drawguess");put("variant","quick");put("stage","playing");put("round",8);put("phase",buildJsonObject{put("name","看词画画")});put("auto_pick",buildJsonObject{put("text","风筝")})}
        val artist=JsonObject(common+mapOf("task" to buildJsonObject {put("id","new-task");put("kind","draw");put("source",buildJsonObject{put("text","风筝")})}))
        val text=tabletopReminder(artist,0);assertTrue(text.contains("第8题"));assertTrue(text.contains("new-task"));assertTrue(text.contains("本步拿到的词是【风筝】"));assertTrue(text.contains("由系统代选"));assertFalse(text.contains("script_say"))
        val other=JsonObject(common.filterKeys{it!="auto_pick"}+mapOf("task" to buildJsonObject{put("kind","wait")}))
        assertFalse(tabletopReminder(other,0).contains("风筝"))
    }
    @Test fun historicalAutoPickCannotOverrideCurrentRelayWord() {
        for(step in listOf(1,3,5,9)) {
            val s=Json.parseToJsonElement("""{"game_kind":"drawguess","variant":"chain","stage":"playing","step":$step,"phase":{"name":"看词画画"},"auto_pick":{"text":"旧题"},"task":{"id":"current","kind":"draw","source":{"text":"新题"}}}""").jsonObject
            val text=tabletopReminder(s,0)
            assertTrue(text.contains("本步拿到的词是【新题】"))
            assertFalse(text.contains("旧题"));assertFalse(text.contains("由系统代选"))
            assertTrue(text.contains("通知生成时task_id"));assertTrue(text.contains("以最新任务为准"))
        }
    }
    @Test fun matchingOldWordDoesNotMakeLaterRelayAnAutoPick() {
        val s=Json.parseToJsonElement("""{"game_kind":"drawguess","variant":"chain","stage":"playing","step":5,"auto_pick":{"text":"风筝"},"task":{"id":"current","kind":"draw","source":{"text":"风筝"}}}""").jsonObject
        assertFalse(tabletopReminder(s,0).contains("由系统代选"))
    }
    @Test fun lobbyListsMixedOriginalSeatWithoutJoiningOrReconnecting()=runTest {
        val catalog=Json.parseToJsonElement("""{"rooms":[{"id":"empty-ai","mode":"ai_only","stage":"lobby"},{"id":"own-mixed","mode":"mixed","stage":"playing"}],"current_room":{"room":"own-mixed","mode":"mixed"}}""").jsonObject
        val tool=drawGuessLobbyTool(allowed={true},catalog={catalog},join={error("must not join")},reconnect={error("must not reconnect")})
        val reply=json(tool.execute(buildJsonObject{put("action","list")}))
        val rooms=reply["rooms"]!!.jsonArray
        assertEquals(2,rooms.size);assertEquals("own-mixed",reply["current_room"]!!.jsonObject["room"]!!.jsonPrimitive.content)
        assertTrue(rooms[0].jsonObject["can_join_ai"]!!.jsonPrimitive.boolean)
        assertFalse(rooms[1].jsonObject["can_join_ai"]!!.jsonPrimitive.boolean)
    }
}
