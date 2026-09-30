package me.rerere.rikkahub.data.orbis.companiontools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lover.connect.CompanionToolDescriptor
import com.lover.connect.CompanionToolImage
import com.lover.connect.CompanionToolResult
import com.lover.connect.LcExternalRecoveryGate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Synthetic solid-color pixels only. No screen capture, Koin, app settings, real tools or network.
 * Requires IsolatedGenerationLoopRunner so companion/background recovery stays closed.
 */
@RunWith(AndroidJUnit4::class)
class CompanionScreenshotWireDeviceTest {
    @Test fun nativeImageSurvivesTheActualOpenAiToolResultSerializer() = runBlocking {
        withSyntheticImage { imageFile ->
            val output = syntheticToolOutput(imageFile)
            val wire = serialize(output, listOf(Modality.TEXT, Modality.IMAGE))
            val receipt = wire.map { it.jsonObject }.single { it["role"] == JsonPrimitive("tool") }
            val content = receipt.getValue("content").jsonArray.map { it.jsonObject }
            assertTrue(content.any { it["type"] == JsonPrimitive("text") && it.getValue("text").jsonPrimitive.content.contains("synthetic raw observation") })
            val image = content.single { it["type"] == JsonPrimitive("image_url") }
            assertTrue(image.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content.startsWith("data:image/jpeg;base64,/9j/"))
            assertEquals("synthetic-screen-call", receipt.getValue("tool_call_id").jsonPrimitive.content)
            assertTrue(output.any { it is UIMessagePart.Image })
        }
    }

    @Test fun textOnlyModelGetsAnExplicitImageOmissionNotAnInventedCaption() = runBlocking {
        withSyntheticImage { imageFile ->
            val wire = serialize(syntheticToolOutput(imageFile), listOf(Modality.TEXT))
            val receipt = wire.map { it.jsonObject }.single { it["role"] == JsonPrimitive("tool") }
            val content = receipt.getValue("content").jsonPrimitive.content
            assertTrue(content.contains("synthetic raw observation"))
            assertTrue(content.contains("Image output omitted: current model does not support image input"))
            assertFalse(content.contains("data:image"))
        }
    }

    private suspend fun syntheticToolOutput(file: File): List<UIMessagePart> {
        val descriptor = CompanionToolDescriptor("take_screenshot", "synthetic screen capture", """{"type":"object","properties":{}}""", "write", true)
        val tool = buildCompanionTools(listOf(descriptor), null, { "synthetic" },
            imagePart = { UIMessagePart.Image(file.toUri().toString()) }) { _, _, _ ->
                CompanionToolResult(true, "synthetic raw observation; no OCR and no actual phone capture",
                    images = listOf(CompanionToolImage("/9j/synthetic-not-used-by-file-writer", 64, 32)))
            }.single()
        assertTrue(tool.hostApproval!!.rememberable)
        assertFalse(tool.requiresFreshApproval(JsonObject(emptyMap())))
        return tool.execute(JsonObject(emptyMap()))
    }

    private fun serialize(output: List<UIMessagePart>, modalities: List<Modality>): JsonArray {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "synthetic-screen-call", toolName = "companion_take_screenshot", input = "{}", output = output,
        ))))
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        // Exercise the production serializer without a network request or a real provider credential.
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages", List::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, List::class.java,
        ).apply { isAccessible = true }
        return method.invoke(api, messages, false, false, modalities) as JsonArray
    }

    private suspend fun withSyntheticImage(block: suspend (File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val owner = instrumentation.targetContext
        assertEquals(Application::class.java, owner.applicationContext.javaClass)
        assertFalse(LcExternalRecoveryGate.isAllowed())
        val root = owner.cacheDir.canonicalFile
        val directory = File(root, "stage29-screen-wire-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == root && !directory.exists())
        check(directory.mkdir())
        try {
            val file = File(directory, "synthetic-solid.jpg")
            val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(80, 100, 120))
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            } finally { bitmap.recycle() }
            block(file)
        } finally {
            check(directory.canonicalFile.parentFile == root && directory.name.startsWith("stage29-screen-wire-"))
            check(directory.deleteRecursively())
        }
    }
}
