package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Rule
import org.junit.Test

class GardenQuickChatTimelineDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    private var count by mutableIntStateOf(35)
    private var lines by mutableIntStateOf(12)
    private var height by mutableIntStateOf(600)
    private var extraHistory by mutableIntStateOf(0)
    private fun show() = compose.setContent {
        MaterialTheme {
            GardenQuickChatTimeline(Modifier.width(320.dp).height(height.dp),true,count to lines) {
                item(key="progress") { Text("Latest edge",Modifier.testTag("latest-edge")) }
                items((-extraHistory..count).reversed().toList(),key={it}) { id ->
                    Text("Message $id\n" + "Long variable height message\n".repeat(if(id==count) lines else 4),Modifier.testTag("message-$id"))
                }
            }
        }
    }
    @Test fun openingStreamingAppendingAndKeyboardResizeKeepLatestEdgeVisible() {
        show()
        compose.onNodeWithTag("latest-edge").assertIsDisplayed()
        repeat(6) {
            compose.runOnIdle { lines+=8 }
            compose.onNodeWithTag("latest-edge").assertIsDisplayed()
            compose.runOnIdle { count++;lines=10 }
            compose.onNodeWithTag("latest-edge").assertIsDisplayed()
        }
        compose.runOnIdle { height=280 }
        compose.onNodeWithTag("latest-edge").assertIsDisplayed()
        compose.runOnIdle { height=600;extraHistory=30 }
        compose.onNodeWithTag("latest-edge").assertIsDisplayed()
    }
    @Test fun readingOlderMessagesStaysPutAcrossNewOutputAndHistoryPrepend() {
        show()
        compose.onNodeWithTag("garden-quick-chat-messages").performScrollToNode(hasTestTag("message-29"))
        val before=compose.onNodeWithTag("message-29").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { count++;lines=40;extraHistory=30 }
        compose.onNodeWithTag("latest-edge").assertIsNotDisplayed()
        val after=compose.onNodeWithTag("message-29").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertEquals(before,after,1f)
    }
}
