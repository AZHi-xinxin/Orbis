package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.search.DeepSeekSearchService
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekSearchSettingsTest {
    @get:Rule val compose = createShellComposeRule()
    @Test fun keyEditorKeepsIdentityAndShowsIndependentBillingAndGatewayWarning() {
        var options by mutableStateOf(SearchServiceOptions.DeepSeekOptions())
        val id = options.id
        compose.setContent { MaterialTheme { Column {
            DeepSeekOptions(options) { options = it }
            DeepSeekSearchService.Description()
        } } }
        compose.onNodeWithTag("deepseek-search-key").performTextInput("synthetic-test-only")
        compose.runOnIdle { assertEquals("synthetic-test-only", options.apiKey); assertEquals(id, options.id) }
        compose.onNodeWithText("测试搜索也会计费", substring = true).assertExists()
        compose.onNodeWithText("不能填写 ST 网关密钥", substring = true).assertExists()
        compose.onNodeWithText("获取 DeepSeek 官方 API Key").assertExists()
    }
}
