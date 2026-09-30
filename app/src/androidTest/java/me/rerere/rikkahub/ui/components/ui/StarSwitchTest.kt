package me.rerere.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lover.connect.ui.components.StarSwitch
import java.util.concurrent.atomic.AtomicInteger
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI fixtures: isolated runner + synthetic activity, no preferences, services or permissions. */
@RunWith(AndroidJUnit4::class)
class StarSwitchTest {
    @get:Rule val compose = createShellComposeRule()
    private val switchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test fun enabledSwitchExposesStateAndTogglesExactlyOncePerAction() {
        val changes = AtomicInteger()
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            MaterialTheme {
                StarSwitch(checked, { checked = it; changes.incrementAndGet() }, Modifier.testTag("star"))
            }
        }
        compose.onNodeWithTag("star").assert(switchRole).assertIsEnabled().assertIsOff().assertHasClickAction()
            .performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, changes.get()) }
        compose.onNodeWithTag("star").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, changes.get()) }
    }

    @Test fun disabledSwitchKeepsItsCheckedStateWithoutHandlingTouch() {
        val changes = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                StarSwitch(true, { changes.incrementAndGet() }, Modifier.testTag("disabled"), enabled = false)
            }
        }
        compose.onNodeWithTag("disabled").assert(switchRole).assertIsOn().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, changes.get()) }
    }

    @Test fun indicatorWithoutCallbackHasStateButNoIndependentClickAction() {
        compose.setContent {
            MaterialTheme { StarSwitch(false, null, Modifier.testTag("indicator")) }
        }
        compose.onNodeWithTag("indicator").assert(switchRole).assertIsOff().assertHasNoClickAction()
    }

    @Test fun smallestVisualStillHas48DpTarget() {
        compose.setContent {
            MaterialTheme { StarSwitch(false, {}, Modifier.testTag("small"), iconSize = 20.dp) }
        }
        compose.onNodeWithTag("small").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun legacySmallSwitchDelegatesStateSemanticsAndMinimumTarget() {
        compose.setContent {
            MaterialTheme {
                Switch(false, {}, Modifier.testTag("legacy"), size = SwitchSize.Small, enabled = false)
            }
        }
        compose.onNodeWithTag("legacy").assert(switchRole).assertIsOff().assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun parentOwnedGridToggleDoesNotAcquireASecondClickHandler() {
        val changes = AtomicInteger()
        compose.setContent {
            var checked by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.testTag("tile").toggleable(checked, role = Role.Switch,
                    onValueChange = { checked = it; changes.incrementAndGet() })) {
                    Text("Synthetic preference")
                    StarSwitch(checked, null, Modifier.testTag("tile-star"))
                }
            }
        }
        compose.onNodeWithTag("tile-star", useUnmergedTree = true).assertHasNoClickAction()
        compose.onNodeWithTag("tile").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertEquals(1, changes.get()) }
    }
}
