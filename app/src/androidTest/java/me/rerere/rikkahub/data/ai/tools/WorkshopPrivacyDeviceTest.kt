package me.rerere.rikkahub.data.ai.tools

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.data.ai.*
import me.rerere.rikkahub.data.ai.transformers.OrbisEventTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.workshop.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

/** Local originals and intercepted HTTP only. No paid generation or real chats. */
class WorkshopPrivacyDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun uuid() = UUID.randomUUID().toString()
    private fun fixture(ai: Boolean): Pair<ImageWorkshop, WorkshopJob> {
        check(context.packageName == "org.orbis.agent.bodylab")
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(UUID.randomUUID().hashCode() or 0xff000000.toInt())
            it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle()
        }
        val workshop = ImageWorkshop.open(context)
        val asset = workshop.saveOriginal(out.toByteArray(), "synthetic")
        val job = workshop.ledger.submit(WorkshopJob(requestId = uuid(), assistantId = uuid(), conversationId = uuid(),
            byAi = ai, modelId = uuid(), maker = "test", configHash = "hash", prompt = "synthetic private prompt"))
        return workshop to workshop.ledger.update(job.id) { it.copy(status = WorkshopStatus.COMPLETE, assets = listOf(asset)) }
    }

    @Test fun manualResultsCannotNotifyBeReadOrBorrowedEvenWithStaleFlags() = runBlocking {
        val (workshop, human) = fixture(false)
        // No global Koin exists in the isolated runner: any attempted chat delivery would fail.
        workshop.deliver(human.copy(byAi = true, notifyConversation = true))
        assertFalse(workshop.ledger.get(human.id)!!.delivered)
        val tool = createOrbisImageWorkshopTool(context, human.assistantId, human.conversationId!!)
        val result = tool.execute(buildJsonObject { put("action", "get"); put("job_id", human.id) })
        assertTrue(result.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertTrue(result.single().let { it as UIMessagePart.Text }.text.contains("workshop_missing"))
        assertFalse(workshop.ledger.ownsAiReference(human.assistantId, human.conversationId, human.assets.single().file))
        assertTrue(runCatching { workshop.assetFileForEvent("image_workshop.${human.id}", human.assistantId,
            human.conversationId, human.assets.single().file) }.isFailure)
        assertTrue(workshop.assetFile(human.assets.single().file).exists())
    }

    @Test fun deleteUnsharedImageRemovesBytesAndRecordSurvivesReloadAsReceipt() {
        val (workshop, human) = fixture(false)
        val original = workshop.assetFile(human.assets.single().file)
        workshop.delete(human.id, human.assistantId)
        assertFalse(original.exists())
        val reloaded = WorkshopLedger({ File(context.filesDir, "orbis-image-workshop/jobs-v1.json").readText() }, { error("read only") })
        val deleted = reloaded.get(human.id)!!
        assertTrue(deleted.deleted); assertTrue(deleted.assets.isEmpty()); assertEquals("", deleted.prompt)
        assertFalse(deleted.needsRecovery())
    }

    @Test fun aiGetReturnsImageAndRevocationDuringRecognitionPreventsDisclosure() = runBlocking {
        val (workshop, ai) = fixture(true)
        val args = buildJsonObject { put("action", "get"); put("job_id", ai.id) }
        val tool = createOrbisImageWorkshopTool(context, ai.assistantId, ai.conversationId!!)
        val image = tool.execute(args).filterIsInstance<UIMessagePart.Image>().single()
        assertEquals(Uri.fromFile(workshop.assetFile(ai.assets.single().file)).toString(), image.url)
        val textModel = createOrbisImageWorkshopTool(context, ai.assistantId, ai.conversationId, presentImage = {
            workshop.delete(ai.id, ai.assistantId)
            listOf(UIMessagePart.Text("This description must not escape after removal"))
        })
        val revoked = textModel.execute(args).filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        assertTrue(revoked.contains("workshop_missing")); assertFalse(revoked.contains("description must"))
        assertTrue(workshop.assetFile(ai.assets.single().file).exists()) // Already publishable chat copy is safe.
    }

    @Test fun aiEventAndGetOriginalReachRealVisionWireEncoder() = runBlocking {
        val (workshop, ai) = fixture(true)
        val tool = createOrbisImageWorkshopTool(context, ai.assistantId, ai.conversationId!!)
        val retrieved = tool.execute(buildJsonObject { put("action", "get"); put("job_id", ai.id) })
            .filterIsInstance<UIMessagePart.Image>().single()
        val eventImage = UIMessagePart.Image(Uri.fromFile(workshop.assetFileForEvent("image_workshop.${ai.id}",
            ai.assistantId, ai.conversationId, ai.assets.single().file)).toString())
        assertEquals(retrieved, eventImage)
        val model = Model(modelId = "synthetic-vision", inputModalities = listOf(Modality.TEXT, Modality.IMAGE))
        for (sendHistory in listOf(true, false)) {
            val assistant = Assistant(id = Uuid.parse(ai.assistantId), streamOutput = false, sendHistoryImages = sendHistory,
                chatModelId = model.id, contextMessageLimit = 0)
            val event = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Completed image"), eventImage),
                orbisEvent = OrbisEventMetadata(uuid(), "image_workshop.${ai.id}", "result-${ai.id}", 1))
            var calls = 0
            var captured: JsonObject? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                captured = body
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("""{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"received"}}]}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val provider = ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/v1", models = listOf(model))
            try {
                GenerationLoop(context, ProviderManager(client, context), Json).generateText(
                    settings = Settings(providers = listOf(provider), assistants = listOf(assistant), chatModelId = model.id),
                    model = model, assistant = assistant, messages = listOf(event), tools = emptyList(),
                    conversationId = Uuid.parse(ai.conversationId), inputTransformers = listOf(OcrTransformer, OrbisEventTransformer)
                ).collect { }
                assertEquals(1, calls)
                val body = checkNotNull(captured)
                val contents = body.getValue("messages").jsonArray.flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
                val img = contents.single { it.jsonObject["type"] == JsonPrimitive("image_url") }.jsonObject
                val url = img.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                assertTrue("image must be inline bytes, prefix=${url.take(30)}", url.startsWith("data:image/"))
                val bytes = java.util.Base64.getDecoder().decode(url.substringAfter("base64,"))
                val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertNotNull(decoded); assertEquals(64, decoded.width); assertEquals(64, decoded.height); decoded.recycle()
                assertTrue("event provenance must reach provider", body.toString().contains("ORBIS_EVENT_V1"))
                assertEquals(eventImage, event.parts.last()) // Durable image is untouched.
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }
}
