package me.rerere.rikkahub.ui.pages.orbis

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.tools.createConnectionProposalTool
import me.rerere.rikkahub.data.orbis.integration.*
import me.rerere.rikkahub.data.orbis.health.*
import me.rerere.rikkahub.testutil.createShellComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class OrbisFeedbackDesignDeviceTest {
    @get:Rule val compose=createShellComposeRule()
    private fun screenshot(name:String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun closePromptIsSeparateFromMinimizeAndDoesNotSubmitTypedCredential() {
        val shown = mutableStateOf(true)
        val proposal = ConnectionProposal("synthetic-owner", ConnectionDraft("mcp_http", "旧测试连接", "https://example.invalid/mcp"))
        var saves = 0; var collapsed = 0; var dismissed = 0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            if (shown.value) ConnectionProposalSheet(listOf(proposal), proposal, false, null,
                { collapsed++; shown.value = false }, {}, false, { _, _, _, _ -> saves++ }, { _, _ -> }, {},
                onDismissSetup = { dismissed++; shown.value = false })
        } } }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-unsaved")
        compose.onNodeWithTag("connection-dismiss-setup").assertIsDisplayed().performClick()
        compose.onNodeWithTag("connection-proposals").assertDoesNotExist()
        assertEquals(1, dismissed); assertEquals(0, collapsed); assertEquals(0, saves)
    }
    @Test fun aiClosesVisibleSheetWithoutVerificationAndCanReopenItAgain() = runBlocking {
        val owner = Assistant(); val other = Assistant()
        val proposal = ConnectionProposal(owner.id.toString(), ConnectionDraft("mcp_http", "旧测试连接", "https://example.invalid/mcp"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SettingsStore(context, AppScope().also { it.cancel() }).also {
            it.settingsFlow.value = Settings(assistants = listOf(owner, other), connectionProposals = listOf(proposal))
        }
        val shown = mutableStateOf(true)
        val tool = createConnectionProposalTool(owner.id.toString(), { store.settingsFlow.value },
            { f -> store.settingsFlow.value = f(store.settingsFlow.value); true },
            onSetupDismissed = { connectionSetupQueue.closeSetup(owner.id.toString(), it) },
            onSetupNeeded = { _, _ -> shown.value = true })
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme = false) {
            if (shown.value) OrbisConnectionProposals(owner.id.toString(), { shown.value = false }, store = store)
        } } }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-unsaved")
        connectionSetupQueue.closeSetup(other.id.toString())
        compose.waitForIdle()
        compose.onNodeWithTag("connection-proposals").assertIsDisplayed()
        tool.execute(buildJsonObject { put("action", "dismiss_setup") })
        compose.waitUntil(5000) { !shown.value }
        compose.onNodeWithTag("connection-proposals").assertDoesNotExist()
        assertTrue(store.settingsFlow.value.connectionProposals.single().setupDismissed)
        assertFalse(store.settingsFlow.value.connectionProposals.single().setupCompleted)
        tool.execute(buildJsonObject { put("action", "open_setup"); put("id", proposal.id) })
        compose.onNodeWithTag("connection-proposals").assertIsDisplayed()
        assertEquals("", compose.onNodeWithTag("connection-key").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertTrue(store.settingsFlow.value.connectionProposals.single().setupPromptPending)
        assertEquals(proposal, store.settingsFlow.value.connectionProposals.single())
    }
    @Test fun connectionCanMinimizeAndReopenWithoutLosingProposalOrSubmittingKey() {
        val shown=mutableStateOf(true)
        val proposal=ConnectionProposal("synthetic-owner",ConnectionDraft("mcp_http","ST MCP","https://example.invalid/mcp"))
        var saves=0;var guides=0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme=false) {
            Column(Modifier.fillMaxSize()) { Text("原来的聊天"); TextButton(onClick={shown.value=true}){Text("连接设置")} }
            if(shown.value) ConnectionProposalSheet(listOf(proposal),proposal,false,null,{shown.value=false},{},false,
                {_,_,_,_->saves++},{_,_->},{},onGuide={guides++})
        } } }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-secret-not-real")
        compose.onNodeWithTag("connection-minimize").performClick()
        compose.onNodeWithTag("connection-proposals").assertDoesNotExist()
        compose.onNodeWithText("原来的聊天").assertIsDisplayed()
        compose.onNodeWithText("连接设置").performClick()
        compose.onNodeWithTag("connection-proposals").assertIsDisplayed()
        assertEquals("", compose.onNodeWithTag("connection-key").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("密钥在哪里？查看保存指引").performScrollTo().performClick()
        assertEquals(1,guides);assertEquals(0,saves)
        screenshot("connection-reopen-dev127.png")
    }
    @Test fun connectionStaysInChatSheetAndClosesWithoutSavingOrLosingChat() {
        val shown=mutableStateOf(true)
        val proposal=ConnectionProposal("synthetic-owner",ConnectionDraft("mcp_http","文档助手","https://example.invalid/mcp"))
        var saves=0
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme=false) {
            Column(Modifier.fillMaxSize()) { Text("原来的聊天");Text("AI 已准备好连接，填完就能接着聊。") }
            if(shown.value) ConnectionProposalSheet(listOf(proposal),proposal,false,null,{shown.value=false},{},false,{_,_,_,_->saves++},{_,_->},{})
        } } }
        compose.onNodeWithTag("connection-key").performTextInput("synthetic-key-not-a-secret")
        compose.onNodeWithText("保存并开启连接").assertIsEnabled()
        val bounds=compose.onNodeWithTag("connection-proposals").fetchSemanticsNode().boundsInWindow
        assertTrue("Sheet must leave a substantial portion of chat above it",bounds.top>bounds.height*.16f)
        screenshot("connection-sheet-dev124.png")
        compose.onNodeWithText("回到聊天").performClick()
        compose.onNodeWithTag("connection-proposals").assertDoesNotExist()
        compose.onNodeWithText("原来的聊天").assertIsDisplayed()
        assertEquals(0,saves)
    }
    @Test fun connectionLargeFontKeepsExitVisibleAndAdvancedSettingsInsideSheet() {
        val proposal=ConnectionProposal("synthetic-owner",ConnectionDraft("mcp_http","Context7 文档","https://example.invalid/mcp",auth="none"))
        val shown=mutableStateOf(true)
        compose.setContent { val d=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density,1.5f)) { MaterialTheme {
                if(shown.value) ConnectionProposalSheet(listOf(proposal),proposal,false,null,{shown.value=false},{},false,{_,_,_,_->},{_,_->},{})
            } }
        }
        compose.onNodeWithText("回到聊天").assertIsDisplayed()
        compose.onNodeWithText("⚙  高级设置").performScrollTo().performClick()
        compose.onNodeWithText("服务地址（URL）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("回到聊天").performClick()
        compose.onNodeWithTag("connection-proposals").assertDoesNotExist()
    }
    @Test fun healthCardsRenderActualConfirmedDataAndKeepAbsentValuesEmpty() {
        val storage=object:HealthStorage {var text:String?=null;override fun read()=text;override fun write(value:String){text=value}}
        val repo=HealthRepository(storage){123}
        val date=LocalDate.now().toString()
        val p=repo.propose(date,0,mapOf("sleepMinutes" to JsonPrimitive(465),"steps" to JsonPrimitive(3286),"averageHeart" to JsonPrimitive(76)),"screenshot","ai:test","合成测试")
        // Use the same human-confirmation UI rather than writing raw state.
        compose.setContent { MaterialTheme { OrbisVisualTheme(darkTheme=false) { HealthPageContent(repo,{}) } } }
        compose.onNodeWithTag("health-dashboard").performScrollToNode(hasText("检查建议"))
        compose.onNodeWithText("检查建议").performClick();compose.onNodeWithText("确认保存").performClick()
        compose.waitUntil(5000){repo.day(date).revision==1}
        compose.onNodeWithTag("health-dashboard").performScrollToIndex(0)
        compose.onNodeWithText("3286").assertIsDisplayed()
        compose.onNodeWithText("76").assertIsDisplayed()
        screenshot("health-dashboard-dev124.png")
        assertFalse(repo.day(date).fields.containsKey("spo2"))
    }
}
