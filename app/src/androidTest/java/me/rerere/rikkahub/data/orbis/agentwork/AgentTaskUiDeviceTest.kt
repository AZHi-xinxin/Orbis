package me.rerere.rikkahub.data.orbis.agentwork

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.ui.pages.orbis.AgentTaskCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentTaskUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun largeFontTaskCardKeepsHumanControlsAndDoesNotTreatReceiptAsCompletion() {
        val inst = InstrumentationRegistry.getInstrumentation()
        check(inst is IsolatedGenerationLoopRunner && inst.targetContext.packageName == "org.orbis.agent.bodylab")
        check(inst.targetContext.applicationContext.javaClass == Application::class.java)
        val root = File(inst.targetContext.cacheDir,"task-ui-${UUID.randomUUID()}").canonicalFile
        val store = AgentTaskStore(root)
        val created = store.create(UUID.randomUUID().toString(),"一起完成今天的小事",listOf(AgentStep("打开计算器并计算"),AgentStep("重新读取页面，核对结果")))
        store.claim(created.id,"one","orbis_phone")
        store.receipt(created.id,"one","orbis_phone","returned")
        try {
            compose.setContent {
                var task by remember { mutableStateOf(store.list().single()) }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.8f)) {
                    MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("任务与能力",style=MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        AgentTaskCard(task,false) { task=store.control(task.id,task.revision,it) }
                    } } }
                }
            }
            compose.onNodeWithText("待推进").assertIsDisplayed()
            compose.onNodeWithText("AI报告完成").assertDoesNotExist()
            compose.onNodeWithText("暂停").performScrollTo().performClick()
            compose.onNodeWithText("已暂停").performScrollTo().assertIsDisplayed()
            assertEquals("paused",store.list().single().state)
            compose.onNodeWithText("恢复记录").performScrollTo().performClick()
            assertEquals("active",store.list().single().state)
            assertEquals(1,store.list().single().receipts.size)
            compose.onNodeWithText("取消任务").performScrollTo().assertIsDisplayed()
            val bitmap=inst.uiAutomation.takeScreenshot()
            val dir=File(inst.targetContext.getExternalFilesDir(null),"agent-work-check").apply { mkdirs() }
            File(dir,"large-font-task.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }; bitmap.recycle()
            compose.onNodeWithText("取消任务").performClick()
            assertEquals("cancelled",store.list().single().state)
        } finally { root.deleteRecursively() }
    }
}
