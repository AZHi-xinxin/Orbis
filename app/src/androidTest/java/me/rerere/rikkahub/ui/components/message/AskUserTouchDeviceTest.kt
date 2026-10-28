package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AskUserTouchDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun actualTouchSelectsSingleChoiceAndSubmits() {
        var answer = ""
        val tool = UIMessagePart.Tool("touch-one", "ask_user", """{"questions":[{"id":"target","question":"装在哪里？","options":["手机","电脑","VPS"],"selection_type":"single"}]}""", approvalState = ToolApprovalState.Pending)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { Column(Modifier.width(320.dp).padding(12.dp)) {
            ChainOfThought(steps = listOf(tool)) { ChatMessageToolStep(it, onToolAnswer = { _, value -> answer = value }) }
        } } }
        compose.onNodeWithText("电脑").performTouchInput { click(center) }
        compose.onNodeWithText("电脑").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("电脑").assertIsSelected()
        compose.onAllNodes(isEnabled() and hasClickAction()).fetchSemanticsNodes()
        // Submission has no text, only the tick icon; select its Button role rather than a test-only callback.
        compose.onNode(isEnabled() and androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)
            and !hasAnyDescendant(hasText("手机")) and !hasAnyDescendant(hasText("电脑")) and !hasAnyDescendant(hasText("VPS")))
            .performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(answer.contains("电脑")) }
    }
}
