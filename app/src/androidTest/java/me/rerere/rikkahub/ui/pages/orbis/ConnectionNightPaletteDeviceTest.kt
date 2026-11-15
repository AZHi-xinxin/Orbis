package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.orbis.integration.ConnectionDraft
import me.rerere.rikkahub.data.orbis.integration.ConnectionProposal
import me.rerere.rikkahub.data.orbis.theme.ThemeColors
import me.rerere.rikkahub.data.orbis.theme.ThemePackage
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ConnectionNightPaletteDeviceTest {
    @get:Rule val compose = createShellComposeRule()

    @Test fun paleChatMaterialCannotWashOutConnectionSheetAndLiveToggleKeepsDraft() {
        var dark by mutableStateOf(true)
        val proposal = ConnectionProposal("synthetic-owner", ConnectionDraft("mcp_http", "Orbis 测试连接", "https://example.invalid/mcp"))
        compose.setContent {
            CompositionLocalProvider(LocalDarkMode provides dark) { MaterialTheme { OrbisVisualTheme {
                // Reproduce a fixed pale chat surface with text following the human night setting.
                val chat = ChatTheme(ThemePackage(name = "合成浅色聊天素材", colors = ThemeColors(
                    background = "#EAF2FF", surface = "#F4F7FF", text = if(dark) "#F4EBDD" else "#263342")), null)
                CompositionLocalProvider(LocalChatTheme provides chat) {
                    ConnectionProposalSheet(listOf(proposal), proposal, false, null, {}, {}, false,
                        { _, _, _, _ -> }, { _, _ -> }, {})
                }
            } } }
        }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-unsaved")
        for (night in listOf(true, false, true)) {
            compose.runOnIdle { dark = night }
            val sheet = compose.onNodeWithTag("connection-proposals")
            sheet.assertIsDisplayed()
            val image = sheet.captureToImage()
            val pixels = image.toPixelMap()
            val surface = pixels[2, 2]
            assertEquals("Actual sheet background must follow night mode", night, surface.luminance() < .15f)
            compose.onNodeWithTag("connection-key").assertTextContains("synthetic-unsaved")
            val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "connection-${if(night) "night" else "day"}.png")
            output.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun invitationGuideOpensActualGroupCardAndReturnsWithoutLeavingLogin() {
        compose.setContent { CompositionLocalProvider(LocalDarkMode provides true) {
            OrbisInterfaceTheme { Column { OrbisInvitationGuide() } }
        } }
        compose.onNodeWithTag("invitation-open-group").performClick()
        compose.onNodeWithText("ST / Orbis · QQ 交流群").assertIsDisplayed()
        compose.onNodeWithText("复制群号").assertIsDisplayed()
        compose.onNodeWithText("返回申请指引").performClick()
        compose.onNodeWithTag("orbis-invitation-guide").assertIsDisplayed()
    }
}
