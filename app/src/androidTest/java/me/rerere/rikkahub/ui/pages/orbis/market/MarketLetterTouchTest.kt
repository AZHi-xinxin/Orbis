package me.rerere.rikkahub.ui.pages.orbis.market

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use physical pointer input, rather than invoking the OnClick semantics callback directly. */
@RunWith(AndroidJUnit4::class)
class MarketLetterTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun touchActions(lines: Int, scale: Float = 1f) {
        check(InstrumentationRegistry.getInstrumentation() is IsolatedGenerationLoopRunner)
        var reports = 0
        var closes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                MarketLetterTheme {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        LetterHeading("detail", {}, {})
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                            item { LetterSheet(flowers = true) {
                                Text("合成测试信纸")
                                Text(List(lines) { "第${it + 1}行：这是测试设定，请保留原文。" }.joinToString("\n"), lineHeight = 26.sp)
                                TextButton({ reports++ }, Modifier.testTag("report")) { Text("举报内容") }
                                TextButton({ closes++ }, Modifier.testTag("close")) { Text("收起信纸") }
                            } }
                        }
                        Row { OutlinedButton({}) { Text("收藏") }; Button({}) { Text("发送给 AI") } }
                    }
                }
            }
        }
        compose.onNodeWithTag("report").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals("Report pointer must reach its callback", 1, reports) }
        compose.onNodeWithTag("close").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals("Close pointer must reach its callback", 1, closes) }
    }

    @Test fun shortLetterActionsReceiveTouch() = touchActions(3)
    @Test fun longLetterActionsReceiveTouch() = touchActions(59)
    @Test fun largeFontLongLetterActionsReceiveTouch() = touchActions(59, 1.5f)
}
