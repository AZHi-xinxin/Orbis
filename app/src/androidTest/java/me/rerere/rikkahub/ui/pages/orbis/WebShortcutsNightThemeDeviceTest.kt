package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.theme.ThemeColors
import me.rerere.rikkahub.data.orbis.theme.ThemePackage
import me.rerere.rikkahub.data.orbis.webshortcuts.*
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.AppScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual shortcut sheet + add dialog under the reported incompatible chat palette. */
class WebShortcutsNightThemeDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun sheetAndUnsavedLinkFollowInterfaceNightModeWithoutChangingTheChatOrSaving() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        check(Build.FINGERPRINT.contains("generic", true) || Build.MODEL.contains("sdk", true))
        val owner = Assistant(name = "合成助手")
        val store = SettingsStore(inst.targetContext, AppScope().also { it.cancel() }).also {
            it.settingsFlow.value = Settings(init = false, assistants = listOf(owner))
        }
        var dark by mutableStateOf(true)
        var inheritedPanel = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalDarkMode provides dark) { MaterialTheme { OrbisVisualTheme {
                val chat = ChatTheme(ThemePackage(name = "合成浅色聊天素材", colors = ThemeColors(
                    background = "#EAF2FF", surface = "#F4F7FF", text = if(dark) "#F4EBDD" else "#263342")), null)
                CompositionLocalProvider(LocalChatTheme provides chat) {
                    val original = OrbisTheme.colors.panel
                    SideEffect { inheritedPanel = original }
                    OrbisWebShortcutsPage(owner.id.toString(), store = store, onClose = {})
                }
            } } }
        }
        fun checkSurface(tag: String, night: Boolean, suffix: String) {
            val node = compose.onNodeWithTag(tag); node.assertIsDisplayed()
            val image = node.captureToImage(); val pixels = image.toPixelMap()
            // Sample below the rounded top and away from text/control hit areas.
            val surface = pixels[3, pixels.height / 2]
            assertEquals("Actual $tag background must follow interface mode", night, surface.luminance() < .15f)
            assertTrue("Custom chat background must remain unchanged", inheritedPanel.luminance() > .8f)
            val output = File(inst.targetContext.getExternalFilesDir(null), "shortcut-$suffix-${if(night) "night" else "day"}.png")
            output.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        for (night in listOf(true, false, true)) {
            compose.runOnIdle { dark = night }
            checkSurface("web-shortcuts-sheet", night, "sheet")
            compose.onNodeWithText("允许 AI 管理并展示网页").assertIsDisplayed()
        }
        compose.onNodeWithText("＋ 保存网页链接").performClick()
        compose.onNodeWithText("http / https 链接").performTextInput("https://example.invalid/unsaved")
        for (night in listOf(true, false, true)) {
            compose.runOnIdle { dark = night }
            checkSurface("web-shortcuts-add-dialog", night, "draft")
            compose.onNodeWithText("https://example.invalid/unsaved").assertExists()
        }
        compose.onNodeWithText("取消").performClick()
        assertTrue(openWebShortcuts(inst.targetContext, owner.id.toString()).snapshot().entries.isEmpty())
    }
    @Test fun lastOfEighteenMcpEntriesCanBeReachedAndGrantedForOnlyThisOrigin() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        val mcps = (1..18).map { index -> McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(name = "fixture_$index",
                displayName = if(index == 18) "阿止的调酒台" else "合成连接 $index")) }
        val owner = Assistant(name = "合成助手", mcpServers = mcps.map { it.id }.toSet())
        val store = SettingsStore(inst.targetContext, AppScope().also { it.cancel() }).also {
            it.settingsFlow.value = Settings(init = false, assistants = listOf(owner), mcpServers = mcps)
        }
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        kotlin.concurrent.thread(isDaemon = true) {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 2000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = "<html><body>synthetic local page</body></html>".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            } } catch (_: java.io.IOException) { }
        }
        val url = "http://127.0.0.1:${server.localPort}/fixture"
        val conversation = java.util.UUID.randomUUID().toString()
        try {
            WebRecommendations.show(owner.id.toString(), conversation, WebShortcut("test", url, "合成网页", "human"))
            compose.setContent { CompositionLocalProvider(LocalDarkMode provides true) { MaterialTheme {
                OrbisVisualTheme { OrbisWebEntry(owner.id.toString(), conversation, store) }
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("网页授权").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("网页授权").performClick()
            val proof = compose.onNodeWithText("关联：阿止的调酒台")
            proof.performScrollTo().assertIsDisplayed()
            val repo = openWebShortcuts(inst.targetContext, owner.id.toString())
            assertTrue(repo.snapshot().interactiveOrigins.isEmpty())
            proof.performClick()
            compose.waitUntil(10_000) { repo.snapshot().interactiveOrigins[shortcutOrigin(url)] == mcps.last().id.toString() }
            assertEquals(mapOf(shortcutOrigin(url) to mcps.last().id.toString()), repo.snapshot().interactiveOrigins)
        } finally {
            compose.runOnIdle { WebRecommendations.close(owner.id.toString(), conversation, "test") }
            server.close()
        }
    }

}
