package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI callbacks: no settings, models, real history or services. */
@RunWith(AndroidJUnit4::class)
class OrbisCompactionBranchSettingTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun directToggleCanBeEnabledAndDisabledWithoutAConfirmationOrTool() {
        val changes = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { Column {
            var checked by remember { mutableStateOf(false) }
            OrbisCompactionBranchSetting(checked, true) { checked = it; changes += it }
        } } }
        compose.onNodeWithTag("orbis-compaction-branch-switch").assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("orbis-compaction-branch-switch").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(listOf(true, false), changes) }
        compose.onNodeWithText("保存分支只在本机完成", substring = true).assertExists()
    }

    @Test fun unavailableSettingsDoNotPretendToSave() {
        compose.setContent { MaterialTheme { Column {
            OrbisCompactionBranchSetting(false, false) { error("Not writable") }
        } } }
        compose.onNodeWithTag("orbis-compaction-branch-switch").assertIsNotEnabled().assertIsOff()
    }
}
