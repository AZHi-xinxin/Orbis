package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic selection only. Does not touch real preferences, companion service or Bluetooth. */
@RunWith(AndroidJUnit4::class)
class OrbisNativeToolSelectionUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun memoryAndCompanionStartOffAndOnlyExplicitTapChangesTheirSelection() {
        val changes = mutableListOf<Pair<LocalToolOption, Boolean>>()
        compose.setContent { MaterialTheme {
            var selected by remember { mutableStateOf(emptyList<LocalToolOption>()) }
            OrbisNativeToolSelectionCard("合成 AI", listOf(LocalToolOption.CompanionDevice, LocalToolOption.CompanionMemory),
                selected, true) { option, enabled ->
                changes += option to enabled
                selected = if (enabled) selected + option else selected - option
            }
        } }
        compose.onNodeWithTag("native-tool-toggle-companion").assertIsOff()
        compose.onNodeWithTag("native-tool-toggle-memory").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("native-tool-toggle-companion").assertIsOff()
        compose.runOnIdle { assertEquals(listOf(LocalToolOption.CompanionMemory to true), changes) }
    }

    @Test fun loadingIdentityCannotEnableToy() {
        compose.setContent { MaterialTheme {
            OrbisNativeToolSelectionCard("未选择 AI", listOf(LocalToolOption.BluetoothToy), emptyList(), false) { _, _ ->
                error("Disabled selection must not call a writer")
            }
        } }
        compose.onNodeWithTag("native-tool-toggle-toy").assertIsOff().assertIsNotEnabled()
    }
}
