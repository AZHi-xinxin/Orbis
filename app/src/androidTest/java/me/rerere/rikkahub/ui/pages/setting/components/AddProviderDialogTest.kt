package me.rerere.rikkahub.ui.pages.setting.components

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import me.rerere.rikkahub.testutil.createShellComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.sonner.rememberToasterState
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.context.LocalToaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Injected drafts only: no Koin, settings writes, API keys, model requests or real app startup. */
@RunWith(AndroidJUnit4::class)
class AddProviderDialogTest {
    private val compose = createShellComposeRule()
    private val isolatedApplication = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(isolatedApplication).around(compose)

    @Test fun editingThenCancelLeavesSavedConfigurationUntouched() {
        val original = ProviderSetting.Claude(name = "Synthetic connection", baseUrl = "https://synthetic.invalid/v1")
        val draft = mutableStateOf<ProviderSetting>(original)
        var saved: ProviderSetting? = null
        var cancelled = false
        compose.setContent {
            SyntheticProviderTheme {
                AddProviderDialog(draft.value, false, { draft.value = it },
                    onDismiss = { cancelled = true }, onSave = { saved = draft.value })
            }
        }
        val nameLabel = compose.activity.getString(R.string.setting_provider_page_name)
        compose.onNodeWithText(nameLabel).performScrollTo().performTextReplacement("Edited draft")
        compose.onNodeWithTag("cancel-model-connection").performClick()
        compose.runOnIdle {
            assertTrue(cancelled)
            assertNull(saved)
            assertEquals("Synthetic connection", original.name)
        }
    }

    @Test fun saveIsAccessibleAndPreservesTheChosenProtocolAndAddress() {
        val original = ProviderSetting.Claude(name = "Synthetic connection", baseUrl = "https://synthetic.invalid/v1")
        val draft = mutableStateOf<ProviderSetting>(original)
        var saved: ProviderSetting? = null
        compose.setContent {
            SyntheticProviderTheme {
                AddProviderDialog(draft.value, false, { draft.value = it },
                    onDismiss = {}, onSave = { saved = draft.value })
            }
        }
        compose.runOnIdle { assertNull(saved) }
        compose.onNodeWithTag("save-model-connection").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(original, saved)
            assertEquals("", (saved as ProviderSetting.Claude).apiKey)
        }
    }

    @Test fun savingPreventsDuplicateSubmissionAndCancellation() {
        compose.setContent {
            SyntheticProviderTheme {
                AddProviderDialog(ProviderSetting.Claude(), true, {}, {}, {})
            }
        }
        compose.onNodeWithTag("save-model-connection").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-model-connection").assertIsNotEnabled()
    }

    @Test fun longCustomFormScrollsByTouchInsideShortDialogAndKeepsActionsVisible() {
        val draft = mutableStateOf<ProviderSetting>(ProviderSetting.Google(
            name = "Synthetic Vertex", vertexAI = true, useServiceAccount = true, projectId = "test"))
        compose.setContent {
            SyntheticProviderTheme {
                AddProviderDialog(draft.value, false, { draft.value = it }, {}, {}, modifier = Modifier.height(340.dp))
            }
        }
        val form = compose.onNodeWithTag("model-connection-form")
        val range = form.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue(range.maxValue() > 0f); assertEquals(0f, range.value(), 0f) }
        form.performTouchInput { swipeUp(durationMillis = 300) }
        compose.runOnIdle { assertTrue("A real swipe must move the form", range.value() > 0f) }
        compose.onNodeWithTag("save-model-connection").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("cancel-model-connection").assertIsDisplayed()
    }

    @Test fun protocolChoicesRemainAvailableForCustomConnections() {
        val draft = mutableStateOf<ProviderSetting>(ProviderSetting.OpenAI(
            name = "Synthetic gateway", baseUrl = "https://synthetic.invalid/v1"))
        compose.setContent {
            SyntheticProviderTheme { AddProviderDialog(draft.value, false, { draft.value = it }, {}, {}) }
        }
        compose.onNodeWithTag("model-connection-protocol-Claude").performClick()
        compose.runOnIdle { assertTrue(draft.value is ProviderSetting.Claude); assertEquals("Synthetic gateway", draft.value.name) }
        compose.onNodeWithTag("model-connection-protocol-Google").performClick()
        compose.runOnIdle { assertTrue(draft.value is ProviderSetting.Google) }
        compose.onNodeWithTag("model-connection-protocol-OpenAI").performClick()
        compose.runOnIdle { assertTrue(draft.value is ProviderSetting.OpenAI) }
    }

    @Test fun emptyCustomAddressCannotBeSaved() {
        compose.setContent {
            SyntheticProviderTheme { AddProviderDialog(ProviderSetting.OpenAI(name = "Custom", baseUrl = ""), false, {}, {}, {}) }
        }
        compose.onNodeWithTag("save-model-connection").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-model-connection").assertIsEnabled()
    }
}

/** UI-local state only; never create RouteActivity, Koin, or real settings. */
@Composable
private fun SyntheticProviderTheme(content: @Composable () -> Unit) {
    val toaster = rememberToasterState()
    MaterialTheme {
        CompositionLocalProvider(LocalToaster provides toaster, content = content)
    }
}
