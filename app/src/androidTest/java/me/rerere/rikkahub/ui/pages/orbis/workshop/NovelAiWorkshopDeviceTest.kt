package me.rerere.rikkahub.ui.pages.orbis.workshop

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.cancel
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.providers.openai.NovelAiImageApi
import me.rerere.ai.ui.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.OrbisInboxEvent
import me.rerere.rikkahub.data.orbis.workshop.*
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.chat.OrbisPendingNotificationCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.uuid.Uuid

/** Uses synthetic local images/account only; no HTTP, worker, real chat or user settings. */
class NovelAiWorkshopDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val detail = "Synthetic制作记录，不是模型回复"
    private val prompt = "A quiet observatory under the Milky Way.\n没有人物和文字。"
    private fun uuid() = UUID.randomUUID().toString()
    private fun fixture(): Pair<WorkshopJob, String> {
        check(context.packageName == "org.orbis.agent.bodylab")
        val bytes = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(256, 320, Bitmap.Config.ARGB_8888).also { b ->
                b.eraseColor(android.graphics.Color.rgb(38, 57, 139))
                b.compress(Bitmap.CompressFormat.PNG, 100, out); b.recycle()
            }
        }.toByteArray()
        val workshop = ImageWorkshop.open(context)
        val asset = workshop.saveOriginal(bytes, "synthetic-star-field")
        val job = workshop.ledger.submit(WorkshopJob(requestId = uuid(), assistantId = uuid(), conversationId = uuid(),
            byAi = true, modelId = uuid(), maker = "NovelAI synthetic", configHash = "synthetic", prompt = prompt, notifyConversation = true))
        val complete = workshop.ledger.update(job.id) { it.copy(status = WorkshopStatus.COMPLETE, assets = listOf(asset)) }
        assertArrayEquals(bytes, workshop.assetFileForEvent("image_workshop.${job.id}", job.assistantId, job.conversationId!!, asset.file).readBytes())
        // Reopen the durable ledger as a new instance, rather than relying on its live Flow.
        val reloaded = WorkshopLedger(read = { File(context.filesDir, "orbis-image-workshop/jobs-v1.json").readText() }, write = { error("read only") })
        assertEquals(complete, reloaded.get(job.id))
        assertTrue(runCatching { workshop.assetFileForEvent("image_workshop.${job.id}", uuid(), job.conversationId, asset.file) }.isFailure)
        return complete to Uri.fromFile(workshop.assetFile(asset.file)).toString()
    }

    @Test fun pendingResultShowsOriginalBeforeAnyModelReply() {
        val (job, _) = fixture()
        val event = OrbisInboxEvent(eventId = "result-${job.id}", source = "image_workshop.${job.id}", text = detail,
            wake = true, assistantId = job.assistantId, conversationId = job.conversationId!!,
            receivedAt = 1, independentDelivery = true, workshopImages = job.assets.map { it.file })
        compose.setContent { MaterialTheme { OrbisPendingNotificationCard(event) } }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("workshop-result-image-0").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("workshop-result-image-0").assertIsDisplayed().assertHeightIsAtLeast(160.dp)
        compose.onNodeWithText(detail).assertDoesNotExist()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        compose.onNodeWithText("查看生成提示词").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(prompt).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(prompt).assertIsDisplayed()
        compose.onNodeWithText(detail).assertDoesNotExist()
        compose.onNodeWithText("复制提示词").performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            assertEquals(prompt, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithText("收起生成提示词").performClick()
        compose.onNodeWithText(prompt).assertDoesNotExist()
        compose.onNodeWithTag("workshop-result-image-0").assertIsDisplayed()
    }

    @Test fun persistedChatUsesFullImageCardAndOpensOriginalViewer() {
        val (job, uri) = fixture()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val scope = AppScope().also { it.cancel() }
        val files = FilesManager(context, FilesRepository(db.managedFileDao()), scope)
        val node = MessageNode.of(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(detail), UIMessagePart.Image(uri)),
            orbisEvent = OrbisEventMetadata(uuid(), "image_workshop.${job.id}", "result-${job.id}", 1)))
        try {
            compose.setContent {
                val isolated = remember { koinApplication { modules(module { single { files } }) } }
                DisposableEffect(isolated) { onDispose { isolated.close() } }
                val toaster = rememberToasterState()
                KoinIsolatedContext(isolated) {
                    CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides toaster) {
                        MaterialTheme { ChatMessage(node = node, assistant = Assistant(id = Uuid.parse(job.assistantId)),
                            onFork = {}, onRegenerate = {}, onEdit = {},
                            onShare = {}, onDelete = {}, onUpdate = {}) }
                    }
                }
            }
            compose.onNodeWithTag("workshop-result-image-0").assertIsDisplayed().assertHeightIsAtLeast(160.dp)
            compose.onNodeWithText(detail).assertDoesNotExist()
            compose.onNodeWithText("查看生成提示词").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText(prompt).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithText(prompt).assertIsDisplayed()
            compose.onNodeWithText(detail).assertDoesNotExist()
            compose.onNodeWithText("收起生成提示词").performClick()
            // Capture the real production history renderer before opening its viewer.
            Thread.sleep(500)
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.filesDir, "novelai-chat-fixture.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("workshop-result-image-0").performClick()
            compose.onNodeWithContentDescription("保存原图").assertIsDisplayed().assertHasClickAction()
        } finally { db.close() }
    }

    @Test fun referenceControlsExposeStrengthAndOnlySupportedModes() {
        var mode by mutableStateOf("img2img")
        var strength by mutableFloatStateOf(0.6f)
        var precise by mutableStateOf(false)
        compose.setContent { MaterialTheme { NovelAiReferenceControls(precise, mode, strength, true, {mode=it}, {strength=it}) } }
        compose.onNodeWithText("角色与画风参考").assertDoesNotExist()
        compose.onNodeWithText("原图重绘").assertIsDisplayed()
        compose.onNodeWithTag("novelai-reference-strength").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.3f) }
        compose.runOnIdle { assertEquals(0.3f,strength,0.01f);precise=true }
        compose.onNodeWithText("角色与画风参考").performClick()
        compose.runOnIdle { assertEquals("character&style",mode) }
        compose.onNodeWithText("参考强度：30%").assertIsDisplayed()
    }

    @Test fun deletionRequiresExplicitConfirmationAndCanBeCancelled() {
        var shown by mutableStateOf(true)
        var deletes = 0
        compose.setContent { MaterialTheme { if (shown) WorkshopDeleteDialog(true,
            onDismiss = { shown = false }, onConfirm = { deletes++; shown = false }) } }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deletes); shown = true }
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(1, deletes) }
    }

    @Test fun privateManualNotificationIsNotDisplayed() {
        val workshop = ImageWorkshop.open(context)
        val job = workshop.ledger.submit(WorkshopJob(requestId = uuid(), assistantId = uuid(), conversationId = uuid(),
            byAi = false, modelId = uuid(), maker = "synthetic", configHash = "hash", prompt = "private"))
        workshop.ledger.update(job.id) { it.copy(status = WorkshopStatus.COMPLETE) }
        val event = OrbisInboxEvent(eventId = "result-${job.id}", source = "image_workshop.${job.id}", text = "private",
            wake = true, assistantId = job.assistantId, conversationId = job.conversationId!!, receivedAt = 1, independentDelivery = true)
        compose.setContent { MaterialTheme { OrbisPendingNotificationCard(event) } }
        compose.waitForIdle()
        compose.onNodeWithTag("workshop-result-${event.id}").assertDoesNotExist()
        compose.onNodeWithText("private").assertDoesNotExist()
    }

    @Test fun connectionFormNormalizesKnownModelAndPreservesOtherModelsAndId() {
        val known = NovelAiImageApi.model().copy(type = ModelType.CHAT)
        val other = NovelAiImageApi.model().copy(modelId = "other")
        val previous = ProviderSetting.OpenAI(baseUrl = NovelAiImageApi.BASE_URL, models = listOf(known, other))
        var saved: ProviderSetting.OpenAI? = null
        var selectedModel: String? = null
        compose.setContent { MaterialTheme { NovelAiConnectionDialog(previous, false, {}, { provider, modelId -> saved = provider; selectedModel = modelId }) } }
        compose.onNodeWithTag("novelai-save").assertIsNotEnabled()
        compose.onNodeWithTag("novelai-token").performTextInput("pst-synthetic-only")
        compose.onNodeWithTag("novelai-save").performClick()
        compose.runOnIdle {
            val actual = checkNotNull(saved)
            assertEquals(previous.id, actual.id)
            assertEquals(NovelAiImageApi.V5_FULL, selectedModel)
            assertTrue(actual.models.any { it.modelId == NovelAiImageApi.V5_FULL })
            assertEquals(known.id, actual.models.single { it.modelId == NovelAiImageApi.MODEL }.id)
            assertEquals(ModelType.IMAGE, actual.models.single { it.modelId == NovelAiImageApi.MODEL }.type)
            assertEquals(other, actual.models.single { it.modelId == "other" })
        }
    }
}
