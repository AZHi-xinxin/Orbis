package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic records, under IsolatedGenerationLoopRunner; no application/service startup. */
@RunWith(AndroidJUnit4::class)
class OrbisCallSourceMessagesUiTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun pendingToolOnlyExpandsLiteralHistoryAndCannotApproveOrExecute() {
        val tool = UIMessagePart.Tool("synthetic-tool", "workspace_write_file", "{\"text\":\"<script>not executed</script>\"}",
            approvalState = ToolApprovalState.Pending)
        val node = UIMessage.assistant("").copy(parts = listOf(tool)).toMessageNode()
        compose.setContent { MaterialTheme { OrbisCallSourceMessages(listOf(node)) } }
        compose.onNodeWithText("记录状态：等待批准；此处不可操作，请返回原消息处理").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("orbis-call-source-toggle-0-0-tool").performClick()
        compose.onNodeWithText(tool.input).assertExists()
        compose.onNodeWithText("批准").assertDoesNotExist()
        compose.onNodeWithText("执行").assertDoesNotExist()
        compose.onNodeWithText("重新生成").assertDoesNotExist()
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithTag("orbis-call-source-toggle-0-0-tool").performClick()
        compose.runOnIdle {
            assertSame(tool, node.currentMessage.parts.single())
            assertEquals(ToolApprovalState.Pending, tool.approvalState)
            assertEquals(emptyList<UIMessagePart>(), tool.output)
        }
    }

    @Test fun distantNodesAreLazyAndOnlySelectedBranchIsShown() {
        val nodes = List(200) { index -> UIMessage.assistant("合成原文-$index").toMessageNode() }
        val selected = nodes.toMutableList().apply {
            this[0] = MessageNode(messages = listOf(UIMessage.assistant("未选分支"), UIMessage.assistant("当前分支")), selectIndex = 1)
        }
        compose.setContent { MaterialTheme { OrbisCallSourceMessages(selected) } }
        compose.onNodeWithText("当前分支").assertExists()
        compose.onNodeWithText("未选分支").assertDoesNotExist()
        compose.onNodeWithTag("orbis-call-source-node-199").assertDoesNotExist()
        compose.onNodeWithTag("orbis-call-source-list").performScrollToIndex(200)
        compose.onNodeWithText("合成原文-199").assertExists()
    }

    @Test fun htmlIsLiteralAttachmentsDoNotOpenAndLongTextIsOnlyPreviewed() {
        val literal = "<svg onload=\"doNotExecute()\">literal SVG</svg>"
        val node = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Text(literal),
            UIMessagePart.Document("file:///not-real/gift.html", "../gift.html"),
            UIMessagePart.Text("x".repeat(16384) + "必须被截断的结尾"),
        )).toMessageNode()
        compose.setContent { MaterialTheme { OrbisCallSourceMessages(listOf(node)) } }
        compose.onNodeWithText(literal).assertExists()
        compose.onNodeWithText("文件 · gift.html（文件入口在卡片下方）").assertExists()
        compose.onNodeWithText("预览已截断；原始记录未修改。").assertExists()
        compose.onNodeWithText("必须被截断的结尾", substring = true).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(16384 + "必须被截断的结尾".length, (node.currentMessage.parts[2] as UIMessagePart.Text).text.length) }
    }

    @Test fun invalidSelectedBranchDoesNotThrowOrMutateTheSource() {
        val node = MessageNode(messages = emptyList(), selectIndex = 9)
        compose.setContent { MaterialTheme { OrbisCallSourceMessages(listOf(node)) } }
        compose.onNodeWithText("当前分支索引无效；未更改或丢弃原记录。").assertExists()
        compose.runOnIdle { assertEquals(9, node.selectIndex); assertEquals(0, node.messages.size) }
    }
}
