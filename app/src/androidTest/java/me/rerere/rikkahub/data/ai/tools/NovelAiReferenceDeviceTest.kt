package me.rerere.rikkahub.data.ai.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.orbis.workshop.ImageWorkshop
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlin.uuid.Uuid

/** Native bitmap conversion and actual provider serialization, all HTTP intercepted locally. */
class NovelAiReferenceDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():File {
        check(context.packageName=="org.orbis.agent.bodylab")
        val b=Bitmap.createBitmap(832,1216,Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.CYAN)
        val f=File(context.filesDir,"upload/ref-${Uuid.random()}.png");f.parentFile!!.mkdirs()
        f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();return f
    }
    @Test fun actualEditEncoderPadsReferencePreservesOriginalAndUsesOnePaidRequest() = runBlocking {
        val f=fixture();val original=f.readBytes()
        for(mode in listOf("img2img","character&style")) {
            var calls=0
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val buf=Buffer();chain.request().body!!.writeTo(buf)
                assertTrue(chain.request().body!!.isOneShot())
                val body=Json.parseToJsonElement(buf.readUtf8()).jsonObject
                val p=body.getValue("parameters").jsonObject
                assertEquals("https://image.novelai.net/ai/generate-image",chain.request().url.toString())
                assertEquals(JsonPrimitive(832),p["width"]);assertEquals(JsonPrimitive(1216),p["height"])
                val encoded=if(mode=="img2img") p.getValue("image").jsonPrimitive.content else p.getValue("director_reference_images").jsonArray.single().jsonPrimitive.content
                val bytes=Base64.getDecoder().decode(encoded)
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                assertEquals(if(mode=="img2img")832 else 1024,bitmap.width)
                assertEquals(if(mode=="img2img")1216 else 1536,bitmap.height);bitmap.recycle()
                val response=buildJsonObject{put("images",buildJsonArray{add(buildJsonObject{put("image",Base64.getEncoder().encodeToString(original))})})}
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .header("Content-Type","application/json").body(response.toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                val results=OpenAIProvider(client).editImage(ProviderSetting.OpenAI(baseUrl=NovelAiImageApi.BASE_URL,apiKey="pst-synthetic"),
                    ImageEditParams(NovelAiImageApi.model(if(mode=="img2img")NovelAiImageApi.V5_FULL else NovelAiImageApi.MODEL),
                        "same portrait in a garden",listOf(f.path),customBody=listOf(CustomBody("reference_mode",JsonPrimitive(mode)),CustomBody("strength",JsonPrimitive(0.4))))).toList()
                assertEquals(1,calls);assertEquals(1,results.size)
                assertArrayEquals(original,f.readBytes())
                assertArrayEquals(original,Base64.getDecoder().decode(results.single().data))
            } finally {client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow()}
        }
    }
    @Test fun chatReferencesListOnlyBoundImagesAndRejectChangedConversationOrArbitraryPaths() = runBlocking {
        val file=fixture();val image=UIMessagePart.Image(Uri.fromFile(file).toString())
        val message=UIMessage.user("this is the reference").copy(parts=listOf(UIMessagePart.Text("this is the reference"),image))
        var current:Conversation?=Conversation(assistantId=Uuid.random(),messageNodes=listOf(message.toMessageNode()))
        val scope=ChatImageReadScope.capture(current!!,setOf(message.id))
        val tool=createOrbisImageWorkshopTool(context,scope.owner.toString(),scope.conversationId.toString(),chatImages=scope,readChat={current})
        val list=tool.execute(buildJsonObject{put("action","list_images")}).filterIsInstance<UIMessagePart.Text>().single().text
        val data=Json.parseToJsonElement(list).jsonObject
        assertEquals(scope.images.keys.single(),data.getValue("images").jsonArray.single().jsonObject.getValue("image_code").jsonPrimitive.content)
        assertFalse(list.contains(file.path))
        val workshop=ImageWorkshop.open(context)
        val asset=importWorkshopChatImage(context,workshop,scope.resolve(scope.images.keys.single(),current))
        assertArrayEquals(file.readBytes(),workshop.assetFile(asset.file).readBytes())
        val outside=File(context.filesDir,"not-an-upload.png").also{it.writeBytes(file.readBytes())}
        assertTrue(runCatching{importWorkshopChatImage(context,workshop,UIMessagePart.Image(Uri.fromFile(outside).toString()))}.isFailure)
        current=current!!.copy(id=Uuid.random())
        val changed=tool.execute(buildJsonObject{put("action","list_images")}).filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(changed.contains("chat_image_scope_changed"));assertFalse(changed.contains(scope.images.keys.single()))
    }
}
