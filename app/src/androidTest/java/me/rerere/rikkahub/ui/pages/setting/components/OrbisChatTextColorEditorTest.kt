package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI and in-memory callbacks; no preferences, model, account, service, file or network. */
@RunWith(AndroidJUnit4::class)
class OrbisChatTextColorEditorTest {
    @get:Rule val compose = createShellComposeRule()
    private val selected = mutableStateOf<Int?>(null)
    private val themeDefault = mutableStateOf(Color(0xFF333333))
    private val writes = mutableListOf<Int?>()

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrbisChatTextColorEditor(selected.value, themeDefault.value, {
                        writes += it
                        selected.value = it
                    }, Modifier.fillMaxWidth())
                }
            }
        }
    }

    private fun progress(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        compose.waitForIdle()
    }

    private fun expandCustom() {
        compose.onNodeWithText("自定义颜色").performScrollTo().performClick()
    }

    @Test
    fun followingThemeAndOpeningCustomControlsDoNotWriteSettings() {
        show()
        compose.onNodeWithText("随主题").assertIsDisplayed()
        compose.onNodeWithTag("chat-text-color-preview-深色背景").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chat-text-color-preview-浅色背景").assertIsDisplayed()
        expandCustom()
        compose.onNodeWithTag("chat-text-color-hex").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Int?>(), writes); assertNull(selected.value) }
    }

    @Test
    fun presetWritesAnOpaqueColorAndResetWritesNull() {
        show()
        compose.onNodeWithTag("chat-text-color-preset-奶油").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0xFFF5EAD2.toInt(), selected.value); assertEquals(1, writes.size) }
        compose.onNodeWithText("恢复默认").performScrollTo().performClick()
        compose.onNodeWithText("随主题").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(0xFFF5EAD2.toInt(), null), writes); assertNull(selected.value) }
    }

    @Test
    fun invalidHexAndAlphaHexCannotBeAppliedOrSubmittedFromKeyboard() {
        selected.value = 0xFF336699.toInt()
        show()
        expandCustom()
        listOf("#12345", "#GG00CC", "#80336699", "336699", "").forEach { invalid ->
            compose.onNodeWithTag("chat-text-color-hex").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("chat-text-color-hex").performImeAction()
            compose.onNodeWithTag("chat-text-color-apply").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(emptyList<Int?>(), writes); assertEquals(0xFF336699.toInt(), selected.value) }
    }

    @Test
    fun validHexIsExplicitlyAppliedAsOpaqueRgb() {
        show()
        expandCustom()
        compose.onNodeWithTag("chat-text-color-hex").performScrollTo().performTextReplacement("#12abef")
        compose.runOnIdle { assertEquals(0, writes.size) }
        compose.onNodeWithTag("chat-text-color-apply").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(0xFF12ABEF.toInt()), writes) }
    }

    @Test
    fun brightnessMeansBlackToWhiteAndNeverTransparency() {
        selected.value = 0x80336699.toInt() // Even incoming legacy alpha is never propagated by the editor.
        show()
        progress("chat-text-color-lightness", 0f)
        compose.runOnIdle { assertEquals(0xFF000000.toInt(), selected.value) }
        progress("chat-text-color-lightness", 1f)
        compose.runOnIdle { assertEquals(0xFFFFFFFF.toInt(), selected.value) }
        progress("chat-text-color-lightness", 0.4f)
        compose.runOnIdle { writes.forEach { assertEquals(255, it!! ushr 24) } }
    }

    @Test
    fun blackAndWhiteEndpointsKeepHueAndSaturationAcrossParentEchoes() {
        selected.value = 0xFF00CC00.toInt()
        show()
        progress("chat-text-color-lightness", 0f)
        progress("chat-text-color-lightness", 0.4f)
        compose.runOnIdle { assertEquals(0xFF00CC00.toInt(), selected.value) }
        progress("chat-text-color-lightness", 1f)
        progress("chat-text-color-lightness", 0.4f)
        compose.runOnIdle { assertEquals(0xFF00CC00.toInt(), selected.value) }
    }

    @Test
    fun saturationCanReturnFromGrayWithoutLosingItsHue() {
        selected.value = 0xFF0000FF.toInt()
        show()
        expandCustom()
        progress("chat-text-color-saturation", 0f)
        compose.runOnIdle { assertEquals(0xFF808080.toInt(), selected.value) }
        progress("chat-text-color-saturation", 1f)
        compose.runOnIdle { assertEquals(0xFF0000FF.toInt(), selected.value) }
        progress("chat-text-color-hue", 120f)
        compose.runOnIdle { assertEquals(0xFF00FF00.toInt(), selected.value) }
    }

    @Test
    fun externalColorAndThemeDefaultChangesSynchronizeWithoutWritingBack() {
        selected.value = 0xFFFF0000.toInt()
        show()
        compose.runOnIdle { selected.value = 0xFF0000FF.toInt() }
        compose.onNodeWithText("#0000FF").assertIsDisplayed()
        compose.runOnIdle { selected.value = null; themeDefault.value = Color(0xFF00FF00) }
        compose.onNodeWithText("随主题").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, writes.size) }
        progress("chat-text-color-lightness", 0.4f)
        compose.runOnIdle { assertEquals(0xFF00CC00.toInt(), selected.value) }
        // A later external value may equal an older local emission; it is not
        // an acknowledgement of the current editor state and must still sync.
        compose.runOnIdle { selected.value = 0xFF0000FF.toInt() }
        compose.onNodeWithText("#0000FF").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { selected.value = 0xFF00CC00.toInt() }
        compose.onNodeWithText("#00CC00").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, writes.size) }
    }

    @Test
    fun savedStateAtBlackCanStillRestoreThePreviousHueWhenBrightened() {
        selected.value = 0xFF0000CC.toInt()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OrbisChatTextColorEditor(selected.value, themeDefault.value, {
                        writes += it
                        selected.value = it
                    })
                }
            }
        }
        progress("chat-text-color-lightness", 0f)
        restoration.emulateSavedInstanceStateRestore()
        progress("chat-text-color-lightness", 0.4f)
        compose.runOnIdle { assertEquals(0xFF0000CC.toInt(), selected.value) }
    }
}
