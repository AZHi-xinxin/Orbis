package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.HostToolFailure
import me.rerere.rikkahub.data.ai.withHostToolFailure
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Exercises the production MessagePartsBlock -> ChainOfThought -> reasoning/tool rows path.
 * The shell rule requires IsolatedGenerationLoopRunner and a plain Application. Everything below
 * is synthetic: no database/Koin/private settings, tool execution, provider, media or network.
 */
@RunWith(AndroidJUnit4::class)
class OrbisThinkingChainCollapseTest {
    @get:Rule val compose = createShellComposeRule()
    private var sourceParts by mutableStateOf<List<UIMessagePart>>(emptyList())
    private val base = Instant.parse("2026-09-24T12:00:00Z")

    private fun reasoning(number: Int) = UIMessagePart.Reasoning(
        reasoning = "synthetic reasoning body $number",
        createdAt = base + (number * 10).seconds,
        finishedAt = base + (number * 10 + number).seconds,
    )

    private fun tool(number: Int) = UIMessagePart.Tool(
        toolCallId = "synthetic-call-$number",
        toolName = "synthetic_chain_tool_$number",
        input = "{}",
        output = listOf(UIMessagePart.Text("{\"synthetic\":true}")),
    )

    private fun longChain(): List<UIMessagePart> = listOf(
        reasoning(1), tool(1), reasoning(2), tool(2), reasoning(3), tool(3),
        UIMessagePart.Text(FINAL_PROSE),
    )

    private fun toolTitle(number: Int): String = compose.activity.getString(
        R.string.chat_message_tool_call_generic, tool(number).toolName,
    )

    private fun reasoningTitle(number: Int): String = compose.activity.getString(
        R.string.deep_thinking_seconds, number.toFloat(),
    )

    private fun moreTitle(hidden: Int): String = compose.activity.getString(
        R.string.chain_of_thought_show_more_steps, hidden,
    )

