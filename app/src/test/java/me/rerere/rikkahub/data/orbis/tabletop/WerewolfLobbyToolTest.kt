package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class WerewolfLobbyToolTest {
    private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
    @Test fun endedMixedAdmissionExplainsHumanRecoveryAndPreservesStructuredError()=runTest {
        val problem=werewolfPreviousRoomProblem(obj("""{"room":"old-room","stage":"ended","mode":"mixed"}"""))
        val tool=werewolfLobbyTool({true},{obj("{}")} ,{throw problem})
        val result=obj((tool.execute(obj("""{"action":"join","room":"new-room"}""")).single() as UIMessagePart.Text).text)
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("human_control_required",result["error"]!!.jsonPrimitive.content)
        assertEquals("old-room",result["current_room"]!!.jsonPrimitive.content)
        assertFalse(result["retryable"]!!.jsonPrimitive.boolean)
        assertTrue(result["next_step"]!!.jsonPrimitive.content.contains("我和助手一起离席"))
        assertFalse(result["next_step"]!!.jsonPrimitive.content.contains("werewolf_leave"))
    }
    @Test fun oldServerAndPlayingTablesDoNotPretendTheyCanAutoRelease() {
        val legacy=werewolfPreviousRoomProblem(obj("""{"room":"old","ended":true}""" )).details
        assertEquals("ended",legacy["stage"]!!.jsonPrimitive.content)
        assertTrue(legacy["message"]!!.jsonPrimitive.content.contains("若原桌是人机共玩"))
        val playing=werewolfPreviousRoomProblem(obj("""{"room":"old","stage":"playing","mode":"mixed"}""")).details
        assertEquals("game_in_progress",playing["error"]!!.jsonPrimitive.content)
        assertTrue(playing["next_step"]!!.jsonPrimitive.content.contains("不能退出"))
        assertFalse(playing["next_step"]!!.jsonPrimitive.content.contains("一起离席"))
        val pure=werewolfPreviousRoomProblem(obj("""{"room":"old","stage":"ended","mode":"ai_only"}""")).details
        assertTrue(pure["next_step"]!!.jsonPrimitive.content.contains("werewolf_leave"))
    }
    @Test fun disabledDoesNotReadOrJoinAndListShowsOnlyAiTables()=runTest {
        var enabled=false;var reads=0;var joins=0
        val tool=werewolfLobbyTool({enabled},{reads++;obj("""{"rooms":[{"id":"ai","mode":"ai_only"},{"id":"mixed","mode":"mixed"}]}""")},{joins++;obj("""{"joined":true}""")})
        tool.execute(obj("""{"action":"join","room":"ai"}"""));assertEquals(0,joins);assertEquals(0,reads)
        enabled=true
        val result=obj((tool.execute(obj("""{"action":"list"}""")).single() as UIMessagePart.Text).text)
        assertEquals(1,reads);assertEquals(0,joins);assertEquals("ai",result["rooms"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        tool.execute(obj("""{"action":"join","room":"ai"}"""));assertEquals(1,joins)
    }
    @Test fun waitingAiHasRealLeaveToolAndStopsBindingOnlyAfterSuccess()=runTest {
        var departed=0;var succeed=false
        val api=object:TabletopGameApi {
            override val assistantId="own-ai"
            override val room="test-room"
            override val gameKind="werewolf"
            override val latestStatus=obj("""{"allowed_actions":["ready","leave"]}""")
            override fun pending(asAi:Boolean)=false
            override suspend fun roleImage(name:String)=""
            override suspend fun game(path:String,body:JsonObject?,params:Map<String,String>,asAi:Boolean):JsonObject {
                assertTrue(asAi);assertEquals("script_pass",path);assertEquals("leave",body!!["action"]!!.jsonPrimitive.content)
                return obj(if(succeed)"""{"ok":true,"left":true}""" else """{"status":"rejected"}""")
            }
        }
        val leave=werewolfTools(api,onLeave={departed++}){}.single{it.name=="werewolf_leave"}
        leave.execute(obj("{}"));assertEquals(0,departed)
        succeed=true;leave.execute(obj("{}"));assertEquals(1,departed)
    }
}
