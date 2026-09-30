package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.orbis.cloudtools.CloudToolDescriptor
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure fake catalogs and state. No Koin, real app settings, Keystore, tokens, network or model. */
@RunWith(AndroidJUnit4::class)
class OrbisAssistantCloudToolsUiTest {
    @get:Rule val compose = createShellComposeRule()
    private fun tool(name: String, write: Boolean = false) = CloudToolDescriptor(name,
        "合成工具说明", JsonObject(emptyMap()), if (write) "write" else "read", write)

    @Test fun readToolStartsOffAndOnlyExplicitStarTapRequestsSelection() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            OrbisCloudToolGrid("合成 AI", listOf(tool("read_example")), emptySet(), true, false, null, true, emptySet(), {}, {},
                { item, value -> calls += item.name to value })
        } } }
        compose.onNodeWithTag("orbis-cloud-tools-grid").performScrollToNode(hasTestTag("cloud-tool-toggle-read_example"))
        compose.onNodeWithTag("cloud-tool-toggle-read_example").assertIsOff()
        compose.runOnIdle { assertEquals(emptyList<Pair<String, Boolean>>(), calls) }
        compose.onNodeWithTag("cloud-tool-toggle-read_example").performClick()
        compose.runOnIdle { assertEquals(listOf("read_example" to true), calls) }
    }

    @Test fun writeToolRequiresASeparateEnableConfirmationAndDoesNotExecuteIt() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            OrbisCloudToolGrid("合成 AI", listOf(tool("write_example", true)), emptySet(), true, false, null, true, emptySet(), {}, {},
                { item, value -> calls += item.name to value })
        } } }
        compose.onNodeWithTag("orbis-cloud-tools-grid").performScrollToNode(hasTestTag("cloud-tool-toggle-write_example"))
        compose.onNodeWithTag("cloud-tool-toggle-write_example").performClick()
        compose.onNodeWithText("允许此 AI 提出写入请求？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, calls.size) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, calls.size) }
        compose.onNodeWithTag("cloud-tool-toggle-write_example").performClick()
        compose.onNodeWithText("开启工具选用").performClick()
        compose.runOnIdle { assertEquals(listOf("write_example" to true), calls) }
    }

    @Test fun missingAuthorizationDoesNotEnableToolsOrPretendTheServiceWorks() {
        var calls = 0
        var configurationOpened = 0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            OrbisCloudToolGrid("合成 AI", listOf(tool("read_example")), emptySet(), false, false, null, false, emptySet(),
                { configurationOpened++ }, {}, { _, _ -> calls++ })
        } } }
        compose.onNodeWithTag("orbis-cloud-auth-state").assertTextContains("当前不能调用", substring = true)
        compose.onNodeWithText("统一授权设置").performClick()
        compose.onNodeWithTag("orbis-cloud-tools-grid").performScrollToNode(hasTestTag("cloud-tool-toggle-read_example"))
        compose.onNodeWithTag("cloud-tool-toggle-read_example").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, calls); assertEquals(1, configurationOpened) }
    }

    @Test fun shortScreenToolGridCanBeSwipedWithoutInvokingAnyTool() {
        var calls = 0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            Box(Modifier.height(350.dp)) {
                OrbisCloudToolGrid("合成 AI", (1..9).map { tool("read_$it") }, emptySet(), true, false, null, true, emptySet(), {}, {},
                    { _, _ -> calls++ })
            }
        } } }
        repeat(7) { compose.onNodeWithTag("orbis-cloud-tools-grid").performTouchInput { swipeUp() } }
        compose.onNodeWithTag("cloud-tool-toggle-read_9").assertIsDisplayed().assertIsOff()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun savedAuthorizationNeverPrefillsASecretAndBlankSecretCannotBeSaved() {
        compose.setContent { MaterialTheme {
            OrbisCloudToolCredentialForm("https://synthetic.example.ts.net:18910", "", true, true, false, null, null,
                {}, {}, {}, {})
        } }
        compose.onNodeWithTag("cloud-credential-token").performScrollTo()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("cloud-credential-save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun validSyntheticCredentialRequiresExplicitSave() {
        var saves = 0
        compose.setContent { MaterialTheme {
            OrbisCloudToolCredentialForm("https://synthetic.example.ts.net:18910", "a".repeat(48), false, true, false, null, null,
                {}, {}, { saves++ }, {})
        } }
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("cloud-credential-save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun unreadableStoredCredentialCanOnlyBeExplicitlyClearedWhileSavingStaysDisabled() {
        var saves = 0
        var clears = 0
        compose.setContent { MaterialTheme {
            OrbisCloudToolCredentialForm("", "", false, false, false,
                "授权文件不可读，请明确清除后重新授权。", null,
                {}, {}, { saves++ }, { clears++ }, canClear = true)
        } }
        compose.onNodeWithTag("cloud-credential-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("cloud-credential-clear").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, clears) }
        compose.onNodeWithTag("cloud-credential-clear").performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(1, clears) }
    }
}