    private fun show(
        segmented: Boolean,
        parts: List<UIMessagePart> = longChain(),
        onApproval: ((String, Boolean, String, Boolean) -> Unit)? = null,
    ) {
        sourceParts = parts
        val settings = Settings(init = true, providers = emptyList(), displaySetting = DisplaySetting(
            enableMessageGenerationHapticEffect = false,
            showThinkingContent = true,
            autoCloseThinking = true,
            orbisAppearance = OrbisAppearance(chatFlow = OrbisChatFlowSettings(enabled = segmented)),
        ))
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides settings,
                LocalNavController provides Navigator(mutableListOf()),
            ) {
                MaterialTheme {
                    OrbisVisualTheme(darkTheme = false) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MessagePartsBlock(
                                assistant = null,
                                role = MessageRole.ASSISTANT,
                                model = null,
                                parts = sourceParts,
                                annotations = emptyList(),
                                loading = false,
                                segmentedReply = segmented,
                                messageKey = "synthetic-thinking-chain",
                                onToolApproval = onApproval,
                            )
                        }
                    }
                }
            }
        }
        awaitText(FINAL_PROSE)
    }

    private fun awaitText(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun toggle(index: Int = 0) {
        compose.onAllNodesWithTag(TOGGLE)[index].performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun assertLongChainCollapsed() {
        compose.onNodeWithText(reasoningTitle(1), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(toolTitle(1), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(reasoningTitle(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(toolTitle(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(reasoningTitle(3), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(toolTitle(3), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(moreTitle(4), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    private fun assertLongChainExpanded() {
        (1..3).forEach { number ->
            compose.onNodeWithText(reasoningTitle(number), useUnmergedTree = true).assertExists()
            compose.onNodeWithText(toolTitle(number), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.chain_of_thought_collapse), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    @Test fun segmentedLongChainInitiallyShowsOnlyTheLastTwoStepsAndKeepsProse() {
        show(segmented = true)
        compose.onAllNodesWithTag(TOGGLE).assertCountEquals(1)
        assertLongChainCollapsed()
        compose.onNodeWithText(FINAL_PROSE).performScrollTo().assertIsDisplayed()
    }

    @Test fun segmentedChainCanExpandAndCollapseRepeatedlyWithoutHidingProse() {
        show(segmented = true)
        repeat(2) {
            toggle()
            assertLongChainExpanded()
            toggle()
            assertLongChainCollapsed()
        }
    }

    @Test fun originalLargeBubbleModeRetainsTheSameGroupCollapseBehavior() {
        show(segmented = false)
        assertLongChainCollapsed()
        toggle()
        assertLongChainExpanded()
        toggle()
        assertLongChainCollapsed()
    }

    @Test fun interleavedReasoningToolsAndProseStayInTheirOriginalVisualOrder() {
        show(segmented = true, parts = listOf(
            reasoning(1), tool(1), reasoning(2), tool(2), UIMessagePart.Text("synthetic middle prose"),
            reasoning(3), tool(3), reasoning(4), tool(4), UIMessagePart.Text(FINAL_PROSE),
        ))
        compose.onAllNodesWithTag(TOGGLE).assertCountEquals(2)
        compose.onNodeWithText("synthetic middle prose", useUnmergedTree = true).assertExists()
        toggle(0)
        toggle(1)
        val expectedOrder = listOf(reasoningTitle(1), toolTitle(1), reasoningTitle(2), toolTitle(2),
            "synthetic middle prose", reasoningTitle(3), toolTitle(3), reasoningTitle(4), toolTitle(4), FINAL_PROSE)
        val tops = expectedOrder.map {
            compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value
        }
        tops.zipWithNext().forEachIndexed { index, (first, second) ->
            assertTrue("${expectedOrder[index]} must precede ${expectedOrder[index + 1]}", first < second)
        }
        // One group can close independently; neither its adjacent prose nor the other group moves into it.
        toggle(0)
        compose.onNodeWithText(toolTitle(1), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(toolTitle(3), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("synthetic middle prose", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    @Test fun twoStepChainHasNoRedundantGroupToggleAndBothStepsStayVisible() {
        show(segmented = true, parts = listOf(reasoning(1), tool(1), UIMessagePart.Text(FINAL_PROSE)))
        compose.onAllNodesWithTag(TOGGLE).assertCountEquals(0)
        compose.onNodeWithText(reasoningTitle(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(toolTitle(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    @Test fun chainGrowingPastTwoStepsBecomesCollapsibleThroughTheRealRenderer() {
        show(segmented = true, parts = listOf(reasoning(1), tool(1), UIMessagePart.Text(FINAL_PROSE)))
        compose.onAllNodesWithTag(TOGGLE).assertCountEquals(0)
        compose.runOnIdle { sourceParts = longChain() }
        awaitText(moreTitle(4))
        assertLongChainCollapsed()
        toggle()
        assertLongChainExpanded()
    }

    @Test fun pendingApprovalOutsideTheTailRemainsVisibleAndActionableWhileOtherOldStepsFold() {
        var approvedId: String? = null
        val pending = tool(1).copy(output = emptyList(), approvalState = ToolApprovalState.Pending)
        show(segmented = true, parts = listOf(pending, reasoning(1), tool(2), reasoning(2),
            reasoning(3), tool(3), UIMessagePart.Text(FINAL_PROSE)),
            onApproval = { id, approved, _, _ -> if (approved) approvedId = id })
        compose.onNodeWithText(toolTitle(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(reasoningTitle(1), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(toolTitle(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(reasoningTitle(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(moreTitle(3), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("此次允许").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(pending.toolCallId, approvedId) }
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    @Test fun hostFailureOutsideTheTailStaysVisibleInOriginalLayoutMode() {
        val failed = tool(1).copy(output = emptyList()).withHostToolFailure(HostToolFailure.INTERRUPTED)
        show(segmented = false, parts = listOf(failed, reasoning(1), tool(2), reasoning(2),
            reasoning(3), tool(3), UIMessagePart.Text(FINAL_PROSE)))
        compose.onNodeWithText(toolTitle(1), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(HostToolFailure.INTERRUPTED.message, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(toolTitle(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(moreTitle(3), useUnmergedTree = true).assertExists()
        toggle()
        compose.onNodeWithText(toolTitle(2), useUnmergedTree = true).assertExists()
        toggle()
        compose.onNodeWithText(HostToolFailure.INTERRUPTED.message, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(FINAL_PROSE, useUnmergedTree = true).assertExists()
    }

    private companion object {
        const val TOGGLE = "chain-of-thought-toggle"
        const val FINAL_PROSE = "synthetic final prose remains visible"
    }
}
