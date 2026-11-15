package me.rerere.rikkahub.data.orbis.community

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

class CommunityContactsTest {
    private val ai=UUID.randomUUID().toString()
    private val other=UUID.randomUUID().toString()
    private inner class Api: CommunityApi {
        override val owner="synthetic-owner"
        val reads=mutableListOf<String>();val writes=mutableListOf<JsonObject>()
        var legacy=false;var fail=false
        override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject {
            assertEquals(ai,actor);reads+=path
            return if(path.startsWith("/profile")) communityBody("id" to other,"name" to "测试申请人","number" to "1234567890","kind" to "ai")
            else buildJsonObject {put("requests",JsonArray(listOf(communityBody("id" to other,"name" to "测试申请人","number" to "1234567890"))));put("request_count",3);put("next_request_offset",2);put("items",JsonArray(emptyList()))}
        }
        override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject {
            assertEquals(ai,actor);assertEquals("/friends",path);writes+=body
            if(fail) throw MarketProblem(409,communityBody("error" to "request_missing"))
            return buildJsonObject {put("ok",true);if(!legacy) {put("request_processed",true);put("request_state",body.ms("action"));put("friend",body.ms("action")=="accept")}}
        }
    }
    @Test fun requestsReadImmediatelyAndBothCursorsAreKept()=runBlocking {
        val api=Api();val result=communityRead(api,ai,buildJsonObject {put("action","read");put("target","contacts");put("limit",1);put("offset",4);put("request_offset",1)})
        assertEquals("/contacts?limit=1&offset=4&request_offset=1",api.reads.single());assertTrue(api.writes.isEmpty())
        assertEquals(3,result.mi("request_count"));assertEquals(2,result.mi("next_request_offset"));assertEquals("测试申请人",result.ma("requests").single().ms("name"))
    }
    @Test fun numberAndUuidAcceptDeclineResolveSameMember()=runBlocking {
        for(action in listOf("accept","decline")) for(target in listOf(other,"1234567890")) {
            val api=Api();val result=communityFriendAction(api,ai,communityBody("action" to action,"target" to target))
            assertEquals(other,api.writes.single().ms("target"));assertEquals(action,api.writes.single().ms("action"))
            assertTrue(result.mb("request_processed"));assertEquals("1234567890",result["target"]!!.jsonObject.ms("number"))
        }
    }
    @Test fun oldReceiptNotOverclaimedMissingRequestAndForeignIdentityNeverAccepted()=runBlocking {
        val args=communityBody("action" to "accept","target" to other)
        assertFalse(communityFriendAction(Api().apply {legacy=true},ai,args).mb("request_processed"))
        val missing=Api().apply {fail=true};assertTrue(runCatching {communityFriendAction(missing,ai,args)}.exceptionOrNull() is MarketProblem);assertEquals(1,missing.writes.size)
        val foreign=Api();assertTrue(runCatching {communityFriendAction(foreign,ai,JsonObject(args+mapOf("actor" to JsonPrimitive(other))))}.isFailure);assertTrue(foreign.reads.isEmpty());assertTrue(foreign.writes.isEmpty())
    }
}
