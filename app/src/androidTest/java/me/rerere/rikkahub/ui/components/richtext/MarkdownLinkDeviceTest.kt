package me.rerere.rikkahub.ui.components.richtext

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.AndroidUriHandler
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.context.LocalSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MarkdownLinkDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val url = "https://example.invalid/page/#" + "%E6%98%9F(abc);,".repeat(60) + "%25%2Ftail"
    private val intents = mutableListOf<Intent>()
    private fun resolver(fail: Boolean = false): UriHandler {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                // Capture the actual system-launch request without opening any network destination.
                intents += intent
                if (fail) throw ActivityNotFoundException("synthetic-resolver-missing")
            }
        }
        return AndroidUriHandler(context)
    }
    private fun clickLabel(label: String) {
        compose.waitUntil(5000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText(label)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        // HTML paragraphs fill the row: its box center may be blank, so tap the actual glyph.
        val point = layouts.single().getBoundingBox(label.length / 2).center
        node.performTouchInput { click(point) }
        compose.waitForIdle()
    }
    private fun assertOpened(expected: String) {
        compose.waitUntil(5000) { intents.size == 1 }
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals(expected, intents.single().dataString)
    }
    @Test fun angleWrappedLongUrlInSelectableChatLaunchesExactSystemIntent() {
        val handler = resolver()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings(), LocalUriHandler provides handler) {
            SelectionContainer { MarkdownBlock("[打开链接](<$url>)") }
        } } }
        clickLabel("打开链接")
        assertOpened(url)
    }
    @Test fun ordinaryLongUrlStillLaunchesUnchanged() {
        val handler = resolver()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings(), LocalUriHandler provides handler) {
            MarkdownBlock("[普通链接]($url)")
        } } }
        clickLabel("普通链接")
        assertOpened(url)
    }
    @Test fun mixedHtmlPathLaunchesExactLongHash() {
        val handler = resolver()
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings(), LocalUriHandler provides handler) {
            MarkdownBlock("<div>网页说明</div>\n\n[查看网页](<$url>)")
        } } }
        clickLabel("查看网页")
        assertOpened(url)
    }
    @Test fun imageParagraphUsesTheSameDestinationAndSystemHandler() {
        val handler = resolver()
        compose.setContent { MaterialTheme { CompositionLocalProvider(
            LocalSettings provides Settings(), LocalUriHandler provides handler, LocalImportedHistory provides true,
        ) {
            // Imported-history mode prevents image loading; the image still selects the FlowRow renderer.
            MarkdownBlock("![示意图](https://example.invalid/synthetic.png) [图片旁链接](<$url>)")
        } } }
        clickLabel("图片旁链接")
        assertOpened(url)
    }
    @Test fun standaloneHtmlRendererUsesSameFailureBoundary() {
        val failures = mutableIntStateOf(0)
        val handler = MarkdownUriHandler(resolver(true)) { failures.intValue++ }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings(), LocalUriHandler provides handler) {
            Column {
                MarkdownNew("<a href=\"$url\">失败链接</a>")
                if (failures.intValue > 0) Text("打开失败，原消息仍在")
            }
        } } }
        clickLabel("失败链接")
        assertOpened(url)
        compose.onNodeWithText("打开失败，原消息仍在").assertIsDisplayed()
        assertEquals(1, failures.intValue)
    }
    @Test fun nativeMarkdownReportsMissingBrowserOnceWithoutCrashingChat() {
        val failures = mutableIntStateOf(0)
        val handler = MarkdownUriHandler(resolver(true)) { failures.intValue++ }
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalSettings provides Settings(), LocalUriHandler provides handler) {
            Column {
                MarkdownBlock("[重试链接](<$url>)")
                if (failures.intValue > 0) Text("打开失败，原消息仍在")
            }
        } } }
        clickLabel("重试链接")
        assertOpened(url)
        compose.onNodeWithText("打开失败，原消息仍在").assertIsDisplayed()
        assertEquals(1, failures.intValue)
    }
}
