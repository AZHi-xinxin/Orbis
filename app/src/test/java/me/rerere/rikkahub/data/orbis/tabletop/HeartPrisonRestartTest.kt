package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartPrisonRestartTest {
    private class Api : TabletopGameApi {
        override val assistantId = "assistant"
        override val room = "room"
        override val gameKind = "heartprison"
        var key = "0:0"
        override fun pending(asAi:Boolean) = false
        override suspend fun roleImage(name:String) = error("no images")
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean) =
            buildJsonObject { put("stage","lobby");put("wake",true);put("wake_key",key);put("phase_version",0) }
    }
    private fun receipt(id:String="old",state:String="replied",time:Long=1) = OrbisInboxEvent(
        id=id,eventId="room:old-random-cycle:0:0:0",source="source",text="already delivered",
        wake=true,assistantId="assistant",conversationId="original",receivedAt=time,state=state)

    @Test fun oldVersionReceiptSurvivesCleanupAndRejectsOtherSeatsRoomsAndKeys() {
        val old=receipt()
        val data=OrbisInboxState(events=listOf(old.copy(id="foreign",assistantId="other",receivedAt=9)),
            historyReceipts=listOf(OrbisEventHistoryReceipt(old.copy(text=""),"hash")))
        assertEquals("old",latestHeartPrisonReceipt(data,"assistant","source","room","0:0"))
        assertNull(latestHeartPrisonReceipt(data,"assistant","source","room","1:0"))
        assertNull(latestHeartPrisonReceipt(data,"assistant","source","other","0:0"))
        assertNull(latestHeartPrisonReceipt(data,"assistant","other","room","0:0"))
        assertEquals("newer",latestHeartPrisonReceipt(data.copy(events=listOf(receipt("newer","failed",10))),"assistant","source","room","0:0"))
    }

    @Test fun restartingCompletedLobbyDoesNotWakeButNewWaveDoes() = runTest {
        val api=Api();var data=OrbisInboxState(events=listOf(receipt()));val delivered=mutableListOf<String>()
        fun start()=backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},
            {id->data.events.firstOrNull{it.id==id}?.state},{id,_,_->delivered+=id;data=data.copy(events=data.events+receipt(id).copy(eventId=id,receivedAt=testScheduler.currentTime));id},{},
            now={testScheduler.currentTime},pollMs=100,restoreWake={latestHeartPrisonReceipt(data,"assistant","source","room",it)}).run() }
        repeat(3) { val job=start();advanceTimeBy(300);runCurrent();job.cancelAndJoin() }
        assertTrue(delivered.isEmpty())
        val job=start();api.key="1:4";advanceTimeBy(300);runCurrent();assertEquals(1,delivered.size)
        job.cancelAndJoin();val resumed=start();advanceTimeBy(300);runCurrent();assertEquals(1,delivered.size);resumed.cancelAndJoin()
    }

    @Test fun restartingPendingWakeWaitsForSameReceipt() = runTest {
        val api=Api();var state="generating";var delivered=0
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{state},{_,_,_->delivered++;"new"},{},
            now={testScheduler.currentTime},pollMs=100,restoreWake={"old"}).run() }
        advanceTimeBy(500);runCurrent();assertEquals(0,delivered)
        state="replied";advanceTimeBy(500);runCurrent();assertEquals(0,delivered)
    }

    @Test fun restartingFailedWakePreservesBackoffThenRetriesCurrentWave() = runTest {
        val api=Api();var delivered=0
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{if(it=="old")"failed" else "generating"},
            {_,_,_->delivered++;"new"},{},now={testScheduler.currentTime},pollMs=100,retryMs=1000,restoreWake={"old"}).run() }
        advanceTimeBy(900);runCurrent();assertEquals(0,delivered)
        advanceTimeBy(300);runCurrent();assertEquals(1,delivered)
        advanceTimeBy(2000);runCurrent();assertEquals(1,delivered)
    }
}
