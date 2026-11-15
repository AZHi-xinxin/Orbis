package me.rerere.rikkahub.ui.pages.orbis.workshop

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createOrbisImageWorkshopTool
import me.rerere.rikkahub.data.orbis.workshop.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class WorkshopReferenceDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun uuid() = UUID.randomUUID().toString()
    private fun fixture(): Pair<ImageWorkshop, String> {
        check(context.packageName == "org.orbis.agent.bodylab")
        return ImageWorkshop.open(context).also { it.ledger.preferences(allowAi = true) } to uuid()
    }
    private fun asset(workshop: ImageWorkshop): WorkshopAsset {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(uuid().hashCode() or 0xff000000.toInt())
            it.compress(Bitmap.CompressFormat.PNG, 100, output); it.recycle()
        }
        return workshop.saveOriginal(output.toByteArray(), "synthetic reference")
    }
    @Test fun sharedReferenceWorksAcrossChatsButNotAcrossAssistantsOrRevocation() = runBlocking {
        val (workshop, owner) = fixture()
        val shared = workshop.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset(workshop), allowAi = true))
        workshop.saveReference(WorkshopReference(assistantId = owner, name = "private-person", asset = asset(workshop)))
        for (chat in listOf(uuid(), uuid())) {
            val tool = createOrbisImageWorkshopTool(context, owner, chat)
            val listed = tool.execute(buildJsonObject { put("action", "list_references") }).single() as UIMessagePart.Text
            assertTrue(listed.text.contains(shared.id)); assertFalse(listed.text.contains("private-person"))
            val image = tool.execute(buildJsonObject { put("action", "get_reference"); put("reference_id", shared.id) })
                .filterIsInstance<UIMessagePart.Image>().single()
            assertEquals(Uri.fromFile(workshop.assetFile(shared.asset.file)).toString(), image.url)
        }
        val other = createOrbisImageWorkshopTool(context, uuid(), uuid())
        val args = buildJsonObject { put("action", "get_reference"); put("reference_id", shared.id) }
        assertTrue(other.execute(args).filterIsInstance<UIMessagePart.Image>().isEmpty())
        val revoked = createOrbisImageWorkshopTool(context, owner, uuid(), presentImage = {
            workshop.saveReference(shared.copy(allowAi = false), shared.revision)
            listOf(UIMessagePart.Text("SENSITIVE_RECOGNITION_MUST_NOT_ESCAPE"))
        }).execute(args)
        assertTrue(revoked.filterIsInstance<UIMessagePart.Image>().isEmpty())
        assertFalse(revoked.toString().contains("SENSITIVE_RECOGNITION"))
    }
    @Test fun twoSavedPeopleReachActualImageEditMultipartAsBytesWithCorrectNames() = runBlocking {
        val (workshop, owner) = fixture()
        val me = workshop.saveReference(WorkshopReference(assistantId = owner, name = "我", asset = asset(workshop), allowAi = true))
        val partner = workshop.saveReference(WorkshopReference(assistantId = owner, name = "阿止", asset = asset(workshop), allowAi = true))
        val reopened = WorkshopLedger({ File(context.filesDir, "orbis-image-workshop/jobs-v1.json").readText() }, { error("read only") })
        val selected = reopened.resolveReferences(owner, true, listOf(partner.id, me.id))
        val files = selected.map { it.asset.file }
        val prompt = workshopReferencePrompt("一起旅行", files, selected)
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("/v1/images/edits", chain.request().url.encodedPath)
            val body = chain.request().body as MultipartBody
            val photos = body.parts.filter { it.headers?.get("Content-Disposition")?.contains("name=\"image[]\"") == true }
            assertEquals(2, photos.size)
            photos.forEachIndexed { index, part ->
                val buffer = Buffer(); part.body.writeTo(buffer)
                assertArrayEquals(workshop.assetFile(files[index]).readBytes(), buffer.readByteArray())
            }
            val promptPart = body.parts.single { it.headers?.get("Content-Disposition")?.contains("name=\"prompt\"") == true }
            val buffer = Buffer(); promptPart.body.writeTo(buffer); val sent = buffer.readUtf8()
            assertEquals(prompt, sent)
            assertTrue(sent.contains("\"image_number\":1,\"person_name\":\"阿止\""))
            assertTrue(sent.contains("\"image_number\":2,\"person_name\":\"我\""))
            val data = java.util.Base64.getEncoder().encodeToString(workshop.assetFile(me.asset.file).readBytes())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"created":1,"data":[{"b64_json":"$data"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val config = ProviderSetting.OpenAI(baseUrl = "https://synthetic.invalid/v1", apiKey = "synthetic")
        try {
            val provider = ProviderManager(client, context).getProviderByType(config)
            val output = provider.editImage(config, ImageEditParams(model = Model(modelId = "gpt-image-2"),
                prompt = prompt, images = files.map { workshop.assetFile(it).absolutePath }, numOfImages = 1, partialImages = 0)).toList()
            assertEquals(1, output.size); assertEquals(1, calls)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun humanCanNamePhotoSaveSelectAndReopenEditor() {
        val (workshop, owner) = fixture(); val seed = asset(workshop)
        var selected by mutableStateOf(emptyList<String>())
        var editing by mutableStateOf(true)
        var entry by mutableStateOf<WorkshopReference?>(null)
        compose.setContent { MaterialTheme {
            val state by workshop.state.collectAsState()
            WorkshopReferenceSection(state.referenceLibrary.filter { it.assistantId == owner }, selected, true, workshop::assetFile,
                { selected = if (it in selected) selected - it else selected + it }, { editing = true }, { entry = it; editing = true })
            if (editing) WorkshopReferenceEditor(workshop, owner, entry, seed, false, null,
                { editing = false }, { reference, revision -> entry = workshop.saveReference(reference, revision); editing = false },
                { workshop.deleteReference(it, owner); editing = false })
        } }
        compose.onNodeWithText("人物名字，例如：我、阿止").performTextInput("阿止")
        compose.onNodeWithText("保存参考").performClick()
        compose.onNodeWithText("阿止", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(listOf(entry!!.id), selected); assertTrue(entry!!.allowAi) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "reference-library.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.onNodeWithText("编辑 阿止").performClick()
        compose.onNodeWithText("删除这份参考").performScrollTo().performClick()
        compose.onNodeWithText("删除参考", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(workshop.ledger.visibleReferences(owner, false).isEmpty()) }
    }
}
