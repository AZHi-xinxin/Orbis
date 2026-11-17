package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.compaction.*
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.ui.pages.chat.ManualSummaryController
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import kotlin.uuid.Uuid

/** Synthetic provider and chat only. The real draft store and Compose controller are exercised. */
@RunWith(AndroidJUnit4::class)
class OrbisManualSummaryUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun selectCountPromptFailResumeEditAndConfirm() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val c = Conversation(assistantId = Uuid.random(), messageNodes = List(12) { UIMessage.user("Synthetic original $it").toMessageNode() })
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        var fail = true
        var applied: OrbisManualContextPreview? = null
        val generator = ManualSummaryGenerator("synthetic-model", "Synthetic model") { draft, index, _ ->
            assertEquals("FIRST PERSON REQUIREMENT", draft.prompt)
            calls += index
            delay(30)
            if (index == 2 && fail) { fail = false; error("synthetic interrupted request") }
            "Synthetic saved summary $index"
        }
        fun controller() = ManualSummaryController(context, scope, c.id, { c }, { prepareOrbisManualContext(c, it) },
            { generator }, { d -> d.requireSource(c); prepareOrbisGeneratedContext(c, d.archiveThrough, d.summaries.map { checkNotNull(it) }) },
            { p -> applied = p; Uuid.random() })
        val control = controller()
        val store = ManualSummaryStore(File(context.noBackupFilesDir, "manual-summary-drafts"))
        try {
            compose.setContent { MaterialTheme { OrbisManualSummarySheet(c, control, {}) } }
            compose.onNodeWithTag("summary-through").performScrollTo().performTextReplacement("10")
            compose.onNodeWithTag("summary-count").performScrollTo().performTextReplacement("6")
            compose.onNodeWithTag("summary-prompt").performScrollTo().performTextReplacement("FIRST PERSON REQUIREMENT")
            compose.onNodeWithTag("summary-plan").performScrollTo().performClick()
            compose.waitUntil(10000) { !control.busy && control.draft != null }
            assertEquals(6, control.draft!!.chunks.size); assertTrue(calls.isEmpty())
            compose.onNodeWithText("1 段").performScrollTo().performClick()
            compose.onNodeWithTag("summary-generate").performScrollTo().performClick()
            compose.waitUntil(10000) { !control.busy && control.error != null }
            assertEquals(listOf(0,1,2), calls.toList()); assertEquals(2, store.read(c.id.toString())!!.completed)
            compose.runOnIdle { control.open() }
            compose.waitUntil(10000) { !control.busy }
            assertEquals(2, control.draft!!.completed)
            compose.onNodeWithTag("summary-generate").performScrollTo().performClick()
            compose.waitUntil(10000) { !control.busy && control.draft?.complete == true }
            assertEquals(listOf(0,1,2,2,3,4,5), calls.toList())
            compose.onNodeWithTag("summary-edit").performScrollTo().performTextReplacement("HUMAN REVIEWED SUMMARY")
            compose.onNodeWithText("保存本段修改").performScrollTo().performClick()
            compose.waitUntil(10000) { !control.busy && control.draft?.summaries?.first() == "HUMAN REVIEWED SUMMARY" }
            compose.onNodeWithTag("summary-preview").performScrollTo().performClick()
            compose.waitUntil(10000) { !control.busy && control.preview != null }
            assertNull(applied)
            compose.onNodeWithTag("summary-apply").performScrollTo().assertIsEnabled()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir, "manual-summary-preview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.onNodeWithTag("summary-apply").performClick()
            compose.onNodeWithText("保留原文并应用").performClick()
            compose.waitUntil(10000) { !control.busy && control.archiveId != null }
            assertEquals(6, applied!!.metadata.manualSummaryParts.size)
            assertEquals("HUMAN REVIEWED SUMMARY", applied!!.metadata.manualSummaryParts.first())
            assertNull(store.read(c.id.toString()))
            assertEquals(12, c.messageNodes.size)
        } finally { scope.cancel(); store.clear(c.id.toString()) }
    }
}
