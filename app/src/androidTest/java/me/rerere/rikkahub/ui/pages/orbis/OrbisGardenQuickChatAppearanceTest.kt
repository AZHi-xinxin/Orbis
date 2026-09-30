package me.rerere.rikkahub.ui.pages.orbis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OrbisEventMetadata
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.OrbisAppearance
import me.rerere.rikkahub.data.model.OrbisBubbleStyle
import me.rerere.rikkahub.data.model.OrbisChatFlowSettings
import me.rerere.rikkahub.ui.components.message.MessagePartsBlock
import me.rerere.rikkahub.ui.components.message.OrbisChatMessageLayout
import me.rerere.rikkahub.ui.components.message.rememberChatMessageTextStyle
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic text + in-memory settings only: no Koin, model, ChatService or preference writes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class OrbisGardenQuickChatAppearanceTest {
    @get:Rule val compose = createShellComposeRule()
    private var display by mutableStateOf(DisplaySetting(
        showUserAvatar = false, showModelIcon = false, showDateTimeInMessage = false,
        orbisAppearance = OrbisAppearance(floatingStars = false,
            chatFlow = OrbisChatFlowSettings(enabled = false)),
    ))
    private var dark by mutableStateOf(true)
    private var deepSeek by mutableStateOf(false)
    private var role by mutableStateOf(MessageRole.ASSISTANT)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, 1f),
                LocalSettings provides Settings(displaySetting = display),
                LocalOrbisDeepSeekStyle provides deepSeek,
                LocalNavController provides Navigator(mutableListOf()),
            ) {
                MaterialTheme {
                    OrbisVisualTheme(darkTheme = dark) { Column(Modifier.requiredWidth(320.dp)) { content() } }
                }
            }
        }
    }

    private fun textStyle(text: String): TextStyle {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(results) }
        return results.single().layoutInput.style
    }

    @Composable private fun MainPresentation(text: String) {
        val message = UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))
        OrbisChatMessageLayout(message, null, null, false, false) {
            ProvideTextStyle(rememberChatMessageTextStyle()) {
                MessagePartsBlock(null, role, null, message.parts, emptyList(), false)
            }
        }
    }

    @Test fun drawerAndMainShareDarkCustomTextFontAndLiveSettings() {
        display = display.copy(chatFontFamily = ChatFontFamily.SERIF, fontSizeRatio = 1.5f,
            orbisAppearance = display.orbisAppearance.copy(chatTextColor = 0xFFFFE8BD.toInt()))
        show {
            GardenQuickChatMessage(UIMessage.assistant("Drawer text"), null, null, false)
            MainPresentation("Main text")
        }
        fun verify(color: Color, family: FontFamily, size: Float) {
            val drawer = textStyle("Drawer text")
            val main = textStyle("Main text")
            assertEquals(color, drawer.color)
            assertEquals(family, drawer.fontFamily)
            assertEquals(size.sp, drawer.fontSize)
            assertEquals(main.fontSize, drawer.fontSize)
            assertEquals(main.lineHeight, drawer.lineHeight)
            assertEquals(main.fontFamily, drawer.fontFamily)
            assertEquals(main.color, drawer.color)
        }
        verify(Color(0xFFFFE8BD), FontFamily.Serif, 21f)
        compose.runOnIdle {
            display = display.copy(chatFontFamily = ChatFontFamily.MONOSPACE, fontSizeRatio = 1f,
                orbisAppearance = display.orbisAppearance.copy(chatTextColor = 0xFFE4CAF8.toInt()))
        }
        verify(Color(0xFFE4CAF8), FontFamily.Monospace, 14f)
    }

    @Test fun drawerFollowsActiveStyleProfileAndThemeColorReset() {
        display = display.copy(
            orbisAppearance = display.orbisAppearance.copy(chatTextColor = 0xFFFFE8BD.toInt()),
            deepSeekAppearance = OrbisAppearance(chatTextColor = 0xFFB0DFFF.toInt(), floatingStars = false,
                chatFlow = OrbisChatFlowSettings(enabled = false)),
        )
        show { GardenQuickChatMessage(UIMessage.assistant("Profile prose"), null, null, false) }
        assertEquals(Color(0xFFFFE8BD), textStyle("Profile prose").color)
        compose.runOnIdle { deepSeek = true }
        assertEquals(Color(0xFFB0DFFF), textStyle("Profile prose").color)
        compose.runOnIdle { display = display.copy(deepSeekAppearance = display.deepSeekAppearance.copy(chatTextColor = null)) }
        assertEquals(OrbisPalette.DeepSeekDark.ink, textStyle("Profile prose").color)
        compose.runOnIdle { dark = false }
        assertEquals(OrbisPalette.DeepSeekLight.ink, textStyle("Profile prose").color)
    }

    @Test fun drawerAndMainHaveIdenticalRoleOpacityAndBubbleDecorationPixels() {
        display = display.copy(orbisAppearance = display.orbisAppearance.copy(
            userBubbleOpacity = 0f, assistantBubbleOpacity = .65f))
        show {
            Box(Modifier.fillMaxWidth().height(90.dp).background(Color.Black).testTag("drawer-pixels")) {
                GardenQuickChatMessage(UIMessage(role = role, parts = listOf(UIMessagePart.Text("Same prose"))), null, null, false)
            }
            Box(Modifier.fillMaxWidth().height(90.dp).background(Color.Black).testTag("main-pixels")) {
                MainPresentation("Same prose")
            }
        }
        for (style in OrbisBubbleStyle.entries) for (messageRole in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
            compose.runOnIdle { role = messageRole; display = display.copy(orbisAppearance = display.orbisAppearance.copy(bubbleStyle = style)) }
            val drawer = compose.onNodeWithTag("drawer-pixels").captureToImage().toPixelMap()
            val main = compose.onNodeWithTag("main-pixels").captureToImage().toPixelMap()
            assertEquals(main.width, drawer.width)
            assertEquals(main.height, drawer.height)
            var different = 0
            for (y in 0 until main.height) for (x in 0 until main.width) {
                val a = drawer[x, y]; val b = main[x, y]
                if (kotlin.math.abs(a.red - b.red) > .025f || kotlin.math.abs(a.green - b.green) > .025f ||
                    kotlin.math.abs(a.blue - b.blue) > .025f) different++
            }
            assertTrue("$style/$messageRole main/drawer pixels differed: $different", different <= 4)
        }
    }

    @Test fun drawerParagraphBubblesFollowMainFlowSettingWithoutChangingText() {
        val message = UIMessage.assistant("First paragraph\n\nSecond paragraph")
        show { GardenQuickChatMessage(message, null, null, false) }
        compose.onNodeWithTag("orbis-reply-bubble-0").assertDoesNotExist()
        compose.runOnIdle { display = display.copy(orbisAppearance = display.orbisAppearance.copy(chatFlow = OrbisChatFlowSettings(enabled = true))) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("orbis-reply-bubble-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("First paragraph", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Second paragraph", useUnmergedTree = true).assertExists()
        compose.runOnIdle { display = display.copy(orbisAppearance = display.orbisAppearance.copy(chatFlow = OrbisChatFlowSettings(enabled = false))) }
        compose.onNodeWithTag("orbis-reply-bubble-0").assertDoesNotExist()
        assertEquals("First paragraph\n\nSecond paragraph", message.toText())
    }

    @Test fun drawerComposerFollowsMainOpacityAndKeepsOpaqueReadableInput() {
        var draft by mutableStateOf("")
        display = display.copy(orbisAppearance = display.orbisAppearance.copy(composerOpacity = .15f))
        show {
            Box(Modifier.background(Color.Black).testTag("composer-pixels")) {
                GardenQuickChatInput(draft, { draft = it }, enabled = true)
            }
        }
        fun background() = compose.onNodeWithTag("composer-pixels").captureToImage().toPixelMap().let { it[it.width / 2, 5] }
        val faint = background()
        compose.onNodeWithTag("garden-quick-chat-input").performTextInput("Draft remains mine")
        assertEquals(OrbisPalette.Dark.ink, textStyle("Draft remains mine").color)
        assertEquals(14.sp, textStyle("Draft remains mine").fontSize)
        compose.runOnIdle { display = display.copy(orbisAppearance = display.orbisAppearance.copy(composerOpacity = 1f)) }
        val opaque = background()
        assertTrue(opaque.red > faint.red && opaque.blue > faint.blue)
        assertEquals("Draft remains mine", draft)
        assertEquals(OrbisPalette.Dark.ink, textStyle("Draft remains mine").color)
    }

    @Test fun sentinelUsesMainCardAndOnlyPersistsPresentationFlags() {
        val original = UIMessage.user("Synthetic observation\nexact original text").copy(
            orbisEvent = OrbisEventMetadata("fixture", "lc_sentinel", "event", 1_000L))
        var message by mutableStateOf(original)
        var saves = 0
        show { GardenQuickChatMessage(message, null, null, false,
            onEventPresentation = { metadata -> saves++; message = message.copy(orbisEvent = metadata) }) }
        compose.onNodeWithTag("orbis-event-card").assertExists()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertExists()
        assertEquals(original.toText(), message.toText())
        assertEquals(original.parts, message.parts)
        assertEquals(original.orbisEvent!!.copy(read = true, collapsed = false), message.orbisEvent)
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        assertEquals(2, saves)
        assertEquals(original.toText(), message.toText())
    }

    @Test fun sentinelFailedSaveStaysFoldedAndPlainLookalikeRemainsOrdinaryText() {
        val original = UIMessage.user("Private fixture observation").copy(
            orbisEvent = OrbisEventMetadata("fixture", "lc_sentinel", "event", 1_000L))
        show {
            GardenQuickChatMessage(original, null, null, false,
                onEventPresentation = { error("synthetic write failure") })
            GardenQuickChatMessage(UIMessage.user("lc_sentinel receivedAt ordinary quoted text"), null, null, false)
        }
        compose.onNodeWithText("lc_sentinel receivedAt ordinary quoted text", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("orbis-event-collapse").performClick()
        compose.onNodeWithTag("orbis-event-original").assertDoesNotExist()
        assertTrue(original.orbisEvent!!.collapsed)
        compose.onNodeWithText("未更新显示状态；正在回复时请等回复结束后再试。若有恢复提示，请先处理恢复记录。", useUnmergedTree = true).assertExists()
    }
}
