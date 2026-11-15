package me.rerere.rikkahub.data.ai

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.orbis.spaces.OrbisCompanionSpacesStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PhotoWallChatImagesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val temporary = TemporaryFolder(instrumentation.targetContext.cacheDir)
    @Before fun isolatedOnly() {
        check(instrumentation is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        check(instrumentation.targetContext.packageName == "org.orbis.agent.bodylab")
    }
    private fun context(base: File) = object : ContextWrapper(instrumentation.targetContext) {
        override fun getFilesDir() = base
        override fun getApplicationContext(): Context = this
    }

    @Test fun selectedChatJpegRetainsOrientationAfterSourceDeletedAndStoreReopened() = runBlocking {
        val base = temporary.newFolder("photos"); val ctx = context(base); val owner = Uuid.random()
        val source = File(base, "upload/rotated.jpg").apply { parentFile.mkdirs() }
        val bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }; bitmap.recycle()
        ExifInterface(source.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
        val original = source.readBytes()
        val image = UIMessagePart.Image(Uri.fromFile(source).toString())
        val message = UIMessage.user("合成图片").copy(parts = listOf(image))
        val chat = Conversation(assistantId = owner, messageNodes = listOf(message.toMessageNode()))
        val images = PhotoWallChatImages.capture(chat, { chat }, { loadPhotoWallChatImage(ctx, it) }, { listOf(it) })
        val tool = createOrbisCompanionSpaceTools(ctx, owner.toString(), images).single { it.name == "orbis_photo_wall" }
        val args = buildJsonObject { put("action", "save_chat"); put("image_code", chatImageCode(owner, chat.id, message.id, image.url)); put("text", "旋转后的照片") }
        val receipt = Json.parseToJsonElement((tool.execute(args).single() as UIMessagePart.Text).text).jsonObject
        assertTrue(receipt.getValue("saved").jsonPrimitive.boolean)
        assertArrayEquals(original, source.readBytes()); assertTrue(source.delete())
        val reopened = OrbisCompanionSpacesStore.open(ctx, owner.toString())
        val photo = reopened.snapshot().photos.single()
        val bytes = reopened.imageBytes(photo.mediaId).second
        val retained = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(24, retained.width); assertEquals(48, retained.height); retained.recycle()
        assertEquals("旋转后的照片", photo.note)
        assertTrue(OrbisCompanionSpacesStore.open(ctx, Uuid.random().toString()).snapshot().photos.isEmpty())
    }

    @Test fun corruptAttachmentDoesNotCreatePhotoOrMedia() = runBlocking {
        val base = temporary.newFolder("corrupt"); val ctx = context(base); val owner = Uuid.random()
        val source = File(base, "upload/bad.jpg").apply { parentFile.mkdirs(); writeText("not an image") }
        val image = UIMessagePart.Image(Uri.fromFile(source).toString())
        val message = UIMessage.user("").copy(parts = listOf(image))
        val chat = Conversation(assistantId = owner, messageNodes = listOf(message.toMessageNode()))
        val images = PhotoWallChatImages.capture(chat, { chat }, { loadPhotoWallChatImage(ctx, it) }, { listOf(it) })
        val tool = createOrbisCompanionSpaceTools(ctx, owner.toString(), images).single { it.name == "orbis_photo_wall" }
        val receipt = Json.parseToJsonElement((tool.execute(buildJsonObject {
            put("action", "save_chat"); put("image_code", chatImageCode(owner, chat.id, message.id, image.url))
        }).single() as UIMessagePart.Text).text).jsonObject
        assertFalse(receipt.getValue("ok").jsonPrimitive.boolean)
        val snapshot = OrbisCompanionSpacesStore.open(ctx, owner.toString()).snapshot()
        assertTrue(snapshot.photos.isEmpty()); assertTrue(snapshot.media.isEmpty()); assertTrue(source.exists())
    }
}
