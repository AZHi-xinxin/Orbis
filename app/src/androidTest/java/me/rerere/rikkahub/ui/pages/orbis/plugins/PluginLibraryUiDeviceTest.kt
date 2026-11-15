package me.rerere.rikkahub.ui.pages.orbis.plugins

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.IsolatedGenerationLoopRunner
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.orbis.plugins.*
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.orbis.OrbisVisualTheme
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PluginLibraryUiDeviceTest {
    private val compose=createAndroidComposeRule<ComponentActivity>()
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val owner=Assistant(name="测试伙伴",localTools=listOf(LocalToolOption.PersonalPlugins))
    private lateinit var store: SettingsStore
    private lateinit var db: AppDatabase
    private lateinit var host: PluginHost
    private lateinit var workspaceRoot: File
    private lateinit var first: PluginEntry
    private val fixture=object : ExternalResource() {
        override fun before() {
            check(inst is IsolatedGenerationLoopRunner && inst.targetContext.packageName.endsWith(".bodylab"))
            check(inst.targetContext.applicationContext.javaClass==Application::class.java)
            store=SettingsStore(inst.targetContext,AppScope().also {it.cancel()}).also {
                it.settingsFlow.value=Settings(assistants=listOf(owner),assistantId=owner.id)
            }
            db=Room.inMemoryDatabaseBuilder(inst.targetContext,AppDatabase::class.java).build()
            workspaceRoot=File(inst.targetContext.cacheDir,"plugin-ui-${Uuid.random()}")
            val manager=WorkspaceManager(workspaceRoot)
            val repository=WorkspaceRepository(db.workspaceDao(),manager,RootfsInstaller(manager),store)
            startKoin {modules(module {single<SettingsStore> {store};single<WorkspaceRepository> {repository}})}
            host=PluginHost(inst.targetContext,owner.id.toString(),store,repository)
            val body=PluginManifest("bedtime","睡前小约定","把约好的睡前小事收好","把我们约好的睡前小事，轻轻收在这里。\n\n你想用的时候，我会按约定陪你完成，也记住哪些已经做过。","return {ok:true};")
            first=host.repository.propose(body,null,0)
            host.repository.propose(body.copy(name="album",title="共同创作册",summary="收藏我们一起完成的作品",palette="blue",icon="book"),null,0)
            host.repository.propose(body.copy(name="promise",title="未完的小事",summary="记住答应过、还没完成的事",palette="rose",icon="letter"),null,0)
            host.repository.propose(body.copy(name="story",title="故事续章",summary="接着上次的故事继续写",palette="mint",icon="leaf"),null,0)
        }
        override fun after() {
            stopKoin();db.close();host.repository.root.deleteRecursively()
            File(inst.targetContext.noBackupFilesDir,"personal-plugin-grants/${owner.id}").deleteRecursively();workspaceRoot.deleteRecursively()
        }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(fixture).around(compose)
    private fun show(fontScale: Float=1f) {
        compose.setContent {
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalSettings provides store.settingsFlow.value,LocalNavController provides Navigator(mutableListOf()),
                LocalDensity provides Density(density,fontScale)) {
                MaterialTheme {OrbisVisualTheme(darkTheme=true) {PluginLibraryPage(owner.id.toString(),owner.name,null) {}}}
            }
        }
        compose.waitUntil(10_000) {compose.onAllNodesWithText("按使用频率").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap=inst.uiAutomation.takeScreenshot()
        val dir=File(inst.targetContext.getExternalFilesDir(null),"plugin-design-check");check(dir.isDirectory || dir.mkdirs())
        File(dir,"$name.png").outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    @Test fun humanReviewsVersionThenCanDisableAndDeleteWithoutReadingCode() {
        show()
        capture("library")
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasTestTag("plugin-card-${first.id}"))
        compose.onNodeWithTag("plugin-card-${first.id}").performClick()
        compose.onNodeWithText("插件详情").assertIsDisplayed()
        compose.onNodeWithText("测试伙伴 写给你的介绍").assertIsDisplayed()
        compose.onNodeWithText("return {ok:true};").assertDoesNotExist()
        capture("detail")
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasTestTag("plugin-review"))
        compose.onNodeWithTag("plugin-review").performClick()
        compose.onNodeWithTag("plugin-approve").performClick()
        compose.waitUntil(10_000) {host.repository.snapshot().entries.first {it.id==first.id}.enabled}
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasTestTag("plugin-enabled"))
        compose.onNodeWithTag("plugin-enabled").performClick()
        compose.waitUntil(10_000) {!host.repository.snapshot().entries.first {it.id==first.id}.enabled}
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasText("删除插件"))
        compose.onNodeWithText("删除插件").performClick()
        compose.onNodeWithText("删除",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000) {host.repository.snapshot().entries.none {it.id==first.id}}
    }
    @Test fun largeFontKeepsCardsAndPermissionActionsReachable() {
        show(1.8f)
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasTestTag("plugin-card-${first.id}"))
        compose.onNodeWithTag("plugin-card-${first.id}").performClick()
        compose.onNodeWithTag("personal-plugin-page").performScrollToNode(hasTestTag("plugin-review"))
        compose.onNodeWithTag("plugin-review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plugin-approve").assertIsDisplayed()
        capture("large-font-review")
    }
}
