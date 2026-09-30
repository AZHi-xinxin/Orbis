package me.rerere.rikkahub.data.ai

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.NetworkSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.OrbisConversationPrompt
import me.rerere.rikkahub.data.model.orbisStickerImageParts
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** Real GenerationLoop/OpenAI serialization, but HTTP is intercepted before DNS or sockets.
 * Uses only a generated image in a new unique target-cache child directory (instrumentation
 * executes as the target UID). No existing cache files, app settings, Room, Koin or accounts.
 * Placeholder/Template only alter Text parts; they are statically reviewed, not instantiated here.
 */
@RunWith(AndroidJUnit4::class)
class OrbisStickerWireTest {
    @Test(timeout = 20_000) fun nonStreamingStickerRemainsImageInActualProviderJson() = runBlocking<Unit> {
        checkWire(stream = false)
    }

    @Test(timeout = 20_000) fun streamingStickerRemainsImageInActualProviderJson() = runBlocking<Unit> {
        checkWire(stream = true)
    }

    private suspend fun checkWire(stream: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
        val context = DenyPrivateStorageContext(instrumentation.context)
        val cacheRoot = instrumentation.targetContext.cacheDir.canonicalFile
        check(cacheRoot.isDirectory || cacheRoot.mkdirs())
        val ownedDirectory = File(cacheRoot, "orbis-sticker-wire-${Uuid.random()}")
        check(ownedDirectory.canonicalFile.parentFile == cacheRoot && ownedDirectory.mkdir())
        val image = File(ownedDirectory, "synthetic.png")
        try {
            check(image.createNewFile())
            checkWireImage(context, image, stream)
        } finally {
            // Exact objects created above only. No enumeration or recursive cache cleanup.
            check(image.canonicalFile == image.absoluteFile)
            check(!image.exists() || image.delete())
            check(ownedDirectory.canonicalFile.parentFile == cacheRoot)
            check(ownedDirectory.delete() || !ownedDirectory.exists())
        }
    }

    private suspend fun checkWireImage(context: Context, image: File, stream: Boolean) {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.MAGENTA)
            image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val captured = AtomicReference<JsonObject?>()
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .dns { throw AssertionError("No network permitted in synthetic sticker fixture") }
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "orbis-sticker.fixture.invalid" && request.method == "POST")
                check(requests.incrementAndGet() == 1)
                val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                captured.set(Json.parseToJsonElement(body).jsonObject)
                val response = if (stream) """
                    data: {"id":"synthetic","model":"synthetic-image","choices":[{"index":0,"delta":{"content":"synthetic done"},"finish_reason":null}]}

                    data: {"id":"synthetic","model":"synthetic-image","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]


                """.trimIndent() + "\n\n" else """{"id":"synthetic","model":"synthetic-image","choices":[{"index":0,"message":{"role":"assistant","content":"synthetic done"},"finish_reason":"stop"}]}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(response.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType()))
                    .build() // Deliberately never chain.proceed: no socket is opened.
            }.build()
        try {
            val model = Model(modelId = "synthetic-image", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
            val assistant = Assistant(chatModelId = model.id, systemPrompt = "synthetic base",
                contextMessageLimit = 1, streamOutput = stream, enableMemory = false,
                enableTimeReminder = true, localTools = emptyList())
            val provider = ProviderSetting.OpenAI(name = "synthetic", models = listOf(model), apiKey = "",
                baseUrl = "https://orbis-sticker.fixture.invalid/v1")
            val settings = Settings(providers = listOf(provider), assistants = listOf(assistant),
                assistantId = assistant.id, chatModelId = model.id,
                networkSetting = NetworkSetting(enableAutoRetry = false), enableSuggestion = false,
                modeInjections = emptyList(), lorebooks = emptyList(), searchServices = emptyList(), ttsProviders = emptyList())
            val current = UIMessage(role = MessageRole.USER, parts = orbisStickerImageParts(image.toUri().toString()))
            val input = listOf(UIMessage.user("synthetic old history that must be trimmed"),
                UIMessage.assistant("synthetic old answer"), current)
            val loop = GenerationLoop(context, ProviderManager(client, context), Json)
            withTimeout(10_000) {
                loop.generateText(settings, model, input, assistant = assistant, maxSteps = 1,
                    inputTransformers = listOf(TimeReminderTransformer, PromptInjectionTransformer,
                        DocumentAsPromptTransformer, OcrTransformer),
                    orbisPrompt = OrbisConversationPrompt(worldBookText = "synthetic worldbook", worldBookEnabled = true),
                    conversationId = Uuid.random()).collect()
            }
            val request = requireNotNull(captured.get())
            val messages = request.getValue("messages").jsonArray.map { it.jsonObject }
            val parts = messages.flatMap { (it["content"] as? JsonArray)?.map { part -> part.jsonObject }.orEmpty() }
            val images = parts.filter { it["type"] == JsonPrimitive("image_url") }
            assertEquals(1, requests.get())
            assertEquals(1, images.size)
            val data = images.single().getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
            assertTrue(data.startsWith("data:image/"))
            val pixels = Base64.decode(data.substringAfter("base64,"), Base64.DEFAULT)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(pixels, 0, pixels.size)) {
                "Provider JSON must carry decodable image pixels"
            }
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            decoded.recycle()
            assertFalse(request.toString().contains("synthetic old history"))
            assertFalse(request.toString().contains("file:"))
            assertTrue(request.toString().contains("synthetic worldbook"))
            assertEquals(listOf(UIMessagePart.Image(image.toUri().toString())), current.parts)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private class DenyPrivateStorageContext(base: Context) : ContextWrapper(base) {
        private fun forbidden(): Nothing = throw AssertionError("Fixture accessed private app storage")
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = forbidden()
        override fun getFilesDir(): File = forbidden()
        override fun getNoBackupFilesDir(): File = forbidden()
        override fun getDatabasePath(name: String): File = forbidden()
        override fun getDir(name: String, mode: Int): File = forbidden()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = forbidden()
    }
}
