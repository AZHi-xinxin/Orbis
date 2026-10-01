package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout-only: no settings store, selector opening, network, or saved model changes. */
@RunWith(AndroidJUnit4::class)
class ModelSettingLayoutUiTest {
    @get:Rule val compose = createShellComposeRule()
    private val title = mutableStateOf("快速模型")
    private val scale = mutableStateOf(1f)
    private val model = Model(displayName = "Synthetic_gateway_very_long_model_name_".repeat(8), modelId = "synthetic-model-id")
    private var selections = 0

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale.value)) {
                MaterialTheme { Column(Modifier.requiredWidth(280.dp)) {
                    ModelSettingItem(title.value, "合成描述", model.id,
                        listOf(ProviderSetting.OpenAI(models = listOf(model), baseUrl = "https://example.invalid", apiKey = "")),
                        onSelect = { selections++ })
                } }
            }
        }
    }

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    @Test fun longSelectedNameCannotStealTitleWidthAndValueIsBoundedBelowTitle() {
        show()
        for (label in listOf("快速模型", "翻译模型", "语音通话归档备用模型")) {
            compose.runOnIdle { title.value = label }
            val headline = layout("model-setting-title")
            val value = layout("model-setting-value")
            assertEquals(label, headline.layoutInput.text.text)
            assertFalse(headline.hasVisualOverflow)
            assertTrue(headline.lineCount <= 2)
            assertTrue(value.lineCount <= 2)
            assertTrue(value.isLineEllipsized(value.lineCount - 1))
            val headBounds = compose.onNodeWithTag("model-setting-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val valueBounds = compose.onNodeWithTag("model-setting-value", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(valueBounds.top >= headBounds.bottom)
            assertTrue(headBounds.width >= 180f)
        }
        compose.runOnIdle { scale.value = 1.6f; title.value = "翻译模型" }
        assertFalse(layout("model-setting-title").hasVisualOverflow)
        assertTrue(layout("model-setting-title").lineCount <= 2)
        compose.runOnIdle { assertEquals(0, selections) }
    }

    @Test fun longPressShowsUntruncatedFullNameWithoutSelectingOrSaving() {
        show()
        compose.onNodeWithTag("model-setting-value").performTouchInput { longClick() }
        compose.onNodeWithTag("model-setting-full-name").assertIsDisplayed()
        assertEquals("${model.displayName}\n\n模型 ID：${model.modelId}", layout("model-setting-full-name").layoutInput.text.text)
        assertFalse(layout("model-setting-full-name").hasVisualOverflow)
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("model-setting-full-name").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, selections) }
    }
}
