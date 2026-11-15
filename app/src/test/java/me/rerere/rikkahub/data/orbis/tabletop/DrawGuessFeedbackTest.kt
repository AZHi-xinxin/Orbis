package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.MarketProblem
import org.junit.Assert.*
import org.junit.Test

class DrawGuessFeedbackTest {
    private class Api : TabletopGameApi {
        override val assistantId="assistant"
        override val room="room"
        override val gameKind="drawguess"
        override val latestStatus=buildJsonObject {}
        var params=emptyMap<String,String>()
        var body:JsonObject?=null
        var calls=0
        var failure:Exception?=null
        var afterWrite:()->Unit={}
        override fun pending(asAi:Boolean)=false
        override suspend fun roleImage(name:String)="file:///isolated/test.png"
        override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
            calls++;this.params=params;this.body=body;failure?.let {throw it};afterWrite()
            return buildJsonObject {put("ok",true);put("saved",true)}
        }
    }
    private fun text(parts:List<UIMessagePart>)=(parts.first() as UIMessagePart.Text).text
    @Test fun replayCursorAndVotesReachServer()=runTest {
        val api=Api();val tools=drawGuessTools(api,true){}
        tools.single{it.name=="drawguess_read"}.execute(Json.parseToJsonElement("""{"kind":"replay","target":"chain-owner","cursor":4}"""))
        assertEquals(mapOf("kind" to "replay","target" to "chain-owner","cursor" to "4"),api.params)
        tools.single{it.name=="drawguess_review"}.execute(Json.parseToJsonElement("""{"task_id":"current","votes":{"chain-owner":"creative"}}"""))
        assertEquals("creative",api.body!!["votes"]!!.jsonObject["chain-owner"]!!.jsonPrimitive.content)
    }
    @Test fun connectionChangeAfterSuccessfulWriteKeepsReceipt()=runTest {
        val api=Api();var connected=true;api.afterWrite={connected=false}
        val tool=drawGuessTools(api,true){check(connected)}.single{it.name=="drawguess_submit"}
        val result=Json.parseToJsonElement(text(tool.execute(Json.parseToJsonElement("""{"task_id":"current"}""")))).jsonObject
        assertEquals(1,api.calls);assertTrue(result["saved"]!!.jsonPrimitive.boolean)
    }
    @Test fun connectionFailureBeforeAndAfterRequestHaveDifferentExecutionStatus()=runTest {
        val api=Api()
        val inactive=drawGuessTools(api,true){error("private connection detail")}.single{it.name=="drawguess_choose"}
        val args=Json.parseToJsonElement("""{"task_id":"current","text":"海盗船"}""")
        var reply=text(inactive.execute(args));assertTrue(reply.contains("not_started"));assertFalse(reply.contains("private connection detail"));assertEquals(0,api.calls)
        api.failure=IllegalStateException("private connection detail")
        reply=text(drawGuessTools(api,true){}.single{it.name=="drawguess_choose"}.execute(args))
        assertTrue(reply.contains("unknown"));assertFalse(reply.contains("private connection detail"));assertEquals(1,api.calls)
    }
    @Test fun cancellationStillCancelsAndDoesNotBecomeConnectionError()=runTest {
        val api=Api();api.failure=CancellationException("cancelled")
        try {drawGuessTools(api,true){}.single{it.name=="drawguess_status"}.execute(buildJsonObject {});fail("must cancel")}
        catch(_:CancellationException){}
        assertEquals(1,api.calls)
    }
    @Test fun conflictReturnsCurrentTaskWithoutRetrying()=runTest {
        val api=Api();api.failure=MarketProblem(409,Json.parseToJsonElement("""{"error":"canvas_changed","my_draft_revision":2,"current_task_id":"current","remaining_ms":45000}""").jsonObject)
        val reply=text(drawGuessTools(api,true){}.single{it.name=="drawguess_canvas"}.execute(Json.parseToJsonElement("""{"task_id":"current","code":"cat","format":"ascii","revision":0}""")))
        assertTrue(reply.contains("my_draft_revision"));assertTrue(reply.contains("45000"));assertEquals(1,api.calls)
    }
    @Test fun leaderboardCanBeReadWithoutJoiningRoom()=runTest {
        var cursor=-1;var joined=false
        val tool=drawGuessLobbyTool(allowed={true},catalog={error("must not fetch catalog")},join={joined=true;buildJsonObject{}},leaderboard={cursor=it;buildJsonObject{put("total_players",55)}})
        val reply=text(tool.execute(Json.parseToJsonElement("""{"action":"leaderboard","cursor":50}""")))
        assertEquals(50,cursor);assertFalse(joined);assertTrue(reply.contains("55"))
    }
}
