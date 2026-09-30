package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.appearanceForStyle
import me.rerere.rikkahub.data.model.deepSeekDefaultAppearance
import me.rerere.rikkahub.data.model.deepSeekWelcomeText
import me.rerere.rikkahub.data.model.withAppearanceForStyle
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic component state only, using the plain-Application shell; never opens preferences or models. */
@RunWith(AndroidJUnit4::class)
class OrbisPersonalAppearanceControlsTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun nicknameRequiresSaveAndCancelDoesNotChangeStoredWelcomeOrAppearance() {
        val original = DisplaySetting(userNickname = "Original", userAvatar = me.rerere.rikkahub.data.model.Avatar.Emoji("☁"),
            orbisAppearance = OrbisAppearance(userBubbleOpacity = .3f, assistantBubbleOpacity = .8f))
        val state = mutableStateOf(original)
        var saves = 0
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) { Column {
                OrbisNicknameSetting(state.value.userNickname) { name ->
                    saves++; state.value = state.value.copy(userNickname = name)
                }
                Text(deepSeekWelcomeText(state.value.userNickname))
            } } }
        }
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("Discarded")
        compose.runOnIdle { assertEquals(original, state.value); assertEquals(0, saves) }
        compose.onNodeWithText("Original，欢迎回家").assertExists()
        compose.onNodeWithTag("orbis-personal-nickname-cancel").performClick()
        compose.onNodeWithTag("orbis-personal-nickname-save").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(original, state.value); assertEquals(0, saves) }
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("  Saved name  ")
        compose.onNodeWithTag("orbis-personal-nickname-save").performClick()
        compose.onNodeWithText("Saved name，欢迎回家").assertExists()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(original.copy(userNickname = "Saved name"), state.value)
        }
    }

    @Test fun clearingNicknameExplicitlyRestoresBlankWelcomeFallback() {
        val nickname = mutableStateOf("Original")
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) { Column {
                OrbisNicknameSetting(nickname.value) { nickname.value = it }
                Text(deepSeekWelcomeText(nickname.value))
            } } }
        }
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("   ")
        compose.onNodeWithText("Original，欢迎回家").assertExists()
        compose.onNodeWithTag("orbis-personal-nickname-save").performClick()
        compose.onNodeWithText("___，欢迎回家").assertExists()
        compose.runOnIdle { assertEquals("", nickname.value) }
    }

    @Test fun savingWhitespaceOnlyEditsLeavesACleanDraftEvenWhenStoredValueDoesNotChange() {
        var saves = 0
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                OrbisNicknameSetting("Original") { assertEquals("Original", it); saves++ }
            } }
        }
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("  Original  ")
        compose.onNodeWithTag("orbis-personal-nickname-save").performClick()
        compose.onNodeWithTag("orbis-personal-nickname-save").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-personal-nickname-cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun oversizedNicknamePasteIsRejectedWithoutTruncationOrSaving() {
        val saved = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) { OrbisNicknameSetting("Original", saved::add) } }
        }
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("x".repeat(100_000))
        compose.onNodeWithTag("orbis-personal-nickname").assertTextContains("Original")
        compose.onNodeWithText("昵称最多 80 字符，本次超长输入未采用", substring = true).assertExists()
        compose.onNodeWithTag("orbis-personal-nickname-save").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("orbis-personal-nickname-cancel").performClick()
        compose.onNodeWithTag("orbis-personal-nickname").performTextReplacement("n".repeat(80))
        compose.onNodeWithTag("orbis-personal-nickname-save").performClick()
        compose.runOnIdle { assertEquals(listOf("n".repeat(80)), saved) }
    }

    @Test fun eachSliderChangesOnlyItsRoleAndThemeWithoutTouchingEventOrComposer() {
        val original = DisplaySetting(userNickname = "Original",
            orbisAppearance = OrbisAppearance(bubbleOpacity = .7f, composerOpacity = .6f, eventOpacity = .19f),
            deepSeekAppearance = deepSeekDefaultAppearance().copy(bubbleOpacity = .9f, eventOpacity = .21f))
        val display = mutableStateOf(original)
        val deepSeek = mutableStateOf(false)
        compose.setContent {
            MaterialTheme { OrbisVisualTheme(darkTheme = false) {
                OrbisBubbleOpacityControls(display.value.appearanceForStyle(deepSeek.value)) { transform ->
                    display.value = display.value.withAppearanceForStyle(deepSeek.value, transform)
                }
            } }
        }
        setOpacity("orbis-user-opacity", .25f)
        compose.runOnIdle {
            assertEquals(original.copy(orbisAppearance = original.orbisAppearance.copy(userBubbleOpacity = .25f)), display.value)
        }
        setOpacity("orbis-assistant-opacity", .8f)
        compose.runOnIdle {
            assertEquals(.25f, display.value.orbisAppearance.bubbleOpacityForRole(true), .001f)
            assertEquals(.8f, display.value.orbisAppearance.bubbleOpacityForRole(false), .001f)
            deepSeek.value = true
        }
        setOpacity("orbis-user-opacity", .4f)
        compose.runOnIdle {
            assertEquals(.4f, display.value.deepSeekAppearance.bubbleOpacityForRole(true), .001f)
            assertEquals(.9f, display.value.deepSeekAppearance.bubbleOpacityForRole(false), .001f)
            assertEquals(.25f, display.value.orbisAppearance.bubbleOpacityForRole(true), .001f)
            assertEquals(.8f, display.value.orbisAppearance.bubbleOpacityForRole(false), .001f)
            assertEquals(.19f, display.value.orbisAppearance.eventOpacity, 0f)
            assertEquals(.21f, display.value.deepSeekAppearance.eventOpacity, 0f)
            assertEquals(.6f, display.value.orbisAppearance.composerOpacity, 0f)
            assertEquals("Original", display.value.userNickname)
        }
    }

    private fun setOpacity(tag: String, value: Float) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(value))
        }
    }
}
