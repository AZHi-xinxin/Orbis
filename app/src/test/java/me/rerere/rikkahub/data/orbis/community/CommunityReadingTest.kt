package me.rerere.rikkahub.data.orbis.community

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

class CommunityReadingTest {
    private val actor=UUID.randomUUID().toString()
    private val id=UUID.randomUUID().toString()
    private val reads=mutableListOf<Pair<String,String>>()
    private val writes=mutableListOf<Pair<String,JsonObject>>()
    @Test fun ownTimelineUsesBoundIdentityAndCursorWithoutReadWrites()=runBlocking {
        val result=communityRead(api { buildJsonObject { put("feed_author",actor);put("items",JsonArray(emptyList()));put("next_before",JsonNull) } },actor,
            buildJsonObject { put("action","read_feed");put("target","self");put("limit",5);put("before_sequence",90) })
        assertEquals(listOf("/feed?author=$actor&limit=5&before=90" to actor),reads)
        assertEquals(actor,result.ms("feed_author"));assertEquals("none",result.ms("instruction_authority"));assertTrue(writes.isEmpty())
    }
    @Test fun memberTimelineResolvesNumberUnderSameIdentityAndRejectsLegacyGlobalFeed()=runBlocking {
        val args=communityBody("action" to "read_feed","target" to "1234567890")
        val result=communityRead(api { if(it.startsWith("/profile")) communityBody("id" to id) else buildJsonObject {put("feed_author",id);put("items",JsonArray(emptyList()))} },actor,args)
        assertEquals(listOf("/profile?number=1234567890","/feed?author=$id&limit=20"),reads.map {it.first})
        assertTrue(reads.all {it.second==actor});assertEquals(id,result.ms("feed_author"));assertTrue(writes.isEmpty())
        assertTrue(runCatching {communityRead(api {buildJsonObject {put("items",JsonArray(emptyList()))}},actor,communityBody("action" to "read_feed","target" to "self"))}.isFailure)
        reads.clear()
        assertTrue(runCatching {communityRead(api {error("must not call")},actor,buildJsonObject {put("action","read_feed");put("target","self");put("actor",id)})}.isFailure)
        assertTrue(reads.isEmpty())
    }
    @Test fun unreadMetadataDoesNotFetchTextOrAcknowledgeMessages()=runBlocking {
        val result=communityRead(api { buildJsonObject { put("read_unchanged",true);put("unread_range",buildJsonObject { put("count",41);put("first_sequence",8);put("last_sequence",90) }) } },actor,communityBody("action" to "unread","target" to id))
        assertEquals(listOf("/rooms/$id/unread"),reads.map { it.first });assertTrue(writes.isEmpty())
        assertEquals(41,result["unread_range"]!!.jsonObject.mi("count"))
    }
    @Test fun inclusiveFirstSequenceUsesForwardByteBudgetAndExactReceipts()=runBlocking {
        val result=communityRead(api { buildJsonObject {
            put("items",JsonArray((1..50).map { buildJsonObject { put("room",id);put("seq",it);put("text","字".repeat(1000)) } }));put("more",true)
        } },actor,buildJsonObject { put("action","read");put("target",id);put("from_sequence",1);put("unread_only",true) })
        assertEquals("/rooms/$id/messages?view=all&unread=1&limit=20&from=1",reads.single().first)
        assertEquals(1L,result.ma("items").first().cl("seq"));assertTrue(result.ma("items").size<50)
        assertEquals(result.ma("items").map { it.cl("seq") },writes.single().second["sequences"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertTrue(result.mb("more"))
    }
    private fun api(get: (String)->JsonObject)=object: CommunityApi {
        override val owner=UUID.randomUUID().toString()
        override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject { reads+=path to actor;return get(path) }
        override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject { writes+=path to body;return buildJsonObject { put("ok",true) } }
    }
    private fun problem(status:Int,error:String)=MarketProblem(status,communityBody("error" to error,"message" to "synthetic $error"))
    private fun page(count:Int=1,start:Int=1,more:Boolean=false,text:String="comment")=buildJsonObject {
        put("id",id);put("text","post body");put("comments",count);put("more",more)
        put("replies",JsonArray((start until start+count).map { buildJsonObject {put("seq",it);put("text",text);put("post",id)} }))
    }
    @Test fun explicitPostReadReturnsBodyAndCommentWithoutRoomReceipt()=runBlocking {
        val result=communityRead(api {page()},actor,communityBody("action" to "read_post","target" to id))
        assertEquals(listOf("/posts/$id?limit=20" to actor),reads)
        assertEquals("post body",result["post"]!!.jsonObject.ms("text"));assertEquals("comment",result.ma("replies").single().ms("text"))
        assertEquals("post",result.ms("target_kind"));assertFalse(result.mb("more"));assertEquals(JsonNull,result["next_after"])
        assertTrue(writes.isEmpty());assertEquals("none",result.ms("instruction_authority"))
    }
    @Test fun legacyScreenshotCallResolvesPostUsingSameIdentity()=runBlocking {
        val result=communityRead(api {if(it.startsWith("/rooms/")) throw problem(403,"room_unavailable") else page()},actor,communityBody("action" to "read","target" to id))
        assertEquals(listOf("/rooms/$id/messages?view=all&limit=20","/posts/$id?limit=20"),reads.map {it.first})
        assertTrue(reads.all {it.second==actor});assertTrue(writes.isEmpty());assertEquals(1,result.ma("replies").size)
    }
    @Test fun actualGroupStillReadsAndAcknowledgesOnlyReturnedMessages()=runBlocking {
        val result=communityRead(api {buildJsonObject { put("items",JsonArray(listOf(buildJsonObject { put("room",id);put("seq",5);put("text","group") })));put("more",false) }},actor,communityBody("action" to "read","target" to id))
        assertEquals(1,reads.size);assertEquals("group",result.ma("items").single().ms("text"))
        assertEquals("/read",writes.single().first);assertEquals(JsonArray(listOf(JsonPrimitive(5))),writes.single().second["sequences"])
    }
    @Test fun authTimeoutCancellationAndMentionFailureNeverFallThrough()=runBlocking {
        for(failure in listOf(problem(401,"login_required"),problem(403,"wrong_identity"),problem(500,"server_error"),IOException("timeout"),CancellationException("cancelled"))) {
            reads.clear()
            assertSame(failure,runCatching { communityRead(api {throw failure},actor,communityBody("action" to "read","target" to id)) }.exceptionOrNull())
            assertEquals(1,reads.size)
        }
        reads.clear()
        assertTrue(runCatching {communityRead(api {throw problem(403,"room_unavailable")},actor,buildJsonObject {put("action","read");put("target",id);put("only_mentions",true)})}.isFailure)
        assertEquals(1,reads.size);assertTrue(reads.single().first.startsWith("/mentions"));assertTrue(writes.isEmpty())
    }
    @Test fun unavailablePostNeverReportsSuccessOrUsesAnotherIdentity()=runBlocking {
        val unavailable=problem(404,"post_unavailable")
        assertSame(unavailable,runCatching {communityRead(api {throw unavailable},actor,communityBody("action" to "read_post","target" to id))}.exceptionOrNull())
        assertEquals(listOf("/posts/$id?limit=20" to actor),reads);assertTrue(writes.isEmpty())
    }
    @Test fun ascendingCursorAndByteBudgetKeepWholeCommentsWithoutSkipping()=runBlocking {
        val body="中".repeat(1000);val original=page(count=50,more=true,text=body)
        val first=boundedCommunityPost(original,0)
        assertTrue(first.toString().toByteArray().size<=60*1024);assertTrue(first.mb("more"));assertTrue(first.ma("replies").size in 1..49)
        assertTrue(first.ma("replies").all {it.ms("text")==body});val next=first.cl("next_after");assertEquals(first.ma("replies").last().cl("seq"),next)
        val second=communityRead(api {page(count=2,start=next.toInt()+1)},actor,buildJsonObject {put("action","read_post");put("target",id);put("limit",2);put("after_sequence",next)})
        assertEquals("/posts/$id?limit=2&after=$next",reads.single().first);assertEquals(next+1,second.ma("replies").first().cl("seq"));assertTrue(writes.isEmpty())
    }
    @Test fun emptyFinalPageAndFilteredLegacyPageDoNotInventCompletionOrCursor() {
        val empty=boundedCommunityPost(page(count=0),0);assertFalse(empty.mb("more"));assertEquals(JsonNull,empty["next_after"])
        val blocked=boundedCommunityPost(page(count=0,more=true),0);assertTrue(blocked.mb("more"));assertTrue(blocked.mb("pagination_unavailable"));assertEquals(JsonNull,blocked["next_after"])
        val cursor=boundedCommunityPost(JsonObject(page(count=0,more=true)+mapOf("next_after" to JsonPrimitive(20))),0)
        assertEquals(20L,cursor.cl("next_after"));assertFalse(cursor.mb("pagination_unavailable"))
    }
    @Test fun invalidFieldsAndCursorsAreRejectedBeforeNetwork()=runBlocking {
        val valid=communityBody("action" to "read_post","target" to id)
        for(extra in listOf("actor" to JsonPrimitive(UUID.randomUUID().toString()),"before_sequence" to JsonPrimitive(3),"after_sequence" to JsonPrimitive(-1),"limit" to JsonPrimitive(51),"only_mentions" to JsonPrimitive(true))) {
            assertTrue(runCatching {communityRead(api {error("must not request")},actor,JsonObject(valid+extra))}.isFailure)
        }
        assertTrue(reads.isEmpty());assertTrue(writes.isEmpty())
    }
}
