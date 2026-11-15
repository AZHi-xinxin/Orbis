package me.rerere.rikkahub.data.orbis.community

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.chatImageCode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CommunityChatImagesTest {
    private val owner=Uuid.random()
    private val actor=UUID.randomUUID().toString()
    private fun human(name:String,count:Int=1)=UIMessage.user("private-$name").copy(parts=(1..count).map { UIMessagePart.Image("file:///private/$name-$it.png") })
    private fun chat(vararg messages:UIMessage)=Conversation(assistantId=owner,messageNodes=messages.map { it.toMessageNode() })
    private suspend fun codes(source:CommunityChatImages)=source.page(communityBody("action" to "chat_images")).ma("items").map { it.ms("image_code") }
    private fun args(codes:List<String>)=buildJsonObject {put("action","publish");put("visibility","friends");put("image_codes",JsonArray(codes.map(::JsonPrimitive)))}
    private class Api:CommunityApi {
        override val owner=UUID.randomUUID().toString()
        val writes=mutableListOf<JsonObject>();val id=UUID.randomUUID().toString()
        var readFailure=false;var wrongMedia=false
        override suspend fun post(path:String,actor:String,body:JsonObject):JsonObject {
            assertTrue(path in setOf("/posts","/messages"));writes+=JsonObject(body+communityBody("actor" to actor,"id" to id)+mapOf("seq" to JsonPrimitive(42)))
            return buildJsonObject {put("id",id);put("seq",42);put("reply_to",body.ms("reply_to"));put("quote_notifies",false)}
        }
        override suspend fun get(path:String,actor:String,quick:Boolean):JsonObject {
            if(readFailure) throw IOException("readback failed")
            val row=if(wrongMedia) JsonObject(writes.last()+mapOf("media" to JsonArray(emptyList()))) else writes.last()
            if(path.startsWith("/rooms/")) {assertTrue(path.endsWith("?from=42&limit=1"));return buildJsonObject {put("items",JsonArray(listOf(row)))}}
            assertEquals("/posts/$id",path);return row
        }
    }
    @Test fun listsOnlyHumanDirectAttachmentsNewestFirstWithoutPathsOrChatText()=runTest {
        val old=human("old");val latest=human("latest",2)
        val ai=UIMessage.assistant("").copy(parts=listOf(UIMessagePart.Image("file:///private/generated.png")))
        val tool=UIMessage.user("").copy(parts=listOf(UIMessagePart.Tool(toolCallId="tool",toolName="photo",input="{}",output=listOf(UIMessagePart.Image("file:///private/tool.png")))))
        val c=chat(old,ai,tool,human("event").copy(isSynthetic=true),latest)
        val source=CommunityChatImages.capture(c,{c},{})
        val page=source.page(buildJsonObject {put("action","chat_images");put("limit",2)})
        assertEquals(3,page.mi("total"));assertEquals(2,page.mi("next_offset"))
        assertEquals(listOf(1,2),page.ma("items").map{it.mi("image_index")})
        assertTrue(page.ma("items").all{it.ms("message_id")==latest.id.toString()})
        assertFalse(page.toString().contains("/private/"));assertFalse(page.toString().contains("private-"))
        val tail=source.page(buildJsonObject {put("action","chat_images");put("offset",2)})
        assertEquals(old.id.toString(),tail.ma("items").single().ms("message_id"))
        assertEquals(JsonNull,tail["next_offset"])
        assertEquals(chatImageCode(owner,c.id,old.id,(old.parts.single() as UIMessagePart.Image).url),tail.ma("items").single().ms("image_code"))
    }
    @Test fun nineImagesPublishInSelectedOrderAndVerifyPersistedMedia()=runTest {
        val c=chat(human("nine",9));val source=CommunityChatImages.capture(c,{c},{})
        val picked=codes(source).reversed();val uploaded=mutableListOf<String>();val media=mutableListOf<String>();val api=Api()
        val result=communityPublication(api,actor,args(picked),source) { image ->uploaded+=image.url;UUID.randomUUID().toString().also{media+=it} }
        assertEquals((9 downTo 1).map{"file:///private/nine-$it.png"},uploaded)
        assertEquals(media,api.writes.single().cs("media"));assertEquals("",api.writes.single().ms("text"))
        assertTrue(result.mb("published"));assertTrue(result.mb("images_verified"));assertEquals(9,result.mi("image_count"))
        assertFalse(api.writes.single().toString().contains("orbisimg_"));assertFalse(api.writes.single().toString().contains("file:"))
    }
    @Test fun groupImagesAllowTextlessQuoteAndMentionButRejectFifthBeforeUpload()=runTest {
        val c=chat(human("group",5));val source=CommunityChatImages.capture(c,{c},{})
        val chosen=codes(source);val api=Api();val room=UUID.randomUUID().toString();val quote=UUID.randomUUID().toString();val mention=UUID.randomUUID().toString()
        fun request(values:List<String>)=buildJsonObject {put("action","send_message");put("target",room);put("reply_to",quote);put("mentions",JsonArray(listOf(JsonPrimitive(mention))));put("image_codes",JsonArray(values.map(::JsonPrimitive)))}
        var uploads=0
        assertTrue(runCatching {communityPublication(api,actor,request(chosen),source){uploads++;UUID.randomUUID().toString()}}.isFailure)
        assertEquals(0,uploads);assertTrue(api.writes.isEmpty())
        val result=communityPublication(api,actor,request(chosen.take(4)),source){uploads++;UUID.randomUUID().toString()}
        assertEquals(4,uploads);assertTrue(result.mb("sent"));assertTrue(result.mb("images_verified"));assertEquals(room,result.ms("room"));assertEquals(4,result.mi("image_count"))
        assertEquals("",api.writes.single().ms("text"));assertEquals(quote,api.writes.single().ms("reply_to"));assertEquals(listOf(mention),api.writes.single().cs("mentions"))
    }
    @Test fun invalidArgumentsAndForeignCodesNeverUploadOrPost()=runTest {
        val c=chat(human("ours"));val source=CommunityChatImages.capture(c,{c},{})
        val selected=codes(source);val api=Api();var uploads=0
        val foreign=chatImageCode(owner,Uuid.random(),c.currentMessages.single().id,"file:///private/ours-1.png")
        for(a in listOf(args(selected+foreign),args(List(10){selected.single()}),args(selected+selected),args(listOf("file:///private/token")),JsonObject(args(selected)-"visibility"),JsonObject(args(selected)+mapOf("text" to JsonPrimitive(4))),JsonObject(args(selected)+mapOf("mentions" to JsonArray(listOf(JsonPrimitive("bad"))))))) {
            assertTrue(runCatching {communityPublication(api,actor,a,source){uploads++;UUID.randomUUID().toString()}}.isFailure)
        }
        assertEquals(0,uploads);assertTrue(api.writes.isEmpty())
    }
    @Test fun branchOwnerNewTurnAndRemovedImagePreventPublication()=runTest {
        val h=human("one");val c=chat(h);var live:Conversation?=c
        val source=CommunityChatImages.capture(c,{live},{})
        val chosen=codes(source);val api=Api();var uploads=0
        val mutations=listOf(null,c.copy(id=Uuid.random()),c.copy(assistantId=Uuid.random()),c.copy(compactionEpoch=1),
            c.copy(messageNodes=c.messageNodes+UIMessage.user("new turn").toMessageNode()),
            c.copy(messageNodes=listOf(h.copy(parts=listOf(UIMessagePart.Text("deleted"))).toMessageNode())),
            c.copy(messageNodes=listOf(h.copy(isSynthetic=true).toMessageNode())))
        for(bad in mutations) {
            live=bad;assertTrue(runCatching {communityPublication(api,actor,args(chosen),source){uploads++;UUID.randomUUID().toString()}}.isFailure)
        }
        assertEquals(0,uploads);assertTrue(api.writes.isEmpty())
    }
    @Test fun uploadFailureDoesNotPublishTextOnlyOrRetry()=runTest {
        val c=chat(human("pair",2));val source=CommunityChatImages.capture(c,{c},{})
        val api=Api();var uploads=0
        assertTrue(runCatching {communityPublication(api,actor,JsonObject(args(codes(source))+communityBody("text" to "chosen text")),source) {
            uploads++;if(uploads==2) throw IOException("upload failed") else UUID.randomUUID().toString()
        }}.isFailure)
        assertEquals(2,uploads);assertTrue(api.writes.isEmpty())
    }
    @Test fun permissionOrChatChangeDuringUploadStopsBeforePost()=runTest {
        val c=chat(human("one"));var live=c;var enabled=true
        val source=CommunityChatImages.capture(c,{live},{check(enabled)})
        val selected=codes(source);val api=Api()
        assertTrue(runCatching {communityPublication(api,actor,args(selected),source){enabled=false;UUID.randomUUID().toString()}}.isFailure)
        enabled=true
        assertTrue(runCatching {communityPublication(api,actor,args(selected),source){live=c.copy(messageNodes=c.messageNodes+UIMessage.user("next").toMessageNode());UUID.randomUUID().toString()}}.isFailure)
        assertTrue(api.writes.isEmpty())
    }
    @Test fun missingOrMismatchedReadbackDoesNotClaimVerifiedOrRetryPost()=runTest {
        val c=chat(human("one"));val source=CommunityChatImages.capture(c,{c},{})
        for(badMedia in listOf(false,true)) {
            val api=Api().apply{readFailure=!badMedia;wrongMedia=badMedia}
            val err=runCatching {communityPublication(api,actor,args(codes(source)),source){UUID.randomUUID().toString()}}.exceptionOrNull()
            assertNotNull(err);assertTrue(err!!.message!!.contains(api.id));assertTrue(err.message!!.contains("不要重复publish"))
            assertEquals(1,api.writes.size)
        }
    }
}
