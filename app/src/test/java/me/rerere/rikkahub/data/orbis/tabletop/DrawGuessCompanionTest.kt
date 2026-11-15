package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawGuessCompanionTest {
    private class Api:TabletopGameApi {
        override val assistantId="original-assistant"
        override val room="draw-room"
        override val gameKind="drawguess"
        var key="3.0:me";var wake=true;var failImage=false;var observer=false;var stage="playing"
        val calls=mutableListOf<Pair<String,JsonObject?>>()
        override var latestStatus=buildJsonObject {}
        override fun pending(asAi:Boolean)=false
        override suspend fun roleImage(name:String):String { if(failImage)throw java.io.IOException("image offline");return "file:///isolated/test.png" }
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            calls.add(path to body)
            if(path!="script_status")return buildJsonObject {put("ok",true);put("image","rendered-id");put("revision",1)}
            latestStatus=buildJsonObject {put("id",room);put("game_kind",gameKind);put("stage",stage);put("phase_version",3);put("wake",wake);put("wake_key",key);put("observer",observer);put("allowed_actions",buildJsonArray {if(stage=="playing")add("canvas")});put("actors",buildJsonArray {})}
            return latestStatus
        }
    }
    @Test fun stableCatalogUsesDrawingToolsAndImages()=runTest {
        val api=Api();val tools=tabletopTools(api,true){}
        assertTrue(tools.map{it.name}.containsAll(listOf("drawguess_status","drawguess_canvas","drawguess_read","drawguess_guess")))
        assertFalse(tools.any{it.name.startsWith("werewolf_")})
        val answer=tools.single{it.name=="drawguess_read"}.execute(Json.parseToJsonElement("""{"kind":"image","target":"visible"}"""))
        assertTrue(answer.any{it is UIMessagePart.Image})
    }
    @Test fun imageFailureAfterSavingRetainsReceiptWithoutReplayingWrite()=runTest {
        val api=Api();api.failImage=true
        val answer=drawGuessTools(api,true){}.single{it.name=="drawguess_canvas"}.execute(Json.parseToJsonElement("""{"task_id":"3.me","format":"ascii","code":"cat","revision":0}"""))
        assertEquals(1,api.calls.size)
        assertTrue((answer.first() as UIMessagePart.Text).text.contains("rendered-id"))
        assertTrue((answer.last() as UIMessagePart.Text).text.contains("已经保存"))
    }
    @Test fun noVisionIsExplicitAndDoesNotPretendToRead()=runTest {
        val api=Api();val answer=drawGuessTools(api,false){}.single{it.name=="drawguess_read"}.execute(Json.parseToJsonElement("""{"kind":"image","target":"x"}"""))
        assertEquals(1,answer.size);assertTrue((answer.single() as UIMessagePart.Text).text.contains("尚未看见"));assertTrue(api.calls.isEmpty())
    }
    @Test fun wakeMatchesQuestionAndWaveNotCanvasRevision()=runTest {
        val api=Api();val s=api.game("script_status",asAi=true)
        assertTrue(drawGuessEventMatchesTurn("draw-room:cycle:3.0:me:0","draw-room",s))
        assertFalse(drawGuessEventMatchesTurn("draw-room:cycle:2.0:me:0","draw-room",s))
        assertFalse(drawGuessEventMatchesTurn("other:cycle:3.0:me:0","draw-room",s))
        api.observer=true;assertFalse(drawGuessCanWake(api.game("script_status",asAi=true)))
    }
    @Test fun onlyNewWaveWakesAfterReplyWhileOriginalChatBusyDefers()=runTest {
        val api=Api();var busy=true;val ids=mutableListOf<String>()
        backgroundScope.launch{TabletopCompanionLoop(api,{true},{false},{busy},{"replied"},{id,_,_->ids.add(id);"event"},{},now={testScheduler.currentTime},pollMs=100).run()}
        advanceTimeBy(600);runCurrent();assertTrue(ids.isEmpty());busy=false
        advanceTimeBy(600);runCurrent();assertEquals(1,ids.size)
        advanceTimeBy(600);runCurrent();assertEquals(1,ids.size);api.key="3.1:me"
        advanceTimeBy(200);runCurrent();assertEquals(2,ids.size)
    }
    @Test fun savedEndingHasNoTasksAndRejectsWrites()=runTest {
        val s=Json.parseToJsonElement("""{"game_kind":"drawguess","stage":"ended","mode":"mixed","task":{"source":{"text":"secret"}},"actors":[],"results":{"chains":["secret"]}}""").jsonObject
        val api=DrawGuessEndingApi("assistant","room",s);assertFalse(api.latestStatus.toString().contains("secret"))
        val result=api.game("script_pass",buildJsonObject{put("action","canvas")},asAi=true)
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
    }
    @Test fun quickEndingIsDeliveredOnFirstPollAndNotAgainThreeMinutesLater()=runTest {
        val api=Api();api.stage="ended";var finished=false
        val ids=mutableListOf<String>();val messages=mutableListOf<String>()
        val job=backgroundScope.launch {TabletopCompanionLoop(api,{true},{false},{false},{"replied"},
            {id,s,_->ids.add(id);messages.add(tabletopReminder(s,0));"receipt"},{},finished={finished=true},
            now={testScheduler.currentTime},pollMs=2500).run()}
        runCurrent();assertEquals(1,ids.size);assertTrue(ids.single().contains(":ended:"))
        assertTrue(messages.single().contains("无需等待下一回合"))
        advanceTimeBy(2501);runCurrent();assertTrue(finished);assertTrue(job.isCompleted)
        advanceTimeBy(180000);runCurrent();assertEquals(1,ids.size)
    }
}
