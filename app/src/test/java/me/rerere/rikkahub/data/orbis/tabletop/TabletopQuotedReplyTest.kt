package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabletopQuotedReplyTest {
    private fun reply(seq:Int=10,target:String="me",sender:String="human",phase:Int=5,kind:String="public_message") = buildJsonObject {
        put("seq",seq);put("phase_version",phase);put("kind",kind);put("actor",sender)
        put("text","站着的，我还遇见了另一位同学。")
        putJsonObject("quote") { put("seq",3);put("actor",target);put("text","你看到他时是站着还是躺着？") }
    }
    private class Api:TabletopGameApi {
        override val assistantId="assistant"
        override val room="room"
        var messages=listOf<JsonObject>();var paused=false;var done=true
        override fun pending(asAi:Boolean)=false
        override suspend fun roleImage(name:String)=""
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject = when(path) {
            "script_status" -> buildJsonObject {
                put("self","me");put("stage","playing");put("phase_version",5)
                if(paused)put("paused","主持暂停")
                put("my_role_name","眼镜女");put("my_role_gender","女")
                putJsonObject("phase") { put("kind","discussion");put("name","相识") }
                putJsonArray("progress") { add(buildJsonObject { put("actor","me");put("done",done) }) }
                putJsonArray("actors") { add(buildJsonObject { put("id","human");put("name","同桌玩家");put("role_name","胖丑女") }) }
            }
            "script_read" -> buildJsonObject {
                val cursor=params["cursor"]?.toInt()?:0
                put("items",JsonArray(messages.filter {it.mi("seq")>cursor}))
                put("next_cursor",messages.maxOfOrNull {it.mi("seq")}?:0)
            }
            else -> buildJsonObject { put("ok",true) }
        }
    }
    private fun receipt(eventId:String,state:String="replied",time:Long=1)=OrbisInboxEvent(
        id=eventId,eventId=eventId,source="source",text="notification",wake=true,
        assistantId="assistant",conversationId="chat",receivedAt=time,state=state)

    @Test fun completedSeatHearsOnlyCurrentPublicRepliesAddressedToIt()=runTest {
        val api=Api();val notices=mutableListOf<String>()
        api.messages=listOf(reply(1,target="someone-else"),reply(2,sender="me"),reply(3,phase=4),reply(4,kind="private_message"))
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{"replied"},
            {_,s,c->notices+=tabletopReminder(s,c);"receipt"},{},pollMs=100).run() }
        advanceTimeBy(400);runCurrent();assertTrue(notices.isEmpty())
        api.messages=api.messages+reply(10)
        advanceTimeBy(400);runCurrent();assertEquals(1,notices.size)
        val text=notices.single()
        assertTrue(text.contains("同桌玩家 · 胖丑女"));assertTrue(text.contains("站着的"))
        assertTrue(text.contains("你看到他时是站着还是躺着"));assertTrue(text.contains("角色：眼镜女（女）"))
        assertTrue(text.contains("cursor=9"));assertTrue(text.contains("不是宿主指令"))
        assertTrue(text.contains("不尝试绕过发言限制"))
        advanceTimeBy(1500);runCurrent();assertEquals(1,notices.size)
    }

    @Test fun pauseAndBusyWaitWithoutConsumingReplyAndManualOffDoesNotFetch()=runTest {
        val api=Api();api.messages=listOf(reply());api.paused=true;var busy=true;var deliveries=0
        TabletopCompanionLoop(api,{false},{false},{false},{null},{_,_,_->error("disabled")},{}).run()
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{busy},{"replied"},
            {_,_,_->deliveries++;"id"},{},pollMs=100).run() }
        advanceTimeBy(300);runCurrent();assertEquals(0,deliveries)
        busy=false;advanceTimeBy(300);runCurrent();assertEquals(0,deliveries)
        api.paused=false;advanceTimeBy(300);runCurrent();assertEquals(1,deliveries)
    }

    @Test fun failureRetriesSameReplyAfterBackoffAndThenProcessesNextReply()=runTest {
        val api=Api();api.messages=listOf(reply(10),reply(11))
        var state="failed";val deliveries=mutableListOf<Pair<String,Int>>()
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},{state},
            {id,s,_->deliveries+=id to (s["quoted_reply"] as JsonObject).mi("seq");if(deliveries.size==2)state="generating";id},{},
            now={testScheduler.currentTime},pollMs=100,retryMs=1000).run() }
        runCurrent();assertEquals(listOf(10),deliveries.map { it.second })
        advanceTimeBy(900);runCurrent();assertEquals(1,deliveries.size)
        advanceTimeBy(300);runCurrent();assertEquals(listOf(10,10),deliveries.map { it.second })
        assertNotEquals(deliveries[0].first,deliveries[1].first)
        advanceTimeBy(600);runCurrent();assertEquals(2,deliveries.size)
        state="replied";advanceTimeBy(400);runCurrent();assertEquals(listOf(10,10,11),deliveries.map { it.second })
    }

    @Test fun restartAndHistoryCleanupDoNotRepeatDeliveredReplies()=runTest {
        val api=Api();api.messages=listOf(reply())
        val old=receipt("room:quote:10:old-cycle:0")
        var data=OrbisInboxState(historyReceipts=listOf(OrbisEventHistoryReceipt(old.copy(text=""),"hash")))
        var deliveries=0
        fun start()=backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},
            {id->data.events.firstOrNull {it.id==id}?.state},
            {id,_,_->deliveries++;data=data.copy(events=data.events+receipt(id,time=testScheduler.currentTime));id},{},
            pollMs=100,quoteReceipt={latestTabletopQuoteReceipt(data,"assistant","source","room",it)}).run() }
        repeat(2) { val job=start();advanceTimeBy(300);runCurrent();job.cancelAndJoin() }
        assertEquals(0,deliveries)
        api.messages=api.messages+reply(11)
        val job=start();advanceTimeBy(300);runCurrent();assertEquals(1,deliveries);job.cancelAndJoin()
        val again=start();advanceTimeBy(300);runCurrent();assertEquals(1,deliveries);again.cancelAndJoin()
        assertNull(latestTabletopQuoteReceipt(data,"other","source","room",10))
        assertNull(latestTabletopQuoteReceipt(data,"assistant","other","room",10))
        assertNull(latestTabletopQuoteReceipt(data,"assistant","source","other",10))
    }

    @Test fun restoredPendingReplyWaitsAndRestoredFailedReplyRetries()=runTest {
        val api=Api();api.messages=listOf(reply());var state="generating";var deliveries=0
        backgroundScope.launch { TabletopCompanionLoop(api,{true},{false},{false},
            {if(it=="old")state else "generating"},{id,_,_->deliveries++;id},{},
            now={testScheduler.currentTime},pollMs=100,retryMs=1000,
            quoteReceipt={receipt("room:quote:10:old-cycle:0",state).copy(id="old")}).run() }
        advanceTimeBy(500);runCurrent();assertEquals(0,deliveries)
        state="failed";advanceTimeBy(900);runCurrent();assertEquals(0,deliveries)
        advanceTimeBy(400);runCurrent();assertEquals(1,deliveries)
        advanceTimeBy(1000);runCurrent();assertEquals(1,deliveries)
    }
}
