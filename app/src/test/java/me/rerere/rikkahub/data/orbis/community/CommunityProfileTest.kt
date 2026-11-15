package me.rerere.rikkahub.data.orbis.community

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CommunityProfileTest {
    private val ai=UUID.randomUUID().toString()
    private val other=UUID.randomUUID().toString()
    private inner class Api: CommunityApi {
        override val owner="synthetic"
        val reads=mutableListOf<String>();val writes=mutableListOf<Pair<String,JsonObject>>()
        var bio="原来的介绍";var wrongReceipt=false
        override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject {
            assertEquals(ai,actor);reads+=path
            return communityBody("id" to if(path=="/profile") ai else other,"bio" to bio,"name" to "保留昵称","number" to "1234567890")
        }
        override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject {
            assertEquals(ai,actor);writes+=path to body
            return if(path=="/profile") {bio=body.ms("bio");communityBody("id" to if(wrongReceipt) other else ai,"bio" to bio,"name" to "保留昵称")}
            else buildJsonObject {put("ok",true);if(path=="/rooms") {put("renamed",true);put("room",body.ms("room"));put("title",body.ms("title"));put("actor",actor)}}
        }
    }
    @Test fun signatureIsFreeTextOrEmptyAndWritesOnlySelfBio()=runBlocking {
        val api=Api()
        for(bio in listOf("XX家的小可爱","🌟".repeat(200),"")) {
            val r=communityProfile(api,ai,communityBody("action" to "profile","operation" to "save","bio" to bio))
            assertTrue(r.mb("saved"));assertEquals(bio,r["profile"]!!.jsonObject.ms("bio"))
            assertEquals(setOf("bio"),api.writes.last().second.keys)
        }
        assertEquals("none",communityProfile(api,ai,communityBody("operation" to "read")).ms("instruction_authority"))
    }
    @Test fun targetProfileReadBeforeApplyingNeverWritesOrChangesIdentity()=runBlocking {
        val api=Api()
        for(target in listOf("1234567890",other)) {
            val r=communityProfile(api,ai,communityBody("action" to "profile","operation" to "read","target" to target))
            assertEquals(other,r["profile"]!!.jsonObject.ms("id"));assertEquals("原来的介绍",r["profile"]!!.jsonObject.ms("bio"))
        }
        assertTrue(api.writes.isEmpty());assertEquals(listOf("/profile?number=1234567890","/profile?id=$other"),api.reads)
    }
    @Test fun invalidOrForeignSaveStopsBeforeAnyRequestAndBadReceiptIsNotSuccess()=runBlocking {
        val api=Api();val args=communityBody("action" to "profile","operation" to "save","bio" to "心情")
        val bad=listOf(JsonObject(args+("target" to JsonPrimitive(other))),JsonObject(args+("actor" to JsonPrimitive(ai))),
            JsonObject(args-"bio"),JsonObject(args+("bio" to JsonNull)),JsonObject(args+("bio" to JsonPrimitive("x".repeat(201)))))
        bad.forEach {assertTrue(runCatching {communityProfile(api,ai,it)}.isFailure)}
        assertTrue(api.reads.isEmpty());assertTrue(api.writes.isEmpty())
        api.wrongReceipt=true;assertTrue(runCatching {communityProfile(api,ai,args)}.isFailure);assertEquals(1,api.writes.size)
    }
    @Test fun friendRequestAllowsOmittedBlankAndWrittenGreetingWithoutSignatureEnforcement()=runBlocking {
        for(note in listOf(null,"","你好，我是来认识新朋友的")) {
            val api=Api().apply {bio="加我好友请告诉我你是谁"}
            val args=buildJsonObject {put("action","add_friend");put("target","1234567890");if(note!=null) put("note",note)}
            val r=communityFriendAction(api,ai,args)
            assertEquals(note.orEmpty(),api.writes.single().second.ms("note"));assertEquals(other,api.writes.single().second.ms("target"))
            assertEquals(api.bio,r["target"]!!.jsonObject.ms("bio"))
        }
        val api=Api()
        for(action in listOf("accept","decline","block","add_friend")) {
            val args=communityBody("action" to action,"target" to other,"note" to "x".repeat(121))
            assertTrue(runCatching {communityFriendAction(api,ai,args)}.isFailure)
        }
        assertTrue(api.reads.isEmpty());assertTrue(api.writes.isEmpty())
    }
    @Test fun uiLimitKeepsUnicodeCharactersWhole() {
        assertEquals("🌟".repeat(200),communityPublicTextLimit("🌟".repeat(201),200))
    }
    @Test fun renameUsesBoundActorAndRequiresExactReceiptAndValidTitle()=runBlocking {
        val api=Api();val args=communityBody("action" to "manage","operation" to "rename","target" to other,"title" to "我们的小群")
        val r=communityRenameGroup(api,ai,args)
        assertTrue(r.mb("renamed"));assertEquals(ai,r.ms("actor"));assertEquals(other,r.ms("room"))
        assertEquals("rename",api.writes.single().second.ms("action"))
        for(title in listOf("", "  ","x".repeat(61))) {
            assertTrue(runCatching {communityRenameGroup(api,ai,JsonObject(args+("title" to JsonPrimitive(title))))}.isFailure)
        }
        assertTrue(runCatching {communityRenameGroup(api,ai,JsonObject(args+("actor" to JsonPrimitive(other))))}.isFailure)
        assertEquals(1,api.writes.size)
    }
}
