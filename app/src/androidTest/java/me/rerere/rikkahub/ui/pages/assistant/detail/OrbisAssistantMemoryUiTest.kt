package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.orbis.OrbisMemoryAtlas
import me.rerere.rikkahub.data.orbis.localMemoryAtlas
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMetadata
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryMode
import me.rerere.rikkahub.data.orbis.memory.OrbisMemoryStats
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisMemoryAtlasCanvas
import me.rerere.rikkahub.ui.pages.orbis.localMemoryAtlasColor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Synthetic metadata only. No real database, Koin, model, ST, content listing or export is opened. */
@RunWith(AndroidJUnit4::class)
class OrbisAssistantMemoryUiTest {
    private val compose = createShellComposeRule()
    private val isolation = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolation).around(compose)

    @Test fun lightModeShowsZeroInjectionAndAnonymousCountsWithoutAnEditableContentSurface() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OrbisAssistantMemorySettingsContent(OrbisMemoryMode.LIGHT, true, OrbisMemoryStats(9, 6, 1, 2, 2),
                onMode = { fail("Read-only render must not change preferences") }, onAutoInject = { fail("No implicit stop") })
        } } }
        compose.onNodeWithText("已有 ST 等外置记忆：本机只保存和按需查询，零自动带入。").assertExists()
        compose.onNodeWithTag("local_memory_counts").performScrollTo().assertTextContains("本机 7 条", substring = true)
        compose.onNodeWithTag("local_memory_pinned_count").assertTextEquals("已置顶 2 条，当前档位不注入")
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        listOf("删除", "编辑", "审批", "查看正文", "共享", "恢复此条").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun modeAndStopSwitchOnlyIssueTheirOwnResourceChangesAndKeepCounts() {
        val mode = mutableStateOf(OrbisMemoryMode.LIGHT)
        val auto = mutableStateOf(true)
        var modeChanges = 0
        var autoChanges = 0
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OrbisAssistantMemorySettingsContent(mode.value, auto.value, OrbisMemoryStats(4, 4, 0, 1, 0),
                onMode = { mode.value = it; modeChanges++ }, onAutoInject = { auto.value = it; autoChanges++ })
        } } }
        compose.onNodeWithTag("local_memory_mode_standard").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OrbisMemoryMode.STANDARD, mode.value); assertEquals(1, modeChanges); assertEquals(0, autoChanges) }
        compose.onNodeWithTag("local_memory_stop_auto").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertFalse(auto.value); assertEquals(1, autoChanges); assertEquals(1, modeChanges) }
        compose.onNodeWithTag("local_memory_counts").performScrollTo().assertTextContains("本机 4 条", substring = true)
        compose.onNodeWithTag("local_memory_pinned_count").assertTextEquals("已置顶 1 条，自动带入已停止")
    }

    @Test fun unavailableCountsAreNotPresentedAsAnEmptyMemoryStore() {
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            OrbisAssistantMemorySettingsContent(OrbisMemoryMode.LIGHT, false, null, onMode = {}, onAutoInject = {})
        } } }
        compose.onNodeWithTag("local_memory_counts").performScrollTo().assertTextEquals("正在读取匿名数量…")
        compose.onNodeWithTag("local_memory_pinned_count").assertDoesNotExist()
    }

    @Test fun sharedCanvasAnnouncesLocalMetadataWithoutPretendingItIsST() {
        val graph = localMemoryAtlas(listOf(OrbisMemoryMetadata("synthetic-id", "pinned", listOf("synthetic-family"),
            1_780_000_000_000, 1_780_000_000_001, 1, false)), 1)
        val camera = OrbisMemoryAtlas.Camera()
        compose.setContent { MaterialTheme {
            OrbisMemoryAtlasCanvas(graph, { camera }, { 0.0 }, null, "静览", false, {}, {}, {}, {}, {},
                Modifier.size(300.dp, 400.dp).testTag("local-atlas"),
                sourceDescription = "本机助手记忆，只读元信息，不含正文或摘要", typeColor = ::localMemoryAtlasColor)
        } }
        val description = compose.onNodeWithTag("local-atlas").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.contains("本机助手记忆"))
        assertFalse(description.contains("ST"))
        assertFalse(description.contains("synthetic-family")) // No hidden per-node content in canvas semantics.
        assertEquals(4, listOf("pinned", "conditional", "static", "paused").map(::localMemoryAtlasColor).distinct().size)
    }
}
