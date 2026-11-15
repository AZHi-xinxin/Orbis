package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartPrisonCompanionTest {
    private class Api:TabletopGameApi {
        override val assistantId="original"
        override val room="heart-room"
        override val gameKind="heartprison"
        var key="1:0";var wake=true;var restored=0;var ended=false
        val calls=mutableListOf<Pair<Boolean,JsonObject?>>()
        override var latestStatus=buildJsonObject{}
        override fun pending(asAi:Boolean)=false
        override suspend fun restoreConnection(){restored++}
        override suspend fun roleImage(name:String):String=error("not an image game")
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            calls.add(asAi to body)
            if(body!=null)return buildJsonObject{put("ok",true);put("request_id",body["request_id"]?:JsonNull)}
            latestStatus=buildJsonObject{put("game_kind",gameKind);put("id",room);put("stage",if(ended)"ended" else "playing");put("phase_version",1);put("game",1);put("round",1);put("phase",buildJsonObject{put("name","交流")});put("wake",wake);put("wake_key",key);put("observer",false);put("allowed_actions",buildJsonArray{add("done")})}
            return latestStatus
        }
    }
    @Test fun toolCallsAlwaysUseAiIdentityAndWriteUniqueReceipt()=runTest {
        val api=Api();val tools=tabletopTools(api,false){}
        assertEquals(setOf("heartprison_status","heartprison_read","heartprison_act","heartprison_reconnect"),tools.map{it.name}.toSet())
        val act=tools.single{it.name=="heartprison_act"}
        act.execute(buildJsonObject{put("action","private");put("task_id","1");put("target","a");put("text","test")})
        assertTrue(api.calls.single().first);assertTrue(api.calls.single().second!!["request_id"]!!.jsonPrimitive.content.isNotBlank())
        tools.single{it.name=="heartprison_reconnect"}.execute(buildJsonObject{});assertEquals(1,api.restored);assertTrue(api.calls.all{it.first})
    }
    @Test fun catalogAndReminderDoNotTeachUndisclosedMechanics()=runTest {
        val api=Api();val content=tabletopTools(api,false){}.joinToString{it.description}+tabletopReminder(api.game("script_status",asAi=true),0)
        for(secret in listOf("潜伏者","四轮","审判","shoot","judge","枪支"))assertFalse(secret,content.contains(secret))
    }
    @Test fun phaseAndIncomingConversationWakeWithoutRepeatingSameWave()=runTest {
        val api=Api();val ids=mutableListOf<String>();var busy=true
        backgroundScope.launch {TabletopCompanionLoop(api,{true},{false},{busy},{"replied"},{id,_,_->ids.add(id);"event"},{},now={testScheduler.currentTime},pollMs=100).run()}
        advanceTimeBy(400);runCurrent();assertEquals(0,ids.size);busy=false
        advanceTimeBy(400);runCurrent();assertEquals(1,ids.size)
        advanceTimeBy(400);runCurrent();assertEquals(1,ids.size);api.key="1:9"
        advanceTimeBy(200);runCurrent();assertEquals(2,ids.size);api.key="2:9"
        advanceTimeBy(200);runCurrent();assertEquals(3,ids.size)
        assertTrue(heartPrisonEventMatchesTurn(ids.last(),api.room,api.latestStatus))
        assertFalse(heartPrisonEventMatchesTurn(ids.first(),api.room,api.latestStatus))
        api.wake=false;advanceTimeBy(200);runCurrent();assertEquals(3,ids.size)
    }
    @Test fun endingSnapshotStoresRankingButNotPrivateDraftsOrHumanSecrets()=runTest {
        val status=Json.parseToJsonElement("""{"game_kind":"heartprison","stage":"ended","actors":[{"name":"A","rank":1,"score":8,"color":"secret","guns":"secret"}],"hidden_state":{"password":"secret"},"guesses":{"a":"secret"},"box_inputs":{"a":"secret"},"rules":"secret"}""").jsonObject
        val api=savedTabletopEndingApi("a","r",status);assertEquals("heartprison",api.gameKind);assertFalse(api.latestStatus.toString().contains("secret"));assertTrue(tabletopReminder(api.latestStatus,0).contains("8分"))
        assertEquals(JsonPrimitive(false),api.game("script_pass",buildJsonObject{put("action","private")},asAi=true)["ok"])
        assertEquals(JsonPrimitive(true),api.game("script_pass",buildJsonObject{put("action","leave")},asAi=true)["left"])
    }
    @Test fun notificationPhasesWakeWithoutGrantingActions() {
        for(stage in listOf("lobby","playing")) {
            val s=buildJsonObject {put("stage",stage);put("phase",buildJsonObject{put("kind",if(stage=="lobby") "lobby" else "intermission")});put("wake",true);put("notification_only",true);put("observer",false);put("wake_key","2:7");put("allowed_actions",buildJsonArray{})}
            assertTrue(heartPrisonCanWake(s));assertTrue(heartPrisonEventMatchesTurn("r:cycle:2:7:0","r",s))
            assertFalse(werewolfCanWake(s));assertFalse(heartPrisonEventMatchesTurn("r:cycle:1:7:0","r",s))
        }
    }
    @Test fun savedDrawGuessOptionIsNotReinterpretedAsNewGame() {
        val old=Json.decodeFromString<LocalToolOption>("""{"type":"orbis_drawguess"}""");assertEquals(LocalToolOption.DrawGuess,old)
        val new=Json.decodeFromString<LocalToolOption>("""{"type":"orbis_heartprison"}""");assertEquals(LocalToolOption.HeartPrison,new)
    }
    @Test fun eliminatedPlayerRejectsQueuedAndLegacyServerPhaseWakes() {
        for(phase in listOf("talk","box","action","guess","settle")) {
            val s=Json.parseToJsonElement("""{"stage":"playing","self":"a","actors":[{"id":"a","alive":false}],"phase":{"kind":"$phase"},"wake":true,"wake_key":"2:7","notification_only":true,"allowed_actions":[]}""").jsonObject
            assertFalse(heartPrisonCanWake(s));assertFalse(heartPrisonEventMatchesTurn("r:cycle:2:7:0","r",s))
        }
        val next=Json.parseToJsonElement("""{"stage":"playing","self_alive":false,"phase":{"kind":"intermission"},"wake":true,"wake_key":"3:7","allowed_actions":[]}""").jsonObject
        assertTrue(heartPrisonCanWake(next));assertTrue(heartPrisonEventMatchesTurn("r:cycle:3:7:0","r",next))
        assertFalse(heartPrisonEventMatchesTurn("r:cycle:2:7:0","r",next))
        val ended=buildJsonObject{put("stage","ended");put("phase_version",4)}
        assertTrue(heartPrisonEventMatchesTurn("r:ended:4","r",ended))
    }
}
