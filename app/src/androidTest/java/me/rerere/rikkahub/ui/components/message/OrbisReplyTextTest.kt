package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisActionMarker
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic text/settings only. No database, provider, microphone, credentials or real chat. */
@RunWith(AndroidJUnit4::class)
class OrbisReplyTextTest {
    @get:Rule val compose = createShellComposeRule()
    private var text by mutableStateOf("第一段。\n\n（抬手）\n\n第二段。")
    private var flow by mutableStateOf(OrbisChatFlowSettings())

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(),
                LocalNavController provides Navigator(mutableListOf())) {
                MaterialTheme {
                    OrbisVisualTheme(darkTheme = false) {
                        OrbisReplyText(text, OrbisAppearance(chatFlow = flow), "synthetic-test-message")
                    }
                }
            }
        }
        awaitTag("orbis-reply-bubble-0")
    }

    private fun awaitTag(tag: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun recognize(collapse: Boolean = false) = OrbisChatFlowSettings(
        distinguishActions = true, markers = setOf(OrbisActionMarker.FULLWIDTH_ROUND),
        collapseActions = collapse,
    )

    @Test fun defaultNoneKeepsParenthesesAsVisibleNormalProse() {
        show()
        compose.onNodeWithText("（抬手）").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
        compose.onNodeWithText("第一段。").assertExists()
        compose.onNodeWithText("第二段。").assertExists()
    }

    @Test fun selectingMarkerDefaultsToVisibleDimmingNotFolding() {
        flow = recognize()
        show()
        awaitTag("orbis-action-content-6")
        compose.onNodeWithText("（抬手）").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
    }

    @Test fun foldSwitchHidesUntilTappedAndCanCloseAgain() {
        flow = recognize(collapse = true)
        show()
        awaitTag("orbis-action-toggle-6")
        compose.onNodeWithText("（抬手）").assertDoesNotExist()
        compose.onNodeWithTag("orbis-action-toggle-6").performClick()
        compose.onNodeWithText("（抬手）").assertExists()
        compose.onNodeWithTag("orbis-action-toggle-6").performClick()
        compose.onNodeWithText("（抬手）").assertDoesNotExist()
    }

    @Test fun noneOverridesRememberedMarkersAndFoldSetting() {
        flow = recognize(collapse = true).copy(distinguishActions = false)
        show()
        compose.onNodeWithText("（抬手）").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
    }

    @Test fun switchingBackToNoneRestoresOriginalTextWithoutAnActionRow() {
        flow = recognize(collapse = true)
        show()
        awaitTag("orbis-action-toggle-6")
        compose.runOnIdle { flow = flow.copy(distinguishActions = false) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("orbis-action-toggle-6"), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodes(hasTestTag("orbis-reply-parsing")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("（抬手）").assertExists()
        compose.onNodeWithText("第二段。").assertExists()
    }

    @Test fun unfinishedMarkerStaysVisibleDuringStreaming() {
        text = "第一段。\n\n（动作还没有结束"
        flow = recognize(collapse = true)
        show()
        compose.onNodeWithText("（动作还没有结束").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
    }

    @Test fun completeBoldInsideActionAndAroundWholeActionUseDimming() {
        text = "第一段。\n\n（轻轻**点头**）\n\n**（挥手）**"
        flow = recognize()
        val inner = text.indexOf('（')
        val wrapper = text.indexOf("**（")
        show()
        awaitTag("orbis-action-content-$inner")
        awaitTag("orbis-action-content-$wrapper")
        compose.onNodeWithTag("orbis-reply-bubble-$inner").assertDoesNotExist()
        compose.onNodeWithTag("orbis-reply-bubble-$wrapper").assertDoesNotExist()
        compose.onNodeWithText("（轻轻点头）").assertExists()
        compose.onNodeWithText("（挥手）").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
    }

    @Test fun formattedActionFoldsAndCanExpandAndCloseWithoutOrphanAsterisks() {
        text = "第一段。\n\n**（挥手）**"
        flow = recognize(collapse = true)
        val start = text.indexOf("**（")
        show()
        awaitTag("orbis-action-toggle-$start")
        compose.onNodeWithText("（挥手）").assertDoesNotExist()
        compose.onNodeWithText("**").assertDoesNotExist()
        compose.onNodeWithTag("orbis-action-toggle-$start").performClick()
        compose.onNodeWithText("（挥手）").assertExists()
        compose.onNodeWithTag("orbis-action-toggle-$start").performClick()
        compose.onNodeWithText("（挥手）").assertDoesNotExist()
    }

    @Test fun noActionDistinctionKeepsFormattedParenthesesInNormalBubble() {
        text = "第一段。\n\n**（挥手）**"
        flow = recognize(collapse = true).copy(distinguishActions = false)
        val start = text.indexOf("**（")
        show()
        compose.onNodeWithTag("orbis-reply-bubble-$start").assertExists()
        compose.onNodeWithTag("orbis-action-toggle-$start").assertDoesNotExist()
        compose.onNodeWithText("（挥手）").assertExists()
    }

    @Test fun emphasisSpanningActionAndDialogueRemainsOneVisibleBubble() {
        text = "第一段。\n\n**说话（挥手）继续说话**"
        flow = recognize(collapse = true)
        val start = text.indexOf("**")
        show()
        compose.onNodeWithTag("orbis-reply-bubble-$start").assertExists()
        compose.onNodeWithText("说话（挥手）继续说话").assertExists()
        compose.onNodeWithText("动作").assertDoesNotExist()
    }
}
