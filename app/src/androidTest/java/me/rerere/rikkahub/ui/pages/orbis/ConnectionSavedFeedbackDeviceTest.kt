package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConnectionSavedFeedbackDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val owner = Assistant()
    private fun proposal(name: String) = ConnectionProposal(owner.id.toString(), ConnectionDraft("mcp_http", name, "https://example.invalid/$name"))
    private fun store(settings: Settings) = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext,
        AppScope().also { it.cancel() }).also { it.settingsFlow.value = settings }
    private fun keyText() = compose.onNodeWithTag("connection-key").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun screenshot(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        compose.waitForIdle()
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun failedSaveKeepsInputAndSuccessfulSaveShowsStoredStatusAfterReopen() {
        val p = proposal("test")
        val store = store(Settings(assistants = listOf(owner), connectionProposals = listOf(proposal("later"), p)))
        val fail = AtomicBoolean(true); val saves = AtomicInteger(); val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            if (shown.value) OrbisConnectionProposals(owner.id.toString(), { shown.value = false }, store,
                updateSettings = { transform -> if (fail.get()) false else { store.settingsFlow.value = transform(store.settingsFlow.value); saves.incrementAndGet(); true } })
        } } }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-new-key")
        closeSoftKeyboard()
        compose.onNodeWithText("保存并开启连接").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("这次没有保存，原配置和输入的密钥保留，请重试。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("显示").performScrollTo().performClick()
        assertEquals("synthetic-new-key", keyText())
        compose.onNodeWithText("隐藏").performClick()
        closeSoftKeyboard()
        assertNull(store.settingsFlow.value.connectionProposals.single { it.id == p.id }.installed)
        fail.set(false)
        compose.onNodeWithText("保存并开启连接").performScrollTo().performClick()
        compose.waitUntil(5000) { store.settingsFlow.value.connectionProposals.single { it.id == p.id }.status == "saved" }
        compose.waitForIdle()
        assertEquals("", keyText())
        compose.onNodeWithTag("connection-key-state", useUnmergedTree = true).assertTextEquals("密钥已保存 · 留空保留，填写新密钥可替换。")
        assertTrue(store.settingsFlow.value.mcpServers.single().commonOptions.enable)
        compose.onNodeWithTag("connection-close").performClick()
        compose.runOnIdle { shown.value = true }
        compose.onAllNodesWithText("test").onFirst().performScrollTo().performClick()
        compose.onNodeWithTag("connection-key-state", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertEquals("", keyText())
        compose.onNodeWithText("保存并开启连接").performScrollTo().performClick()
        compose.waitUntil(5000) { saves.get() == 2 }
        compose.waitForIdle()
        assertEquals("Bearer synthetic-new-key", store.settingsFlow.value.mcpServers.single().commonOptions.headers.single().second)
        screenshot("connection-saved-dev238.png")
    }

    @Test fun humanCanCloseOneThenAllAndFindSavedConfigurationAgain() {
        val first = proposal("first"); val second = proposal("second"); val other = Assistant()
        val alien = ConnectionProposal(other.id.toString(), ConnectionDraft("mcp_http", "other", "https://example.invalid/other"))
        var start = Settings(assistants = listOf(owner, other), connectionProposals = listOf(first, second, alien))
        start = ConnectionProposalRules.approve(start, first.id, "synthetic-existing", true)
        val store = store(start); val shown = mutableStateOf(true)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            if (shown.value) OrbisConnectionProposals(owner.id.toString(), { shown.value = false }, store, initialRequest = first,
                updateSettings = { transform -> store.settingsFlow.value = transform(store.settingsFlow.value); true })
        } } }
        compose.onNodeWithTag("connection-dismiss-entry").performScrollTo().performClick()
        compose.waitUntil(5000) { store.settingsFlow.value.connectionProposals.first { it.id == first.id }.setupDismissed }
        compose.onAllNodesWithText("first").assertCountEquals(0)
        assertTrue(store.settingsFlow.value.mcpServers.single().commonOptions.enable)
        compose.onNodeWithTag("connection-show-closed").performScrollTo().performClick()
        compose.onNodeWithText("first").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-key-state", useUnmergedTree = true).assertTextEquals("密钥已保存 · 留空保留，填写新密钥可替换。")
        compose.onNodeWithTag("connection-show-closed").performScrollTo().performClick()
        compose.onNodeWithTag("connection-key").performTextInput("must-not-be-saved")
        closeSoftKeyboard()
        compose.onNodeWithTag("connection-dismiss-setup").performClick()
        compose.waitUntil(5000) { !shown.value }
        val closed = store.settingsFlow.value
        assertTrue(closed.connectionProposals.filter { it.owner == owner.id.toString() }.all { it.setupDismissed && !it.setupCompleted })
        assertEquals(alien, closed.connectionProposals.single { it.id == alien.id })
        assertEquals(start.mcpServers, closed.mcpServers)
        assertEquals(start.assistants, closed.assistants)
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("没有待处理的连接建议。已关闭的建议可以从上方找回，连接配置仍保留。").assertExists()
        compose.onNodeWithTag("connection-show-closed").performScrollTo().performClick()
        compose.onAllNodesWithText("first").onFirst().performClick()
        compose.onNodeWithTag("connection-key-state", useUnmergedTree = true).assertTextEquals("密钥已保存 · 留空保留，填写新密钥可替换。")
        assertEquals("", keyText())
        screenshot("connection-history-dev238.png")
    }
}
