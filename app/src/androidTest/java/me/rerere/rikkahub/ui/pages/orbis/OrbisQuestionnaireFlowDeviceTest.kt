package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.gallery.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrbisQuestionnaireFlowDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun pagingBranchesFullscreenAndExplicitSubmissionPreserveOnlyVisibleAnswers() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, inst.targetContext.applicationContext.javaClass)
        val root = File(inst.targetContext.cacheDir, "questionnaire-fixture-${UUID.randomUUID()}").canonicalFile
        try {
            val form = GalleryQuestionnaire(version = 2, description = "慢慢填写，按你的节奏。", pages = listOf(GalleryFormPage("one", "日常"), GalleryFormPage("two", "一起出发")), questions = listOf(
                GalleryQuestion("first", "空闲的午后，你更想？", "single", listOf("出门走走", "在家休息"), true, "one"),
                GalleryQuestion("second", "最想去哪里？", "single", listOf("海边", "山间", "街角小店"), true, "two", listOf(GalleryCondition("first", listOf("出门走走"))))))
            val repo = GalleryRepository(root.resolve("orbis-gallery/owner"))
            val item = repo.save("慢慢认识你", "questionnaire", galleryJson.encodeToString(form), "ai")
            val fullscreen = mutableStateOf(false)
            compose.setContent { MaterialTheme { GalleryHumanForm(form, item, 1, repo, false, Modifier.fillMaxSize(),
                onTask = { runBlocking { it() } }, onReturn = {}, fullScreen = fullscreen.value) } }
            compose.onNodeWithText("检查答案").performClick()
            compose.onNodeWithText("题目 first：此题必填，请提供非空答案。").assertExists()
            compose.onNodeWithText("出门走走").performClick()
            compose.onNodeWithText("下一页").performClick()
            compose.onNodeWithText("最想去哪里？ *").assertIsDisplayed()
            compose.onNodeWithText("海边").performClick()
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(inst.targetContext.getExternalFilesDir(null), "questionnaire-page-two.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.runOnIdle { fullscreen.value = true }
            compose.onNodeWithText("海边").assertIsDisplayed()
            compose.onNodeWithText("上一页").performClick()
            compose.onNodeWithText("在家休息").performClick()
            compose.onNodeWithText("检查答案").performClick()
            compose.onNodeWithText("准备交给你").assertIsDisplayed()
            compose.onNodeWithText("海边").assertDoesNotExist()
            assertTrue(repo.snapshot().answers.isEmpty())
            compose.onNodeWithText("确认提交").performClick()
            compose.onNodeWithText("提交这份答案？").assertIsDisplayed()
            compose.onAllNodesWithText("确认提交").onLast().performClick()
            compose.waitForIdle()
            val ref = repo.snapshot().answers.single()
            assertEquals(mapOf("first" to listOf("在家休息")), GalleryRepository(root.resolve("orbis-gallery/owner")).answers(ref.id).answers)
            compose.onNodeWithText("带回聊天").assertIsDisplayed()
        } finally { root.deleteRecursively() }
    }
}
