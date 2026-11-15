package me.rerere.rikkahub.data.orbis.community

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.market.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic local files and transport only; no real account or network. */
class CommunityChatImageDeviceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val assistant=Uuid.random();private val actor=UUID.randomUUID().toString()
    private class Transport(override val owner:String=UUID.randomUUID().toString(),val handler:suspend(String,String,JsonObject?,String?)->JsonObject):CommunityTransport {
        override suspend fun request(method:String,path:String,body:JsonObject?,id:String?,quick:Boolean)=handler(method,path,body,id)
    }
    private fun image(index:Int):UIMessagePart.Image {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        val file=File(context.cacheDir,"community-ai-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(2100,1200,Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(30+index*15,80,160))
            file.outputStream().use {compress(Bitmap.CompressFormat.PNG,100,it)};recycle()
        }
        return UIMessagePart.Image(Uri.fromFile(file).toString())
    }
    private fun report(parts:List<UIMessagePart>)=Json.parseToJsonElement((parts.first() as UIMessagePart.Text).text).jsonObject
    private suspend fun setup(count:Int=2,failUpload:Boolean=false,timeoutPost:Boolean=false,group:String?=null):Fixture {
        val pics=(1..count).map(::image)
        val c=Conversation(assistantId=assistant,messageNodes=listOf(UIMessage.user("private human text").copy(parts=pics).toMessageNode()))
        val calls=mutableListOf<Triple<String,JsonObject?,String?>>()
        val media=mutableListOf<String>();val jpegSizes=mutableListOf<Pair<Int,Int>>()
        var saved:JsonObject?=null;var postKey:String?=null
        val api=CommunityClient(context,Transport { method,path,body,id ->
            if(method=="POST") calls+=Triple(path,body,id)
            when {
                path.startsWith("/community/identities") -> buildJsonObject {put("items",JsonArray(listOf(buildJsonObject {put("id",actor);put("kind","ai");put("local_key",assistant.toString())})))}
                path=="/community/media" -> {
                    require(body!!.ms("actor")==actor && body.ms("purpose")==if(group==null) "feed" else "group");if(group!=null) assertEquals(group,body.ms("room"))
                    if(failUpload && media.size==1) throw MarketProblem(400,communityBody("error" to "synthetic","message" to "合成第二张上传失败"))
                    val bytes=Base64.decode(body.ms("data"),Base64.NO_WRAP)
                    assertTrue(bytes.size<=256*1024);assertEquals(0xff,bytes[0].toInt() and 255);assertEquals(0xd8,bytes[1].toInt() and 255)
                    val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size);assertNotNull(bitmap)
                    assertTrue(maxOf(bitmap.width,bitmap.height)<=1600);jpegSizes+=bitmap.width to bitmap.height;bitmap.recycle()
                    UUID.randomUUID().toString().also { media+=it }.let {communityBody("id" to it)}
                }
                path in setOf("/community/posts","/community/messages") && method=="POST" -> {
                    require(body!!.ms("actor")==actor);assertEquals(media,body.cs("media"))
                    saved=JsonObject(body+communityBody("id" to UUID.randomUUID().toString())+mapOf("seq" to JsonPrimitive(42)));postKey=id
                    if(timeoutPost) throw IOException("synthetic timeout after commit")
                    buildJsonObject {put("id",saved!!.ms("id"));put("seq",42);put("reply_to",body.ms("reply_to"));put("quote_notifies",false)}
                }
                path.startsWith("/community/posts/") -> saved!!
                path.startsWith("/community/rooms/$group/messages?") -> {
                    assertTrue(path.contains("from=42&limit=1"));buildJsonObject {put("items",JsonArray(listOf(saved!!)))}
                }
                path.startsWith("/receipts/") -> {
                    assertEquals(postKey,path.substringAfterLast('/'))
                    buildJsonObject {put("found",true);put("result",communityBody("id" to saved!!.ms("id")))}
                }
                else -> error("Unexpected synthetic request: $path")
            }
        })
        api.identities()
        val source=CommunityChatImages.capture(c,{c},{})
        val tool=CommunityRuntime.tool(context,assistant.toString(),c.id.toString(),client={api},allowed={true},supportsImages=true,chatImages=source)
        return Fixture(api,tool,calls,media,jpegSizes,pics)
    }
    private data class Fixture(val api:CommunityClient,val tool:me.rerere.ai.core.Tool,val calls:List<Triple<String,JsonObject?,String?>>,val media:List<String>,val sizes:List<Pair<Int,Int>>,val pics:List<UIMessagePart.Image>)
    private suspend fun selected(f:Fixture):List<String> = report(f.tool.execute(communityBody("action" to "chat_images"))).ma("items").map {it.ms("image_code")}
    private fun post(codes:List<String>)=buildJsonObject {put("action","publish");put("visibility","public");put("image_codes",JsonArray(codes.map(::JsonPrimitive)));put("text","合成图片帖")}

    @Test fun runtimeListsPreviewsUploadsNineImagesAndVerifiesPublishedPost()=runBlocking {
        val f=setup(9)
        val preview=f.tool.execute(buildJsonObject {put("action","chat_images");put("images",true)})
        assertEquals(2,preview.filterIsInstance<UIMessagePart.Image>().size)
        val codes=report(preview).ma("items").map {it.ms("image_code")}
        assertEquals(9,codes.size);assertTrue(f.calls.isEmpty())
        val out=report(f.tool.execute(post(codes)))
        assertTrue(out.toString(),out.mb("published"));assertTrue(out.mb("images_verified"));assertEquals(9,out.mi("image_count"))
        assertEquals(9,f.sizes.size);assertEquals(f.media,out.cs("media"))
        assertEquals(1,f.calls.count {it.first=="/community/posts"})
        val body=f.calls.last().second!!;assertFalse(body.toString().contains("private human text"));assertFalse(body.toString().contains("file:"));assertFalse(body.toString().contains("orbisimg_"))
        assertFalse(f.api.pending(actor));assertTrue(f.media.all {f.api.savedImage(actor,it)?.isFile==true})
    }
    @Test fun secondImageFailureDoesNotPublishTextFallback()=runBlocking {
        val f=setup(failUpload=true);val out=report(f.tool.execute(post(selected(f))))
        assertFalse(out.mb("ok"));assertFalse(out.mb("published"))
        assertEquals(2,f.calls.count {it.first=="/community/media"});assertEquals(0,f.calls.count {it.first=="/community/posts"})
        assertFalse(f.api.pending(actor))
    }
    @Test fun timedOutPostUsesDurableReceiptWithoutSecondUploadOrPublication()=runBlocking {
        val f=setup(timeoutPost=true);val codes=selected(f)
        val out=report(f.tool.execute(post(codes)));assertFalse(out.mb("ok"));assertTrue(f.api.pending(actor))
        val retried=report(f.tool.execute(post(codes)));assertFalse(retried.mb("ok"))
        assertEquals(2,f.calls.count {it.first=="/community/media"});assertEquals(1,f.calls.count {it.first=="/community/posts"})
        val receipt=report(f.tool.execute(communityBody("action" to "reconcile")))
        assertTrue(receipt.toString(),receipt.ms("id").isNotEmpty());communityUuid(receipt.ms("id"));assertFalse(f.api.pending(actor))
        assertEquals(3,f.calls.size)
    }
    @Test fun runtimeUploadsImagesToTargetGroupWithQuoteAndExplicitMention()=runBlocking {
        val room=UUID.randomUUID().toString();val quote=UUID.randomUUID().toString();val mention=UUID.randomUUID().toString()
        val f=setup(4,group=room)
        val args=buildJsonObject {put("action","send_message");put("target",room);put("reply_to",quote);put("mentions",JsonArray(listOf(JsonPrimitive(mention))));put("image_codes",JsonArray(selected(f).map(::JsonPrimitive)))}
        val result=report(f.tool.execute(args));assertTrue(result.toString(),result.mb("sent"));assertTrue(result.mb("images_verified"));assertEquals(4,result.mi("image_count"));assertEquals(room,result.ms("room"))
        assertEquals(4,f.calls.count {it.first=="/community/media"});assertEquals(1,f.calls.count {it.first=="/community/messages"})
        val body=f.calls.last().second!!;assertEquals("",body.ms("text"));assertEquals(quote,body.ms("reply_to"));assertEquals(listOf(mention),body.cs("mentions"))
        assertEquals(f.media,body.cs("media"));assertFalse(f.calls.any{it.first=="/community/posts"})
    }
    @Test fun foreignReferenceFailsBeforeUploadAtPublicToolBoundary()=runBlocking {
        val f=setup();val out=report(f.tool.execute(post(listOf("orbisimg_"+"0".repeat(64)))))
        assertFalse(out.mb("ok"));assertTrue(f.calls.isEmpty())
    }
}
