package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.StateRestorationTester
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IncomingChatDraftDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun leavingAndReturningOrRestoringDoesNotRefillSentDraftButNewReturnDoes() {
        val visible = mutableStateOf(true)
        val delivery = mutableStateOf("first-return")
        var imports = 0
        val restore = StateRestorationTester(compose)
        restore.setContent {
            val holder = rememberSaveableStateHolder()
            if(visible.value) holder.SaveableStateProvider(delivery.value) {
                ConsumeIncomingChatDraft("chat", delivery.value, "same-answer", emptyList()) { imports++ }
                Text("chat")
            } else Text("home")
        }
        compose.runOnIdle { assertEquals(1, imports); visible.value = false }
        compose.runOnIdle { visible.value = true }
        compose.runOnIdle { assertEquals(1, imports) }
        restore.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1, imports); delivery.value = "second-return" }
        compose.runOnIdle { assertEquals(2, imports) }
    }
}
