package me.rerere.rikkahub.data.orbis.st

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.extensions.workspace.StRuntimeCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class StRuntimeCardDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun firstStartExplainsRegistrationInsteadOfSendingHumanToDisabledButton() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner)
        val context = inst.targetContext
        check(context.packageName.endsWith(".bodylab"))
        val root = "st-card-fixture-${Uuid.random()}"
        val host = StRuntimeHost.get(context)
        host.setEnabled(root, false)
        try {
            compose.setContent { MaterialTheme {
                StRuntimeCard(WorkspaceEntity(root, "Synthetic workspace", root, "READY", 0L, 0L))
            } }
            compose.onNodeWithText("等待 AI 登记实例").assertIsDisplayed()
            compose.onNodeWithText("启动已配置实例").assertIsNotEnabled()
            compose.onNode(isToggleable()).performClick()
            compose.waitUntil(5000) { host.enabled(root) }
            compose.onNodeWithText("开关已开启。回原聊天另发一条新消息", substring = true).assertIsDisplayed()
            compose.onNodeWithText("启动已配置实例").assertIsNotEnabled()
            assertNull(host.saved(root).spec)
            assertFalse(host.current(root).active)
        } finally {
            host.setEnabled(root, false)
            File(context.noBackupFilesDir, "st-host/$root.json").delete()
        }
    }
}
