package me.rerere.rikkahub.data.orbis.community

import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test

class CommunityRemindersTest {
    private class Api : CommunityApi {
        override val owner = "owner"
        val writes = mutableListOf<Triple<String,String,JsonObject>>()
        var failure = false
        var legacy = false
        override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject = error("Do not widen a snapshot")
        override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject {
            writes += Triple(path,actor,body)
            if(failure) throw IOException("unknown")
            if(legacy) return buildJsonObject { put("cleared_through",0) }
            return buildJsonObject {
                put("newer_hints_preserved",true)
                putJsonObject("cleared_cursors") {
                    if("group_cursor" in body) put("groups",body.cl("group_cursor"))
                    if("feed_cursor" in body) put("feed",body.cl("feed_cursor"))
                }
            }
        }
    }
    private fun args(target:String="all")=buildJsonObject { put("action","clear_hints");put("target",target);put("group_cursor",12);put("feed_cursor",8) }
    @Test fun explicitSnapshotUsesBoundIdentityOnceAndKeepsChannelsSeparate()=runBlocking {
        for(target in listOf("groups","feed","all")) {
            val api=Api();communityClearHints(api,"bound-ai",args(target))
            val (path,actor,b)=api.writes.single()
            assertEquals("/hints/clear",path);assertEquals("bound-ai",actor)
            assertEquals(target!="feed","group_cursor" in b);assertEquals(target!="groups","feed_cursor" in b)
        }
    }
    @Test fun missingInvalidCursorOrForeignIdentityNeverWrites()=runBlocking {
        for(b in listOf(JsonObject(args()-"feed_cursor"),JsonObject(args()+mapOf("group_cursor" to JsonPrimitive(-1))),JsonObject(args()+mapOf("feed_cursor" to JsonPrimitive("8"))),JsonObject(args()+mapOf("actor" to JsonPrimitive("other"))),args("unknown"))) {
            val api=Api();assertTrue(runCatching {communityClearHints(api,"ai",b)}.isFailure);assertTrue(api.writes.isEmpty())
        }
    }
    @Test fun TimeoutOrLegacyReceiptIsNotSuccessAndDoesNotRetry()=runBlocking {
        for(api in listOf(Api().apply {failure=true},Api().apply {legacy=true})) {
            assertTrue(runCatching {communityClearHints(api,"ai",args())}.isFailure);assertEquals(1,api.writes.size)
        }
    }
    @Test fun injectedInstructionCarriesObservedSnapshotOnlyWhenSupported() {
        val data=buildJsonObject {put("clear_hints_supported",true);put("group_cursor",12);put("feed_cursor",8)}
        val report=communityHintReport(data)
        assertTrue(report.contains("若已回复可调用工具清空旧提醒"));assertTrue(report.contains("group_cursor=12"));assertTrue(report.contains("feed_cursor=8"))
        assertFalse(communityHintReport(buildJsonObject {}).contains("action=clear_hints"))
    }
}
