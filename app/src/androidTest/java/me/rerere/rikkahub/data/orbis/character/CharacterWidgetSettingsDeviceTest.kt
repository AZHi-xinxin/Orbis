package me.rerere.rikkahub.data.orbis.character

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.activity.OrbisCharacterActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File

/** Synthetic owner on an isolated emulator; no production chats/providers or room renderer. */
@RunWith(AndroidJUnit4::class)
class CharacterWidgetSettingsDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun realWidgetPreviewThumbnailsDraftAndAssetsAreUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        val qemu = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        check(android.os.ParcelFileDescriptor.AutoCloseInputStream(qemu).bufferedReader().use { it.readText().trim() } == "1")
        val context = instrumentation.targetContext
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        check(GlobalContext.getOrNull() == null)
        val owner = Assistant(name = "阿止 · 界面验收")
        val store = openCharacterStore(context, owner.id.toString())
        // This test exercises the public starter, never an operator's private art fixture.
        val fixture = File(context.getExternalFilesDir(null), "no-fixture-${owner.id}.zip")
        if (fixture.exists()) {
            val draft = fixture.inputStream().use(CharacterArchive::read)
            verifyCharacterDraft(draft)
            // Copy art only; never include private dialogue even if an operator supplied a richer pack.
            store.install(draft.copy(interactions = CharacterInteractions(), presentation = draft.presentation?.copy(greeting = "把今天，慢慢说给我听。")), 0)
        }
        val dormant = AppScope().also { it.cancel() }
        val settings = SettingsStore(context, dormant).also { it.settingsFlow.value = Settings(
            assistants = listOf(owner), assistantId = owner.id, providers = emptyList(), mcpServers = emptyList(), ttsProviders = emptyList()) }
        startKoin { modules(module { single { settings } }) }
        val output = File(context.getExternalFilesDir(null), "widget-settings-v1-output").apply { check(mkdirs() || isDirectory) }
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(output, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        try {
            val intent = Intent(context, OrbisCharacterActivity::class.java).putExtra("character_owner", owner.id.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<OrbisCharacterActivity>(intent).use { scenario ->
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-tile-cloud-blue").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-effect-preview").assertIsDisplayed()
                compose.onNodeWithTag("widget-tile-cloud-blue").assertIsDisplayed()
                compose.onNodeWithTag("widget-tile-peach-cream").performClick()
                compose.waitUntil(5_000) { store.read().presentation.card == "peach-cream" }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-preview-ready-peach-cream").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("添加到桌面").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("widget-tile-peach-cream").assertIsSelected()
                compose.onNodeWithTag("widget-tile-cloud-blue").performClick()
                compose.waitUntil(5_000) { store.read().presentation.card == "cloud-blue" }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-preview-ready-cloud-blue").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("添加到桌面").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("widget-tile-cloud-blue").assertIsSelected()
                screenshot("widget-appearance.png")
                compose.onNodeWithText("人物与素材").performClick()
                if (!fixture.exists()) {
                    compose.onNodeWithTag("character-hair-mimo").performScrollTo().performClick()
                    compose.waitUntil(5_000) { store.read().presentation.colors.hair == "mimo" }
                    compose.onNodeWithTag("character-eyes-gpt").performScrollTo().performClick()
                    compose.waitUntil(5_000) { store.read().presentation.colors == CharacterColors("mimo", "gpt") }
                    screenshot("widget-colors.png")
                }
                compose.onNodeWithText("动作", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                screenshot("widget-assets.png")
                compose.onNodeWithText("外观").performScrollTo().assertIsDisplayed().performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-greeting").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-greeting").performScrollTo().performTextReplacement("测试短句草稿")
                // Activity recreation preserves an unsaved text draft without writing it prematurely.
                scenario.recreate()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("widget-greeting").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("widget-greeting").performScrollTo().assertTextContains("测试短句草稿")
                assertNotEquals("测试短句草稿", store.read().presentation.greeting)
                compose.onNodeWithText("保存短句").performScrollTo().performClick()
                compose.waitUntil(5_000) { store.read().presentation.greeting == "测试短句草稿" }
                compose.onNodeWithText("高级设置").performScrollTo().performClick()
                compose.onNodeWithText("全屏互动开发中，入口暂未开放；已有房间素材与台词保留。").performScrollTo().assertIsDisplayed()
            }
        } finally { stopKoin() }
    }
}
