package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic shared group/TechHub composer only: never opens repositories or sends a request. */
@RunWith(AndroidJUnit4::class)
class OrbisCommunityComposerTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun emptyComposerStaysCompactAndDoesNotSend() {
        var sent = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) {
            OrbisCommunityComposer("", {}, { sent++ }, true, false, "向群里说话", OrbisAppearance(composerOpacity = .2f))
        } } }
        val bounds = compose.onNodeWithTag("orbis-community-composer").fetchSemanticsNode().boundsInRoot
        assertTrue("empty composer must be at most 90dp", bounds.height <= with(compose.density) { 90.dp.toPx() })
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, sent) }
    }
    @Test fun typingAndOneExplicitTapSendsOnlyCurrentText() {
        val sent = mutableListOf<String>()
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) {
            var text by remember { mutableStateOf("") }
            OrbisCommunityComposer(text, { text = it }, { sent += text }, true, text.isNotBlank(),
                "向群里说话", OrbisAppearance())
        } } }
        compose.onNode(hasSetTextAction()).performTextInput("仅合成群消息")
        compose.runOnIdle { assertEquals(0, sent.size) }
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.runOnIdle { assertEquals(listOf("仅合成群消息"), sent) }
    }
    @Test fun stopIsSeparateFromSendingAndNeverResendsDraft() {
        var sent = 0; var stopped = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) {
            OrbisCommunityComposer("未发送草稿", {}, { sent++ }, true, false, "群聊", OrbisAppearance(),
                sending = true, onStop = { stopped++ })
        } } }
        compose.onNodeWithContentDescription("发送消息").assertDoesNotExist()
        compose.onNodeWithContentDescription("停止本轮").performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(0, sent) }
    }
}
