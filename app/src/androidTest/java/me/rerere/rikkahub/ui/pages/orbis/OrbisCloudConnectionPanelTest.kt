package me.rerere.rikkahub.ui.pages.orbis

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.model.OrbisCloudHomeConfig
import me.rerere.rikkahub.data.model.normalizeOrbisCloudHomeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Synthetic, injected UI only: no Koin, settings files, WebView, network or real cloud data.
 * Use -PorbisIsolatedTests=true and explicitly select IsolatedGenerationLoopRunner.
 */
@RunWith(AndroidJUnit4::class)
class OrbisCloudConnectionPanelTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val isolatedApplication = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            check(instrumentation is IsolatedGenerationLoopRunner)
            assertEquals(Application::class.java, instrumentation.targetContext.applicationContext.javaClass)
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(isolatedApplication).around(compose)

    private val url = "https://synthetic-home.netlify.app/"
    private val nextUrl = "https://synthetic-other.netlify.app/"

    @Test fun loadingHasNoEditingOrConnectionActions() {
        val loaded = mutableStateOf(false)
        var saves = 0
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(loaded.value, OrbisCloudHomeConfig(homeUrl = url),
                    onSave = { saves++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").assertDoesNotExist()
        compose.onNodeWithTag("orbis-cloud-connect").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, saves); loaded.value = true }
        compose.onNodeWithTag("orbis-cloud-home-url").assertTextContains(url)
    }

    @Test fun loadFailureOffersRetryWithoutOverwritingUnreadSettings() {
        var saves = 0
        var retries = 0
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(),
                    onSave = { saves++ }, onDismiss = {}, loadError = "合成本机读取失败",
                    canEdit = false,
                    onReload = { retries++ })
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").assertDoesNotExist()
        compose.onNodeWithTag("orbis-cloud-connect").assertDoesNotExist()
        compose.onNodeWithTag("orbis-cloud-reload").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, saves) }
    }

    @Test fun invalidSuggestedAddressShowsWarningAndPermitsManualRecovery() {
        var saved: OrbisCloudHomeConfig? = null
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(),
                    onSave = { saved = it }, onDismiss = {},
                    loadError = "合成建议地址无效，可以手动填写。", canEdit = true)
            }
        }
        compose.onNodeWithText("合成建议地址无效，可以手动填写。").assertExists()
        compose.onNodeWithTag("orbis-cloud-home-url").assertIsEnabled().performTextReplacement(url)
        compose.onNodeWithTag("orbis-cloud-save-address").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OrbisCloudHomeConfig(enabled = false, homeUrl = url), saved) }
    }

    @Test fun cancelDiscardsDraftWithoutSavingOrConnecting() {
        var saves = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(homeUrl = url),
                    onSave = { saves++ }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement(nextUrl)
        compose.onNodeWithTag("orbis-cloud-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(0, saves) }
    }

    @Test fun savingAddressAlwaysStoresDisabledEvenWhenPreviouslyConnected() {
        var saved: OrbisCloudHomeConfig? = null
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(enabled = true, homeUrl = url),
                    onSave = { saved = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement(nextUrl)
        compose.onNodeWithTag("orbis-cloud-save-address").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(OrbisCloudHomeConfig(enabled = false,
                homeUrl = requireNotNull(normalizeOrbisCloudHomeUrl(nextUrl))), saved)
        }
    }

    @Test fun connectionRequiresSecondConfirmationShowingTheExactAddress() {
        var saved: OrbisCloudHomeConfig? = null
        var saves = 0
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(homeUrl = url),
                    onSave = { saved = it; saves++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("orbis-cloud-connect").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-cloud-confirm-url").assertTextContains(url)
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("orbis-cloud-confirm-cancel").performClick()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement(nextUrl)
        compose.onNodeWithTag("orbis-cloud-connect").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-cloud-confirm-url").assertTextContains(nextUrl)
        compose.onNodeWithTag("orbis-cloud-confirm-connect").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals(OrbisCloudHomeConfig(enabled = true, homeUrl = nextUrl), saved)
        }
    }

    @Test fun invalidAddressCannotBeSavedOrConnected() {
        var saves = 0
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(),
                    onSave = { saves++ }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement("http://synthetic-home.netlify.app/")
        compose.onNodeWithTag("orbis-cloud-save-address").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-cloud-connect").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun disconnectKeepsTheSavedAddressRatherThanAnUnconfirmedDraft() {
        var saved: OrbisCloudHomeConfig? = null
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(enabled = true, homeUrl = url),
                    onSave = { saved = it }, onDismiss = {})
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement(nextUrl)
        compose.onNodeWithTag("orbis-cloud-disconnect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(OrbisCloudHomeConfig(enabled = false, homeUrl = url), saved) }
    }

    @Test fun failedSaveRetainsDraftAndAllowsRetry() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(),
                    onSave = { error("Synthetic save failure") }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithTag("orbis-cloud-home-url").performTextReplacement(url)
        compose.onNodeWithTag("orbis-cloud-save-address").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-cloud-home-url").assertTextContains(url).assertIsEnabled()
        compose.onNodeWithTag("orbis-cloud-save-address").assertIsEnabled()
        compose.onNodeWithText("设置暂未保存成功，填写的地址还在这里，可以重试。").assertExists()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun pendingSaveBlocksRepeatedActionsAndDismissal() {
        val gate = CompletableDeferred<Unit>()
        var saves = 0
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                OrbisCloudConnectionEditor(true, OrbisCloudHomeConfig(homeUrl = url),
                    onSave = { saves++; gate.await() }, onDismiss = { dismissed = true })
            }
        }
        compose.onNodeWithTag("orbis-cloud-save-address").performScrollTo().performClick()
        compose.onNodeWithTag("orbis-cloud-home-url").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-cloud-save-address").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-cloud-connect").assertIsNotEnabled()
        compose.onNodeWithTag("orbis-cloud-cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, saves); assertFalse(dismissed); gate.complete(Unit) }
        compose.waitUntil(timeoutMillis = 5_000) { dismissed }
    }

    @Test fun personalGardenNamesSaveWithoutSwitchingExistingRemoteMode() {
        var saved: OrbisCloudHomeConfig? = null
        val previous = OrbisCloudHomeConfig(enabled = true, homeUrl = url, humanName = "原名字")
        compose.setContent { MaterialTheme {
            OrbisCloudConnectionEditor(true, previous, onSave = { saved = it }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-garden-human").performScrollTo().performTextReplacement("自己的名字")
        compose.onNodeWithTag("orbis-garden-save-names").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(previous.copy(humanName = "自己的名字"), saved) }
    }

    @Test fun selectingLocalPreservesRemoteAddressAndGardenNames() {
        var saved: OrbisCloudHomeConfig? = null
        val previous = OrbisCloudHomeConfig(enabled = true, homeUrl = url, companionName = "自定义伙伴")
        compose.setContent { MaterialTheme {
            OrbisCloudConnectionEditor(true, previous, onSave = { saved = it }, onDismiss = {})
        } }
        compose.onNodeWithTag("orbis-cloud-disconnect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(previous.copy(enabled = false), saved) }
    }
}
