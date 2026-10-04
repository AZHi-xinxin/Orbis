package me.rerere.rikkahub.ui.components.message

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.contextpruning.ContextPruningDisplayProjection
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ContextPruningNoticeTest {
    @get:Rule val compose = createShellComposeRule()
    @Before fun isolated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation is IsolatedGenerationLoopRunner)
        check(instrumentation.targetContext.applicationContext.javaClass == Application::class.java)
    }
    @Test fun oneTombstoneAndRestoreOnlyReturnsCommittedBatchId() {
        val calls = mutableListOf<String>()
        val state = ContextPruningDisplayProjection(hiddenToolIndexes = setOf(1, 3, 5), activeBatchIds = setOf("fixture-batch"))
        compose.setContent { MaterialTheme { Column { ContextPruningNotice(state) { calls.add(it) } } } }
        compose.onNodeWithText("该条工具调用记录已删除").assertIsDisplayed()
        compose.onNodeWithText("恢复这批记录（不重执行）").performClick()
        assertEquals(listOf("fixture-batch"), calls)
    }
    @Test fun successfulControlHasFixedNoticeWithoutAnotherCleanupOrRestoreAction() {
        compose.setContent { MaterialTheme { ContextPruningNotice(ContextPruningDisplayProjection(controlToolIndexes = setOf(0)), null) } }
        compose.onNodeWithText("旧记录已清理，清理动作不重复展开").assertIsDisplayed()
        compose.onNodeWithText("恢复这批记录（不重执行）").assertDoesNotExist()
    }
    @Test fun emptyProjectionNeverClaimsDeletion() {
        compose.setContent { MaterialTheme { ContextPruningNotice(ContextPruningDisplayProjection(), null) } }
        compose.onNodeWithText("该条工具调用记录已删除").assertDoesNotExist()
        compose.onNodeWithText("旧记录已清理，清理动作不重复展开").assertDoesNotExist()
    }
}
