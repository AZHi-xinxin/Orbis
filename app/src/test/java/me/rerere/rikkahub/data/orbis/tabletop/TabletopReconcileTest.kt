package me.rerere.rikkahub.data.orbis.tabletop

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.MarketProblem
import org.junit.Assert.*
import org.junit.Test

class TabletopReconcileTest {
    private fun operation(id:String,phase:Int=5,action:String="search")=buildJsonObject {
        put("path","script_pass");put("body",buildJsonObject {
            put("room","original-room");put("request_id",id);put("phase_version",phase)
            put("action",action);put("target","role-3")
        })
    }
    @Test fun lostResponseReturnsOriginalSearchReceiptWithoutSpendingAnotherActionPoint()=runTest {
        val receipts=mutableMapOf<String,JsonObject>();val ids=mutableListOf<String>();var spent=0;var lose=true
        suspend fun server(op:JsonObject):JsonObject {
            val id=op["body"]!!.jsonObject["request_id"]!!.jsonPrimitive.content;ids.add(id)
            val receipt=receipts.getOrPut(id) { spent++;buildJsonObject { put("remaining_ap",0);put("clue","visible-clue") } }
            if(lose){lose=false;throw IOException("response lost after acceptance")}
            return receipt
        }
        val persisted=operation("original-id")
        try { server(persisted);fail("response should be lost") }catch(_:IOException){}
        val receipt=tabletopReconcileBeforeWrite(persisted,operation("new-id"),::server)
        assertEquals(0,receipt!!["remaining_ap"]!!.jsonPrimitive.int)
        assertEquals(1,spent);assertEquals(listOf("original-id","original-id"),ids)
    }
    @Test fun unknownOutcomeMustFinishBeforeAnotherActionIsSent()=runTest {
        var reached=0
        try {
            tabletopReconcileBeforeWrite(operation("original-id"),operation("later-id",action="pass")) {
                reached++;throw IOException("still offline")
            }
            fail("transport error must keep the original operation pending")
        }catch(_:IOException){}
        assertEquals(1,reached)
    }
    @Test fun DefinitivelyRejectedOldPhaseDoesNotBlockCurrentPhase()=runTest {
        val result=tabletopReconcileBeforeWrite(operation("old",4),operation("current",5)) {
            throw MarketProblem(409,buildJsonObject { put("message","phase changed") })
        }
        assertNull(result)
        assertFalse(tabletopSameOperation(operation("old",4),operation("current",5)))
    }
    @Test fun ExpiredConnectionRequiresReconnectBeforeOriginalRequestIsResolved()=runTest {
        for(status in listOf(401,403,408)) {
            try {
                tabletopReconcileBeforeWrite(operation("old"),operation("new")) {
                    throw MarketProblem(status,buildJsonObject { put("message","reconnect") })
                }
                fail("unresolved request must be retained")
            }catch(e:MarketProblem){assertEquals(status,e.status)}
        }
    }
    @Test fun rejectedSearchDoesNotReplaceNewSearchOrPublicSpeech()=runTest {
        val rejection=MarketProblem(403,buildJsonObject { put("error","clue_locked");put("message","线索尚未开放") })
        assertTrue(tabletopDefinitiveRejection(rejection))
        for(next in listOf(operation("new"),buildJsonObject {
            put("path","script_say");putJsonObject("body") { put("request_id","say");put("channel","public");put("text","公开发言") }
        })) {
            assertNull(tabletopReconcileBeforeWrite(operation("bad-target"),next) { throw rejection })
        }
        for(code in listOf("not_member","stale_lease","left_table","expired_ticket","not_seated","unknown")) {
            val error=MarketProblem(403,buildJsonObject { put("error",code) })
            assertFalse(tabletopDefinitiveRejection(error))
            try { tabletopReconcileBeforeWrite(operation("old"),operation("new")) { throw error };fail("must retain") }
            catch(e:MarketProblem) { assertSame(error,e) }
        }
        assertFalse(tabletopDefinitiveRejection(MarketProblem(500,buildJsonObject { put("error","clue_locked") })))
    }
}
