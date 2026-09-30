package me.rerere.rikkahub.ui.components.approval

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.HostToolApproval
import me.rerere.rikkahub.data.ai.approval.ToolApprovalStore
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic in-memory grants only. Never touches real assistants, services, tools or device permissions. */
@RunWith(AndroidJUnit4::class)
class AllToolsApprovalUiTest {
    @get:Rule val compose = createShellComposeRule()
    private fun store() = ToolApprovalStore({ null }, {})

    @Test fun oneTapEnablesAndOneTapDisablesWithoutSecondConfirmation() {
        val store = store()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            RememberedToolApprovals("synthetic-a", store)
        } } }
        compose.onNodeWithTag("approval-allow-all").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(store.allowsAll("synthetic-a")); assertFalse(store.allowsAll("synthetic-b")) }
        compose.onNodeWithTag("approval-allow-all").performClick().assertIsOff()
        compose.runOnIdle { assertFalse(store.allowsAll("synthetic-a")) }
    }

    @Test fun changingAssistantDoesNotReusePreviousSwitchState() {
        val store = store()
        val assistant = mutableStateOf("synthetic-a")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            RememberedToolApprovals(assistant.value, store)
        } } }
        compose.onNodeWithTag("approval-allow-all").performClick().assertIsOn()
        compose.runOnIdle { assistant.value = "synthetic-b" }
        compose.onNodeWithTag("approval-allow-all").assertIsOff()
        compose.runOnIdle { assertTrue(store.allowsAll("synthetic-a")); assertFalse(store.allowsAll("synthetic-b")) }
    }

    @Test fun revokeAllClearsCurrentGlobalAndIndividualGrantsOnly() {
        val store = store()
        store.setAllowAll("synthetic-a", true)
        store.setAllowAll("synthetic-b", true)
        store.allow("synthetic-a", HostToolApproval("synthetic-a:test", "one", "Synthetic individual tool"))
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            RememberedToolApprovals("synthetic-a", store)
        } } }
        compose.onNodeWithTag("approval-revoke-all").performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(store.allowsAll("synthetic-a"))
            assertTrue(store.allowsAll("synthetic-b"))
            assertTrue(store.grants.value.none { it.assistantId == "synthetic-a" })
        }
    }

    @Test fun failedSaveLeavesSwitchOffAndReportsFailure() {
        var fail = false
        val store = ToolApprovalStore({ null }) { check(!fail) }
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            RememberedToolApprovals("synthetic-a", store)
        } } }
        compose.runOnIdle { fail = true }
        compose.onNodeWithTag("approval-allow-all").performClick().assertIsOff()
        compose.onNodeWithText("授权未能保存", substring = true).performScrollTo().assertExists()
        compose.runOnIdle { assertFalse(store.allowsAll("synthetic-a")) }
    }
}
