package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.R
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.theme.PresetThemes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only stateless components in the isolated plain-Application shell; no Koin/user preferences. */
@RunWith(AndroidJUnit4::class)
class OrbisThemeControlsTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun presetGridContainsOrbisDeepSeekAndFourSeasonsWithoutRetiredThemes() {
        val selections = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { PresetThemeButtonGroup(themeId = "orbis", onChangeTheme = { selections.add(it) }) }
        }
        compose.onNodeWithTag("preset-theme-orbis").assertIsDisplayed()
        compose.onNodeWithTag("preset-theme-deepseek").assertIsDisplayed()
        val activeIds = setOf("orbis", "deepseek", "orbis-spring", "orbis-summer", "orbis-autumn", "orbis-winter")
        activeIds.forEach { compose.onNodeWithTag("preset-theme-$it").assertIsDisplayed() }
        PresetThemes.filter { it.id !in activeIds }.forEach {
            compose.onNodeWithTag("preset-theme-${it.id}").assertDoesNotExist()
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(6)
        compose.onNodeWithTag("preset-theme-orbis").performClick()
        compose.onNodeWithTag("preset-theme-deepseek").performClick()
        compose.runOnIdle { assertEquals(listOf("orbis", "deepseek"), selections) }
    }

    @Test fun legacyColorTogglesAreAbsentEvenWhenPreviouslyEnabled() {
        var writes = 0
        var opened = 0
        compose.setContent {
            MaterialTheme {
                ThemePreferencesCard(dynamicColor = true, amoledDarkMode = true,
                    onDynamicColorChange = { writes++ }, onAmoledDarkModeChange = { writes++ },
                    onOpenThemes = { opened++ })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.setting_page_dynamic_color)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.setting_display_page_amoled_dark_mode_title)).assertDoesNotExist()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText(compose.activity.getString(R.string.setting_page_theme_setting)).performClick()
        compose.runOnIdle { assertEquals(0, writes); assertEquals(1, opened) }
    }
}
