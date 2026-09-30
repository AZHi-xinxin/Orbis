package me.rerere.rikkahub.ui.components.message

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrbisDeletedToolRecordsTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun receiptIsCollapsedUntilOpenedAndRestoresOnlyTheSelectedCall() {
        var restored = ""
        compose.setContent {
            MaterialTheme {
                OrbisDeletedToolRecords(listOf("one" to "search_web", "two" to "device_context")) { restored = it }
            }
        }
        compose.onNodeWithText("search_web").assertDoesNotExist()
        compose.onNodeWithTag("orbis-deleted-tools").performClick()
        compose.onNodeWithText("search_web").assertIsDisplayed()
        compose.onNodeWithTag("orbis-restore-tool-two").performClick()
        compose.runOnIdle { assertEquals("two", restored) }
        compose.onNodeWithTag("orbis-deleted-tools").performClick()
        compose.onNodeWithText("device_context").assertDoesNotExist()
    }

    @Test fun readOnlyReceiptDoesNotPretendRestoreIsAvailable() {
        compose.setContent { MaterialTheme { OrbisDeletedToolRecords(listOf("one" to "search_web"), null) } }
        compose.onNodeWithTag("orbis-deleted-tools").performClick()
        compose.onNodeWithTag("orbis-restore-tool-one").assertIsNotEnabled()
    }

    @Test fun deleteButtonCallsOnceWithoutAnExtraConfirmation() {
        var count = 0
        compose.setContent { MaterialTheme { ToolRecordDeleteButton { count++ } } }
        compose.onNodeWithTag("orbis-delete-tool-record").performClick()
        compose.runOnIdle { assertEquals(1, count) }
    }
}
