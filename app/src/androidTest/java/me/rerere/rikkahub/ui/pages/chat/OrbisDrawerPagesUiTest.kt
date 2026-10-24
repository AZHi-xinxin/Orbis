package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrbisDrawerPagesUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun fixedEntryStaysAboveNewChatAndReturningRestoresLongHistoryScroll() {
        lateinit var history: LazyListState
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            history = rememberLazyListState()
            OrbisDrawerPages("owner", true, Modifier.requiredSize(340.dp, 520.dp),
                newConversation = { Button({}, Modifier.fillMaxWidth().testTag("new-chat")) { Text("新建同一助手会话") } },
                history = { LazyColumn(state = history, modifier = Modifier.weight(1f).testTag("history")) {
                    items(200) { Text("会话 $it", Modifier.fillMaxWidth().height(48.dp)) }
                } }, appearance = { back ->
                    Text("已有主题、背景和字体")
                    repeat(30) { Text("原有设置 $it", Modifier.height(48.dp)) }
                    TextButton(back) { Text("完成预览") }
                })
        } } }
        compose.onNodeWithTag("history").performScrollToIndex(120)
        compose.onNodeWithTag("drawer-appearance-open").assertIsDisplayed()
        val entry = compose.onNodeWithTag("drawer-appearance-open").fetchSemanticsNode().boundsInRoot
        val create = compose.onNodeWithTag("new-chat").fetchSemanticsNode().boundsInRoot
        assertTrue(entry.bottom <= create.top)
        val position = compose.runOnIdle { history.firstVisibleItemIndex to history.firstVisibleItemScrollOffset }
        compose.onNodeWithTag("drawer-appearance-open").performClick()
        compose.onNodeWithTag("history").assertDoesNotExist()
        compose.onNodeWithText("已有主题、背景和字体").assertIsDisplayed()
        compose.onNodeWithTag("drawer-appearance-back").performClick()
        compose.runOnIdle { assertEquals(position, history.firstVisibleItemIndex to history.firstVisibleItemScrollOffset) }
        compose.onNodeWithTag("drawer-appearance-page").assertDoesNotExist()
    }

    @Test fun ownerChangeAndDrawerCloseResetOnlyTheNestedPageAtLargeFont() {
        val owner = mutableStateOf("one"); val open = mutableStateOf(true)
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                OrbisDrawerPages(owner.value, open.value, Modifier.requiredSize(320.dp, 420.dp),
                    newConversation = { Button({}) { Text("新建同一助手会话") } },
                    history = { Text("原搜索与分组") }, appearance = { Text("当前设置") })
            }
        } } }
        compose.onNodeWithTag("drawer-appearance-open").performClick()
        compose.runOnIdle { owner.value = "two" }
        compose.onNodeWithTag("drawer-appearance-open").assertIsDisplayed()
        compose.onNodeWithTag("drawer-appearance-open").performClick()
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("原搜索与分组").assertIsDisplayed()
        compose.onNodeWithTag("drawer-appearance-page").assertDoesNotExist()
    }
}
