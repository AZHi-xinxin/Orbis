package me.rerere.rikkahub.data.orbis.tabletop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WerewolfEndingTest {
    private fun ended()=buildJsonObject {
        put("game_kind","werewolf");put("stage","ended");put("winner","wolves");put("phase_version",57)
        put("night_info",buildJsonObject { put("private","secret-target") })
        put("roles",buildJsonArray {
            for(seat in 1..10)add(buildJsonObject { put("seat",seat);put("name","玩家$seat");put("role",if(seat<=3)"狼人" else "好人") })
        })
    }
    @Test fun finalNotificationIncludesWinnerEverySeatAndCommunityDestinationOnlyAfterEnding() {
        val snapshot=werewolfEndingSnapshot(ended());val text=tabletopReminder(snapshot,0)
        assertTrue(text.contains("狼人阵营获胜"));assertTrue(text.contains(WEREWOLF_COMMUNITY_RECAP))
        for(seat in 1..10)assertTrue(text.contains("${seat}号 玩家$seat："))
        assertFalse(snapshot.containsKey("night_info"));assertFalse(text.contains("secret-target"))
        assertFalse(text.contains("轮到你的回合了"))
        val playing=JsonObject(ended()+mapOf("stage" to JsonPrimitive("playing")))
        assertThrows(IllegalArgumentException::class.java) { werewolfEndingSnapshot(playing) }
        assertFalse(tabletopReminder(playing,0).contains("玩家1"))
    }
    @Test fun savedResultCanBeReadOrDetachedButNeverExecutesAnOldAction()=runTest {
        val api=WerewolfEndingApi("me","finished-room",ended())
        val state=api.game("script_status",asAi=true)
        assertEquals(10,state["roles"]!!.jsonArray.size)
        val action=api.game("script_say",buildJsonObject {put("phase_version",57);put("text","旧行动")},asAi=true)
        assertEquals("game_ended",action["detail"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        val leave=api.game("script_pass",buildJsonObject {put("action","leave")},asAi=true)
        assertTrue(leave["left"]!!.jsonPrimitive.boolean);assertTrue(leave["local_only"]!!.jsonPrimitive.boolean)
    }
    @Test fun savedMixedEndingKeepsHumanControlOfPairedDeparture()=runTest {
        val api=WerewolfEndingApi("me","finished-room",JsonObject(ended()+mapOf("mode" to JsonPrimitive("mixed"))))
        assertTrue(api.latestStatus["allowed_actions"]!!.jsonArray.isEmpty())
        val leave=api.game("script_pass",buildJsonObject {put("action","leave")},asAi=true)
        assertEquals("human_control_required",leave["detail"]!!.jsonObject["error"]!!.jsonPrimitive.content)
        assertFalse(leave.containsKey("left"))
        val message=tabletopReminder(api.latestStatus,0)
        assertTrue(message.contains("我和助手一起离席"))
        assertFalse(message.contains("werewolf_leave"))
        val pure=tabletopReminder(JsonObject(ended()+mapOf("mode" to JsonPrimitive("ai_only"))),0)
        assertTrue(pure.contains("werewolf_leave"))
    }
}
