package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommunityHintPreferencesTest {
    private val data=buildJsonObject {
        put("mention_count",2);put("group_mention_count",2);put("feed_mention_count",3)
        putJsonArray("items") {addJsonObject {put("title","group-secret")}}
        putJsonArray("feed_items") {addJsonObject {put("text","feed-secret");put("post","post-id")}}
    }
    @Test fun exactTwoChannelWordingAndReadEntrypoints() {
        val report=communityHintReport(data)
        assertTrue(report.startsWith("你在群聊中被艾特了2条，在朋友圈中被艾特了3条。"))
        assertTrue(report.contains("read_post"));assertTrue(report.contains("post-id"))
        assertTrue(report.contains("only_mentions=true"));assertTrue(report.contains("enabled=false"))
    }
    @Test fun switchesAreIndependentDoNotExposeDisabledChannelAndCanReopen() {
        val both=CommunityHintPreferences()
        val feedOnly=both.update("groups",false)
        assertTrue(feedOnly.enabled("all"));assertFalse(feedOnly.enabled("groups"));assertTrue(feedOnly.enabled("feed"))
        assertFalse(communityHintReport(data,feedOnly).contains("group-secret"))
        assertTrue(communityHintReport(data,feedOnly).startsWith("你在朋友圈中被艾特了3条。"))
        val groupOnly=both.update("feed",false)
        assertFalse(communityHintReport(data,groupOnly).contains("feed-secret"))
        assertEquals("",communityHintReport(data,both.update("all",false)))
        assertEquals(both,feedOnly.update("groups",true))
        assertEquals(feedOnly,CommunityHintPreferences.parse(feedOnly.json()))
    }
    @Test fun legacyDisabledStaysDisabledAndInvalidScopeDoesNotChangeState() {
        val old=CommunityHintPreferences.parse(buildJsonObject {put("enabled",false)})
        assertEquals(CommunityHintPreferences(false,false),old)
        assertTrue(runCatching {old.update("someone-else",true)}.isFailure)
        assertEquals(CommunityHintPreferences(false,false),old)
    }
}
