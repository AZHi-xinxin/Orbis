package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisGardenEntry
import me.rerere.rikkahub.data.orbis.OrbisGardenKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Injected callbacks only: no Koin, DB, file picker, model or network. */
@RunWith(AndroidJUnit4::class)
class OrbisGardenEditorDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolated = object : ExternalResource() { override fun before() {
        val i = InstrumentationRegistry.getInstrumentation()
        check(i is IsolatedGenerationLoopRunner)
        assertEquals(Application::class.java, i.targetContext.applicationContext.javaClass)
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolated).around(compose)
    @Test fun blankRecordCannotSaveAndValidTextUsesOnlyExplicitCallback() {
        var saved = 0
        compose.setContent { MaterialTheme {
            OrbisGardenEditor(null, OrbisGardenKind.DIARY, "我", {}, { title, body, author ->
                assertEquals("合成标题", title); assertEquals("合成正文", body); assertEquals("我", author); saved++
            }, { error("no delete") })
        } }
        compose.onNodeWithTag("garden-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("garden-editor-title").performTextReplacement("合成标题")
        compose.onNodeWithTag("garden-editor-body").performScrollTo().performTextReplacement("合成正文")
        compose.runOnIdle { assertEquals(0, saved) }
        compose.onNodeWithTag("garden-editor-save").performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }
    @Test fun failedSaveKeepsTheDraftAndDoesNotClaimSuccess() {
        compose.setContent { MaterialTheme {
            OrbisGardenEditor(null, OrbisGardenKind.LETTER, "我", {}, { _, _, _ -> error("synthetic") }, {})
        } }
        compose.onNodeWithTag("garden-editor-title").performTextReplacement("标题")
        compose.onNodeWithTag("garden-editor-body").performScrollTo().performTextReplacement("原文仍在")
        compose.onNodeWithTag("garden-editor-save").performClick()
        compose.onNodeWithTag("garden-editor-body").assertTextContains("原文仍在")
        compose.onNodeWithText("保存未确认成功，草稿保留。可能记录已被更新；取消后重新读取核对，不自动覆盖。").assertExists()
    }
    @Test fun deleteRequiresASecondExplicitConfirmation() {
        var deleted = 0
        val record = OrbisGardenEntry("11111111-1111-1111-1111-111111111111", OrbisGardenKind.ANCHOR, "锚", "原文", "我", 1, 1)
        compose.setContent { MaterialTheme {
            OrbisGardenEditor(record, record.kind, "我", {}, { _, _, _ -> }, { deleted++ })
        } }
        compose.onNodeWithTag("garden-editor-delete").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("保留").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithTag("garden-editor-delete").performScrollTo().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }
}
