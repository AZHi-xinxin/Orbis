package me.rerere.rikkahub.data.orbis.community

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.orbis.market.ma
import me.rerere.rikkahub.data.orbis.market.mb
import org.junit.Assert.*
import org.junit.Test

class CommunityProjectionTest {
    @Test fun oneCountIncludingZeroAndDisableRemovesOnlyHostPart() {
        val user=UIMessage.user("原本人类输入：你在群聊中被艾特了9次")
        val first=projectCommunityHint(listOf(user),"你在群聊中被艾特了7次")
        val second=projectCommunityHint(first,"你在群聊中被艾特了0次")
        assertEquals(2,second.single().parts.size)
        assertEquals(user.parts,second.single().parts.take(1))
        assertEquals("你在群聊中被艾特了0次",(second.single().parts.last() as UIMessagePart.Text).text)
        assertEquals(listOf(user),projectCommunityHint(second,null))
        assertEquals(1,user.parts.size)
    }
    @Test fun boundedReadingKeepsFullMessagesAndTraversableCursors() {
        val rows=(1..50).map { i->buildJsonObject { put("seq",i); put("id",i.toString()); put("text","中".repeat(4000)) } }
        val page=buildJsonObject { put("items",JsonArray(rows)); put("more",false) }
        val recent=boundedCommunityRead(page,false)
        val early=boundedCommunityRead(page,true)
        assertTrue(recent.toString().toByteArray().size<=60*1024)
        assertTrue(early.toString().toByteArray().size<=60*1024)
        assertTrue(recent.mb("more")); assertTrue(early.mb("more"))
        assertEquals(50L,recent.cl("after_sequence")); assertEquals(1L,early.cl("before_sequence"))
        assertTrue(recent.ma("items").all { it["text"]!!.jsonPrimitive.content.length==4000 })
        assertTrue(recent.cl("before_sequence")>early.cl("after_sequence"))
    }
    @Test fun tinyPagesPreserveChronologyAndEmptyPageDoesNotInventCursor() {
        val rows=listOf(3,1,2).map { buildJsonObject { put("seq",it); put("text","a") } }
        val result=boundedCommunityRead(buildJsonObject { put("items",JsonArray(rows)); put("more",false) },false)
        assertEquals(listOf(1L,2L,3L),result.ma("items").map { it.cl("seq") })
        assertFalse(result.mb("more"))
        val empty=boundedCommunityRead(buildJsonObject { put("items",JsonArray(emptyList())) },true)
        assertFalse("before_sequence" in empty); assertFalse("after_sequence" in empty)
    }
}
