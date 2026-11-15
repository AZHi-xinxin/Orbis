package me.rerere.rikkahub.data.orbis.tabletop

import java.io.IOException
import me.rerere.rikkahub.data.orbis.market.MarketProblem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabletopCompanionLoopTest {
    private class Api:TabletopGameApi {
        override val assistantId="original-assistant"
        override val room="original-room"
        var unavailable=false;var pending=true;var done=false;var stage="playing"
        var expired=false;var missing=false
        override suspend fun restoreConnection() {
            calls.add("restore")
            if(expired)throw MarketProblem(410,buildJsonObject { put("error","membership_expired") })
        }
        val calls=mutableListOf<String>();val heartbeats=mutableListOf<JsonObject>()
        override fun pending(asAi:Boolean)=pending
        override suspend fun reconcilePending() { calls.add("reconcile");if(unavailable)throw IOException("offline");pending=false }
        override suspend fun roleImage(name:String)=""
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            calls.add(path);check(!pending)
            if(unavailable)throw IOException("offline")
            if(missing)throw MarketProblem(404,buildJsonObject { put("error","room_missing") })
            if(expired)throw MarketProblem(403,buildJsonObject { put("error","not_member") })
            return when(path) {
                "script_status"->Json.parseToJsonElement("""{"self":"me","stage":"$stage","phase_version":5,"phase":{"kind":"search"},"progress":[{"actor":"me","done":$done}]}""").jsonObject
                "script_read"->Json.parseToJsonElement("""{"items":[{"kind":"phase_open"}],"next_cursor":6}""").jsonObject
                else->{heartbeats.add(body!!);buildJsonObject{put("ok",true)}}
            }
        }
    }
    @Test fun reconnectsInBackgroundAndReconcilesBeforeNewWrites()=runTest {
        val api=Api();api.unavailable=true;var on=true;var delivered=0
        val loop=TabletopCompanionLoop(api,{on},{false},{false},{"generating"},
            {_,_,_->delivered++;"event"},{},now={testScheduler.currentTime},pollMs=100,retryMs=1000)
        val job=backgroundScope.launch { loop.run() };runCurrent()
        assertEquals(0,delivered);assertTrue(on)
        api.unavailable=false;advanceTimeBy(1200);runCurrent()
        assertEquals(1,delivered);assertFalse(api.pending)
        assertEquals(false,api.heartbeats.single()["foreground"]!!.jsonPrimitive.boolean)
        advanceTimeBy(31000);runCurrent()
        assertEquals(1,delivered);assertTrue(api.heartbeats.size>=2)
        on=false;advanceTimeBy(200);runCurrent();assertTrue(job.isCompleted)
    }
    @Test fun expiredMembershipStopsListenerWithoutWakingOrRejoining()=runTest {
        val api=Api();api.expired=true;var finished=0;var delivered=0
        val job=backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{true},{false},{null},
                {_,_,_->delivered++;"event"},{},finished={finished++},
                now={testScheduler.currentTime},pollMs=100,retryMs=1000).run()
        }
        runCurrent();advanceTimeBy(60000);runCurrent()
        assertTrue(job.isCompleted);assertEquals(1,finished);assertEquals(0,delivered)
        assertEquals(listOf("reconcile","script_status","restore"),api.calls)
        assertTrue(api.heartbeats.isEmpty())
    }
    @Test fun failedWakeRetriesCurrentPhaseWithoutDisablingCompanion()=runTest {
        val api=Api();var prior="failed";val ids=mutableListOf<String>()
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{true},{false},{prior},
                {id,_,_->ids.add(id);if(ids.size==2)prior="generating";"event"},{},now={testScheduler.currentTime},pollMs=100,retryMs=1000).run()
        }
        runCurrent();assertEquals(1,ids.size)
        advanceTimeBy(900);runCurrent();assertEquals(1,ids.size)
        advanceTimeBy(300);runCurrent();assertEquals(2,ids.size);assertNotEquals(ids[0],ids[1])
        prior="generating";advanceTimeBy(3000);runCurrent();assertEquals(2,ids.size)
    }
    @Test fun manualPauseAndAlreadyFinishedSeatDoNotWake()=runTest {
        val api=Api();var delivered=0
        TabletopCompanionLoop(api,{false},{false},{false},{null},{_,_,_->delivered++;"event"},{}).run()
        assertTrue(api.calls.isEmpty())
        api.done=true
        backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{true},{false},{null},
                {_,_,_->delivered++;"event"},{},now={testScheduler.currentTime},pollMs=100,retryMs=1000).run()
        }
        advanceTimeBy(1500);runCurrent();assertEquals(0,delivered)
    }
    @Test fun completedGameStopsListeningAfterTheOriginalAssistantReplies()=runTest {
        val api=Api();api.stage="ended";var prior="generating";var finished=0;var delivered=0
        val job=backgroundScope.launch {
            TabletopCompanionLoop(api,{true},{false},{false},{prior},{_,_,_->delivered++;"event"},{},
                finished={finished++},now={testScheduler.currentTime},pollMs=100,retryMs=1000).run()
        }
        runCurrent();assertEquals(1,delivered);assertEquals(0,finished)
        prior="replied";advanceTimeBy(200);runCurrent()
        assertTrue(job.isCompleted);assertEquals(1,finished);assertEquals(1,delivered)
    }
    @Test fun disbandedRoomUsesClosureCleanupInsteadOfKeepingGameEndingBinding()=runTest {
        for(direct in listOf(true,false)) {
            val api=Api();api.missing=direct;api.expired=!direct
            var closed=0;var finished=0;var delivered=0
            val job=backgroundScope.launch {
                TabletopCompanionLoop(api,{true},{true},{false},{null},{_,_,_->delivered++;"event"},{},
                    finished={finished++},closed={closed++},now={testScheduler.currentTime},pollMs=100,retryMs=100).run()
            }
            runCurrent();assertTrue(job.isCompleted);assertEquals(1,closed);assertEquals(0,finished);assertEquals(0,delivered)
        }
    }
}
