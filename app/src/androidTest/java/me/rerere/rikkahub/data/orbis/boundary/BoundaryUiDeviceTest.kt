package me.rerere.rikkahub.data.orbis.boundary

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.testutil.createShellComposeRule
import me.rerere.rikkahub.ui.pages.orbis.BoundaryInput
import me.rerere.rikkahub.ui.pages.orbis.BoundarySettingsCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BoundaryUiDeviceTest {
    @get:Rule val compose = createShellComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun store(assistant:Assistant, model:Model) = SettingsStore(context, AppScope().also { it.cancel() }).also {
        it.settingsFlow.value = Settings(assistants=listOf(assistant), providers=listOf(ProviderSetting.OpenAI(models=listOf(model))))
    }
    @Test fun exhaustedQuotaShowsZeroAndExpiryRemovesCard() {
        val model=Model(modelId="synthetic"); val assistant=Assistant(chatModelId=model.id)
        val store=store(assistant,model); val owner=assistant.id.toString(); val host=BoundaryHost.get(context)
        host.grant(owner,BOUNDARY_CATEGORIES)
        host.update(owner) { changeBoundary(it,it.revision,"chat","pause","conversation","",allowRequest=true,
            until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis(),scope="assistant") }
        val revision=host.pause(owner,"chat")!!.revision
        repeat(3) { host.claimRecovery(owner,"chat",revision);host.finishRecovery(owner,"chat",revision) }
        compose.setContent { MaterialTheme { BoundaryInput(owner,"chat",{_,_->error("exhausted")},store) { Text("恢复的输入框") } } }
        compose.onNodeWithText("剩余申请次数 0/3").assertIsDisplayed()
        compose.onNodeWithText("申请继续").assertDoesNotExist()
        compose.runOnIdle { host.update(owner) { it.copy(assistantPause=it.assistantPause!!.copy(until=System.currentTimeMillis()-1)) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("恢复的输入框").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("boundary-pause").assertDoesNotExist()
    }
    @Test fun grantSwitchPauseAndHumanResetUseSameLedger() {
        val assistant=Assistant(name="合成助手");val model=Model(modelId="synthetic");val store=store(assistant,model)
        val owner=assistant.id.toString();val host=BoundaryHost.get(context)
        compose.setContent { MaterialTheme { BoundarySettingsCard(owner,store) } }
        compose.onNodeWithTag("boundary-all").performClick()
        assertEquals(BOUNDARY_CATEGORIES,host.state(owner).grants)
        compose.runOnIdle { host.update(owner) { changeBoundary(it,it.revision,"chat","pause","conversation","",reason="今天想休息",until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis()) } }
        compose.onNodeWithText("今天想休息").assertDoesNotExist()
        compose.onNodeWithText("已暂停的对话 · 1 · 查看").performClick()
        compose.onNodeWithText("今天想休息").assertIsDisplayed()
        compose.onNodeWithText("人类重置这些选择").performClick()
        assertNull(host.pause(owner,"chat"));assertEquals("human",host.state(owner).events.last().actor)
    }
    @Test fun settingsKeepFiveEventsAndCollapseActiveChoicesUntilRequested() {
        val model=Model(modelId="synthetic");val assistant=Assistant(chatModelId=model.id)
        val store=store(assistant,model);val owner=assistant.id.toString();val host=BoundaryHost.get(context)
        host.grant(owner,BOUNDARY_CATEGORIES)
        repeat(12) { index -> host.update(owner) { changeBoundary(it,it.revision,"chat","disable","tool","synthetic_$index",now=System.currentTimeMillis()) } }
        compose.setContent { MaterialTheme { BoundarySettingsCard(owner,store) } }
        compose.onNodeWithText("最近 5 条操作").assertIsDisplayed()
        compose.onAllNodesWithText("AI 自主选择 · 停用能力").assertCountEquals(5)
        compose.onNodeWithText("synthetic_0 · 已停用").assertDoesNotExist()
        compose.onNodeWithText("已停用 / 已收起 · 12 · 查看").performClick()
        compose.onNodeWithText("synthetic_0 · 已停用").assertExists()
        assertFalse(host.permits(owner,"tool","synthetic_0"))
        assertEquals(5,host.state(owner).events.size)
    }
    @Test fun pausePreservesParentDraftAndOneContinueRequestRestoresComposer() {
        val model=Model(modelId="synthetic");val assistant=Assistant(chatModelId=model.id)
        val store=store(assistant,model);val owner=assistant.id.toString();val host=BoundaryHost.get(context)
        host.grant(owner,BOUNDARY_CATEGORIES)
        val draft=mutableStateOf("尚未发送的草稿");var requests=0
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(),verticalArrangement=Arrangement.Bottom) {
                Text("原聊天记录仍在")
                BoundaryInput(owner,"chat",{revision,_ ->
                    host.claimRecovery(owner,"chat",revision);requests++
                    host.update(owner){changeBoundary(it,it.revision,"chat","resume","conversation","",now=System.currentTimeMillis())}
                    "AI 已主动恢复"
                },settingsStore=store) { OutlinedTextField(draft.value,{draft.value=it},label={Text("合成输入框")}) }
            }
        } } }
        compose.onNodeWithText("尚未发送的草稿").assertIsDisplayed()
        compose.runOnIdle { host.update(owner) { changeBoundary(it,it.revision,"chat","pause","conversation","",reason="先休息一下",allowRequest=true,until=System.currentTimeMillis()+120_000,now=System.currentTimeMillis()) } }
        compose.onNodeWithTag("boundary-pause").assertIsDisplayed()
        compose.onNodeWithText("合成输入框").assertDoesNotExist()
        compose.onNodeWithText("原聊天记录仍在").assertIsDisplayed()
        compose.onRoot().captureToImage().asAndroidBitmap().let { image ->
            File(context.getExternalFilesDir(null),"boundary-pause-dev141.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNodeWithText("申请继续").performClick()
        compose.onNodeWithText("你想说的话").performTextInput("可以继续了吗")
        compose.onNodeWithText("提交申请").performClick()
        compose.onNodeWithText("尚未发送的草稿").assertIsDisplayed()
        assertEquals(1,requests);assertNull(host.pause(owner,"chat"))
        assertEquals("ai",host.state(owner).events.last().actor)
    }
}
