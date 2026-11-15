package me.rerere.rikkahub.data.orbis.community

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

class CommunityPublishingTest {
    private val actor=UUID.randomUUID().toString()
    private val post=UUID.randomUUID().toString()
    private class Api: CommunityApi {
        override val owner=UUID.randomUUID().toString()
        val writes=mutableListOf<Triple<String,String,JsonObject>>()
        var failure: Exception?=null
        override suspend fun get(path: String,actor: String,quick: Boolean)=error("No implicit reads")
        override suspend fun post(path: String,actor: String,body: JsonObject): JsonObject {
            writes+=Triple(path,actor,body); failure?.let { throw it }
            return buildJsonObject { put("id",body.ms("id").ifEmpty { UUID.randomUUID().toString() }); put("ok",true); put("seq",19); put("deleted",path=="/comments" && body.ms("action")=="remove") }
        }
    }
    @Test fun bothVisibilitiesPublishAsBoundAiWithReceiptAndOnlyChosenText()=runBlocking {
        for(visibility in listOf("public","friends")) {
            val api=Api()
            val result=communityPublication(api,actor,communityBody("action" to "publish","text" to "主动写给社区的文字","visibility" to visibility))
            assertEquals(Triple("/posts",actor,communityBody("text" to "主动写给社区的文字","visibility" to visibility)),api.writes.single())
            assertEquals("community",result.ms("scope")); assertEquals(actor,result.ms("actor"))
            assertTrue(result.mb("published")); assertEquals(visibility,result.ms("visibility")); communityUuid(result.ms("id"))
        }
    }
    @Test fun missingAudienceForeignIdentityAndUnsupportedImagesNeverWrite()=runBlocking {
        val valid=communityBody("action" to "publish","text" to "explicit text","visibility" to "friends")
        for(args in listOf(JsonObject(valid-"visibility"),JsonObject(valid+mapOf("actor" to JsonPrimitive(UUID.randomUUID().toString()))),JsonObject(valid+mapOf("media" to JsonArray(emptyList()))),JsonObject(valid+mapOf("text" to JsonPrimitive(" "))))) {
            val api=Api(); assertTrue(runCatching { communityPublication(api,actor,args) }.isFailure); assertTrue(api.writes.isEmpty())
        }
    }
    @Test fun failedRequestNeverReportsPublicationAndNeverRetries()=runBlocking {
        val api=Api().apply { failure=IOException("ambiguous transport result") }
        assertTrue(runCatching { communityPublication(api,actor,communityBody("action" to "publish","text" to "once","visibility" to "public")) }.exceptionOrNull() is IOException)
        assertEquals(1,api.writes.size)
    }
    @Test fun immediateGroupSendAndOwnCommentDeleteHaveExplicitReceipts()=runBlocking {
        val api=Api();val mention=UUID.randomUUID().toString()
        val sent=communityPublication(api,actor,buildJsonObject {put("action","send_message");put("target",post);put("text","群内直接回复");put("mentions",JsonArray(listOf(JsonPrimitive(mention))))})
        assertTrue(sent.mb("sent"));assertEquals(post,sent.ms("room"));assertEquals(19,sent.mi("seq"))
        assertEquals("/messages",api.writes.single().first);assertEquals(actor,api.writes.single().second);assertEquals(mention,api.writes.single().third.cs("mentions").single())
        val deleted=communityPublication(api,actor,communityBody("action" to "delete_comment","target" to post))
        assertTrue(deleted.mb("deleted"));assertEquals(post,deleted.ms("id"));assertEquals("/comments",api.writes.last().first)
        assertTrue(runCatching {communityPublication(api,actor,communityBody("action" to "send_message","target" to post,"text" to " "))}.isFailure)
        assertEquals(2,api.writes.size)
    }
    @Test fun commentLikeAndDeleteUseCommunityEndpointsWithBoundIdentity()=runBlocking {
        val api=Api()
        communityPublication(api,actor,communityBody("action" to "comment","target" to post,"text" to "留言"))
        communityPublication(api,actor,buildJsonObject { put("action","like"); put("target",post); put("liked",false) })
        communityPublication(api,actor,communityBody("action" to "delete_post","target" to post))
        assertEquals(listOf("/comments","/likes","/posts"),api.writes.map { it.first })
        assertTrue(api.writes.all { it.second==actor }); assertFalse(api.writes[1].third.mb("liked"))
        assertEquals("remove",api.writes.last().third.ms("action"))
    }
    @Test fun feedBudgetKeepsPostsWholeAndContinuationDoesNotSkip() {
        val rows=(50 downTo 1).map { buildJsonObject { put("id",it.toString()); put("seq",it); put("text","中".repeat(4000)) } }
        val page=boundedCommunityFeed(buildJsonObject { put("items",JsonArray(rows)); put("next_before",JsonNull) })
        assertTrue(page.toString().toByteArray().size<60*1024)
        assertEquals(page.ma("items").last().cl("seq"),page.cl("next_before"))
        assertEquals(rows.take(page.ma("items").size),page.ma("items"))
        val rest=boundedCommunityFeed(buildJsonObject { put("items",JsonArray(rows.drop(page.ma("items").size))); put("next_before",1) })
        assertEquals(page.cl("next_before")-1,rest.ma("items").first().cl("seq"))
    }
}
