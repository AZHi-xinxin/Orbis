package me.rerere.rikkahub.ui.pages.setting

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import me.rerere.rikkahub.testutil.createShellComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real touch drags in a 300dp viewport; synthetic content only, no Koin or provider requests. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class OrbisSettingsScrollTest {
    private val compose = createShellComposeRule()
    private val isolation = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(isolation).around(compose)

    @Test fun fixedHeaderDoesNotConsumeColumnDragOnShortScreen() {
        lateinit var scroll: ScrollState
        lateinit var behavior: TopAppBarScrollBehavior
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Box(Modifier.size(width = 320.dp, height = 300.dp)) {
                    OrbisSettingsScaffold(modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
                        topBar = { OrbisSettingsTopBar(title = { Text("Settings fixture") }, scrollBehavior = behavior) }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).testTag("short-settings-scroll")) {
                            repeat(30) { Text("Synthetic field $it", Modifier.fillMaxWidth().height(56.dp)) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, scroll.value); assertTrue(scroll.maxValue > 0) }
        compose.onNodeWithTag("short-settings-scroll").performTouchInput { swipeUp(durationMillis = 300) }
        compose.runOnIdle {
            assertTrue("The child must receive the actual swipe", scroll.value > 0)
            assertEquals(0f, behavior.state.heightOffsetLimit, 0f)
            assertEquals(0f, behavior.state.heightOffset, 0f)
        }
    }

    @Test fun fixedHeaderDoesNotConsumeLazyListDragOnShortScreen() {
        lateinit var list: LazyListState
        compose.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
                Box(Modifier.size(width = 320.dp, height = 300.dp)) {
                    OrbisSettingsScaffold(modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
                        topBar = { OrbisSettingsTopBar(title = { Text("Connections fixture") }, scrollBehavior = behavior) }) { padding ->
                        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("short-connections-list"), state = list) {
                            items(30) { Text("Saved synthetic connection $it", Modifier.fillMaxWidth().height(64.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("short-connections-list").performTouchInput { swipeUp(durationMillis = 300) }
        compose.runOnIdle { assertTrue(list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) }
    }
}
