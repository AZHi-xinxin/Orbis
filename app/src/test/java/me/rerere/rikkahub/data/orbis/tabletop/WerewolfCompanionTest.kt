package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WerewolfCompanionTest {
    private class Api:TabletopGameApi {
        override val assistantId="original-assistant"
        override val room="werewolf-room"
        override val gameKind="werewolf"
        var wake=false;var key="1:me";var stage="playing";var offline=false
        val calls=mutableListOf<String>()
        var acknowledgements=0
        var allowed=true;var observer=false
        override suspend fun acknowledgeEnding() { if(offline)throw java.io.IOException("offline");acknowledgements++ }
        override var latestStatus=buildJsonObject {}
        override fun pending(asAi:Boolean)=false
        override suspend fun roleImage(name:String)=""
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            calls.add(path);if(offline)throw java.io.IOException("offline")
            if(path!="script_status")return buildJsonObject { put("ok",true) }
            latestStatus=buildJsonObject {
                put("game_kind","werewolf");put("stage",stage);put("wake",wake);put("wake_key",key)
                put("phase_version",key.substringBefore(':').toInt());put("my_role","seer");put("self","me")
                put("observer",observer)
                put("allowed_actions",buildJsonArray { if(wake && allowed){add("inspect");add("pass")} })
            }
            return latestStatus
        }
    }
    @Test fun nightBystandersAndDeadObserversNeverWakeOrConsumeUnread()=runTest {
        val api=Api();var count=0
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{"replied"},{_,_,_->count++;"event"},{},now={testScheduler.currentTime},pollMs=100,retryMs=300).run() }
        advanceTimeBy(6000);runCurrent();assertEquals(0,count);assertFalse(api.calls.contains("script_read"))
    }
    @Test fun wakesOncePerOwnTurnAndRecoversConnection()=runTest {
        val api=Api();val ids=mutableListOf<String>()
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{"replied"},{id,_,_->ids.add(id);"event"},{},now={testScheduler.currentTime},pollMs=100,retryMs=300).run() }
        api.wake=true;advanceTimeBy(1000);runCurrent();assertEquals(1,ids.size)
        advanceTimeBy(2000);runCurrent();assertEquals(1,ids.size)
        api.offline=true;api.key="2:me";advanceTimeBy(500);runCurrent();assertEquals(1,ids.size)
        api.offline=false;advanceTimeBy(800);runCurrent();assertEquals(2,ids.size)
        assertFalse(api.calls.contains("script_read"))
    }
    @Test fun endedObserverReceivesResultAndStopsOnlyAfterFinalReply()=runTest {
        val api=Api();api.stage="ended";var finished=0;var wakes=0
        var state="queued"
        val job=backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{state},{_,s,_->assertEquals("ended",s["stage"]!!.jsonPrimitive.content);wakes++;"event"},{},finished={finished++},pollMs=100).run()
        }
        runCurrent();advanceTimeBy(500);runCurrent()
        assertEquals(0,finished);assertEquals(1,wakes);assertFalse(api.calls.contains("script_pass"))
        state="replied";advanceTimeBy(200);runCurrent()
        assertEquals(1,finished);assertEquals(1,wakes);assertTrue(job.isCompleted)
        assertEquals(1,api.acknowledgements)
    }
    @Test fun reminderDoesNotRevealRoleOrNightStep() {
        val status=Json.parseToJsonElement("""{"game_kind":"werewolf","my_role":"seer","phase":{"kind":"seer","name":"预言家查验"},"phase_version":42}""").jsonObject
        val reminder=tabletopReminder(status,999)
        assertTrue(reminder.contains("轮到你的回合了"));assertFalse(reminder.contains("seer"));assertFalse(reminder.contains("预言家"));assertFalse(reminder.contains("42"))
    }
    @Test fun toolCatalogStaysStableAcrossTurnsAndElimination()=runTest {
        val api=Api();api.wake=true;api.game("script_status",asAi=true)
        val names=werewolfTools(api){}.map { it.name }
        assertTrue(names.containsAll(listOf("werewolf_status","werewolf_read","werewolf_inspect","werewolf_pass")))
        assertTrue(names.containsAll(listOf("werewolf_medicine","werewolf_shoot","werewolf_say","werewolf_vote","werewolf_leave")))
        api.wake=false;api.game("script_status",asAi=true)
        assertEquals(names,werewolfTools(api){}.map{it.name})
        api.stage="ended";api.game("script_status",asAi=true)
        assertEquals(names,werewolfTools(api){}.map{it.name})
    }
    @Test fun missingOrNonIntegerVersionNeverSubmitsAndQuerySchemaCannotPretendToLeave()=runTest {
        val api=Api();val tools=werewolfTools(api){}
        assertTrue((tools.single {it.name=="werewolf_status"}.parameters() as InputSchema.Obj).properties.isEmpty())
        for(name in listOf("say","pass","vote","wolf_vote","inspect","medicine","shoot")) {
            val tool=tools.single {it.name=="werewolf_$name"}
            assertTrue((tool.parameters() as InputSchema.Obj).required!!.contains("phase_version"))
            for(args in listOf("{}","{\"phase_version\":null}","{\"phase_version\":\"20\"}","{\"phase_version\":true}","{\"phase_version\":20.5}")) {
                val reply=(tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text).text
                assertTrue(reply.contains("phase_version_required"))
            }
        }
        assertTrue(api.calls.isEmpty())
        tools.single {it.name=="werewolf_say"}.execute(Json.parseToJsonElement("""{"phase_version":20,"text":"发言"}"""))
        assertEquals(listOf("script_say"),api.calls)
    }
    @Test fun finalSnapshotSurvivesBusyChatAndServerCleanup()=runTest {
        val api=Api();api.stage="ended";var busy=true;var snapshot:JsonObject?=null;val ids=mutableListOf<String>()
        val job=backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{busy},{"queued"},{id,_,_->ids.add(id);"event"},{},
                pollMs=100,saveEnding={snapshot=it}).run()
        }
        runCurrent();assertNotNull(snapshot);assertTrue(ids.isEmpty())
        api.offline=true;busy=false;advanceTimeBy(500);runCurrent()
        assertEquals(1,ids.size);assertEquals(listOf("script_status"),api.calls)
        job.cancel()
        val restarted=backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{"replied"},{id,_,_->ids.add(id);"event"},{},
                pollMs=100,loadEnding={snapshot}).run()
        }
        advanceTimeBy(500);runCurrent();assertTrue(restarted.isCompleted)
        assertEquals(ids[0],ids[1]) // Inbox deduplicates this stable event ID across a process restart.
        assertEquals(listOf("script_status"),api.calls)
    }
    @Test fun replyToLastActionIsNotMistakenForReplyToEnding()=runTest {
        val api=Api();api.wake=true;var prior="generating";var count=0;var finished=0
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{prior},{_,_,_->count++;"event$count"},{},
                finished={finished++},pollMs=100).run()
        }
        runCurrent();assertEquals(1,count)
        api.stage="ended";prior="replied";advanceTimeBy(100);runCurrent()
        assertEquals(2,count);assertEquals(0,finished)
        advanceTimeBy(100);runCurrent();assertEquals(1,finished)
    }
    @Test fun ambiguousEndingDeliveryReusesIdAndConfirmedFailureCanRetry()=runTest {
        val api=Api();api.stage="ended";val ids=mutableListOf<String>();var prior="failed"
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{prior},{id,_,_->
                ids.add(id)
                if(ids.size==1)throw java.io.IOException("delivery outcome unknown")
                if(ids.size==3)prior="replied"
                "event"
            },{},now={testScheduler.currentTime},pollMs=100,retryMs=200).run()
        }
        advanceTimeBy(1000);runCurrent()
        assertEquals(3,ids.size);assertEquals(ids[0],ids[1]);assertNotEquals(ids[1],ids[2])
    }
    @Test fun dispatchRejectsOldPhaseEmptyActionsAndObserversButKeepsEnding()=runTest {
        val api=Api();api.wake=true
        var status=api.game("script_status",asAi=true)
        assertTrue(werewolfEventMatchesTurn("werewolf-room:cycle:1:me:0",api.room,status))
        api.key="2:me";status=api.game("script_status",asAi=true)
        assertFalse(werewolfEventMatchesTurn("werewolf-room:cycle:1:me:0",api.room,status))
        assertFalse(werewolfEventMatchesTurn("other:cycle:2:me:0",api.room,status))
        api.allowed=false;status=api.game("script_status",asAi=true)
        assertFalse(werewolfCanWake(status))
        api.allowed=true;api.observer=true;status=api.game("script_status",asAi=true)
        assertFalse(werewolfCanWake(status))
        api.stage="ended";status=api.game("script_status",asAi=true)
        assertTrue(werewolfEventMatchesTurn("werewolf-room:ended:2:0",api.room,status))
        assertFalse(werewolfEventMatchesTurn("werewolf-room:cycle:2:me:0",api.room,status))
    }
    @Test fun endedAcknowledgementFollowsDurableSaveAndOfflineDoesNotBlockDelivery()=runTest {
        val api=Api();api.stage="ended";var saved=false;var count=0
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{"replied"},{_,_,_->
                assertTrue(saved);count++;"event"
            },{},pollMs=100,saveEnding={saved=true;api.offline=true}).run()
        }
        advanceTimeBy(400);runCurrent();assertEquals(1,count);assertEquals(0,api.acknowledgements)
    }
    @Test fun inconsistentWakeFlagWithNoActionDoesNotGenerate()=runTest {
        val api=Api();api.wake=true;api.allowed=false;var count=0
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{"replied"},{_,_,_->count++;"event"},{},pollMs=100).run()
        }
        advanceTimeBy(1000);runCurrent();assertEquals(0,count)
        api.allowed=true;api.observer=true;advanceTimeBy(500);runCurrent();assertEquals(0,count)
        api.observer=false;advanceTimeBy(500);runCurrent();assertEquals(1,count)
    }
}
