package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.pages.orbis.community.*
import me.rerere.rikkahub.ui.pages.orbis.market.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommunityMarketNightThemeDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    @Test fun bothDialogPalettesFollowLiveNightSettingWithReadableText() {
        var dark by mutableStateOf(false)
        var community=Color.Unspecified to Color.Unspecified
        var market=Color.Unspecified to Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalDarkMode provides dark) { Column {
                CommunityTheme {
                    community=CommunityPaper to CommunityInk
                    assertEquals(CommunityPaper,MaterialTheme.colorScheme.surface)
                    Text("Community")
                }
                MarketLetterTheme {
                    market=LetterPaper to LetterInk
                    assertEquals(LetterPaper,MaterialTheme.colorScheme.surface)
                    Text("Market")
                }
            } }
        }
        for(value in listOf(false,true,false)) {
            compose.runOnIdle { dark=value }
            compose.runOnIdle {
                for((background,ink) in listOf(community,market)) {
                    assertEquals(value,background.luminance()<.15f)
                    val contrast=(maxOf(background.luminance(),ink.luminance())+.05f)/(minOf(background.luminance(),ink.luminance())+.05f)
                    assertTrue("Readable theme contrast $contrast",contrast>=4.5f)
                }
            }
        }
    }
}
